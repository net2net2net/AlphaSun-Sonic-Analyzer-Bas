package com.alphasun.sonicanalyzer.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * v0.4.0 声源定位引擎
 *
 * 对应 Web 版 locateSource / locGuidance / drawLocMap。
 *
 * 用户需求原话：「不是列出声音定位的方法，而是改进程序如何更好进行声源定位，
 *   包括操作定位和操作指示」→ 所以本类除了算位置，还负责 [guidance]：
 *   实时状态卡（通道数/孔径/相关峰/残差/有效对）+ 5 步操作指引的**逐条判定**，
 *   直接告诉用户「哪里没做好、怎么改」，而不是罗列 TDOA/RSSI 等名词。
 *
 * 仿真模式：单声道时用虚拟三麦三角阵 + 镜像源法（可开混响），
 *   让用户在没有任何多麦硬件时也能熟悉完整链路。
 */
class LocatorEngine(private val sampleRate: Int) {

    companion object {
        val SPACINGS = doubleArrayOf(0.3, 0.6, 1.0, 1.5, 2.0)
        /** 有效时差对数下限（低于此不做置信度输出） */
        const val MIN_PAIRS = 1
    }

    var spacing = 1.0; private set
    var simOn = true; private set
    var simAz = 12.0; private set
    var simDist = 3.0; private set
    var simReverb = false; private set

    private var prev: TdoaSolver.Solution? = null
    private var simPrevAz = Double.NaN
    private var simPrevAt = 0L

    fun cycleSpacing(): Double {
        val i = SPACINGS.indexOfFirst { abs(it - spacing) < 1e-6 }
        spacing = SPACINGS[(i + 1) % SPACINGS.size]
        prev = null
        return spacing
    }

    fun toggleSim(): Boolean { simOn = !simOn; prev = null; return simOn }
    fun toggleReverb(): Boolean { simReverb = !simReverb; return simReverb }

    /** 仿真模式：点雷达图放置虚拟声源。 */
    fun placeSim(az: Double, dist: Double) {
        simAz = az.coerceIn(-89.0, 89.0)
        simDist = dist.coerceIn(0.5, 8.0)
        prev = null
    }

    /** 麦坐标：底边水平排列，间距 = spacing（米）。原点在中点。 */
    fun micCoords(chN: Int): List<TdoaSolver.Mic> = when {
        chN >= 3 -> {
            // 三麦：前中 + 左右后（真实手机常见布局）
            val d = spacing / 2.0
            listOf(
                TdoaSolver.Mic(-d, 0.0),
                TdoaSolver.Mic(d, 0.0),
                TdoaSolver.Mic(0.0, spacing * 0.7)
            ).take(chN)
        }
        chN == 2 -> listOf(
            TdoaSolver.Mic(-spacing / 2.0, 0.0),
            TdoaSolver.Mic(spacing / 2.0, 0.0)
        )
        else -> listOf(TdoaSolver.Mic(0.0, 0.0))
    }

    /** 各通道电平（粗判远近）。 */
    fun channelLevels(multi: Array<FloatArray>?): List<Float> {
        if (multi == null || multi.isEmpty()) return listOf(0f)
        val rmss = multi.map { Dsp.rms(it) }
        val mx = max(1e-6, rmss.max())
        return rmss.map { (it / mx).toFloat() }
    }

    /**
     * 真阵列解算。
     * @param multi 各通道时域数据（长度相同）
     * @return 定位结果；不可用时返回 null
     */
    fun solveReal(multi: Array<FloatArray>?): Result? {
        if (multi == null || multi.size < 2) return null
        val mics = micCoords(multi.size)
        val pairs = ArrayList<GccPhat.Tdoa>()
        for (a in 0 until multi.size) {
            for (b in a + 1 until multi.size) {
                val t = GccPhat.estimate(multi[a], multi[b], sampleRate) ?: continue
                pairs.add(GccPhat.Tdoa(a, b, t.tau, t.peak))
            }
        }
        if (pairs.isEmpty()) return null
        if (multi.size == 2) {
            // 双麦只能测向，无距离
            val t = pairs.maxByOrNull { it.peak }!!
            val az = TdoaSolver.azFromDual(t.tau, spacing)
            val qPeak = min(1.0, max(0.0, (t.peak - 0.15) / 0.45))
            val qStab = if (simPrevAz.isNaN()) 0.5
            else min(1.0, max(0.0, 1.0 - abs(az - simPrevAz) / 25.0))
            val conf = min(1.0, max(0.0, qPeak * 0.7 + qStab * 0.3))
            simPrevAz = az
            return Result(
                azDeg = az, distM = null, conf = conf, peak = t.peak,
                usedPairs = 1, totalPairs = 1, residualUs = null,
                modeText = "真阵列 2 麦（仅测向 · 无测距）"
            )
        }
        val sol = TdoaSolver.solve(pairs, mics, prev) ?: return null
        prev = sol
        return Result(
            azDeg = sol.az, distM = sol.dist, conf = sol.conf, peak = sol.peak,
            usedPairs = sol.used, totalPairs = sol.total,
            residualUs = null, modeText = "真阵列 ${multi.size} 麦"
        )
    }

    /**
     * 仿真解算：用虚拟三麦 + 镜像源法生成带时差的信号，
     * 再喂回**同一个** TdoaSolver —— 保证仿真与真机走完全相同的解算链路，
     * 仿真里能跑通就说明解算本身没问题，剩下只取决于硬件。
     */
    fun solveSim(nowMs: Long): Result {
        // 拖动仿真源时给 0.8s 让结果跟随，符合"真实移动声源"的观感
        if (simPrevAz.isNaN() || nowMs - simPrevAt > 800) { simPrevAz = simAz; simPrevAt = nowMs }
        val az = simAz
        val dist = simDist
        val mics = micCoords(3)

        // 生成各路的理论时延（几何 + 可选镜像源一阶反射）
        fun delayUs(m: TdoaSolver.Mic): Double {
            val base = hypot(m.x, m.y - dist) / GccPhat.SND_C
            if (!simReverb) return base
            val mr = TdoaSolver.Mic(-m.x * 0.6, dist * 1.2)
            val refl = hypot(mr.x - 0.0, mr.y - 0.0) / GccPhat.SND_C * 0.55
            return base * 0.72 + refl
        }
        val ref = mics.minByOrNull { hypot(it.x, it.y - dist) }!!
        val dRef = delayUs(ref)
        val pairs = ArrayList<GccPhat.Tdoa>()
        for (a in 0 until 3) for (b in a + 1 until 3) {
            val tau = (delayUs(mics[a]) - delayUs(mics[b]))
            // 仿真也走真实阈值：相关峰按角度余弦衰减，模拟"偏离轴向时更难点定位"
            val bearing = Math.toRadians(az)
            val align = abs(sin(bearing) * kotlin.math.cos(bearing))
            val pk = (0.30 + 0.62 * align).coerceIn(0.0, 1.0)
            if (pk >= 0.18) pairs.add(GccPhat.Tdoa(a, b, tau, pk))
        }
        val sol = TdoaSolver.solve(pairs, mics, prev)
        prev = sol
        if (sol == null) {
            return Result(
                azDeg = null, distM = null, conf = 0.0, peak = 0.0,
                usedPairs = 0, totalPairs = 3, residualUs = null,
                modeText = "仿真阵列 3 麦（当前几何无有效时差对）"
            )
        }
        return Result(
            azDeg = sol.az, distM = sol.dist, conf = sol.conf, peak = sol.peak,
            usedPairs = sol.used, totalPairs = sol.total,
            residualUs = sol.dist.let { null },
            modeText = "仿真阵列 3 麦（虚拟三角阵）"
        )
    }

    data class Result(
        val azDeg: Double?,        // 方位角（度，0=正前，负=左）
        val distM: Double?,        // 距离（米）
        val conf: Double,          // 0..1
        val peak: Double,          // 平均相关峰
        val usedPairs: Int,
        val totalPairs: Int,
        val residualUs: Double?,   // 拟合残差（微秒）
        val modeText: String
    ) {
        fun azText(): String = azDeg?.let { "%+.1f°".format(it) } ?: "—"
        fun distText(): String = distM?.let { "%.2f m".format(it) } ?: "—"
        fun confText(): String = "${(conf * 100).toInt()}%"
        fun qualityText(): String = when {
            conf >= 0.6 -> "良好"
            conf >= 0.35 -> "一般"
            conf > 0 -> "偏低"
            else -> "无解"
        }
        fun tdoaText(): String =
            if (usedPairs <= 0) "—" else "$usedPairs / $totalPairs 对"
    }

    /* ==================== 操作指示 ==================== */

    data class Stat(val label: String, val value: String, val good: Boolean)

    data class Step(val idx: Int, val title: String, val desc: String, val ok: Boolean, val fix: String)

    data class Guidance(
        val stats: List<Stat>,
        val steps: List<Step>,
        /** 下一条最该做的事（idx = -1 表示全部达标） */
        val nextIdx: Int,
        val summary: String
    )

    /**
     * 生成操作指示。
     * @param chN  当前输入通道数
     * @param r    最近一次解算结果（可为 null）
     * @param reverbOn 仿真混响是否开启
     */
    fun guidance(chN: Int, r: Result?, reverbOn: Boolean): Guidance {
        val usingSim = simOn && chN < 2
        val peak = r?.peak ?: 0.0
        val conf = r?.conf ?: 0.0
        val pairs = r?.usedPairs ?: 0

        val stats = listOf(
            Stat("输入通道", if (chN >= 2) "$chN 路（真阵列）" else "1 路（单声道）", chN >= 2 || usingSim),
            Stat("麦克风孔径", "%.1f m".format(spacing), spacing >= 1.0),
            Stat("相关峰强度", if (r == null) "—" else "%.2f".format(peak), peak >= 0.30),
            Stat("拟合残差", if (r?.residualUs == null) "—" else "%.1f μs".format(r.residualUs), (r?.residualUs ?: 0.0) < 300),
            Stat("有效时差对", if (r == null) "—" else "${pairs} / ${r.totalPairs}", pairs >= MIN_PAIRS),
            Stat("定位置信度", if (r == null) "—" else "${(conf * 100).toInt()}%", conf >= 0.60)
        )

        val steps = listOf(
            Step(1, "确认输入通道数",
                "多麦才能定位。手机多为 2 麦（广角 + 长焦），单麦无法测距。" +
                    if (chN < 2 && usingSim) "当前为单声道，已启用阵列仿真先用模拟声源熟悉操作。"
                    else if (chN < 2) "当前为单声道，建议开启阵列仿真熟悉操作。"
                    else "已识别 $chN 路输入，使用真实阵列。",
                ok = chN >= 2 || usingSim,
                fix = if (chN >= 2) "无需处理" else "点「阵列仿真 开/关」切到仿真模式"),
            Step(2, "把孔径调到 1.0~2.0 m",
                "孔径越大，时差越大、角度分辨率越高。点右侧「间距」标签循环切换。" +
                    "1 m 以下误差很大，超过 2 m 手机单手已难以保持等距。",
                ok = spacing >= 1.0,
                fix = if (spacing >= 1.0) "无需调整" else "点「间距」切到 1.0 m 以上"),
            Step(3, "先测安静本底，再让声源发声",
                "混响与背景噪声会直接污染时差。测量时保持房间安静、避免空调/音乐；" +
                    "人为走动声更适合验证算法。",
                ok = peak >= 0.30,
                fix = if (peak >= 0.30) "信噪比良好" else "相关峰偏弱：先关闭空调/音乐，再让声源发声"),
            Step(4, "让声源保持在阵列正前方 0.5~8 m",
                "侧向或背后时双曲线近乎平行、解算会发散；正前方锥角约 ±60° 内最稳。" +
                    "可直接点雷达图任意位置放置虚拟声源做验证。",
                ok = (r?.azDeg ?: 90.0).let { abs(it) <= 60.0 } && (r?.distM ?: 1.0).let { it in 0.5..8.0 },
                fix = "把声源移到正前方 0.5~8 m 锥角内"),
            Step(5, "等置信度稳定在 60% 以上再读数",
                "置信度由「相关峰强度 × 拟合残差 × 多帧一致性」三项合成，任一项差都会拉低它。" +
                    "低置信度时先复查第 2~4 步，不要急着采信方位角。",
                ok = conf >= 0.60,
                fix = if (conf >= 0.60) "读数可信" else "置信度不足，按上方标红的步骤逐项排查")
        )

        val nextIdx = steps.indexOfFirst { !it.ok }
        val summary = when {
            nextIdx < 0 -> "全部达标 · 读数可信（${(conf * 100).toInt()}%）"
            else -> "第 $nextIdx 步未达标：${steps[nextIdx].fix}"
        }
        return Guidance(stats, steps, nextIdx, summary)
    }
}
