package com.alphasun.sonicanalyzer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Dsp 数值正确性测试。
 *
 * 背景：v0.5.0 之前所有引擎**从未在 JVM 上执行过任何测试**，
 * 仅靠 compileReleaseKotlin 的编译期检查 —— 结果真机上一跑就挂。
 * 本类用**解析可验证**的输入（已知频率的正弦、已知长度的脉冲）
 * 校验数值，证明算法本身没算错。
 */
class DspTest {

    /** 生成 48kHz 采样率、指定频率与长度的正弦（幅度 1.0） */
    private fun sine(freq: Double, n: Int, sr: Double = 48000.0): FloatArray =
        FloatArray(n) { sin(2.0 * PI * freq * it / sr).toFloat() }

    @Test
    fun `fft 3kHz 正弦的峰值 bin 落在正确位置`() {
        val sr = 48000.0
        val n = 2048
        val binHz = sr / n
        val f0 = 3000.0
        val x = sine(f0, n, sr)

        val re = DoubleArray(n) { x[it].toDouble() }
        val im = DoubleArray(n)
        Dsp.fft(re, im, false)

        var peak = 0
        var peakVal = -1.0
        for (k in 0..n / 2) {
            val m = kotlin.math.sqrt(re[k] * re[k] + im[k] * im[k])
            if (m > peakVal) { peakVal = m; peak = k }
        }
        // 峰值 bin 应在真实频率 ±1 个 bin 内
        val expect = (f0 / binHz).toInt()
        assertTrue("峰值 bin=$peak 期望≈$expect（binHz=$binHz）", kotlin.math.abs(peak - expect) <= 1)
    }

    @Test
    fun `fft 逆变换能还原原始信号`() {
        val n = 256
        val x = sine(440.0, n)
        val re = DoubleArray(n) { x[it].toDouble() }
        val im = DoubleArray(n)
        Dsp.fft(re, im, false)
        Dsp.fft(re, im, true)   // 逆变换带 1/N 归一化
        var maxErr = 0.0
        for (i in 0 until n) {
            maxErr = maxOf(maxErr, kotlin.math.abs(re[i] - x[i]))
        }
        assertTrue("往返最大误差 $maxErr 应 < 1e-9", maxErr < 1e-9)
    }

    @Test
    fun `magnitude 归一化后峰值恰为 1`() {
        val x = sine(1000.0, 2048)
        val spec = Dsp.magnitude(x)
        assertEquals("幅度谱长度", 2048 / 2 + 1, spec.size)
        var mx = 0f
        for (v in spec) if (v > mx) mx = v
        assertEquals("归一化峰值", 1f, mx, 1e-5f)
    }

    /**
     * 静音输入必须返回全 0，且**不得除零产生 NaN**。
     * 真机上麦克风静音是常态，NaN 一路传到 UI 会让所有读数显示空白。
     */
    @Test
    fun `静音输入不产生 NaN`() {
        val silence = FloatArray(2048)
        val spec = Dsp.magnitude(silence)
        for (v in spec) {
            assertTrue("静音幅度谱出现 NaN/Inf：$v", !v.isNaN() && !v.isInfinite())
        }
        val power = DoubleArray(1025)
        Dsp.powerSpectrum(silence, power)
        for (v in power) {
            assertTrue("静音功率谱出现 NaN/Inf：$v", !v.isNaN() && !v.isInfinite())
        }
        assertEquals("静音 RMS", 0.0, Dsp.rms(silence), 1e-12)
    }

    @Test
    fun `dbfs 对静音返回有限负值而非负无穷`() {
        val d = Dsp.dbfs(0.0)
        assertTrue("静音 dBFS=$d", !d.isNaN() && !d.isInfinite() && d < 0)
    }

    @Test
    fun `hann 窗两端为 0 中间最大`() {
        val w = Dsp.hann(1024)
        assertEquals("起点", 0.0, w[0], 1e-9)
        assertEquals("终点", 0.0, w[1023], 1e-9)
        var mx = 0.0; var sum = 0.0
        for (v in w) { if (v > mx) mx = v; sum += v }
        assertTrue("中点应接近 1，实测 $mx", mx > 0.99)
        assertTrue("Hann 窗和应≈N/2，实测 $sum", sum in 480.0..544.0)
    }

    @Test
    fun `谱平坦度 白噪高 纯音低`() {
        val n = 1024
        // 白噪：随机但确定性
        val rnd = java.util.Random(42)
        val white = DoubleArray(n) { rnd.nextDouble() }
        val fw = Dsp.spectralFlatness(white)
        assertTrue("白噪平坦度 $fw 应显著大于 0.5", fw > 0.5)

        // 纯音：能量集中在极窄的 3 个 bin 内，其余为极小底噪。
        // 注意：若只有**单个**非零 bin，几何均值与算术均值相等 → 平坦度恒为 1.0，
        // 无法表达"纯音"。故必须构造多个 bin 且差异极大才能体现平坦度趋近 0。
        val tone = DoubleArray(n) { 1e-12 }
        tone[510] = 1.0; tone[511] = 0.9; tone[512] = 0.8
        val ft = Dsp.spectralFlatness(tone)
        assertTrue("纯音平坦度 $ft 应≈0（远小于白噪）", ft < 1e-3)
        assertTrue("纯音($ft) 应远小于白噪($fw)", ft < fw)
    }

    /**
     * **回归测试：FFT 长度必须是 2 的幂。**
     *
     * v0.6.0 修复的根因②：旧版采集层把 AudioRecord.read() 的**可变返回值**
     * 直接当帧长，一旦设备返回非 2 的幂（如 1280），
     * 这里会抛 IllegalArgumentException 并杀死主循环协程。
     * 现在 frame 恒为 fftSize，但 Dsp 仍需保持契约 —— 本测试固化该契约。
     */
    @Test
    fun `fft 拒绝非 2 的幂长度`() {
        val re = DoubleArray(1000)
        val im = DoubleArray(1000)
        var threw = false
        try {
            Dsp.fft(re, im, false)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("非 2 的幂长度必须抛 IllegalArgumentException（契约）", threw)
    }

    @Test
    fun `rms 与 peak 对已知信号计算正确`() {
        // sine() 幅度恒为 1.0 → RMS = 1/√2 ≈ 0.7071，峰值 = 1.0
        // （此前误按幅度 0.5 写期望值，导致 0.3536 vs 0.7071 的假失败）
        val x = sine(1000.0, 48000)
        val r = Dsp.rms(x)
        assertEquals("RMS", 1.0 / kotlin.math.sqrt(2.0), r, 0.01)
        val p = Dsp.peak(x)
        assertEquals("峰值", 1.0, p, 0.01)
    }

    /**
     * **回归测试：spectrum() 与「magnitude + powerSpectrum 两次调用」数值必须完全一致。**
     *
     * v0.3.0 把主循环里两次独立 FFT 合并为一次（spectrum），动机是 20Hz 热路径开销减半。
     * 这属于**纯性能重构，不允许改变任何数值结果** —— 本测试把等价性固化下来，
     * 防止后续有人改坏公式（例如忘记 /N 归一化、或峰值归一化基准不一致），
     * 否则界面频谱/特征分析会静默偏移且极难定位。
     */
    @Test
    fun `spectrum 与分开调用 magnitude+powerSpectrum 结果一致`() {
        val n = 2048
        val x = sine(1000.0, n)
        val half = n / 2 + 1

        // 旧路径：两次独立 FFT
        val oldSpec = Dsp.magnitude(x)
        val oldPower = DoubleArray(half)
        Dsp.powerSpectrum(x, oldPower)

        // 新路径：一次 FFT
        val newPower = DoubleArray(half)
        val newSpec = Dsp.spectrum(x, newPower)

        assertEquals("幅度谱长度", oldSpec.size, newSpec.size)
        for (k in 0 until half) {
            assertEquals("功率谱 bin $k", oldPower[k], newPower[k], 1e-12)
            assertEquals("幅度谱 bin $k", oldSpec[k], newSpec[k], 1e-6f)
        }
    }

    /** 静音输入走 spectrum 合并路径同样不得产生 NaN（主循环静音是常态）。 */
    @Test
    fun `spectrum 静音输入不产生 NaN`() {
        val power = DoubleArray(1025)
        val spec = Dsp.spectrum(FloatArray(2048), power)
        for (v in spec) assertTrue("静音幅度谱出现非法值：$v", !v.isNaN() && !v.isInfinite())
        for (v in power) assertTrue("静音功率谱出现非法值：$v", !v.isNaN() && !v.isInfinite())
    }
}
