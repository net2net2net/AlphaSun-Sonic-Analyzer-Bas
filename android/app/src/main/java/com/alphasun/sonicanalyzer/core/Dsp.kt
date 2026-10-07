package com.alphasun.sonicanalyzer.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * DSP 核心（纯 Kotlin，无 Android 依赖 → 可 JVM 单元测试）
 *
 * 与 Web 版 index.html 的算法逐项对拍，参数保持一致，
 * 保证迁移后数值结果可对照（见 docs/ARCH-NATIVE.md 第五节）。
 */
object Dsp {

    const val EPS = 1e-12

    /** 原地基-2 FFT。[re]/[im] 长度必须是 2 的幂。inverse=true 时做逆变换并归一化 1/N。 */
    @JvmStatic
    fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        val n = re.size
        require(n == im.size && n and (n - 1) == 0) { "FFT 长度必须是 2 的幂，实际 $n" }
        // 位反转置换
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        // 蝶形
        var len = 2
        while (len <= n) {
            val ang = (if (inverse) 2.0 else -2.0) * PI / len
            val wr = cos(ang)
            val wi = kotlin.math.sin(ang)
            val half = len shr 1
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until half) {
                    val ar = re[i + k]
                    val ai = im[i + k]
                    val br = re[i + k + half]
                    val bi = im[i + k + half]
                    val vr = br * cr - bi * ci
                    val vi = br * ci + bi * cr
                    re[i + k] = ar + vr; im[i + k] = ai + vi
                    re[i + k + half] = ar - vr; im[i + k + half] = ai - vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) {
            // 注：Kotlin 的 DoubleArray 元素**不支持 /= 复合赋值**，必须显式写回
            for (k in 0 until n) { re[k] = re[k] / n; im[k] = im[k] / n }
        }
    }

    /** Hann 窗（与 Web 版 0.5-0.5cos(2πi/(N-1)) 一致）。 */
    @JvmStatic
    fun hann(n: Int): DoubleArray = DoubleArray(n) { i ->
        0.5 - 0.5 * cos(2.0 * PI * i / (n - 1))
    }

    /** 幅度谱（长度 N → N/2+1），已归一化到 0..1 峰值。 */
    @JvmStatic
    fun magnitude(samples: FloatArray): FloatArray {
        val n = samples.size
        val re = DoubleArray(n) { samples[it].toDouble() }
        val im = DoubleArray(n)
        fft(re, im, false)
        val half = n / 2 + 1
        val out = FloatArray(half)
        var mx = 0.0
        for (k in 0 until half) {
            val m = sqrt(re[k] * re[k] + im[k] * im[k]) / n
            out[k] = m.toFloat()
            if (m > mx) mx = m
        }
        if (mx > EPS) for (k in 0 until half) out[k] = (out[k] / mx).toFloat()
        return out
    }

    /**
     * 功率谱（未归一化），用于背景模型学习。
     * 与 Web 版一致：P[k] = (re²+im²)/N
     */
    @JvmStatic
    fun powerSpectrum(samples: FloatArray, out: DoubleArray) {
        val n = samples.size
        val re = DoubleArray(n) { samples[it].toDouble() }
        val im = DoubleArray(n)
        fft(re, im, false)
        val half = n / 2 + 1
        val m = if (out.size >= half) half else out.size
        for (k in 0 until m) out[k] = (re[k] * re[k] + im[k] * im[k]) / n
        if (out.size > half) for (k in half until out.size) out[k] = 0.0
    }

    /**
     * 一次 FFT 同时产出：
     *   · 功率谱 [out]（未归一化，P[k]=(re²+im²)/N，与 powerSpectrum 一致）
     *   · 峰值归一化的幅度谱（FloatArray，长度与 [out] 对齐，归一化到 0..1，与 magnitude 一致）
     * 合并 magnitude() 与 powerSpectrum() 的两次独立 FFT 为一次，
     * 供 20Hz 主循环使用，热路径计算量减半且数值结果与分别调用完全一致。
     */
    @JvmStatic
    fun spectrum(samples: FloatArray, out: DoubleArray): FloatArray {
        val n = samples.size
        val re = DoubleArray(n) { samples[it].toDouble() }
        val im = DoubleArray(n)
        fft(re, im, false)
        val half = n / 2 + 1
        val m = if (out.size >= half) half else out.size
        val mag = FloatArray(m)
        var mx = 0.0
        for (k in 0 until m) {
            val p = (re[k] * re[k] + im[k] * im[k]) / n
            out[k] = p
            val mg = sqrt(p)
            mag[k] = mg.toFloat()
            if (mg > mx) mx = mg
        }
        if (mx > EPS) for (k in 0 until m) mag[k] = (mag[k] / mx).toFloat()
        if (out.size > half) for (k in half until out.size) out[k] = 0.0
        return mag
    }

    /** RMS。 */
    @JvmStatic
    fun rms(buf: FloatArray, from: Int = 0, to: Int = buf.size): Double {
        val n = (to - from).coerceAtLeast(0)
        if (n == 0) return 0.0
        var s = 0.0
        for (i in from until from + n) s += buf[i].toDouble() * buf[i]
        return sqrt(s / n)
    }

    /** 峰值（绝对值）。 */
    @JvmStatic
    fun peak(buf: FloatArray, from: Int = 0, to: Int = buf.size): Double {
        val lo = from.coerceIn(0, buf.size)
        val hi = to.coerceIn(lo, buf.size)
        var p = 0.0
        for (i in lo until hi) {
            val a = abs(buf[i].toDouble())
            if (a > p) p = a
        }
        return p
    }

    /**
     * dBFS。仅夹上界，**不夹下界** ——
     * 安静环境本就是负值（实测 -6dB），夹成 0 会让电平显示恒为 0.0。
     */
    @JvmStatic
    fun dbfs(linear: Double): Double = 20.0 * log10(maxOf(linear, 1e-9))

    /**
     * 参考偏移后的环境声级 dB(A) 近似值。
     * 手机麦克风无计量校准，+94dB 是常见参考偏移；可由用户点击柱状图校准。
     */
    @JvmStatic
    fun envDb(rmsLinear: Double, refOffset: Double = 94.0): Double = dbfs(rmsLinear) + refOffset

    /** 对数频率轴映射：把 [fMin,fMax] 线性映射到 bin 区间（与 Web 版 computeBars 同构）。 */
    @JvmStatic
    fun logBinRange(binHz: Double, binCount: Int, fMin: Double, fMax: Double): IntRange {
        val k0 = (ln(fMin / 1000.0) / ln(2.0) / binHz).toInt()
        val k1 = (ln(fMax / 1000.0) / ln(2.0) / binHz).toInt()
        return k0.coerceIn(1, binCount - 1)..k1.coerceIn(1, binCount - 1)
    }

    /** 谱平坦度（0~1）：几何均值/算术均值。1=白噪，0=纯音。 */
    @JvmStatic
    fun spectralFlatness(power: DoubleArray, from: Int = 1, to: Int = power.size): Double {
        val n = (to - from).coerceAtLeast(0)
        if (n <= 0) return 0.0
        var logSum = 0.0
        var sum = 0.0
        var cnt = 0
        for (i in from until from + n) {
            val v = power[i]
            if (v > 0) { logSum += ln(v); sum += v; cnt++ }
        }
        if (cnt == 0 || sum <= 0.0) return 0.0
        return kotlin.math.exp(logSum / cnt) / (sum / cnt)
    }

    /**
     * 归一化互相关系数（-1..1）。任一路能量低于 [minEnergy] 时返回 NaN。
     *
     * 【用途 —— v0.6.1 "伪立体声"判定】
     * 单麦克风机型上 `AudioRecord(CHANNEL_IN_STEREO)` 也会初始化成功，
     * 但两路数据完全相同。直接拿去做 TDOA 会算出恒为 0 的时差（方位角恒 0°），
     * 比用仿真更假。用相关系数区分「真双麦」与「复制单声道」：
     *   · corr ≈ +1  → 复制单声道（伪立体声）
     *   · corr 明显 < 1 → 两路各自独立，是真双麦
     *
     * 阈值取 0.995：真双麦在同位置拾音时相关性也很高，但不会到 0.995 以上；
     * 复制通道则是逐样本全等（浮点上就是 1.0）。
     */
    @JvmStatic
    fun correlation(a: FloatArray, b: FloatArray, minEnergy: Double = 1e-7): Double {
        val n = minOf(a.size, b.size)
        if (n <= 0) return Double.NaN
        var sa = 0.0; var sb = 0.0; var sab = 0.0
        for (i in 0 until n) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            sa += x * x; sb += y * y; sab += x * y
        }
        if (sa < minEnergy || sb < minEnergy) return Double.NaN
        val den = sqrt(sa * sb)
        if (den <= 0.0) return Double.NaN
        return (sab / den).coerceIn(-1.0, 1.0)
    }

    /** 伪立体声判定：两路逐样本全等（相关 > [thr]）。 */
    @JvmStatic
    fun isFakeStereo(a: FloatArray, b: FloatArray, thr: Double = 0.995): Boolean {
        val c = correlation(a, b)
        return !c.isNaN() && c > thr
    }
}
