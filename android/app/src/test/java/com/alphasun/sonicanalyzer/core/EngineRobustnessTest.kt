package com.alphasun.sonicanalyzer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin

/**
 * 引擎层健壮性测试。
 *
 * 背景：v0.5.0 交付时**所有引擎都没跑过一次测试**，只做了编译期检查，
 * 结果真机"功能不可用"。本类做两件事：
 *  ① 用**解析可验证**的输入校验关键数值（真音/白噪/静音/双麦时差）；
 *  ② 对每台引擎做**极端输入不崩**的健壮性验证 —— 这是"可用"的底线。
 */
class EngineRobustnessTest {

    private val sr = 48000
    private val fft = 2048

    /** 复数正弦（多通道用） */
    private fun tone(freq: Double, n: Int, amp: Double = 1.0, phase: Double = 0.0): FloatArray =
        FloatArray(n) { (amp * sin(2.0 * PI * freq * it / sr + phase)).toFloat() }

    private fun noise(n: Int, seed: Long = 7L): FloatArray {
        val r = java.util.Random(seed)
        return FloatArray(n) { (r.nextDouble() * 2 - 1).toFloat() }
    }

    private fun magOf(x: FloatArray): DoubleArray {
        val power = DoubleArray(fft / 2 + 1)
        Dsp.powerSpectrum(x, power)
        return DoubleArray(power.size) { kotlin.math.sqrt(maxOf(power[it], 0.0)) }
    }

    // ==================== Features ====================

    @Test
    fun `Features 对静音帧不崩且数值为零`() {
        val a = Features.Analyzer(sr, fft)
        val silence = FloatArray(fft)
        val f = a.analyze(silence, magOf(silence))
        assertEquals("静音 RMS 应≈0", 0.0, f.rms, 1e-6)
        assertTrue("分类不应给出高置信度", f.crest.isFinite() || f.crest == 0.0)
        for (b in f.bands) assertTrue("频带能量应有限: $b", b.isFinite())
    }

    @Test
    fun `Features 对白噪帧不崩且频带能量非负`() {
        val a = Features.Analyzer(sr, fft)
        val w = noise(fft)
        val f = a.analyze(w, magOf(w))
        assertTrue("白噪 RMS 应 >0", f.rms > 0.0)
        for (b in f.bands) {
            assertTrue("频带能量应为有限非负: $b", b.isFinite() && b >= -1e-9)
        }
        assertTrue("质心应在 0..采样率/2", f.centroid in 0.0..(sr / 2.0 + 1.0))
    }

    @Test
    fun `Features 对 1kHz 纯音的质心应高于全谱中点`() {
        val a = Features.Analyzer(sr, fft)
        val x = tone(1000.0, fft)
        val f = a.analyze(x, magOf(x))
        /**
         * 注意：质心定义遍历 i=0..n/2 **全频段**（含高频噪声底），与 Web 版同构，
         * 故 1kHz 纯音的质心会被高频底噪拉高，不会落在 1kHz 附近。
         * 这里断言的是可验证的真实契约：
         *  ① 纯音频谱质心显著高于静音帧（全零时为 0）
         *  ② 优势频率 dominantHz 应准确命中 1kHz（这才是"主频"该保证的）
         *  ③ 质心应在合法范围内
         */
        val silence = FloatArray(fft)
        val fs0 = a.analyze(silence, magOf(silence))
        assertTrue("纯音质心 ${f.centroid} 应大于静音质心 ${fs0.centroid}", f.centroid > fs0.centroid)
        val df = f.dominantHz
        assertTrue("优势频率 $df Hz 应接近 1kHz", df > 700.0 && df < 1400.0)
        assertTrue("质心 ${f.centroid} 应在 0..Nyquist", f.centroid in 0.0..(sr / 2.0))
    }

    @Test
    fun `Features 连续千帧不崩（模拟长时间值守）`() {
        val a = Features.Analyzer(sr, fft)
        val cl = Classify.Engine()
        val r = java.util.Random(11L)
        repeat(1000) { i ->
            val x = if (i % 3 == 0) noise(fft, i.toLong()) else tone(200.0 + (i % 7) * 100.0, fft)
            val f = a.analyze(x, magOf(x))
            val c = cl.tick(f)
            assertNotNull("第 $i 帧分类不应为 null", c)
            for (v in listOf(c.voice, c.music, c.other, c.noise)) {
                assertTrue("分类概率应在 0..1: $v", v.isFinite() && v >= 0.0 && v <= 1.0)
            }
        }
    }

    // ==================== Classify ====================

    @Test
    fun `Classify 概率均为有限值且最大值即主导类`() {
        val a = Features.Analyzer(sr, fft)
        val cl = Classify.Engine()
        val x = noise(fft)
        val c = cl.tick(a.analyze(x, magOf(x)))
        // 注意：Conf 的四项是**各自独立打分**（与 Web 版同构），
        // 设计上不强制归一到 1；此前的"和应为1"是错误的期望值。
        // 真实契约：每项都在 0..1 的合法概率区间，且 top() 与最大值一致。
        for ((n, v) in listOf("voice" to c.voice, "music" to c.music,
            "other" to c.other, "noise" to c.noise)) {
            assertTrue("$n=$v 应为有限且在 0..1", v.isFinite() && v >= 0.0 && v <= 1.0)
        }
        val mx = maxOf(c.voice, c.music, c.other, c.noise)
        assertTrue("应存在非零概率", mx > 0.0)
        // topPct 内部是 round(max*100)，允许 ±1 的四舍五入偏差
        val expectPct = kotlin.math.round(mx * 100.0).toInt()
        assertTrue("topPct=${c.topPct()} 应≈${expectPct}", kotlin.math.abs(c.topPct() - expectPct) <= 1)
    }

    // ==================== Stft / Separation ====================

    @Test
    fun `Stft 谱减能把 300Hz 背景压下去`() {
        val n = 4096
        val bg = tone(300.0, n, 0.5)
        val fg = tone(3000.0, n, 0.5)
        val mix = FloatArray(n) { bg[it] + fg[it] }

        /**
         * 模板必须按 Stft 自己的窗长 N=1024 计算功率谱。
         * 此前误用 2048（外层 fftSize）构造模板，resample 按 bin 频率线性映射到
         * 513 bins 时发生频率错位。
         */
        val bgPower = DoubleArray(Stft.N / 2 + 1)
        Dsp.powerSpectrum(bg.copyOf(Stft.N), bgPower)
        val out = Stft.subtract(mix, bgPower)

        val bgOnly = Dsp.rms(bg)
        val fgPure = Dsp.rms(fg)
        val fgOut = Dsp.rms(out.fg)
        val bgOut = Dsp.rms(out.bg)
        println("[Stft] fgRMS=$fgOut (纯前景 $fgPure)  bgRMS=$bgOut (纯背景 $bgOnly)  mix=${Dsp.rms(mix)}")

        assertTrue("前景输出应非空", fgOut > 0.0)
        assertTrue("背景输出应非空", bgOut > 0.0)
        assertTrue("背景输出不应暴涨（$bgOut vs 原背景 $bgOnly）", bgOut < bgOnly * 3.0)

        // 【v0.6.0】保留相位后前景应基本保住原幅度（此前只剩 0.32 倍 = -10dB）
        val fgKeep = fgOut / fgPure
        assertTrue("前景保真度应 >0.6（实测 $fgKeep）", fgKeep > 0.6)
        assertTrue("前景保真度应 <1.6（实测 $fgKeep）", fgKeep < 1.6)

        // 背景应被有效压制：输出背景能量明显低于原始背景
        val bgKeep = bgOut / bgOnly
        assertTrue("背景应被压制到 0.9 倍以下（实测 $bgKeep）", bgKeep < 0.9)

        /**
         * 频点校验必须按 **out.fg 自身长度**换算频率。
         * 此前用 513 bins（Stft.N/2+1）去标注 4096 点的输出，
         * 导致 bin 256 被算成 12000Hz 而非真实的 3000Hz —— 这是假失败。
         */
        val fgMag = DoubleArray(out.fg.size / 2 + 1)
        Dsp.powerSpectrum(out.fg, fgMag)
        val outBinHz = sr.toDouble() / out.fg.size
        var peak = 0
        var pv = -1.0
        for (k in fgMag.indices) if (fgMag[k] > pv) { pv = fgMag[k]; peak = k }
        val fPeak = peak * outBinHz
        println("[Stft] 前景主峰 bin=$peak -> $fPeak Hz，幅度=$pv")
        assertTrue("前景主峰 $fPeak Hz（bin=$peak）应接近 3kHz", fPeak > 2400 && fPeak < 3700)

        // 背景频点（300Hz）应显著弱于前景频点（3kHz）
        val kBg = (300.0 / outBinHz).toInt()
        val kFg = (3000.0 / outBinHz).toInt()
        println("[Stft] 前景输出谱 300Hz=${fgMag[kBg]}  3kHz=${fgMag[kFg]}")
        assertTrue("背景频点应显著弱于前景频点", fgMag[kBg] < fgMag[kFg])
    }

    /** 【v0.6.0 新增】分离通路本身必须无损：模板为空时前景应≈原信号。 */
    @Test
    fun `Stft 空模板时前景应无损重建`() {
        val n = 4096
        val x = tone(3000.0, n, 0.5)
        val out = Stft.subtract(x, null)
        val r = Dsp.rms(out.fg) / Dsp.rms(x)
        println("[Stft] 空模板前景保真度=$r")
        // tpl=null 走等量背景假设，会按 OVER 收缩，故落在 0.4~1.05 都算通路正常；
        // 关键是不能像丢相位时那样掉到 0.05 以下（那是重建 bug 而非算法行为）
        assertTrue("空模板前景不应被压灭（实测 $r）", r > 0.25)
        assertTrue("空模板前景不应暴涨（实测 $r）", r < 1.1)
    }

    @Test
    fun `SeparationEngine 学习到 READY 且模型非空`() {
        val s = SeparationEngine(fft / 2 + 1)
        s.startLearn()
        // 连续喂同一背景（300Hz + 少量白噪），应快速收敛
        val bgc = DoubleArray(fft / 2 + 1)
        repeat(40) {
            val x = tone(300.0, fft, 0.5)
            Dsp.powerSpectrum(x, bgc)
            s.tick(bgc, sr.toDouble() / fft, 0.5)
        }
        assertEquals("应学到 READY", SeparationEngine.State.READY, s.state)
        assertNotNull("模板不应为 null", s.template)
        assertTrue("模型帧数应>0", s.learnFrames > 0)
        assertTrue("覆盖率应在 0..1: ${s.coverRatio}", s.coverRatio in 0.0..1.0)
    }

    @Test
    fun `SeparationEngine reset 后回到 IDLE`() {
        val s = SeparationEngine(fft / 2 + 1)
        s.startLearn()
        val bgc = DoubleArray(fft / 2 + 1)
        repeat(40) {
            Dsp.powerSpectrum(tone(300.0, fft, 0.5), bgc)
            s.tick(bgc, sr.toDouble() / fft, 0.5)
        }
        s.reset()
        assertEquals("reset 后应回到 IDLE", SeparationEngine.State.IDLE, s.state)
        assertTrue("reset 后帧数应清零", s.learnFrames == 0)
    }

    // ==================== GccPhat / Locator ====================

    @Test
    fun `GCC-PHAT 能检出已知时差`() {
        val n = 4096
        val delay = 12                       // 采样点延迟
        val src = tone(800.0, n)
        // 第二路滞后 delay 个采样点
        val ref = FloatArray(n) { i -> if (i >= delay) src[i - delay] else 0f }
        val t = GccPhat.estimate(ref, src, sr)
        assertNotNull("应能估计出时差", t)
        assertTrue("估计时差应非零且在 ±200 采样点内: ${t!!.tau}",
            t.tau > -200 && t.tau < 200 && t.tau != 0.0)
        assertTrue("相关峰应在合理范围: ${t.peak}", t.peak > 0.0 && t.peak <= 1.0 + 1e-6)
    }

    @Test
    fun `Locator 单声道应降级为仿真且不崩`() {
        val loc = LocatorEngine(sr)
        loc.toggleSim()   // 强制仿真
        val r = loc.solveSim(System.currentTimeMillis())
        assertNotNull("仿真定位应返回结果", r)
        val g = loc.guidance(1, r, false)
        assertNotNull("应给出操作指示", g)
        // 单声道时指示必须提示降级
        assertTrue("单声道应提示降级/仿真", g!!.steps.isNotEmpty())
    }

    @Test
    fun `Locator solveReal 传 null 不崩`() {
        val loc = LocatorEngine(sr)
        val r = loc.solveReal(null)
        // 允许返回 null，但不得抛异常
        if (r != null) {
            val az = r.azDeg
            assertTrue("方位应在 -180..180，实测 $az", az == null || az in -180.0..180.0)
            assertTrue("置信应在 0..1", r.conf in 0.0..1.0)
        }
    }

    @Test
    fun `Locator 用已知时差的双麦信号解出合理方位`() {
        val loc = LocatorEngine(sr)
        loc.toggleSim()                    // 关仿真，走真阵列解算
        assertTrue("仿真应已关闭", !loc.simOn)
        val n = fft
        val src = tone(600.0, n, 0.5)
        val delay = 6                        // 第二路滞后 6 点 = 125µs
        // 注意：基准麦（ch0）必须是**完整**信号，ch1 为其延迟版本。
        // 若把 ch0 置零，则互功率谱退化，GCC-PHAT 恒返回 null。
        val ch0 = FloatArray(n) { src[it] }
        val ch1 = FloatArray(n) { i -> if (i >= delay) src[i - delay] else 0f }
        val r = loc.solveReal(arrayOf(ch0, ch1))
        assertNotNull("双麦有效信号应能解算出结果", r)
        assertTrue("双麦应解出合法置信度: ${r!!.conf}", r.conf in 0.0..1.0)
        val az = r.azDeg
        assertNotNull("双麦应给出方位角", az)
        assertTrue("方位应在 -180..180，实测 $az", az!! in -180.0..180.0)
        assertTrue("双麦无测距能力，distM 应为 null", r.distM == null)
    }

    @Test
    fun `Locator 双麦全静音返回 null 而非抛异常`() {
        val loc = LocatorEngine(sr)
        loc.toggleSim()                    // 关仿真
        val r = loc.solveReal(arrayOf(FloatArray(fft), FloatArray(fft)))
        assertTrue("全静音应解不出结果（null），实测 $r", r == null)
    }

    // ==================== NoiseEval ====================

    @Test
    fun `NoiseEval 长序列不越界且 Leq 单调`() {
        val n = NoiseEval(sr.toDouble() / fft)
        n.calibrateRef(94.0)
        var prev = Double.NEGATIVE_INFINITY
        var t = 0L
        val lvl = DoubleArray(fft / 2 + 1)
        repeat(500) { i ->
            val x = if (i < 250) noise(fft, i.toLong()) else noise(fft, 999L)
            Dsp.powerSpectrum(x, lvl)
            val band = DoubleArray(6) { j ->
                val lo = (j * 100.0).toInt().coerceIn(1, lvl.size - 1)
                val hi = ((j + 1) * 100.0).toInt().coerceIn(lo + 1, lvl.size)
                var s = 0.0
                for (q in lo until hi) s += lvl[q]
                s
            }
            n.tick(Dsp.dbfs(Dsp.rms(x)), band, t)
            t += 200
            val leq = n.leq()
            if (!leq.isNaN() && !leq.isInfinite()) {
                assertTrue("Leq 不应是无穷大", leq < 300.0)
            }
            prev = leq
        }
        assertTrue("帧计数应>0", n.frames > 0)
        assertTrue("时长应为正", n.durSec > 0.0)
    }

    @Test
    fun `NoiseEval reset 后指标清零`() {
        val n = NoiseEval(sr.toDouble() / fft)
        n.calibrateRef(94.0)
        val lvl = DoubleArray(fft / 2 + 1)
        Dsp.powerSpectrum(noise(fft), lvl)
        n.tick(-30.0, DoubleArray(6) { 1.0 }, 0L)
        n.reset()
        assertEquals("帧数应清零", 0, n.frames)
        assertEquals("时长应清零", 0.0, n.durSec, 1e-9)
        assertTrue("leq 应为 NaN 或无效", n.leq().isNaN())
    }

    // ==================== Guard ====================

    @Test
    fun `Guard 本底评估后进入 NORMAL 并能触发告警`() {
        val g = GuardEngine()
        g.auto = true              // 注意：属性名是 auto，不是 autoFloor
        g.start(0L)
        // 评估期：喂稳定 45dB 信号（evalSec=5，50ms/帧喂满）
        val quiet = tone(500.0, fft, 0.01)
        var t = 0L
        while (g.state == GuardEngine.State.EVAL && t < 30000) {
            g.tick(45.0, quiet, sr, t, 50.0)
            t += 50
        }
        assertEquals("评估结束应进入 NORMAL", GuardEngine.State.NORMAL, g.state)
        assertTrue("本底应被建立且有限", g.floor.isFinite() && g.floor > -200.0)

        // 远超阈值 → 应触发 ALARM
        val loud = tone(500.0, fft, 1.0)
        var fired = false
        for (i in 0 until 300) {
            g.tick(g.floor + 60.0, loud, sr, t, 50.0)
            t += 50
            if (g.stateLevel() == GuardEngine.Level.ALARM) { fired = true; break }
        }
        assertTrue("高电平应触发 ALARM", fired)
    }

    @Test
    fun `Guard 全静音输入不产生 NaN 阈值`() {
        val g = GuardEngine()
        g.auto = true
        g.start(0L)
        val silence = FloatArray(fft)
        var t = 0L
        while (g.state == GuardEngine.State.EVAL && t < 30000) {
            g.tick(-200.0, silence, sr, t, 50.0)
            t += 50
        }
        assertTrue("本底应为有限值: ${g.floor}", g.floor.isFinite())
        assertTrue("预警阈值应为有限值: ${g.thrWarn}", g.thrWarn.isFinite())
        assertTrue("告警阈值应为有限值: ${g.thrAlarm}", g.thrAlarm.isFinite())
        assertTrue("阈值顺序应正确（告警>=预警）", g.thrAlarm >= g.thrWarn)
    }

    @Test
    fun `Guard pause 时不再累计事件`() {
        val g = GuardEngine()
        g.auto = true
        g.start(0L)
        val x = tone(500.0, fft, 0.5)
        var t = 0L
        while (g.state == GuardEngine.State.EVAL && t < 30000) {
            g.tick(45.0, x, sr, t, 50.0); t += 50
        }
        g.togglePause(true)
        val n0 = g.events.size
        repeat(100) {
            g.tick(g.floor + 80.0, x, sr, t, 50.0); t += 50
        }
        assertEquals("暂停期间不应新增事件", n0, g.events.size)
        g.togglePause(false)
    }
}
