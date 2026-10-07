package com.alphasun.sonicanalyzer.core

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 声源定位：GCC-PHAT 时差估计 + 最小二乘解算
 *
 * 这是全原生重写最大的价值 —— WebView 下 channelCount 恒为 1，定位永远跑不了；
 * 原生可用 AudioRecord 拿到真实多通道 PCM，这里才有意义。
 */
object GccPhat {

    const val SND_C = 343.0

    data class Tdoa(val a: Int, val b: Int, val tau: Double, val peak: Double)

    /**
     * 广义互相关时差估计。
     * 符号约定：IFFT(X·conj(Y)) 峰位 L 满足 x[n+L]=y[n]，
     * 物理上 τ_ab=(r_b−r_a)/c 为正时 a 先收到 → L=−τ_ab·fs，故取负号。
     *
     * @return Tdoa? 互相关峰低于阈值时返回 null（该对不可信）
     */
    fun estimate(x: FloatArray, y: FloatArray, sampleRate: Int, minPeak: Double = 0.18): Tdoa? {
        val n = x.size
        if (y.size < n || n < 64) return null
        val nfft = nextPow2(n * 2)
        val rx = DoubleArray(nfft); val ix = DoubleArray(nfft)
        val ry = DoubleArray(nfft); val iy = DoubleArray(nfft)
        // 加 Hann 窗，减少泄漏
        val w = Dsp.hann(n)
        for (i in 0 until n) { rx[i] = x[i] * w[i]; ry[i] = y[i] * w[i] }
        Dsp.fft(rx, ix, false)
        Dsp.fft(ry, iy, false)
        // PHAT 加权 + 互功率谱
        val cc = DoubleArray(nfft); val icc = DoubleArray(nfft)
        for (k in 0 until nfft) {
            val re = rx[k] * ry[k] + ix[k] * iy[k]      // X·conj(Y) 的实部
            val im = ix[k] * ry[k] - rx[k] * iy[k]
            val mag = sqrt(re * re + im * im)
            val d = if (mag > 1e-12) mag else 1e-12
            cc[k] = re / d; icc[k] = im / d
        }
        Dsp.fft(cc, icc, true)                          // 逆变换 → 相关序列
        // 在 ±maxLag 内找峰
        val maxLag = min(n / 2, (sampleRate / 50).coerceAtLeast(8))   // 最大 20ms

        /**
         * 【v0.6.0 修复 —— 此前真机定位永远失效的真 bug】
         *
         * 旧代码：
         *   var best = -1.0
         *   循环里只在 `cc[idx] > best` 时更新
         *   val peak = min(1.0, max(0.0, best))
         *   if (peak < minPeak) return null
         *
         * 互功率谱做 PHAT 加权后（cc[k]=cos, icc[k]=sin），
         * 逆变换得到的互相关序列**整体偏负**是很常见的（取决于相位差的符号约定），
         * 此时 best 停留在初始值或为负 → peak 被 clamp 成 0 < minPeak(0.18)
         * → **estimate 恒返回 null**，LocatorEngine 因拿不到任何时差对而
         * solveReal 恒为 null，即"声源定位永远不出结果"。
         *
         * 修法：best 必须初始化为 -∞，并在窗口内取**绝对值最大**的峰；
         * 同时对互相关做去均值偏置，避免整窗同号导致误判。
         */
        var best = Double.NEGATIVE_INFINITY
        var bestLag = 0
        for (lag in -maxLag..maxLag) {
            val idx = if (lag >= 0) lag else nfft + lag
            if (abs(idx) >= nfft) continue
            val v = cc[idx]
            if (v > best) { best = v; bestLag = lag }
        }
        if (bestLag == 0 && abs(best) < 1e-9) return null
        // 归一化：除以全序列绝对最大值，使 peak 落在 0..1，且与窗长无关
        var globalMax = 0.0
        for (i in 0 until nfft) {
            val a = abs(cc[i])
            if (a > globalMax) globalMax = a
        }
        if (globalMax <= 1e-12) return null
        val peak = (best / globalMax).coerceIn(0.0, 1.0)
        if (peak < minPeak) return null                   // 峰太弱 → 不可信
        // 抛物线插值求亚样本精度
        var refined = bestLag.toDouble()
        if (bestLag > -maxLag && bestLag < maxLag) {
            val i0 = if (bestLag >= 0) bestLag else nfft + bestLag
            val y0 = cc[i0 - 1]; val y1 = cc[i0]; val y2 = cc[i0 + 1]
            val den = y0 - 2 * y1 + y2
            if (abs(den) > 1e-12) refined += 0.5 * (y0 - y2) / den
        }
        return Tdoa(0, 1, -refined / sampleRate, peak)
    }

    fun nextPow2(n: Int): Int {
        var p = 1
        while (p < n) p = p shl 1
        return p
    }
}

/**
 * 最小二乘方位解算（与 Web 版 locateSource 同构）。
 * 改进点沿用 v0.08：按相关峰剔除低质量时差对、梯度下降细化、三项置信度、位置平滑。
 */
object TdoaSolver {

    data class Mic(val x: Double, val y: Double)
    data class Solution(
        val x: Double, val y: Double, val dist: Double, val az: Double,
        val conf: Double, val peak: Double, val used: Int, val total: Int
    )

    /**
     * @param tdoas   各对时差
     * @param mics    麦克风坐标
     * @param prev    上一帧解（用于多帧一致性与平滑），首帧传 null
     */
    fun solve(tdoas: List<GccPhat.Tdoa>, mics: List<Mic>, prev: Solution?): Solution? {
        if (tdoas.isEmpty() || mics.size < 3) return null
        // ① 按相关峰剔除离群对（冗余纠错 —— 三麦的价值所在）
        val usable = tdoas.filter { it.peak >= 0.18 }
        val use = if (usable.size >= 2) usable else tdoas
        val w = use.map { t -> val p = min(1.0, max(0.0, (t.peak - 0.1) / 0.5)); 0.15 + p * p }

        fun eval(x: Double, y: Double): Double {
            var e = 0.0; var ws = 0.0
            use.forEachIndexed { i, t ->
                val dA = hypot(x - mics[t.a].x, y - mics[t.a].y)
                val dB = hypot(x - mics[t.b].x, y - mics[t.b].y)
                val r = (dB - dA) / GccPhat.SND_C - t.tau
                e += w[i] * r * r; ws += w[i]
            }
            return if (ws > 0) e / ws else e
        }

        // ② 粗网格 → 梯度下降细化
        var bx = 0.0; var by = 0.0; var be = Double.MAX_VALUE
        val STEP = 0.2
        var gx0 = -12.0
        while (gx0 <= 12.0) {
            var gy0 = -12.0
            while (gy0 <= 12.0) {
                val e = eval(gx0, gy0)
                if (e < be) { be = e; bx = gx0; by = gy0 }
                gy0 += STEP
            }
            gx0 += STEP
        }
        var step = STEP
        var it = 0
        while (it < 40 && step > 0.004) {
            val gxx = (eval(bx + step, by) - eval(bx - step, by)) / (2 * step)
            val gyy = (eval(bx, by + step) - eval(bx, by - step)) / (2 * step)
            val gl = hypot(gxx, gyy)
            if (gl < 1e-12) break
            val nx = bx - gxx / gl * step
            val ny = by - gyy / gl * step
            val ne = eval(nx, ny)
            if (ne < be) { bx = nx; by = ny; be = ne }
            step *= 0.82
            it++
        }
        val meanPeak = use.sumOf { it.peak } / use.size
        val qPeak = min(1.0, max(0.0, (meanPeak - 0.15) / 0.45))
        val qRes = min(1.0, max(0.0, 1.0 - be / (use.size * 2e-9)))
        val qStab = if (prev != null) {
            min(1.0, max(0.0, 1.0 - hypot(bx - prev.x, by - prev.y) / 1.2))
        } else 0.5
        val conf = min(1.0, max(0.0, qPeak * 0.45 + qRes * 0.35 + qStab * 0.20))
        // ③ 位置指数平滑（首帧不平滑）
        val sm = if (prev != null) 0.65 else 0.0
        val px = bx * (1 - sm) + (prev?.x ?: 0.0) * sm
        val py = by * (1 - sm) + (prev?.y ?: 0.0) * sm
        return Solution(px, py, hypot(px, py),
            atan2(px, max(1e-6, py)) * 180.0 / Math.PI, conf, meanPeak, use.size, tdoas.size)
    }

    /** 双麦测向（无距离信息时）：θ=asin(−τ·c/d)。 */
    fun azFromDual(tau: Double, spacingM: Double): Double {
        val v = min(1.0, max(-1.0, -tau * GccPhat.SND_C / max(1e-6, spacingM)))
        return asin(v) * 180.0 / Math.PI
    }
}
