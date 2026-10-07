package com.alphasun.sonicanalyzer.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * v0.4.0 特征提取（纯 Kotlin，无 Android 依赖 → 可 JVM 单测）
 *
 * 逐项对齐 web-v0.3.1/index.html 的 runAnalysis()，
 * 保证原生版与旧 Web 版在同一输入下**数值一致**（迁移期双轨策略，见 docs/ARCH-NATIVE.md 第五节）。
 *
 * 对应关系：
 *   centroid/spread/rolloff/flux/dominant/tilt  ← runAnalysis 的频谱域段
 *   flat（谱平坦度）                            ← 同上，≤12kHz 积分上限
 *   rms/peak/crest/zcr/dr/snr                    ← 同上，时域段
 *   dba（dB(A)/dB(C)）                          ← IEC 61672-1 计权网络，能量域合成
 *   leq/l10/l50/l90                             ← 120s 滚动窗口
 *   f0/harmonicity/voiced/bpm/f0span/wobble/beat ← detectPitch / estimateBPM
 *   bands（六段能量）                            ← defs=[[30,150],[150,400],[400,1k],[1k,2.5k],[2.5k-6k],[6k,16k]]
 */
object Features {

    // 注意：Kotlin 的 const val 只支持基本类型与 String，DoubleArray 必须用 val
    val BAND_LO = doubleArrayOf(30.0, 150.0, 400.0, 1000.0, 2500.0, 6000.0)
    val BAND_HI = doubleArrayOf(150.0, 400.0, 1000.0, 2500.0, 6000.0, 16000.0)

    /** 单帧的全部参数。数值与 Web 版同名字段一一对应。 */
    data class Frame(
        // 频谱域
        val centroid: Double = 0.0,      // Hz
        val spread: Double = 0.0,         // Hz
        val flat: Double = 0.0,           // 0~1
        val rolloff: Double = 0.0,        // Hz
        val flux: Double = 0.0,
        val dominantHz: Double = 0.0,
        val dominantLabel: String = "—",
        val tilt: Double = 0.0,           // -1~1
        // 时域 / 电平
        val rms: Double = 0.0,
        val peak: Double = 0.0,
        val crest: Double = 0.0,
        val zcr: Double = 0.0,
        val dr: Double = 0.0,             // dB
        val snr: Double = 0.0,            // dB（以 -60dBFS 为基底粗估）
        val dbfs: Double = -100.0,
        // 计权与统计
        val dbA: Double = Double.NaN,
        val dbC: Double = Double.NaN,
        // 音高 / 节奏
        val f0: Double = -1.0,
        val harmonicity: Double = 0.0,    // 0~1
        val f0Span: Double = 0.0,         // Hz
        val wobble: Boolean = false,
        val bpm: Double = 0.0,
        // 归一化特征包（供分类器）
        val bands: FloatArray = FloatArray(6),
        val bandLin: DoubleArray = DoubleArray(6),
        val bandPeakN: Double = 0.0,
        val crestN: Double = 0.0,
        val eventN: Double = 0.0,
        val hfN: Double = 0.0,
        val lfShare: Double = 0.0,
        val domVoice: Double = 0.1,
        val flat12k: Double = 0.0
    )

    /** 有跨帧状态的分析器（通量、F0 历史、BPM 包络、统计声级窗口）。 */
    class Analyzer(private val sampleRate: Int, val fftSize: Int = 2048) {
        private var prevMag: DoubleArray? = null
        private val f0Hist = ArrayDeque<Double>()
        private val envFast = ArrayDeque<Double>()
        private val envFastT = ArrayDeque<Double>()
        private val slHist = ArrayDeque<Double>()      // dB(A) 序列
        private var clockMs = 0.0

        companion object {
            const val SL_CAP = 600        // 120s @ 5Hz
            const val SL_MIN = 30
            const val ENV_CAP = 240
        }

        // 统计声级结果
        var leq = Double.NaN; private set
        var l10 = Double.NaN; private set
        var l50 = Double.NaN; private set
        var l90 = Double.NaN; private set

        /**
         * 计算一帧。
         * @param time 时域样本（-1..1）
         * @param mag  幅度谱（长度 fftSize/2+1，已归一化到 0..1）
         * @param dbMag 每个 bin 的绝对 dB（相对满量程），用于计权；无则传 null
         */
        fun analyze(time: FloatArray, mag: DoubleArray, dbMag: DoubleArray? = null): Frame {
            val n = mag.size
            val binHz = sampleRate.toDouble() / fftSize

            /* ---------- 时域 ---------- */
            var peak = 0.0; var zc = 0
            for (i in time.indices) {
                val v = abs(time[i].toDouble())
                if (v > peak) peak = v
                if (i > 0 && ((time[i] >= 0) != (time[i - 1] >= 0))) zc++
            }
            val rms = Dsp.rms(time)
            val zcr = if (time.size > 1) zc.toDouble() / (time.size - 1) else 0.0
            val crest = if (peak > 1e-4) peak / rms else 0.0
            val dr = if (peak > 0) 20.0 * log10(peak / max(rms, 1e-9)) else 0.0
            val dbfs = Dsp.dbfs(rms)
            val snr = if (rms > 1e-4) max(0.0, dbfs + 60.0) else 0.0

            /* ---------- 频谱质心 / 扩散 / 优势频率 ---------- */
            var num = 0.0; var den = 0.0; var mv = -1.0; var mb = 0
            for (i in 0 until n) {
                val m = mag[i]
                num += i * m; den += m
                if (m > mv) { mv = m; mb = i }
            }
            val centroid = if (den > 0) (num / den) * binHz else 0.0
            var varSum = 0.0
            if (den > 0) {
                for (i in 0 until n) {
                    val f = i * binHz - centroid
                    val w = mag[i]                       // Web 版用 freqData[i]/255，等价于归一化幅度
                    varSum += w * f * f
                }
            }
            val spread = if (den > 0) sqrt(varSum / den) else 0.0
            val pkF = mb * binHz

            /* ---------- 谱平坦度（≤12kHz） ----------
               Web 版关键修正：必须用**线性幅度**而非字节值，且积分上限取 12kHz。
               12kHz 以上在多数真实信号里落在数字噪底，会把几何平均整体拉塌。 */
            val nFlat = max(8, min(n, (12000.0 / binHz).roundToInt()))
            var flat = 0.0
            if (dbMag != null && dbMag.size >= n) {
                var logSum = 0.0; var linSum = 0.0
                for (i in 0 until nFlat) {
                    val db = dbMag[i]
                    val magLin = if (db.isFinite()) Math.pow(10.0, db / 20.0) else 0.0
                    val m = if (magLin > 1e-9) magLin else 1e-9
                    logSum += ln(m); linSum += m
                }
                if (linSum > 0) flat = exp(logSum / nFlat) / (linSum / nFlat)
            } else {
                var logSum = 0.0; var linSum = 0.0
                for (i in 0 until nFlat) {
                    val m = max(1e-9, mag[i])
                    logSum += ln(m); linSum += m
                }
                if (linSum > 0) flat = exp(logSum / nFlat) / (linSum / nFlat)
            }

            /* ---------- 滚降 85% ---------- */
            var acc = 0.0; var roll = 0.0
            val tot = den * 0.85
            for (i in 0 until n) {
                acc += mag[i]
                if (acc >= tot) { roll = i * binHz; break }
            }

            /* ---------- 通量（帧差和，仅正） ---------- */
            var flux = 0.0
            prevMag?.let { p ->
                if (p.size >= n) for (i in 0 until n) { val d = mag[i] - p[i]; if (d > 0) flux += d }
            }
            prevMag = mag.copyOf()

            /* ---------- 六段能量（显示用，幅度均值） ---------- */
            val bands = FloatArray(6)
            for (b in 0 until 6) {
                val a = (BAND_LO[b] / binHz).roundToInt().coerceIn(0, n)
                val z = (BAND_HI[b] / binHz).roundToInt().coerceIn(a + 1, n + 1)
                var s = 0.0; var c = 0
                var i = a
                while (i < z && i < n) { s += mag[i]; c++; i++ }
                bands[b] = (if (c > 0) s / c else 0.0).toFloat()
            }

            /* ---------- 线性功率密度六段（判据用，必须除带宽） ---------- */
            val beLin = DoubleArray(6)
            for (b in 0 until 6) {
                val a = max(0, (BAND_LO[b] / binHz).roundToInt())
                val z = min(n, (BAND_HI[b] / binHz).roundToInt())
                var s = 0.0; var c = 0
                for (i in a until z) {
                    val db = if (dbMag != null && i < dbMag.size && dbMag[i].isFinite()) dbMag[i] else Double.NEGATIVE_INFINITY
                    if (db.isFinite()) s += Math.pow(10.0, db / 10.0)
                    c++
                }
                beLin[b] = if (c > 0) s / c else 0.0
            }
            var beLinSum = 0.0
            for (v in beLin) beLinSum += v
            if (beLinSum <= 0) beLinSum = 1.0
            val tilt = max(-1.0, min(1.0, ((beLin[4] + beLin[5]) - (beLin[0] + beLin[1])) / beLinSum))
            val lfShare = (beLin[0] + beLin[1]) / beLinSum

            /* ---------- 计权声级 dB(A)/dB(C)（IEC 61672-1） ----------
               合成必须在**能量域**（10lg Σ10^((Li+Wi)/10)），直接对 dB 相加是错的。 */
            var dbA = Double.NaN; var dbC = Double.NaN
            run {
                /**
                 * 【v0.6.2 修复 —— 界面上 dB(A)/dB(C) 恒显示 "NaN" 的真 bug】
                 *
                 * 旧代码整块包在 `if (dbMag != null && dbMag.size >= n)` 里，
                 * 而 `dbMag` 是**可选参数**——真实调用点（MainViewModel 主循环、
                 * 以及 Web 版对齐的调用）只传 `(time, mag)`，从不传它。
                 * 于是 `dbA`/`dbC` 永远停留在初始值 `Double.NaN`，
                 * 「声波参数」面板的「dB(A) 计权」「dB(C) 计权」两行
                 * 在真机上**恒显示 `NaN dB`** —— 典型"功能看着在、实际是坏的"。
                 *
                 * 修法：没有 dbMag 时直接从幅度谱 mag 取能量（power = mag²），
                 * 与 `10^(dB/10)`（dB 为 20log10(mag)）完全等价，无需额外开销。
                 */
                val useDb = dbMag != null && dbMag.size >= n
                var sa = 0.0; var sc = 0.0; var su = 0.0
                for (i in 0 until n) {
                    val e: Double = if (useDb) {
                        val db = dbMag!![i]
                        if (!db.isFinite()) continue
                        Math.pow(10.0, db / 10.0)
                    } else {
                        val m = mag[i]
                        if (!m.isFinite()) continue
                        m * m                       // 幅度 → 功率
                    }
                    if (e <= 0.0) continue
                    val f = (i * binHz).coerceAtLeast(1.0)
                    sa += e * weightA(f); sc += e * weightC(f); su += e
                }
                if (su > 0) {
                    dbA = 10.0 * log10(max(sa, 1e-20) / su) + dbfs
                    dbC = 10.0 * log10(max(sc, 1e-20) / su) + dbfs
                }
            }
            if (dbA.isFinite()) {
                slHist.addLast(dbA)
                while (slHist.size > SL_CAP) slHist.removeFirst()
                if (slHist.size >= SL_MIN) {
                    var se = 0.0
                    for (v in slHist) se += Math.pow(10.0, v / 10.0)
                    leq = 10.0 * log10(se / slHist.size)
                    val srt = slHist.sorted()
                    l10 = srt[(srt.size * 0.90).toInt().coerceIn(0, srt.size - 1)]
                    l50 = srt[(srt.size * 0.50).toInt().coerceIn(0, srt.size - 1)]
                    l90 = srt[(srt.size * 0.10).toInt().coerceIn(0, srt.size - 1)]
                }
            }

            /* ---------- 音高 / 节奏 ---------- */
            val pitch = detectPitch(time, sampleRate)
            val harmN = if (flat < 0.6) 1 - flat else 0.1
            var f0Span = 0.0
            if (pitch > 0) {
                f0Hist.addLast(pitch)
                while (f0Hist.size > 40) f0Hist.removeFirst()
                if (f0Hist.size > 1) f0Span = (f0Hist.max() - f0Hist.min())
            } else if (f0Hist.size > 4) {
                // 不稳定期清空，避免单帧离群值污染跨度
                f0Hist.clear()
            }
            val wobble = pitch > 0 && f0Span > 70

            // BPM 包络（每帧推入一次，clockMs 由外部 tick 推进）
            clockMs += FRAME_MS
            envFast.addLast(rms)
            envFastT.addLast(clockMs)
            while (envFast.size > ENV_CAP) { envFast.removeFirst(); envFastT.removeFirst() }
            var bpm = 0.0
            if (envFast.size > 8) {
                val t0 = envFastT.first()
                val t1 = envFastT.last()
                val dtms = (t1 - t0) / (envFast.size - 1)
                if (dtms > 4 && dtms < 100) bpm = estimateBpm(envFast.toList(), dtms)
            }

            /* ---------- 归一化特征包 ---------- */
            var beMax = 1.0
            for (v in bands) if (v > beMax) beMax = v.toDouble()
            var beMean = 0.0
            for (v in bands) beMean += v
            beMean /= 6.0
            val bandPeakN = if (beMean > 0) clamp01((beMax - beMean) / beMax) else 0.0
            val crestN = clamp01((crest - 1.8) / 4.0)
            val eventN = clamp01(min(1.0, flux / 12000.0) * 0.6 + crestN * 0.4)
            var beSum = 0.0
            for (v in bands) beSum += v
            if (beSum <= 0) beSum = 1.0
            val hfN = clamp01(((bands[4] + bands[5]) / beSum) / 0.5)

            return Frame(
                centroid = centroid, spread = spread, flat = flat, rolloff = roll, flux = flux,
                dominantHz = pkF, dominantLabel = bandLabel(pkF), tilt = tilt,
                rms = rms, peak = peak, crest = crest, zcr = zcr, dr = dr, snr = snr, dbfs = dbfs,
                dbA = dbA, dbC = dbC,
                f0 = pitch, harmonicity = harmN, f0Span = f0Span, wobble = wobble, bpm = bpm,
                bands = bands, bandLin = beLin, bandPeakN = bandPeakN, crestN = crestN,
                eventN = eventN, hfN = hfN, lfShare = lfShare,
                domVoice = if (pkF > 250 && pkF < 3200) (if (pitch > 0) 1.0 else 0.45) else 0.1,
                flat12k = flat
            )
        }
    }

    const val FRAME_MS = 200.0   // Web 版 runAnalysis 节流 200ms

    /* ==================== 音高检测 ==================== */

    /**
     * 自相关基频检测（移植 Web 版 detectPitch）。
     * 返回 Hz，检测失败返回 -1。
     *
     * 三道门（缺一不可）：
     *  ① mv/c[0] >= 0.30      自相关峰足够高
     *  ② mv >= cMean * 1.6    峰值显著高于全体滞后均值（排除有色噪声）
     *  ③ 40 < f < 2000       落在人声/乐音可听范围
     */
    @JvmStatic
    fun detectPitch(buf: FloatArray, sr: Int): Double {
        val rms = Dsp.rms(buf)
        if (rms < 0.012) return -1.0
        // 掐头去尾（阈值 0.2），与 Web 版一致
        var r1 = 0; var r2 = buf.size - 1
        val thr = 0.2
        for (i in 0 until buf.size / 2) { if (abs(buf[i]) > thr) { r1 = i; break } }
        for (i in 1..buf.size / 2) { if (abs(buf[buf.size - i]) > thr) { r2 = buf.size - i; break } }
        if (r2 - r1 < 8) return -1.0
        val n = r2 - r1
        val x = FloatArray(n) { buf[r1 + it] }

        // 只需检出 f > 45Hz → 最大滞后 sr/45
        val maxLag = min(n, sr / 45)
        if (maxLag < 4) return -1.0
        val c = DoubleArray(maxLag)
        for (i in 0 until maxLag) {
            var s = 0.0
            for (j in 0 until n - i) s += x[j].toDouble() * x[j + i]
            c[i] = s
        }
        // 找第一个局部极小（跳过 c[0] 主瓣）
        var d = 0
        while (d < maxLag - 1 && c[d] > c[d + 1]) d++
        var mv = -1.0; var mp = -1
        for (i in d until maxLag) if (c[i] > mv) { mv = c[i]; mp = i }
        if (mp <= 0 || c[0] <= 0) return -1.0
        if (mv / c[0] < 0.30) return -1.0

        var cMean = 0.0
        for (i in 1 until maxLag) cMean += abs(c[i])
        cMean /= (maxLag - 1).coerceAtLeast(1)
        if (mv < cMean * 1.6) return -1.0

        // 抛物线插值细化峰值
        var t0 = mp.toDouble()
        val x1 = if (mp - 1 >= 0) c[mp - 1] else 0.0
        val x2 = c[mp]
        val x3 = if (mp + 1 < maxLag) c[mp + 1] else 0.0
        val a = (x1 + x3 - 2 * x2) / 2
        val b = (x3 - x1) / 2
        if (a != 0.0) t0 -= b / (2 * a)
        val f = sr / t0
        return if (f > 40 && f < 2000) f else -1.0
    }

    /* ==================== BPM ==================== */

    /**
     * 节拍估计（移植 Web 版 estimateBPM）。
     *
     * 关键：**稀疏性门** —— 节拍是稀疏瞬态，而语音音节/连续起伏的上升沿几乎占满窗口。
     * 门限 hot/fx.length <= 0.15 由 Web 版实测标定：
     *   理想节拍 0.02~0.04 / 平稳噪声 0.23 / 语音式连续起伏 0.20 / 缓慢正弦 0.27。
     */
    @JvmStatic
    fun estimateBpm(env: List<Double>, dtMs: Double): Double {
        if (env.size < 48 || dtMs <= 0) return 0.0
        val fx = DoubleArray(env.size - 1)
        var mean = 0.0
        for (i in 1 until env.size) {
            val d = env[i] - env[i - 1]
            fx[i - 1] = if (d > 0) d else 0.0
            mean += fx[i - 1]
        }
        mean /= fx.size
        var hot = 0
        for (v in fx) if (v > mean * 2) hot++
        if (mean <= 0 || hot.toDouble() / fx.size > 0.15) return 0.0

        for (i in fx.indices) fx[i] -= mean
        var e2 = 0.0
        for (v in fx) e2 += v * v
        if (e2 <= 0) return 0.0

        val lagMin = max(2, Math.round(60000.0 / (200 * dtMs)).toInt())
        val lagMax = min(fx.size - 4, Math.round(60000.0 / (50 * dtMs)).toInt())
        if (lagMax <= lagMin) return 0.0

        var bl = 0; var bs = -1e18; var sum = 0.0; var cnt = 0
        val norm = DoubleArray(lagMax + 2)
        for (lag in lagMin..lagMax) {
            var s = 0.0; var n1 = 0.0; var n2 = 0.0
            var i = 0
            while (i + lag < fx.size) {
                s += fx[i] * fx[i + lag]
                n1 += fx[i] * fx[i]
                n2 += fx[i + lag] * fx[i + lag]
                i++
            }
            val v = s / (sqrt(n1 * n2) + 1e-12)
            norm[lag] = v; sum += v; cnt++
            if (v > bs) { bs = v; bl = lag }
        }
        val avg = if (cnt > 0) sum / cnt else 0.0
        // 双门限：绝对相关 >= 0.32 且峰值 >= 平均 × 1.5
        if (bl == 0 || bs < 0.32 || bs < avg * 1.5) return 0.0

        // 倍频歧义消解：2×/3× 周期处自相关也很高，会把 150BPM 判成 50BPM。
        // 在所有 >= 0.85× 峰值的候选里取落在 [70,180] 的较快者。
        var cand = bl
        for (d in intArrayOf(2, 3)) {
            val l2 = Math.round(bl.toDouble() / d).toInt()
            if (l2 in lagMin..lagMax && norm[l2] >= bs * 0.85) {
                val bpm2 = 60000.0 / (l2 * dtMs)
                if (bpm2 in 70.0..180.0) cand = l2
            }
        }
        val bpm = 60000.0 / (cand * dtMs)
        return if (bpm in 40.0..240.0) bpm else 0.0
    }

    /* ==================== 计权网络 ==================== */

    /** A 计权（IEC 61672-1），返回线性权重。 */
    @JvmStatic
    fun weightA(f: Double): Double {
        val f2 = f * f
        val num = 12194.0 * 12194.0 * f2 * f2
        val den = (f2 + 20.6 * 20.6) *
            sqrt((f2 + 107.7 * 107.7) * (f2 + 737.9 * 737.9)) *
            (f2 + 12194.0 * 12194.0)
        return Math.pow(10.0, (20 * log10(num / den) + 2.00) / 20.0)
    }

    /** C 计权（IEC 61672-1），返回线性权重。 */
    @JvmStatic
    fun weightC(f: Double): Double {
        val f2 = f * f
        val num = 12194.0 * 12194.0 * f2
        val den = (f2 + 20.6 * 20.6) * (f2 + 12194.0 * 12194.0)
        return Math.pow(10.0, (20 * log10(num / den) + 0.06) / 20.0)
    }

    /* ==================== 辅助 ==================== */

    @JvmStatic
    fun clamp01(x: Double): Double = if (x < 0) 0.0 else if (x > 1) 1.0 else x

    @JvmStatic
    fun bandLabel(f: Double): String = when {
        f < 150 -> "超低/低频"
        f < 400 -> "低频"
        f < 1000 -> "中频"
        f < 2500 -> "中高"
        f < 6000 -> "高频"
        else -> "极高频"
    }

    /** 人声子类型（离线启发式，移植 Web 版 voiceKind）。 */
    @JvmStatic
    fun voiceKind(f0: Double, harmN: Double, f0Span: Double): Pair<String, Int> {
        if (f0 <= 0) return "—" to 0
        var t: String
        var c: Int
        when {
            f0 >= 400 -> { t = "婴儿声/啼哭(估)"; c = 3 }
            f0 >= 300 -> { t = "童声(估)"; c = 3 }
            f0 >= 250 -> { t = "童声/女声临界(估)"; c = 2 }
            f0 >= 165 -> { t = "女声(估)"; c = 2 }
            f0 >= 120 -> { t = "男声/女声临界(估)"; c = 2 }
            f0 >= 85 -> { t = "男声(估)"; c = 0 }
            else -> { t = "极低音(<85Hz·非典型人声)"; c = 4 }
        }
        if (f0Span > 70) t += " · 基频起伏大"
        if (harmN < 0.35) t += " · 谐性偏低"
        return t to c
    }

    /** 采集质量评分（SNR 45% + 电平充分性 30% + 稳定性 25%）。 */
    @JvmStatic
    fun qualityScore(snr: Double, db: Double, dbStd: Double, clipping: Boolean): Int {
        val snrS = clamp01(snr / 40.0)
        val lvlS = if (db <= -3) clamp01((db + 60) / 22.0)
        else if (db <= 1) 1.0 else clamp01(1 - (db - 1) / 3.0)
        val stabS = clamp01(1 - abs(dbStd - 0.10) * 4)
        val base = 100 * (0.45 * snrS + 0.30 * lvlS + 0.25 * stabS)
        return if (clipping) min(25.0, base).toInt() else base.toInt()
    }

    /** 舒适度警报灯：平静/正常/不适/高危（连续 3 帧门在调用侧实现）。 */
    @JvmStatic
    fun comfort(rms: Double, crest: Double): Pair<String, String> = when {
        rms >= 0.45 || (crest > 6 && rms >= 0.22) -> "danger" to "高危"
        rms >= 0.18 -> "warn" to "不适"
        rms >= 0.02 -> "ok" to "正常"
        else -> "calm" to "平静"
    }

    /** 噪音类型识别（离线启发式，按三段能量比 + 质心 + 平稳度）。 */
    @JvmStatic
    fun noiseType(loShare: Double, midShare: Double, hiShare: Double,
                  centroid: Double, stability: Double): String {
        val dom = maxOf(loShare, midShare, hiShare)
        if (dom < 0.45) return "宽频混合（多源叠加）"
        if (loShare >= 0.55 && centroid < 400) return "交通/工业低频"
        if (loShare >= 0.55) return "低频轰鸣（空调外机/柴油机）"
        if (hiShare >= 0.55 && centroid > 3000) return "尖锐高频（摩擦/啸叫）"
        if (hiShare >= 0.55) return "偏高频（人声交谈/键盘）"
        if (midShare >= 0.55) return "中频为主（谈话/广播）"
        if (stability > 0.85) return "稳态噪声（风扇/雨声）"
        return "宽带噪声"
    }

    /** 建议文案。 */
    @JvmStatic
    fun noiseAdvice(db: Double, type: String): String = when {
        db >= 80 -> "严重超标，建议佩戴耳罩并尽快离开；连续暴露会损伤听力。"
        db >= 70 -> "超出一般办公与居住环境舒适范围，建议减少停留时间。"
        db >= 60 -> "偏吵，长期暴露建议做隔音处理。"
        db >= 45 -> "可接受范围，长时间工作建议适当通风降噪。"
        else -> "环境安静，适合长时间工作与休息。"
    }

    /** 频谱质心（Hz）→ 建议（用于噪音评估的分频段诊断）。 */
    @JvmStatic
    fun centroidAdvice(centroid: Double): String = when {
        centroid < 200 -> "能量集中在低频，多为空调/交通/机械类噪声"
        centroid < 800 -> "能量集中在中低频，多为设备运转或谈话"
        centroid < 3000 -> "能量集中在中频，接近人声/广播频段"
        centroid < 6000 -> "能量偏高频，多为摩擦/气流/电子啸叫"
        else -> "能量集中在高频，多为细微摩擦或设备噪声"
    }
}
