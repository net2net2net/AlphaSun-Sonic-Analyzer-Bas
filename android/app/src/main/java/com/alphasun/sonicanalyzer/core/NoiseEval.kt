package com.alphasun.sonicanalyzer.core

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * v0.4.0 噪音评估引擎（纯 Kotlin）
 *
 * ⚠ 诚实边界（必须保留在 UI 上）：手机麦克风的 dBFS 与真实声压级**没有固定关系**
 *   （取决于 AGC 与标定）。所以这里给出可调的**参考偏移**（NZ.ref，默认 +94dB），
 *   让用户点柱状图校准。这是消费级设备能做到的诚实做法 —— 不能假装是专业计。
 *
 * 采样：每 tick 采一点（原生为 5Hz，对应 Web 版 200ms 节流），30 分钟 = 9000 点。
 * 分段：低 <250Hz / 中 250~2k / 高 >2k（GB/T 3098 常用分段）。
 */
class NoiseEval(private val binHz: Double) {

    companion object {
        const val CAP = 9000            // 30min @ 5Hz = 9000
        // 注意：const val 不支持 IntArray，只能用 val
        val CURVE_WINDOWS = intArrayOf(60, 300, 600, 900, 1800)
    }

    var ref = 94.0; private set          // dBFS→dB(A) 参考偏移（可校准）
    var windowSec = 60; private set       // 当前曲线时间窗

    private val buf = ArrayDeque<Float>()  // dB 序列（时间升序）
    var frames = 0; private set            // 已累计帧数
    var durSec = 0.0; private set

    var peakDb = -999.0; private set
    var peakHold = -999.0; private set
    private var peakAge = 0

    // Leq 用能量平均（不是算术平均）
    private var leqSum = 0.0
    private var leqN = 0

    // 三段能量（EMA 平滑，降低抖动）
    var bandLo = 0.0; private set
    var bandMid = 0.0; private set
    var bandHi = 0.0; private set
    var centroid = 0.0; private set
    var stability = 0.0; private set       // 0~1，1=极平稳

    private var lastDb = Double.NaN
    private var lastT = 0L

    /** 校准参考偏移（点柱状图）。 */
    fun calibrateRef(newRef: Double) {
        ref = newRef.coerceIn(60.0, 120.0)
    }

    fun setWindow(sec: Int) { windowSec = sec }

    /** 清空统计（重新开始一段评估）。 */
    fun reset() {
        buf.clear()
        frames = 0
        durSec = 0.0
        peakDb = -999.0
        peakHold = -999.0
        peakAge = 0
        leqSum = 0.0
        leqN = 0
        stability = 0.0
        lastDb = Double.NaN
        lastT = 0L
    }

    /**
     * 每帧调用。
     * @param dbfsFs 帧 RMS 的 dBFS（相对满量程）
     * @param bandLin6 六段线性功率密度（来自 Features.Frame.bandLin）
     */
    fun tick(dbfsFs: Double, bandLin6: DoubleArray, nowMs: Long) {
        val db = dbfsFs + ref
        if (!db.isFinite() || db < -20) {
            // 太弱，不计入统计（否则 Leq 会被大量 -20 拉低）
            stability = stability * 0.95
            return
        }
        buf.addLast(db.toFloat())
        frames++
        while (buf.size > CAP) buf.removeFirst()
        if (lastT > 0) durSec += (nowMs - lastT) / 1000.0 else durSec += 0.2
        lastT = nowMs

        if (db > peakDb) peakDb = db
        peakAge++
        if (peakDb > peakHold || peakAge > 150) { peakHold = peakDb; peakAge = 0 }

        leqSum += Math.pow(10.0, db / 10.0); leqN++

        // 三段能量（把六段映射到三段）
        val lo = bandLin6[0] + bandLin6[1]
        val mid = bandLin6[2] + bandLin6[3]
        val hi = bandLin6[4] + bandLin6[5]
        bandLo = bandLo * 0.7 + lo * 0.3
        bandMid = bandMid * 0.7 + mid * 0.3
        bandHi = bandHi * 0.7 + hi * 0.3

        // 平稳度：相邻帧 dB 变化越小越平稳（用一阶差分的绝对值均值）
        if (lastDb.isFinite()) {
            val d = abs(db - lastDb)
            stability = stability * 0.9 + (1.0 - min(1.0, d / 6.0)) * 0.1
        }
        lastDb = db
    }

    fun leq(): Double = if (leqN <= 0) Double.NaN else 10.0 * log10(leqSum / leqN)

    /** 当前窗内的 dB 序列（供曲线绘制）。 */
    fun curve(): FloatArray {
        val n = (windowSec * 5).coerceAtLeast(2)
        if (buf.isEmpty()) return FloatArray(0)
        val take = min(n, buf.size)
        val out = FloatArray(take)
        var i = buf.size - take
        var k = 0
        while (i < buf.size) { out[k++] = buf.elementAt(i); i++ }
        return out
    }

    /** 统计摘要：最大 / 最小 / 平均。 */
    fun stats(): Triple<Double, Double, Double> {
        if (buf.isEmpty()) return Triple(Double.NaN, Double.NaN, Double.NaN)
        var mx = -1e9f; var mn = 1e9f; var s = 0.0
        for (v in buf) { if (v > mx) mx = v; if (v < mn) mn = v; s += v }
        return Triple(mx.toDouble(), mn.toDouble(), s / buf.size)
    }

    /** 三段能量占比（低/中/高），和为 1。 */
    fun bandShares(): Triple<Double, Double, Double> {
        val t = bandLo + bandMid + bandHi
        if (t <= 0) return Triple(0.0, 0.0, 0.0)
        return Triple(bandLo / t, bandMid / t, bandHi / t)
    }

    /** 噪音类型识别（离线启发式）。 */
    fun type(): String {
        val (lo, mid, hi) = bandShares()
        return Features.noiseType(lo, mid, hi, centroid, stability)
    }

    /** 分贝等级文案（与 core.Nz.LEVELS 一致，纯 Kotlin 不依赖 Compose）。 */
    fun levelText(): String = Nz.of(current()).text

    /** 当前估计声级（最近一帧）。 */
    fun current(): Double = lastDb

    /** 建议。 */
    fun advice(): String = Features.noiseAdvice(lastDb, type())
}
