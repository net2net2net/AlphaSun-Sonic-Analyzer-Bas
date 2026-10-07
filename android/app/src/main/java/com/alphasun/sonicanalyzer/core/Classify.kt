package com.alphasun.sonicanalyzer.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * v0.4.0 智能分类（纯 Kotlin，移植 web-v0.3.1 的 classify + inferOtherSource）
 *
 * ⚠ 诚实边界（与 Web 版一致）：全部为**离线启发式**（softmax 打分 + 模板匹配），
 *   不是训练模型。物种 / 车型 / 曲目均不可作为确定性结论。
 *
 * 关键设计（都是历次修 bug 换来的，不要"简化"掉）：
 *  ① 分类用 softmax(线性打分)，四类互斥且和为 1；
 *  ② music 必须过**连续性门** musicHold（v2.2.0 曾因门限写成 `>=0.45 才累加`造成死锁）；
 *  ③ 展示层再做 EMA 平滑（0.75/0.25），否则扫频信号会让判定以秒级闪烁；
 *  ④ 「其他声音」子类用**形状模板 + 门限 + 硬否决**三层；
 *     牛哞/马嘶/马蹄当年假阳性 8/8 全中，就是因为缺硬否决层（v2.2.1 修复）。
 */
object Classify {

    data class Conf(val voice: Double, val music: Double, val other: Double, val noise: Double) {
        fun top(): String = when {
            voice >= music && voice >= other && voice >= noise -> "voice"
            music >= other && music >= noise -> "music"
            other >= noise -> "other"
            else -> "noise"
        }
        fun topPct(): Int = kotlin.math.round(maxOf(voice, music, other, noise) * 100.0).toInt()
    }

    data class Candidate(val group: String, val name: String, val score: Double)

    /** 分类器状态（跨帧：EMA + 音乐连续性门）。 */
    class Engine {
        private var smooth: Conf? = null
        private var musicHold = 0
        private var pitchHold = 0
        private var voiceRun = 0
        var voiceStable = false; private set

        /** 人声子类型与人数（仅在 voiceStable 时有意义）。 */
        var voiceKind = "—"; private set
        var voiceGender = "—"; private set
        var voiceSpeakers = "—"; private set

        fun reset() {
            smooth = null; musicHold = 0; pitchHold = 0; voiceRun = 0; voiceStable = false
            voiceKind = "—"; voiceGender = "—"; voiceSpeakers = "—"
        }

        fun tick(f: Features.Frame): Conf {
            val musicEvidence = min(
                1.0,
                (if (f.bpm > 0) 0.45 else 0.0) +
                    (if (pitchHold >= 4 && !voiceStable) 0.35 else 0.0) +
                    f.harmonicity * 0.40 * max(0.0, 1 - f.eventN * 0.5)
            )
            val g = classify(
                flat = f.flat,
                zcrN = min(1.0, f.zcr * 8),
                harmN = f.harmonicity,
                music = musicEvidence,
                domVoice = f.domVoice,
                spreadN = min(1.0, f.spread / 4000.0),
                rollN = min(1.0, f.rolloff / 8000.0),
                eventN = f.eventN,
                bandPeakN = f.bandPeakN,
                hfN = f.hfN
            )

            // EMA 平滑：时间常数 ≈4 帧 ≈800ms，覆盖 ≈2.4 个 3Hz 扫频周期
            val s0 = smooth
            smooth = if (s0 == null) g else Conf(
                g0(g.voice, s0.voice), g0(g.music, s0.music),
                g0(g.other, s0.other), g0(g.noise, s0.noise)
            )
            val c = smooth!!

            // 音乐连续性门（三重约束，v2.2.0/v2.6.0 两次加固）
            if (c.music >= 0.22 && c.music > c.voice * 0.9) {
                musicHold = min(120, musicHold + 1 + Math.round((c.music - 0.22) * 2).toInt())
            } else if (c.music < 0.15) {
                musicHold = max(0, musicHold - 3)
            }
            val cM = c.music * min(1.0, musicHold / 40.0)

            // 音高连续性
            if (f.f0 > 80 && f.f0 < 400) voiceRun++ else voiceRun = 0
            voiceStable = voiceRun >= 2 && f.f0 > 80 && f.f0 < 400
            pitchHold = if (f.f0 > 0) pitchHold + 1 else 0

            // 人声细化
            val (vk, _) = Features.voiceKind(f.f0, f.harmonicity, f.f0Span)
            voiceKind = vk
            voiceGender = when {
                f.f0 <= 0 -> "—"
                f.f0 < 140 -> "男声"
                f.f0 < 190 -> "男/女临界"
                f.f0 < 260 -> "女声"
                else -> "女声(偏高)"
            }
            voiceSpeakers = when {
                f.f0 <= 0 -> "—"
                f.f0Span > 55 -> "多人(基频跨度大)"
                else -> "单人(估)"
            }

            return Conf(c.voice, cM, c.other, c.noise)
        }

        private fun g0(now: Double, prev: Double) = now * 0.75 + prev * 0.25
    }

    /** 四类线性打分 → softmax。权重逐项对齐 Web 版 classify()。 */
    @JvmStatic
    fun classify(
        flat: Double, zcrN: Double, harmN: Double, music: Double, domVoice: Double,
        spreadN: Double, rollN: Double, eventN: Double, bandPeakN: Double, hfN: Double
    ): Conf {
        val c01: (Double) -> Double = { Features.clamp01(it) }
        val sv = 2.2 * domVoice + 1.0 * zcrN + 0.6 * harmN - 1.4 * flat +
            0.4 * (1 - spreadN) - 0.6 * eventN - 1.2 * hfN
        val sm = 1.5 * music + 1.0 * harmN + 0.4 * (1 - flat) + 0.5 * rollN -
            0.5 * domVoice - 0.35 * eventN
        // +0.40 先验偏置：没有它「其他声音」几乎永远拿不到份额，子类细化形同虚设
        val so = 1.8 * eventN + 0.9 * bandPeakN + 0.5 * (1 - domVoice) + 0.4 * (1 - music) +
            0.3 * (1 - harmN) - 0.5 * flat + 1.3 * hfN + 0.40
        val sn = 2.4 * flat + 1.2 * (1 - harmN) + 0.8 * (1 - music) + 0.4 * (1 - domVoice) -
            0.7 * eventN - 1.4 * hfN * c01(eventN * 1.4)
        val ev = exp(sv); val em = exp(sm); val eo = exp(so); val en = exp(sn)
        val sum = ev + em + eo + en
        return Conf(ev / sum, em / sum, eo / sum, en / sum)
    }

    /* ==================== 其他声音：形状模板匹配 ==================== */

    /** 模板：六段能量形状 + 门限函数 + 权重 + 可选 veto/hard。 */
    class Proto(
        val group: String,
        val name: String,
        val t: DoubleArray,
        val gates: List<(G) -> Double>,
        val w: DoubleArray,
        val veto: ((G) -> Boolean)? = null,
        val hard: ((G) -> Boolean)? = null
    )

    /** 打分所需的全部特征（避免直接依赖 Features.Frame 内部字段名）。 */
    data class G(
        val be: DoubleArray, val centroid: Double, val flat: Double, val rms: Double,
        val crest: Double, val zcr: Double, val fluxN: Double, val eventN: Double,
        val crestN: Double, val harmN: Double, val f0: Double, val f0N: Double,
        val spreadN: Double, val rollN: Double, val bandPeakN: Double, val hfN: Double,
        val tilt: Double, val db: Double, val lf: Double, val hf: Double, val lfShare: Double,
        val domVoice: Double, val bpm: Double = 0.0
    )

    private fun step(v: Double, hi: Double, lo: Double, inv: Boolean = false): Double {
        val r = if (v >= hi) 1.0 else if (v >= lo) 0.5 else 0.0
        return if (inv) 1 - r else r
    }

    private fun inBand(v: Double, lo: Double, hi: Double): Double {
        if (v >= lo && v <= hi) return 1.0
        val d = if (v < lo) lo - v else v - hi
        val s = if (v < lo) max(0.10, lo * 0.6) else max(0.05, 1 - hi)
        return max(0.0, 1 - d / s)
    }

    private fun between(v: Double, lo: Double, hi: Double): Double =
        if (v >= lo && v <= hi) 1.0
        else max(0.0, 1 - min(abs(v - lo), abs(v - hi)) / max(400.0, hi * 0.5))

    /** 形状相似度（L1 归一化重叠度），0..1。 */
    private fun shapeSim(v: DoubleArray, t: DoubleArray): Double {
        var inter = 0.0; var uni = 0.0
        for (i in v.indices) { inter += min(v[i], t[i]); uni += max(v[i], t[i]) }
        return if (uni > 0) inter / uni else 0.0
    }

    private fun humanBusy(g: G) = g.domVoice >= 0.6 && g.f0 > 85 && g.f0 < 340

    private fun lfShareOf(g: G): Double = if (g.lfShare > 0) g.lfShare else {
        val p = DoubleArray(6) { g.be[it] * g.be[it] }
        var s = 0.0; for (v in p) s += v
        if (s <= 0) 0.0 else (p[0] + p[1]) / s
    }

    /**
     * 24 个模板，全部来自 Web 版 OTHER_PROTO。
     * hard 是**排他性判据**（不满足即根本不可能），veto 是**倾向性判据**（×0.5）。
     */
    val PROTO: List<Proto> = listOf(
        // —— 动物 · 禽鸟 ——
        Proto("动物·鸟类", "鸟鸣(啁啾)", doubleArrayOf(0.0, .10, .30, .75, 1.0, .45),
            listOf({ g -> step(g.centroid, 1800.0, 1200.0) }, { g -> min(1.0, g.fluxN * 1.3) },
                { g -> 1 - Features.clamp01(g.harmN * 1.4) }, { g -> if (g.fluxN > 0.22) 1.0 else 0.0 }),
            doubleArrayOf(1.0, .7, .6, .5),
            veto = { g -> (g.hf + g.lf) > 0 && (g.hf / (g.hf + g.lf)) < 0.45 }),
        Proto("动物·鸟类", "麻雀/小型鸟群", doubleArrayOf(0.0, .10, .35, .85, 1.0, .70),
            listOf({ g -> step(g.centroid, 2400.0, 1600.0) }, { g -> min(1.0, g.fluxN * 1.4) },
                { g -> Features.clamp01(g.zcr * 1.2) }, { g -> if (g.fluxN > 0.22) 1.0 else 0.0 }),
            doubleArrayOf(1.0, .6, .5, .5),
            veto = { g -> (g.hf + g.lf) > 0 && (g.hf / (g.hf + g.lf)) < 0.45 }),
        Proto("动物·禽类", "鸡(咯咯)", doubleArrayOf(0.0, .25, .80, .95, .40, .08),
            listOf({ g -> between(g.centroid, 600.0, 2600.0) }, { g -> min(1.0, g.eventN * 1.4) },
                { g -> Features.clamp01(g.crestN) }),
            doubleArrayOf(.8, .9, .5), hard = { g -> !humanBusy(g) }),
        Proto("动物·禽类", "鸭(嘎嘎)", doubleArrayOf(.10, .60, 1.0, .55, .20, .03),
            listOf({ g -> between(g.centroid, 350.0, 1500.0) }, { g -> min(1.0, g.eventN * 1.3) },
                { g -> 1 - Features.clamp01(g.centroid / 6000.0) }),
            doubleArrayOf(.8, .9, .6), hard = { g -> !humanBusy(g) }),
        Proto("动物·禽类", "鹅(鸣叫)", doubleArrayOf(.10, .55, .95, .75, .30, .05),
            listOf({ g -> between(g.centroid, 500.0, 2200.0) }, { g -> min(1.0, g.eventN * 1.2) },
                { g -> Features.clamp01(g.crestN) }),
            doubleArrayOf(.8, .8, .5), hard = { g -> !humanBusy(g) }),
        // —— 动物 · 哺乳（v2.2.1 加了人声交叉避让 + 纹理窗）——
        Proto("动物·哺乳", "猫叫", doubleArrayOf(0.0, .25, .75, .95, .55, .18),
            listOf({ g -> between(g.centroid, 500.0, 2800.0) }, { g -> Features.clamp01(g.f0N * 1.2) },
                { g -> step(g.f0, 300.0, 180.0) }),
            doubleArrayOf(.7, .9, .8),
            hard = { g -> !humanBusy(g) && g.f0 > 0 }),
        Proto("动物·哺乳", "狗吠", doubleArrayOf(.10, .85, 1.0, .60, .22, .04),
            listOf({ g -> step(g.crest, 3.6, 2.6) }, { g -> min(1.0, g.eventN * 1.5) },
                { g -> 1 - Features.clamp01(g.centroid / 4000.0) }),
            doubleArrayOf(1.1, .9, .5), hard = { g -> !humanBusy(g) }),
        // 牛哞：v2.2.1 修复假阳性（曾 8/8 全中）。七道硬否决缺一不可。
        Proto("动物·哺乳", "牛哞", doubleArrayOf(1.0, .90, .45, .18, .04, 0.0),
            listOf({ g -> step(g.f0, 260.0, 150.0, true) }, { g -> Features.clamp01(g.harmN + 0.2) },
                { g -> 1 - step(g.centroid, 1400.0, 900.0) }),
            doubleArrayOf(1.0, .7, .7),
            hard = label@{ g ->
                if (g.f0 <= 0) return@label false              // 必须有可测基频
                if (g.f0 < 60 || g.f0 > 300) return@label false   // 大牲口共鸣区
                if (g.harmN < 0.52 || g.harmN > 0.92) return@label false  // 排除合成纯音/工频嗡鸣
                if (g.flat < 0.18 || g.flat > 0.55) return@label false    // 纹理窗
                if (g.eventN < 0.12 || g.eventN > 0.55) return@label false// 排除电子音与瞬态爆发
                if ((g.lf + g.hf) > 0 && (g.hf / (g.lf + g.hf)) > 0.28) return@label false
                if (lfShareOf(g) < 0.55) return@label false
                !humanBusy(g)
            }),
        // 马嘶 / 马蹄：原合并模板已拆分（物理机制完全不同，合并后对两侧都不成立）
        Proto("动物·哺乳", "马嘶", doubleArrayOf(.45, .95, .85, .50, .25, .05),
            listOf({ g -> between(g.centroid, 400.0, 2200.0) }, { g -> min(1.0, g.eventN * 1.2) },
                { g -> Features.clamp01(g.zcr) }),
            doubleArrayOf(.7, .8, .5),
            hard = label@{ g ->
                if (g.eventN < 0.55) return@label false
                if (g.flat > 0.70) return@label false
                if (g.f0 > 900) return@label false
                if (g.centroid < 380) return@label false
                if (lfShareOf(g) < 0.40) return@label false
                !(humanBusy(g) && g.eventN < 0.75)
            }),
        Proto("动物·哺乳", "马蹄", doubleArrayOf(.90, .85, .62, .35, .16, .05),
            listOf({ g -> step(g.centroid, 900.0, 500.0, true) }, { g -> min(1.0, g.eventN * 1.2) },
                { g -> 1 - Features.clamp01(g.harmN * 1.2) }),
            doubleArrayOf(.9, .8, .7),
            hard = label@{ g ->
                if (g.eventN < 0.70) return@label false
                if (lfShareOf(g) < 0.55) return@label false
                if (g.centroid > 1000) return@label false
                if (g.flat > 0.72) return@label false
                g.bpm > 0 && g.bpm >= 60      // 节奏证据：马蹄是成串的，零星脚步不满足
            }),
        // —— 交通工具（引擎类非谐波，统一加「非谐波」门）——
        Proto("交通工具", "汽车(行驶/引擎)", doubleArrayOf(.95, .80, .50, .30, .18, .08),
            listOf({ g -> 1 - step(g.centroid, 1200.0, 700.0) },
                { g -> 1 - Features.clamp01(g.eventN * 1.4) },
                { g -> if (g.f0 <= 0 || g.f0 < 90) 1.0 else 0.0 },
                { g -> 1 - Features.clamp01(g.harmN * 1.3) }),
            doubleArrayOf(1.0, .8, .6, .8), veto = { g -> g.harmN > 0.70 }),
        Proto("交通工具", "摩托车", doubleArrayOf(.85, .95, .65, .40, .22, .08),
            listOf({ g -> between(g.centroid, 200.0, 1800.0) }, { g -> min(1.0, g.eventN * 1.2) },
                { g -> Features.clamp01(g.crestN) }, { g -> 1 - Features.clamp01(g.harmN * 1.3) }),
            doubleArrayOf(.8, .9, .5, .8), veto = { g -> g.harmN > 0.70 }),
        Proto("交通工具", "火车/轨道", doubleArrayOf(1.0, .85, .60, .45, .30, .15),
            listOf({ g -> 1 - step(g.centroid, 1600.0, 900.0) },
                { g -> 1 - Features.clamp01(g.eventN * 1.1) },
                { g -> step(g.db, -70.0, -85.0) },
                { g -> 1 - Features.clamp01(g.harmN * 1.3) }),
            doubleArrayOf(1.0, .6, .5, .8), veto = { g -> g.harmN > 0.70 }),
        Proto("交通工具", "飞机(轰鸣)", doubleArrayOf(1.0, .90, .70, .55, .40, .30),
            listOf({ g -> step(g.db, -62.0, -75.0) }, { g -> 1 - Features.clamp01(g.eventN * 1.5) },
                { g -> Features.clamp01(g.spreadN * 1.2) }, { g -> 1 - Features.clamp01(g.harmN * 1.3) }),
            doubleArrayOf(1.0, .7, .6, .8), veto = { g -> g.harmN > 0.70 }),
        // —— 自然 · 环境（统一加「非完全均匀」门，避免白噪误报成下雨）——
        Proto("自然·环境", "下雨", doubleArrayOf(.15, .35, .60, .85, 1.0, .90),
            listOf({ g -> step(g.centroid, 2200.0, 1500.0) }, { g -> Features.clamp01(g.flat * 1.6) },
                { g -> 1 - Features.clamp01(g.eventN * 1.6) }, { g -> inBand(g.flat, 0.35, 0.94) },
                { g -> step(g.tilt, 0.25, 0.10) }),
            doubleArrayOf(1.0, .9, .7, .9, 1.0),
            veto = { g -> g.flat < 0.25 || g.tilt < 0.10 }),
        Proto("自然·环境", "水流/溪流", doubleArrayOf(.25, .50, .72, .90, .95, .65),
            listOf({ g -> step(g.centroid, 1800.0, 1200.0) }, { g -> Features.clamp01(g.flat * 1.5) },
                { g -> 1 - Features.clamp01(g.eventN * 1.4) }, { g -> inBand(g.flat, 0.35, 0.94) },
                { g -> step(g.tilt, 0.15, 0.05) }),
            doubleArrayOf(.9, .8, .6, .9, 1.0),
            veto = { g -> g.flat < 0.25 || g.tilt < 0.05 }),
        Proto("自然·环境", "风声", doubleArrayOf(1.0, .90, .65, .45, .30, .15),
            listOf({ g -> 1 - step(g.centroid, 1200.0, 650.0) }, { g -> Features.clamp01(g.flat * 1.4) },
                { g -> 1 - Features.clamp01(g.zcr * 1.3) }, { g -> inBand(g.flat, 0.30, 0.94) },
                { g -> step(-g.tilt, 0.25, 0.10) }),
            doubleArrayOf(1.0, .6, .7, .9, 1.0),
            veto = { g -> g.flat < 0.25 || g.tilt > -0.10 }),
        Proto("自然·环境", "雷声/远处轰隆", doubleArrayOf(1.0, .85, .55, .35, .20, .10),
            listOf({ g -> step(g.crest, 3.0, 2.0) }, { g -> step(g.db, -55.0, -70.0) },
                { g -> 1 - step(g.centroid, 1000.0, 600.0) }, { g -> inBand(g.flat, 0.20, 0.94) },
                { g -> step(-g.tilt, 0.20, 0.05) }),
            doubleArrayOf(1.0, .8, .7, .9, 1.0),
            veto = { g -> g.flat < 0.25 || g.tilt > -0.05 }),
        // —— 日常 · 家居出行 ——
        Proto("日常·家居出行", "手机/电话铃声", doubleArrayOf(.05, .30, .85, 1.0, .60, .15),
            listOf({ g -> Features.clamp01(g.harmN * 1.6) },
                { g -> between(g.centroid, 700.0, 4500.0) },
                { g -> if (g.f0 > 200) 1.0 else 0.0 },
                { g -> 1 - step(g.eventN, 0.85, 0.65) }),
            doubleArrayOf(1.0, .8, .6, .6), veto = { g -> g.flat > 0.90 }),
        Proto("日常·家居出行", "汽车引擎(怠速·近场)", doubleArrayOf(1.0, .75, .40, .18, .06, 0.0),
            listOf({ g -> 1 - step(g.centroid, 600.0, 350.0) },
                { g -> if ((g.lf + g.hf) > 0) Features.clamp01(((g.lf / (g.lf + g.hf)) - 0.55) * 2.2) else 0.0 },
                { g -> 1 - Features.clamp01(g.eventN * 1.3) },
                { g -> 1 - Features.clamp01(g.harmN * 1.2) }),
            doubleArrayOf(1.0, .9, .7, .6),
            veto = { g -> (g.lf + g.hf) > 0 && (g.lf / (g.lf + g.hf)) < 0.5 }),
        Proto("日常·家居出行", "键盘打字", doubleArrayOf(.15, .40, .80, 1.0, .70, .30),
            listOf({ g -> step(g.crest, 3.0, 2.2) }, { g -> min(1.0, g.eventN * 1.5) },
                { g -> between(g.centroid, 1200.0, 6000.0) },
                { g -> 1 - Features.clamp01(g.harmN * 1.4) }),
            doubleArrayOf(1.0, .9, .7, .6), veto = { g -> g.harmN > 0.55 || g.flat > 0.93 }),
        Proto("日常·家居出行", "鼠标点击", doubleArrayOf(.05, .20, .55, 1.0, .85, .40),
            listOf({ g -> step(g.crest, 4.0, 2.8) }, { g -> Features.clamp01((g.eventN - 0.35) * 2) },
                { g -> between(g.centroid, 2000.0, 8000.0) },
                { g -> if ((g.hf + g.lf) > 0) Features.clamp01(((g.hf / (g.hf + g.lf)) - 0.5) * 2.5) else 0.0 }),
            doubleArrayOf(1.0, .8, .7, .6), veto = { g -> g.harmN > 0.45 }),
        Proto("日常·家居出行", "脚步声", doubleArrayOf(.90, .70, .45, .30, .15, .05),
            listOf({ g -> step(g.crest, 3.2, 2.4) },
                { g -> if ((g.lf + g.hf) > 0) Features.clamp01(((g.lf / (g.lf + g.hf)) - 0.45) * 2) else 0.0 },
                { g -> 1 - step(g.centroid, 1000.0, 550.0) },
                { g -> 1 - Features.clamp01(g.harmN * 1.3) }),
            doubleArrayOf(1.0, .9, .7, .6), veto = { g -> g.harmN > 0.55 }),
        Proto("日常·家居出行", "纸质翻页", doubleArrayOf(.05, .15, .35, .70, 1.0, .55),
            listOf({ g -> if ((g.hf + g.lf) > 0) Features.clamp01(((g.hf / (g.hf + g.lf)) - 0.55) * 2.2) else 0.0 },
                { g -> between(g.centroid, 2800.0, 9000.0) }, { g -> inBand(g.flat, 0.45, 0.92) },
                { g -> 1 - Features.clamp01(g.harmN * 1.5) }),
            doubleArrayOf(1.0, .8, .7, .6), veto = { g -> g.harmN > 0.45 })
    )

    /** 展示阈值（Web 版 OTHER_MIN_*），两处渲染必须共用，否则 UI 会自相矛盾。 */
    const val OTHER_MIN_CLS = 0.28
    const val OTHER_MIN_SCORE = 0.35
    const val OTHER_MIN_MARGIN = 0.05

    /** 打分并按分数降序返回全部候选。 */
    fun inferOtherSource(g: G): List<Candidate> {
        val v = norm6(g.be)
        val out = ArrayList<Candidate>(PROTO.size)
        for (p in PROTO) {
            val t = norm6(p.t)
            val sim = shapeSim(v, t)
            var gs = 0.0; var gw = 0.0
            for (i in p.gates.indices) {
                val r = Features.clamp01(p.gates[i](g))
                gs += r * p.w[i]; gw += p.w[i]
            }
            var score = Features.clamp01(0.60 * sim + 0.40 * (if (gw > 0) gs / gw else 0.0))
            if (p.veto != null && p.veto?.invoke(g) == true) score *= 0.5
            if (p.hard != null && p.hard?.invoke(g) != true) score = 0.0
            out.add(Candidate(p.group, p.name, score))
        }
        return out.sortedByDescending { it.score }
    }

    /** 构造打分用的 G。 */
    fun gOf(f: Features.Frame): G = G(
        be = DoubleArray(6) { f.bands[it].toDouble() },
        centroid = f.centroid, flat = min(1.0, f.flat), rms = f.rms, crest = f.crest,
        zcr = f.zcr, fluxN = min(1.0, f.flux / 15000.0), eventN = f.eventN, crestN = f.crestN,
        harmN = f.harmonicity, f0 = f.f0, f0N = min(1.0, max(0.0, (f.f0 - 80) / 520)),
        spreadN = min(1.0, f.spread / 4000.0), rollN = min(1.0, f.rolloff / 8000.0),
        bandPeakN = f.bandPeakN, hfN = f.hfN, tilt = f.tilt, db = f.dbfs,
        lf = f.bandLin[0] + f.bandLin[1], hf = f.bandLin[4] + f.bandLin[5],
        lfShare = f.lfShare, domVoice = f.domVoice, bpm = f.bpm
    )

    /** L1 归一化到和为 1（模板匹配要求形状可比）。 */
    private fun norm6(x: DoubleArray): DoubleArray {
        var s = 0.0
        for (v in x) s += max(0.0, v)
        if (s <= 0) return DoubleArray(x.size) { 1.0 / x.size }
        return DoubleArray(x.size) { max(0.0, x[it]) / s }
    }

    /** 判定文案。 */
    fun verdictText(c: Conf): String = when (c.top()) {
        "voice" -> if (c.voice >= 0.75) "检测到清晰人声" else "疑似人声"
        "music" -> if (c.music >= 0.6) "检测到音乐（含节拍）" else "疑似音乐/乐音"
        "other" -> if (c.other >= 0.6) "检测到其它声源" else "疑似其它声源"
        else -> if (c.noise >= 0.7) "背景噪声为主" else "噪声成分较重"
    }
}
