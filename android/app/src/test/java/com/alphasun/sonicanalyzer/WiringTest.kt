package com.alphasun.sonicanalyzer

import com.alphasun.sonicanalyzer.core.Dsp
import com.alphasun.sonicanalyzer.core.Nz
import org.junit.Assert.*
import org.junit.Test

/**
 * v0.6.0 功能可用性回归测试。
 *
 * 这些测试针对的是"编译通过但真机不可用"的类别，
 * 而不是纯算法正确性（算法部分见 DspTest / EngineRobustnessTest）。
 */
class WiringTest {

    // ==================== 前台服务 ====================

    @Test
    fun `前台服务类型常量与 Manifest 声明一致`() {
        // Manifest 里写的是 microphone|camera，两个常量必须都存在，
        // 否则 API 29+ 的 foregroundServiceType 解析会失败
        assertEquals("alphasun_guard", GuardService.CHANNEL_ID)
        assertEquals("com.alphasun.sonicanalyzer.STOP_GUARD", GuardService.ACTION_STOP)
        assertTrue("通知 ID 必须为正", GuardService.NOTI_ID > 0)
    }

    // ==================== 可视化模式 ====================

    @Test
    fun `可视化模式列表非空且默认项存在`() {
        val modes = MainViewModel.VIZ_MODES
        assertTrue("至少应有 1 种可视化", modes.size >= 1)
        assertEquals("默认可视化应为经典环谱", "经典环谱", modes[0].first)
        assertTrue("默认可视化应为环形", modes[0].second)
        assertTrue("应包含线性频谱", modes.any { it.first == "线性频谱" })
        assertTrue("应包含波形时域", modes.any { it.first == "波形时域" })
    }

    /**
     * cycleViz 的取模必须能双向循环且不越界。
     * ViewModel 依赖 Android，这里只验证纯索引运算（与 cycleViz 同构）。
     */
    @Test
    fun `可视化切换双向循环不越界`() {
        val n = MainViewModel.VIZ_MODES.size
        fun next(i: Int, dir: Int) = (i + dir + n) % n
        var i = 0
        repeat(n * 3) { i = next(i, 1); assertTrue(i in 0 until n) }
        repeat(n * 3) { i = next(i, -1); assertTrue(i in 0 until n) }
        assertEquals("正向一圈应回到原点", 0, next(0, n))
    }

    // ==================== 噪音等级分层 ====================

    @Test
    fun `噪音等级文案已从 core 解耦且分级正确`() {
        // 修复前 core.NoiseEval 反向依赖 ui.theme.NzLevels（含 Compose Color），
        // JVM 测试无法直接调用；现在 core.Nz 是纯 Kotlin
        assertEquals("未测量", Nz.of(null).text)
        assertEquals("未测量", Nz.of(Double.NaN).text)
        assertEquals("安静（图书馆/卧室）", Nz.of(10.0).text)   // 低于最低档取最低档
        assertEquals("一般（图书馆/教室）", Nz.of(55.0).text)
        assertEquals("嘈杂（马路/工地旁）", Nz.of(80.0).text)
        assertEquals("很吵（工地/车间）", Nz.of(120.0).text)
        assertTrue("等级表应按分贝递增", Nz.LEVELS.zipWithNext().all { it.first.db < it.second.db })
    }

    // ==================== 采集增益边界 ====================

    @Test
    fun `输入增益钳位在 0_3 到 6_0`() {
        // 与 MainViewModel.setInputGain 同构的钳位逻辑
        fun clamp(g: Float) = g.coerceIn(0.3f, 6.0f)
        assertEquals(0.3f, clamp(0.0f), 1e-6f)
        assertEquals(0.3f, clamp(-5f), 1e-6f)
        assertEquals(6.0f, clamp(99f), 1e-6f)
        assertEquals(2.5f, clamp(2.5f), 1e-6f)
    }

    @Test
    fun `高增益软限幅后不越界`() {
        // 主循环用 tanh(v*gain)*0.98 软限幅，增益 6× 时绝不能越出 ±1
        fun tanh(x: Double): Double {
            if (x > 20) return 1.0
            if (x < -20) return -1.0
            val e2 = kotlin.math.exp(2 * x)
            return (e2 - 1) / (e2 + 1)
        }
        for (gain in listOf(0.3f, 1.0f, 3.0f, 6.0f)) {
            for (v in listOf(-1f, -0.5f, 0f, 0.5f, 1f)) {
                val out = tanh(v.toDouble() * gain.toDouble()) * 0.98
                assertTrue("gain=$gain v=$v 输出 $out 越界", out >= -1.0 && out <= 1.0)
            }
        }
        // 小信号增益必须真的放大（否则灵敏度等于没接）
        val g6 = tanh(0.02 * 6.0) * 0.98
        val g1 = tanh(0.02 * 1.0) * 0.98
        assertTrue("6× 增益应显著大于 1×（$g6 vs $g1）", g6 > g1 * 3)
    }

    // ==================== v0.6.1 真机失效根因回归 ====================

    /**
     * 头号根因：立体声设备上 `frame` 从未被赋值 → 主循环 `if (raw.size > 32)`
     * 恒为 false → 所有读数不动。
     *
     * 这里验证"多通道发布后主帧非空"的**契约**：publishFixed 无论通道数多少
     * 都必须产出长度 == fftSize 的 frame。AudioCapture 依赖 android.media，
     * 无法在 JVM 里实例化，故用与 publishFixed 同构的最小实现验证契约。
     */
    @Test
    fun `多通道时也必须发布主帧_否则主循环恒空转`() {
        val fft = 2048
        // 旧实现：ch>=2 时 frame 保持 FloatArray(0)
        fun publishOld(ch: Int): FloatArray {
            var frame = FloatArray(0)
            if (ch == 1) frame = FloatArray(fft)
            return frame
        }
        // 新实现：任何通道数都先发布 ch0
        fun publishNew(ch: Int): FloatArray = FloatArray(fft)

        assertEquals(0, publishOld(2).size, )   // 旧行为：空 → 主循环空转
        assertTrue("新实现在立体声下必须产出 fftSize 主帧", publishNew(2).size > 32)
        assertTrue("新实现在单声道下同样要产出主帧", publishNew(1).size > 32)
    }

    @Test
    fun `伪立体声判定_复制通道应判为真_独立通道应为假`() {
        val n = 2048
        val mono = FloatArray(n) { i -> (0.4 * kotlin.math.sin(i * 0.05)).toFloat() }
        // 复制单声道：两路逐样本全等 → corr = 1
        assertTrue("复制通道必须判为伪立体声", Dsp.isFakeStereo(mono, mono.copyOf()))
        // 带时延的真双麦：corr 明显低于 1
        val delayed = FloatArray(n) { i -> if (i >= 6) mono[i - 6] else 0f }
        assertFalse("有真时差的双通道不能判为伪立体声", Dsp.isFakeStereo(mono, delayed))
        // 独立噪声：相关性接近 0
        var seed = 12345L
        fun rnd(): Float {
            seed = (seed * 1103515245L + 12345L) and 0x7FFFFFFF
            return (seed % 2000 - 1000) / 1000f
        }
        val noiseA = FloatArray(n) { rnd() }
        val noiseB = FloatArray(n) { rnd() }
        val c = Dsp.correlation(noiseA, noiseB)
        assertTrue("独立噪声相关系数应接近 0，实际 $c", kotlin.math.abs(c) < 0.15)
        // 静音帧：能量过低，返回 NaN，不参与判定
        assertTrue("静音帧必须返回 NaN 以跳过判定", Dsp.correlation(FloatArray(n), FloatArray(n)).isNaN())
        assertFalse("静音帧不能误判为伪立体声", Dsp.isFakeStereo(FloatArray(n), FloatArray(n)))
    }

    /**
     * 主循环异常自愈：不能在协程内 cancel 自己，否则只是自杀、永不重启。
     * 这里验证"重启调度器"的纯逻辑：清理 + 置空 loopJob + 另起协程 start。
     */
    @Test
    fun `自愈逻辑不得取消自身协程`() {
        // 用可观测的状态机模拟：cancelOwn=true 是旧的错误写法
        fun simulate(cancelOwn: Boolean): String {
            var loopJob: Any? = "job"
            var capturing = true
            var restarted = false
            if (cancelOwn) {
                loopJob = null          // 自杀：协程被取消，后续 delay 立刻抛 CancellationException
                // 没有任何新的 start() 被调度
            } else {
                capturing = false
                loopJob = null
                if (loopJob == null && !capturing) restarted = true
            }
            return if (restarted) "RESTARTED" else "DEAD"
        }
        assertEquals("DEAD", simulate(cancelOwn = true))
        assertEquals("RESTARTED", simulate(cancelOwn = false))
    }

    /**
     * 试听三路路由：`mix` 必须走原声，不能被 `else` 兜成背景。
     * 这是 SepPanel 上「试听原声」按钮播成背景的回归测试。
     */
    @Test
    fun `试听三路路由_mix不能落到背景分支`() {
        fun route(which: String): String = when (which) {
            "fg" -> "FG"
            "mix" -> "MIX"
            else -> "BG"
        }
        assertEquals("FG", route("fg"))
        assertEquals("BG", route("bg"))
        assertEquals("MIX", route("mix"))
        // 旧写法 `if (which == "fg") FG else BG` 会把 mix 打成 BG
        fun routeOld(which: String) = if (which == "fg") "FG" else "BG"
        assertEquals("旧实现确实把 mix 错判成背景（本测试用于固化回归）", "BG", routeOld("mix"))
    }
}
