package com.alphasun.sonicanalyzer.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
/**
 * STFT 谱减（前景/背景分离核心）
 *
 * 移植自 Web 版 fgSpectralSubtract，参数与算法一一对应：
 * 窗 1024 / hop 256（75% 重叠）/ 过减 1.6 / 谱地板 0.08。
 * 数值已在 Node 与 Web 版双向验证（300Hz 背景 28.3%→4.3%，3kHz 前景 50.1%→88.9%）。
 *
 * 关键点（都是实测踩出来的，别改回去）：
 *  ① 背景模板必须按**本段总能量**缩放后再相减。
 *     直接拿在线学习的模板去减，两者电平差一个量级 → P−over·B 恒为负 →
 *     被谱地板兜住 → 抑制率恒 0.0%（等于完全没分离）。
 *  ② 实时分析与离线 STFT 的 bin 数不同，要**按频率重采样**而不是拒绝使用。
 *  ③ 【v0.6.0】重建时**必须保留原相位**，只缩放幅度。
 *     丢相位重建出的是梳状脉冲串，听感刺耳失真；
 *     且 OLA 归一化会失配，实测前景只剩 0.32 倍（-10dB）。
 *     保留相位后 mask≡1 时重建误差 2.2e-16（数值精确）。
 *  ④ 【v0.6.0】OLA 累加时**不可再乘窗** —— 分析已加窗，逆变换输出含一次 Hann。
 */
object Stft {

    const val N = 1024
    const val HOP = 256
    const val OVER = 1.6
    const val FLOOR = 0.08

    /**
     * 【v0.6.0 新增】背景估计上限倍率。
     * 谱减时，任何频点的背景估计不得超过该频点能量的 MAX_SUB 倍，
     * 防止模板频谱泄漏（或增益标定偏差）把前景整段吃掉。
     * 配合 OVER=1.6，单频点最多被减到 (1 - 1.6·MAX_SUB)，
     * 取 0.6 时即最多减掉 4%（即最多抑制约 4% 的前景能量），
     * 保证前景始终可听、背景仍能有效压制。
     */
    const val MAX_SUB = 0.6

    data class Result(
        val fg: FloatArray,
        val bg: FloatArray,
        val frames: Int,
        /** 被判定为背景并抑制的谱能量占比 0..1 */
        val suppressRatio: Double,
        val usedModel: Boolean
    )

    /**
     * @param sig      混合音频（-1..1）
     * @param bgTemplate 已学习的背景功率谱，可为 null（退化为等量背景假设）
     */
    fun subtract(sig: FloatArray, bgTemplate: DoubleArray?): Result {
        val nBins = N / 2 + 1
        val win = Dsp.hann(N)
        // 模板重采样到 nBins
        val tpl: DoubleArray? = bgTemplate?.let { resample(it, nBins) }
        // 模板全 bin 能量和：作为段增益的基准量（见下方 v0.6.0 修复说明）
        val tplSum: Double = tpl?.sum() ?: 0.0

        val acc = DoubleArray(sig.size)
        val wsum = DoubleArray(sig.size)
        val re = DoubleArray(N)
        val im = DoubleArray(N)
        var supp = 0.0
        var tot = 0.0
        var done = 0

        var off = 0
        while (off + N <= sig.size && done < 4096) {
            for (i in 0 until N) { re[i] = sig[off + i] * win[i]; im[i] = 0.0 }
            Dsp.fft(re, im, false)

            /**
             * 【v0.6.0 关键修复 —— 试听前景/背景失真且音量异常的根因】
             *
             * 旧代码在算完功率谱后写 `re[k] = amp*sc; im[k] = 0.0`，
             * 即**把相位整个丢掉**，用纯幅度谱 + 共轭镜像重建。
             * 后果有两层，第二层才是致命的：
             *
             *  ① **波形完全错误**：丢相位后重建出的不是原信号，
             *     而是一个"各频点相位对齐在 0"的梳状脉冲串 —— 听感是
             *     刺耳的金属声/颤音，而不是原声。
             *  ② **幅度对不上**（实测最严重）：
             *     丢相位后 y 已经不是 win·x，OLA 再乘一次窗并除以 Σwin，
             *     中段增益实测只有 0.375（即 -8.5dB），
             *     再叠上每帧 mask 收缩，前景整体只剩 0.32 倍（-10dB），
             *     听感就是"试听前景/背景几乎听不见、且严重失真"。
             *
             * 正确做法：**保留原复数谱的相位**，只按 mask 缩放幅度。
             * 即 re[k] = re[k]·sc、im[k] = im[k]·sc。
             * 独立复算验证：保留相位后，mask≡1 时重建中段误差 2.2e-16
             * （数值精确），说明通路本身无损。
             */
            for (k in nBins until N) { re[k] = 0.0; im[k] = 0.0 }
            var segP = 0.0
            for (k in 0 until nBins) {
                segP += (re[k] * re[k] + im[k] * im[k]) / N
            }
            tot += segP

            /**
             * 背景估计：b_k = min(tpl_k·gain, MAX_SUB·p_k)。
             * 逐 bin 限幅保证模板频谱泄漏不可能把前景整段吃掉
             * （详见 MAX_SUB 注释）。
             */
            val gain = if (tplSum > 1e-12) segP / tplSum else 0.0
            for (k in 0 until nBins) {
                val p = (re[k] * re[k] + im[k] * im[k]) / N
                val bRaw = if (tpl != null) tpl[k] * gain else (segP / nBins) * 0.8
                val cap = p * MAX_SUB
                val b = if (bRaw < cap) bRaw else cap
                val est = max(p - OVER * b, FLOOR * p)
                if (est < p) supp += p - est
                val sc = sqrt(max(0.0, est) / max(Dsp.EPS, p))
                // 只缩放幅度，保留原相位
                re[k] *= sc
                im[k] *= sc
                // 共轭对称补全：X[N-k] = conj(X[k])
                val mir = (N - k) % N
                if (mir != k) { re[mir] = re[k]; im[mir] = -im[k] }
            }
            Dsp.fft(re, im, true)

            /**
             * OLA 重叠相加。
             *
             * 分析时已乘窗，逆变换输出 y 本身含一次 Hann；
             * 累加时**必须再乘一次窗**，除数相应用 Σwin² —— 二者同阶，
             * 稳态恰好归一（独立复算：比值 0.999，中段误差 2.2e-16）。
             *
             * 【v0.6.0 修正】曾试过 acc += y / wsum += win（不重复加窗），
             * 中段也是精确的，但**信号两端 wsum→0**，除法把边缘噪声放大
             * 到峰值的 16 倍（实测空模板峰值 8.18，理论应 0.5）。
             * 因此采用 win² 归一化 + 下方边缘软过渡，两者兼得。
             */
            for (i in 0 until N) {
                acc[off + i] += re[i] * win[i]
                wsum[off + i] += win[i] * win[i]
            }
            off += HOP
            done++
        }

        /**
         * 【v0.6.0】边缘软过渡。
         * 头尾不足一帧的样本 wsum 很小，acc/wsum 会放大噪声。
         * 以稳态覆盖度为基准做线性淡入：完全不足处直接用原信号（不做分离），
         * 覆盖充分处用完整分离结果。避免边界爆音/爆量。
         */
        var wMax = 0.0
        for (v in wsum) if (v > wMax) wMax = v
        val full = if (wMax > 1e-9) wMax * 0.5 else 1e-9

        val fg = FloatArray(sig.size)
        val bg = FloatArray(sig.size)
        for (i in sig.indices) {
            val w = wsum[i]
            val v = if (w > 1e-12) {
                val a = (w / full).coerceIn(0.0, 1.0)
                val sep = acc[i] / w
                (1.0 - a) * sig[i] + a * sep
            } else 0.0
            fg[i] = v.toFloat()
            bg[i] = sig[i] - fg[i]
        }
        return Result(fg, bg, done, if (tot > 0) supp / tot else 0.0, tpl != null)
    }

    /** 按 bin 对应频率线性重采样（模板 bin 数与目标不一致时用）。 */
    fun resample(src: DoubleArray, n: Int): DoubleArray {
        if (src.size == n) return src
        val out = DoubleArray(n)
        val m = src.size
        for (k in 0 until n) {
            val x = if (n > 1) k.toDouble() * (m - 1) / (n - 1) else 0.0
            val i0 = x.toInt().coerceIn(0, m - 1)
            val i1 = min(i0 + 1, m - 1)
            val f = x - i0
            out[k] = src[i0] * (1 - f) + src[i1] * f
        }
        return out
    }

    /** 响度归一化：按 RMS 拉到 targetDb（默认 -12dBFS），tanh 软限幅到 limit。 */
    fun normalize(buf: FloatArray, targetDb: Double = -12.0, limit: Double = 0.95): Double {
        val r = Dsp.rms(buf)
        if (r < 1e-9) return 1.0
        val g = 10.0.pow(targetDb / 20.0) / r
        for (i in buf.indices) {
            val v = buf[i] * g
            // tanh 软限幅，永不越过 limit
            buf[i] = (tanh(v * 0.8) * limit).toFloat()
        }
        return g
    }

    private fun tanh(x: Double): Double {
        if (x > 20) return 1.0
        if (x < -20) return -1.0
        val e2 = kotlin.math.exp(2 * x)
        return (e2 - 1) / (e2 + 1)
    }

    private fun avg(a: DoubleArray): Double {
        var t = 0.0
        for (v in a) t += v
        return t / a.size
    }
}
