package com.alphasun.sonicanalyzer.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 应用内自检（`SelfCheck`）
 *
 * ## 为什么要有它
 *
 * 前几轮迭代反复出现同一类问题：**编译 100% 通过、单看每个函数都对、
 * 只有真机上才全废**（例如 v0.6.1 的头号根因——立体声设备上主帧恒为空，
 * 界面显示"采集中"但所有读数一动不动）。
 * 这类问题在 CI 里看不见，在开发环境里又因为无法真机安装而验证不了。
 *
 * 本自检把「某个功能到底有没有真的在工作」变成**用户手机上可一键执行、
 * 结果可复制粘贴**的客观清单，分为两层：
 *
 *  · **算法层**（13 项）：纯 Kotlin，不依赖 Android 框架，JVM 单测可直跑；
 *  · **设备层**（8 项）：权限、AudioRecord 是否真的出数、主循环异常计数、
 *    摄像头路数、存储可写等——这些只有真机能回答。
 *
 * 设备层最关键的一项是 **「主帧非空且有能量」**，它正是 v0.6.1 头号根因的
 * 直接验收点：只要这一项红，就说明采集层又没把数据送上来，
 * 不必再去猜算法是不是写错了。
 *
 * ## 用法
 *
 * ```kotlin
 * val report = SelfCheck.run(viewModel.selfCheckDevice())
 * Log.i("SelfCheck", report.text())
 * ```
 */
object SelfCheck {

    data class Item(
        val group: String,
        val name: String,
        val ok: Boolean,
        val detail: String
    )

    data class Report(val items: List<Item>, val ms: Long) {
        val passed: Int get() = items.count { it.ok }
        val failed: Int get() = items.count { !it.ok }

        fun title(): String = if (failed == 0)
            "自检通过：$passed / ${items.size} 项（耗时 ${ms} ms）"
        else
            "自检发现 $failed 项异常：$passed / ${items.size} 项通过（耗时 ${ms} ms）"

        /** 可整段复制粘贴给开发者排障 */
        fun text(): String = buildString {
            appendLine(title())
            var g = ""
            for (it in items) {
                if (it.group != g) {
                    g = it.group
                    appendLine()
                    appendLine("【$g】")
                }
                appendLine("${if (it.ok) "[通过]" else "[异常]"} ${it.name} — ${it.detail}")
            }
        }

        fun countByGroup(): List<Pair<String, Int>> =
            items.groupBy { it.group }.map { it.key to it.value.count { i -> i.ok } }
    }

    /**
     * 设备侧探针接口。由 ViewModel 实现并注入；传 null 时只跑算法层
     * （JVM 单元测试正是这么用的）。
     */
    interface Device {
        val hasMicPermission: Boolean
        val capturing: Boolean
        val sampleRate: Int
        val channelCount: Int
        val fftSize: Int
        /** 采集层最新主帧（ch0），长度应为 fftSize */
        val frame: FloatArray
        /** 多通道原始数据；单声道时为 null */
        val multi: Array<FloatArray>?
        val fakeStereo: Boolean
        /** 主循环累计异常帧数，正常应为 0 */
        val loopErrors: Int
        /** 采集层诊断文本（采样率/通道/音源） */
        val diag: String
        /** 可用摄像头路数（0 = 无） */
        val cameraCount: Int
        val cameraDetail: String
        /** 抓拍/录音目录是否可写 */
        val storageOk: Boolean
        val storageDetail: String
    }

    fun run(d: Device? = null): Report {
        val t0 = System.currentTimeMillis()
        val out = ArrayList<Item>()
        algoChecks(out)
        if (d != null) deviceChecks(out, d)
        return Report(out, System.currentTimeMillis() - t0)
    }

    // ==================== 算法层（纯 Kotlin） ====================

    private fun algoChecks(out: ArrayList<Item>) {
        val G = "算法层（离线）"

        // 1. FFT 往返
        run {
            val n = 1024
            val src = DoubleArray(n) { sin(2.0 * PI * 5.0 * it / n) + 0.3 * sin(2.0 * PI * 37.0 * it / n) }
            val re = src.copyOf()
            val im = DoubleArray(n)
            Dsp.fft(re, im, false)
            Dsp.fft(re, im, true)
            var err = 0.0
            for (i in 0 until n) err = max(err, abs(re[i] - src[i]))
            out.add(Item(G, "FFT 正/逆变换往返", err < 1e-9, "最大重建误差 ${"%.2e".format(err)}"))
        }

        // 2. 功率谱峰值定位
        run {
            val sr = 48000
            val n = 2048
            val f0 = 1000.0
            val sig = FloatArray(n) { (0.6 * sin(2.0 * PI * f0 * it / sr)).toFloat() }
            val power = DoubleArray(n / 2 + 1)
            Dsp.powerSpectrum(sig, power)
            var bi = 1
            for (i in 1 until power.size) if (power[i] > power[bi]) bi = i
            val binHz = sr.toDouble() / n
            val est = bi * binHz
            out.add(
                Item(
                    G, "功率谱峰值定位", abs(est - f0) <= binHz * 1.5,
                    "1kHz 正弦 → 峰值 bin #$bi（${"%.1f".format(est)} Hz，分辨率 ${"%.1f".format(binHz)} Hz）"
                )
            )
        }

        // 3. RMS / dBFS 数值
        run {
            val n = 2048
            val amp = 0.5
            val sig = FloatArray(n) { (amp * sin(2.0 * PI * 11.0 * it / n)).toFloat() }
            val r = Dsp.rms(sig)
            val db = Dsp.dbfs(r)
            // 幅度 A 的正弦 → RMS = A/√2 → dBFS = 20log10(A/√2)
            val want = 20.0 * kotlin.math.log10(amp / sqrt(2.0))
            out.add(
                Item(
                    G, "RMS / dBFS 数值", abs(db - want) < 0.05,
                    "RMS ${"%.4f".format(r)} → ${"%.2f".format(db)} dBFS（理论 ${"%.2f".format(want)}）"
                )
            )
        }

        // 4. 相关系数
        run {
            val n = 1024
            val a = FloatArray(n) { sin(2.0 * PI * 7.0 * it / n).toFloat() }
            val b = FloatArray(n) { (0.5 * sin(2.0 * PI * 23.0 * it / n)).toFloat() }
            val same = Dsp.correlation(a, a.copyOf())
            val diff = Dsp.correlation(a, b)
            out.add(
                Item(
                    G, "通道相关系数", abs(same - 1.0) < 1e-6 && abs(diff) < 0.5,
                    "自身 ${"%.4f".format(same)}（应为 1）/ 异信号 ${"%.4f".format(diff)}（应≈0）"
                )
            )
        }

        // 5. 伪立体声判定
        run {
            val n = 1024
            val a = FloatArray(n) { sin(2.0 * PI * 13.0 * it / n).toFloat() }
            val b = FloatArray(n) { sin(2.0 * PI * 29.0 * it / n + 1.1).toFloat() }
            val fake = Dsp.isFakeStereo(a, a.copyOf())
            val real = Dsp.isFakeStereo(a, b)
            out.add(
                Item(
                    G, "伪立体声判定", fake && !real,
                    "复制通道=${fake}（应 true）/ 独立通道=${real}（应 false）"
                )
            )
        }

        // 6. STFT 谱减：前景/背景必须真的不同且都可听
        run {
            val sr = 48000
            val n = 8192
            val rnd = java.util.Random(7)
            val sig = FloatArray(n) {
                (0.45 * sin(2.0 * PI * 440.0 * it / sr) + 0.18 * rnd.nextGaussian()).toFloat()
            }
            val r = Stft.subtract(sig, null)
            val fgOk = r.fg.size == n && finite(r.fg)
            val bgOk = r.bg.size == n && finite(r.bg)
            val fgRms = Dsp.rms(r.fg)
            val corr = if (fgOk && bgOk) Dsp.correlation(r.fg, r.bg) else Double.NaN
            out.add(
                Item(
                    G, "前景/背景分离", fgOk && bgOk && fgRms > 1e-4 && (corr.isNaN() || abs(corr) < 0.999),
                    "帧数 ${r.frames} · 前景 RMS ${"%.4f".format(fgRms)} · 前景/背景相关 ${"%.3f".format(corr)} · 抑制比 ${"%.1f".format(r.suppressRatio * 100)}%"
                )
            )
        }

        // 7. 响度归一化
        run {
            val buf = FloatArray(4096) { (0.05 * sin(2.0 * PI * 9.0 * it / 4096)).toFloat() }
            val before = Dsp.dbfs(Dsp.rms(buf))
            val gain = Stft.normalize(buf)
            val after = Dsp.dbfs(Dsp.rms(buf))
            out.add(
                Item(
                    G, "试听响度归一化", after > before && after <= -9.0 && gain.isFinite(),
                    "${"%.1f".format(before)} → ${"%.1f".format(after)} dBFS（增益 ${"%.2f".format(gain)}×）"
                )
            )
        }

        // 8. GCC-PHAT 时差估计
        run {
            val sr = 48000
            val n = 2048
            val d = 8
            val rnd = java.util.Random(11)
            val base = FloatArray(n) { (rnd.nextGaussian() * 0.5).toFloat() }
            val x = FloatArray(n)
            val y = FloatArray(n)
            for (i in 0 until n) {
                x[i] = base[i]
                y[i] = if (i >= d) base[i - d] else 0f
            }
            val t = GccPhat.estimate(x, y, sr)
            val errSamples = if (t == null) Double.NaN else abs(abs(t.tau) * sr - d)
            out.add(
                Item(
                    G, "GCC-PHAT 时差估计", t != null && errSamples < 2.0,
                    if (t == null) "估计失败（返回 null —— 会导致定位恒无结果）"
                    else "真值 ${d} 样本 → 估计 ${"%.2f".format(abs(t.tau) * sr)} 样本（误差 ${"%.2f".format(errSamples)}，峰值 ${"%.2f".format(t.peak)}）"
                )
            )
        }

        // 9. 双麦方位解算
        run {
            val az0 = TdoaSolver.azFromDual(0.0, 0.12)
            val az1 = TdoaSolver.azFromDual(0.00015, 0.12)
            out.add(
                Item(
                    G, "双麦方位解算", az0.isFinite() && az1.isFinite() && abs(az1) > 1.0,
                    "τ=0 → ${"%.1f".format(az0)}°；τ=150µs → ${"%.1f".format(az1)}°"
                )
            )
        }

        // 10. 特征分析（23 项参数）
        run {
            val sr = 48000
            val n = 2048
            val sig = FloatArray(n) { (0.4 * sin(2.0 * PI * 220.0 * it / sr)).toFloat() }
            val power = DoubleArray(n / 2 + 1)
            Dsp.powerSpectrum(sig, power)
            val mag = DoubleArray(power.size) { sqrt(maxOf(power[it], 0.0)) }
            val f = Features.Analyzer(sr, n).analyze(sig, mag)
            val ok = f.centroid.isFinite() && f.rms.isFinite() && f.dbfs.isFinite() &&
                f.dbA.isFinite() && f.crest.isFinite() && f.bands.size == 6
            out.add(
                Item(
                    G, "23 项声波特征", ok,
                    "质心 ${"%.0f".format(f.centroid)} Hz · RMS ${"%.4f".format(f.rms)} · ${"%.1f".format(f.dbA)} dB(A) · 六段 ${f.bands.size}"
                )
            )
        }

        // 11. 智能分类
        run {
            val sr = 48000
            val n = 2048
            val sig = FloatArray(n) { (0.4 * sin(2.0 * PI * 220.0 * it / sr)).toFloat() }
            val power = DoubleArray(n / 2 + 1)
            Dsp.powerSpectrum(sig, power)
            val mag = DoubleArray(power.size) { sqrt(maxOf(power[it], 0.0)) }
            val f = Features.Analyzer(sr, n).analyze(sig, mag)
            val c = Classify.Engine().tick(f)
            /**
             * 注意：四条置信度是**各自独立的启发式打分**，不是 softmax 概率分布，
             * 因此**不要求和为 1**（实测常在 0.6~1.2 之间）。
             * 只要求全为有限数、且至少有一条被真正激活（>0），
             * 否则界面上就是"四项全 0 / 出现 NaN"这种坏结果。
             */
            val all = listOf(c.voice, c.music, c.other, c.noise)
            val sum = all.sum()
            out.add(
                Item(
                    G, "智能分类", all.all { it.isFinite() } && all.any { it > 0.01 },
                    "人声 ${"%.2f".format(c.voice)} 音乐 ${"%.2f".format(c.music)} 其他 ${"%.2f".format(c.other)} 噪音 ${"%.2f".format(c.noise)}（非概率分布，和 ${"%.2f".format(sum)}）→ ${Classify.verdictText(c)}"
                )
            )
        }

        // 12. 背景学习
        run {
            val bins = 1025
            val e = SeparationEngine(bins)
            e.startLearn()
            val noise = DoubleArray(bins) { 0.001 + 0.0005 * (it % 7) }
            val rnd = java.util.Random(3)
            for (i in 0..40) {
                val p = DoubleArray(bins) { noise[it] * (0.9 + 0.2 * rnd.nextDouble()) }
                e.tick(p, 48000.0 / 2048.0, 0.05)
            }
            val prog = e.learnProgress()
            val an = e.analyze(FloatArray(8192) { (0.3 * sin(2.0 * PI * 300.0 * it / 48000.0)).toFloat() })
            out.add(
                Item(
                    G, "背景模型学习", prog > 0f && e.stateText().isNotEmpty(),
                    "进度 ${"%.0f".format(prog * 100)}% · 状态 ${e.stateText()} · 模型 ${e.modelStateText()} · 谱型 ${e.profileFeature()}"
                )
            )
            out.add(
                Item(
                    G, "背景模型分析产出", an != null,
                    if (an == null) "analyze() 返回 null（试听与展示将不可用）" else "覆盖/稳定/峰频等字段已产出"
                )
            )
        }

        // 13. 噪音评估
        run {
            val ne = NoiseEval(48000.0 / 2048.0)
            ne.calibrateRef(94.0)
            val bands = doubleArrayOf(0.2, 0.3, 0.2, 0.15, 0.1, 0.05)
            for (i in 0..20) ne.tick(-30.0 - i * 0.1, bands, 1000L + i * 50L)
            val leq = ne.leq()
            out.add(
                Item(
                    G, "噪音评估", leq.isFinite() && ne.current().isFinite(),
                    "Leq ${"%.1f".format(leq)} dB · 当前 ${"%.1f".format(ne.current())} dB · ${ne.type()}"
                )
            )
        }

        // 14. 警戒状态机：预警与告警**都要**能记录
        run {
            val g = GuardEngine()
            g.auto = false
            g.manualThr = -20.0
            g.warnMargin = 8.0
            g.alarmMargin = 15.0
            g.manualUseFloor = true
            g.manualFloor = -60.0
            var t = 1000L
            g.start(t)
            val frame = FloatArray(2048)
            // ① 安静段：不应产生任何事件
            for (i in 0..10) { g.tick(-55.0, frame, 48000, t, 50.0); t += 50 }
            val quietN = g.events.size + (if (g.current == null) 0 else 1)
            // ② 黄色预警段
            for (i in 0..10) { g.tick(-18.0, frame, 48000, t, 50.0); t += 50 }
            /**
             * 注意：进行中的事件挂在 `current` 上，只有 `finishEvent()` 才入 `events`。
             * 只看 `events` 会把"事件正在发生但还没结束"误判为"没记录"。
             */
            val warnRecorded =
                g.current?.level == GuardEngine.Level.WARN || g.events.any { it.level == GuardEngine.Level.WARN }
            // ③ 红色告警段（同一事件升级）
            for (i in 0..10) { g.tick(-5.0, frame, 48000, t, 50.0); t += 50 }
            val alarmRecorded =
                g.current?.level == GuardEngine.Level.ALARM || g.events.any { it.level == GuardEngine.Level.ALARM }
            val upgraded = (g.current?.upgradedAtMs ?: 0L) > 0L || g.events.any { it.upgradedAtMs > 0L }
            g.stop()
            out.add(
                Item(
                    G, "声波警戒状态机", quietN == 0 && warnRecorded && alarmRecorded && upgraded,
                    "安静段事件 $quietN（应为 0） · 预警已记录=$warnRecorded · 告警已记录=$alarmRecorded · 预警→告警升级=$upgraded"
                )
            )
        }
    }

    // ==================== 设备层（真机） ====================

    private fun deviceChecks(out: ArrayList<Item>, d: Device) {
        val G = "设备层（真机）"

        out.add(
            Item(G, "麦克风权限", d.hasMicPermission,
                if (d.hasMicPermission) "已授予 RECORD_AUDIO" else "未授予 —— 采集必然失败")
        )

        out.add(
            Item(G, "采集已启动", d.capturing,
                if (d.capturing) "主循环运行中" else "未在运行 —— 点底部「开始采集」")
        )

        // —— v0.6.1 头号根因的直接验收点 ——
        val fr = d.frame
        val rms = if (fr.isNotEmpty()) Dsp.rms(fr) else 0.0
        out.add(
            Item(
                G, "主帧非空且有信号", fr.size == d.fftSize && rms > 1e-6,
                when {
                    fr.isEmpty() -> "主帧长度 0 —— 采集层没把数据送上来（v0.6.1 头号根因复发了）"
                    fr.size != d.fftSize -> "主帧长度 ${fr.size} ≠ fftSize ${d.fftSize}"
                    rms <= 1e-6 -> "主帧全静音（RMS ${"%.2e".format(rms)}）—— 环境很安静，或麦克风被静音/遮挡"
                    else -> "长度 ${fr.size}（= fftSize）· RMS ${"%.5f".format(rms)} · ${"%.1f".format(Dsp.dbfs(rms))} dBFS"
                }
            )
        )

        out.add(
            Item(G, "主循环无累计异常", d.loopErrors == 0,
                if (d.loopErrors == 0) "0 次异常帧"
                else "已累计 ${d.loopErrors} 次异常帧 —— 说明每帧处理里有抛异常的分支")
        )

        val m = d.multi
        out.add(
            Item(
                G, "通道与阵列判定",
                d.channelCount >= 1,
                "${d.channelCount} 通道 · 多通道数据 ${if (m == null) "无" else "${m.size} 路"} · " +
                    if (d.channelCount >= 2) {
                        if (d.fakeStereo) "判定为伪立体声（单麦复制）→ 定位保持仿真，不做假定位"
                        else "两路相互独立 → 可用真实阵列 TDOA"
                    } else "单声道 → 定位使用阵列仿真"
            )
        )

        out.add(
            Item(G, "摄像头", d.cameraCount >= 1,
                "${d.cameraCount} 路 · ${d.cameraDetail}" +
                    if (d.cameraCount >= 2) "" else "（不足 2 路：双摄抓拍将降级为单路，属正常降级，不应崩溃）")
        )

        out.add(
            Item(G, "抓拍/录音存储可写", d.storageOk, d.storageDetail)
        )

        out.add(Item(G, "采集参数诊断", d.sampleRate > 0, d.diag))
    }

    private fun finite(a: FloatArray): Boolean {
        for (v in a) if (!v.isFinite()) return false
        return true
    }
}
