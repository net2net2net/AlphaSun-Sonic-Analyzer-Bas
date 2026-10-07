package com.alphasun.sonicanalyzer.core

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * v0.4.0 前景/背景分离引擎（在线背景学习 + 模型质量分析）
 *
 * 对应 Web 版 separationTick / separationUI / fgProfileUI / fgAnalyse。
 * 算法本身在 [Stft] 里（已数值验证），本类负责**在线学习状态机 + 可量化指标**。
 *
 * 需求对应（用户原话）：
 *  「增加学习背景的效果展示、模型情况展示分析等功能」
 *  → 学习进度 / 谱覆盖率 / 模型背景电平 / 背景谱特征 / 主频峰 / 谱平坦度 / 模型可用性
 *  「试听前景 和试听 结合学习背景的效果和模型，算法需要进一步改善」
 *  → [analyze] 给出抑制率 / 残留背景比 / 前景可听性 / 综合评价，并驱动 [Stft] 用**当前模型**做离线重算
 */
class SeparationEngine(private val binCount: Int) {

    companion object {
        const val LEARN_FRAMES = 15         // 3s @ 5Hz（Web 版 fgLearn 3s）
        const val COVER_TARGET = 0.86       // 谱覆盖率目标（≥ 则模型可用）
        /** 覆盖判定的 bin 上限（只统计有意义的频段，避开直流与最高频） */
        const val COVER_LO = 1
    }

    enum class State { IDLE, LEARNING, READY }

    var state = State.IDLE; private set
    var template: DoubleArray? = null; private set     // 已收敛的背景功率谱
    var learnFrames = 0; private set                  // 收敛帧数
    var coverRatio = 0.0; private set                 // 谱覆盖率 0..1
    var noiseDb = Double.NaN; private set             // 模型背景电平 dBFS
    var profileFlat = 0.0; private set                // 背景谱平坦度
    var profilePeakHz = 0.0; private set              // 背景谱主频峰
    var stability = 0.0; private set                  // 背景平稳性 0..1

    // 学习累加器
    private var acc: DoubleArray? = null
    private var accN = 0
    private var lastEma = 0.0

    /** 实时统计（不依赖模型，用于「未学习」时也能给前景/背景占比参考）。 */
    var fgRatio = Double.NaN; private set
    var fgLevel = Double.NaN; private set
    var bgLevel = Double.NaN; private set
    var suppressAmt = Double.NaN; private set

    /** 最近一次离线分离质量分析结果（UI 读）。 */
    var lastAnalysis: Analysis? = null

    fun startLearn() {
        state = State.LEARNING
        acc = DoubleArray(binCount)
        accN = 0
        learnFrames = 0
        coverRatio = 0.0
    }

    fun reset() {
        state = State.IDLE
        template = null
        acc = null; accN = 0; learnFrames = 0
        coverRatio = 0.0; noiseDb = Double.NaN
        profileFlat = 0.0; profilePeakHz = 0.0; stability = 0.0
        fgRatio = Double.NaN; fgLevel = Double.NaN; bgLevel = Double.NaN; suppressAmt = Double.NaN
    }

    /** 学习进度 0..1。 */
    fun learnProgress(): Float =
        if (state == State.LEARNING) (learnFrames.toFloat() / LEARN_FRAMES).coerceIn(0f, 1f)
        else if (state == State.READY) 1f else 0f

    fun stateText(): String = when (state) {
        State.IDLE -> "待机"
        State.LEARNING -> "学习中 ${learnFrames}/$LEARN_FRAMES 帧"
        State.READY -> "已收敛"
    }

    fun modelStateText(): String = when (state) {
        State.IDLE -> "未学习"
        State.LEARNING -> "学习中"
        State.READY -> "已学习（${learnFrames} 帧收敛）"
    }

    /**
     * 每帧调用。power 为本帧功率谱（长度 = binCount）。
     * @param binHz 用于把峰值 bin 换算成 Hz
     */
    fun tick(power: DoubleArray, binHz: Double, rms: Double) {
        if (power.size != binCount) return

        if (state == State.LEARNING) {
            val a = acc ?: DoubleArray(binCount).also { acc = it }
            for (k in 0 until binCount) a[k] += max(0.0, power[k])
            accN++
            learnFrames++
            // 平稳性：帧间总能量变化越小越平稳
            val segE = power.sum()
            if (accN > 1) {
                val d = kotlin.math.abs(segE - lastEma) / max(1e-12, lastEma)
                stability = stability * 0.8 + (1.0 - min(1.0, d * 4.0)) * 0.2
            }
            lastEma = segE
            if (learnFrames >= LEARN_FRAMES) {
                val n = accN.coerceAtLeast(1)
                val tpl = DoubleArray(binCount) { a[it] / n }
                template = tpl
                state = State.READY
                analyseTemplate(tpl, binHz)
            }
            return
        }

        // 已收敛：实时更新前景/背景占比参考（用模型做一次在线粗分离）
        val tpl = template ?: return
        var fgE = 0.0; var bgE = 0.0
        var segP = 0.0
        for (k in 0 until binCount) segP += max(0.0, power[k])
        if (segP <= 0) return
        val tplAvg = tpl.average()
        val gain = if (tplAvg > 0) (segP / binCount) / tplAvg else 0.0
        for (k in 0 until binCount) {
            val p = max(0.0, power[k])
            val b = tpl[k] * gain
            val est = max(p - Stft.OVER * b, Stft.FLOOR * p)
            fgE += est; bgE += (p - est)
        }
        val tot = fgE + bgE
        fgRatio = if (tot > 0) fgE / tot else Double.NaN
        suppressAmt = if (tot > 0) bgE / tot else Double.NaN
        fgLevel = Dsp.dbfs(sqrt(max(0.0, fgE) / binCount))
        bgLevel = Dsp.dbfs(sqrt(max(0.0, bgE) / binCount))
    }

    private fun analyseTemplate(tpl: DoubleArray, binHz: Double) {
        // 谱覆盖率：有多少 bin 拿到了有效（非零非噪底）估计
        var cov = 0
        val hi = (12000.0 / binHz).toInt().coerceIn(COVER_LO, binCount)
        for (k in COVER_LO until hi) if (tpl[k] > 1e-12) cov++
        coverRatio = if (hi > COVER_LO) cov.toDouble() / (hi - COVER_LO) else 0.0

        // 背景电平
        var s = 0.0
        for (k in COVER_LO until binCount) s += tpl[k]
        noiseDb = Dsp.dbfs(sqrt(max(0.0, s) / binCount))

        // 平坦度（用 ≤12kHz 区间，与 Features 的口径一致）
        var logSum = 0.0; var linSum = 0.0; var n = 0
        for (k in COVER_LO until hi) {
            val v = max(1e-12, tpl[k])
            logSum += ln(v); linSum += v; n++
        }
        profileFlat = if (n > 0 && linSum > 0) Math.exp(logSum / n) / (linSum / n) else 0.0

        // 主频峰
        var mi = COVER_LO; var mv = -1.0
        for (k in COVER_LO until hi) if (tpl[k] > mv) { mv = tpl[k]; mi = k }
        profilePeakHz = mi * binHz
    }

    /** 背景谱特征文案。 */
    fun profileFeature(): String = when {
        state != State.READY -> "—"
        profileFlat >= 0.72 -> "宽带型（风扇 / 雨声 / 气流）"
        profileFlat >= 0.45 -> "宽带偏窄（空调 / 通风 / 环境底噪）"
        profileFlat >= 0.20 -> "窄带型（嗡声 / 变压器 / 电器）"
        else -> "强 tonal（工频 / 单频啸叫）"
    }

    /** 背景谱主频峰文案。 */
    fun profilePeakText(): String = when {
        state != State.READY -> "—"
        profilePeakHz <= 0 -> "—"
        profilePeakHz < 100 -> "%.0f Hz（低频）".format(profilePeakHz)
        profilePeakHz < 1000 -> "%.0f Hz".format(profilePeakHz)
        else -> "%.1f kHz".format(profilePeakHz / 1000.0)
    }

    /** 模型可用性判读。 */
    fun profileJudge(): String = when {
        state != State.READY -> "未学习"
        coverRatio < 0.5 -> "覆盖不足（背景太安静或太短）· 建议重学"
        profileFlat < 0.12 -> "窄峰过强 · 分离时该频段会被过度削减"
        coverRatio >= COVER_TARGET && stability > 0.5 -> "良好 · 可用于分离与试听"
        coverRatio >= COVER_TARGET -> "可用 · 背景略不稳定，建议重学一次"
        else -> "勉强可用 · 覆盖率 ${(coverRatio * 100).toInt()}%"
    }

    data class Analysis(
        val modelSource: String,
        val suppressPct: Double,
        val residualPct: Double,
        val hearability: String,
        val verdict: String,
        val fgRms: Double,
        val bgRms: Double,
        val gate: String
    )

    /**
     * 离线重算一段混合音频并给出**分离质量分析**（对应 Web 版 fgAnalyse）。
     * 走 [Stft]，因此与 Web 版数值同源。
     */
    fun analyze(clip: FloatArray): Analysis? {
        if (clip.size < Stft.N * 2) return null
        val res = Stft.subtract(clip, template)
        val fgRms = Dsp.rms(res.fg)
        val bgRms = Dsp.rms(res.bg)
        val mixRms = Dsp.rms(clip)
        // 背景能量抑制率：1 - 背景残留能量 / 原背景估计能量
        val bgEst = max(1e-12, mixRms * mixRms - fgRms * fgRms)
        val supp = ((bgEst - bgRms * bgRms) / bgEst).coerceIn(0.0, 1.0)
        val resid = if (mixRms > 1e-9) (bgRms / mixRms).coerceIn(0.0, 1.0) else 0.0
        val hear = when {
            fgRms < 1e-4 -> "几乎无声（前景可能不在本段）"
            fgRms < 0.01 -> "偏弱"
            fgRms < 0.06 -> "可听"
            fgRms < 0.25 -> "清晰"
            else -> "偏响（注意削波）"
        }
        val verdict = when {
            supp < 0.15 -> "抑制不足 · 背景未被有效扣除"
            supp > 0.85 && fgRms < 0.005 -> "过度抑制 · 前景可能被误删"
            supp > 0.55 -> "分离良好"
            else -> "分离一般 · 可调高过减因子或重学背景"
        }
        return Analysis(
            modelSource = if (res.usedModel) "在线学习模型（${learnFrames} 帧）" else "未学习 · 等量背景假设",
            suppressPct = supp * 100.0,
            residualPct = resid * 100.0,
            hearability = hear,
            verdict = verdict,
            fgRms = fgRms,
            bgRms = bgRms,
            gate = "过减 ${Stft.OVER} · 谱地板 ${Stft.FLOOR} · 学习 ${learnFrames} 帧"
        )
    }
}
