package com.alphasun.sonicanalyzer

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alphasun.sonicanalyzer.audio.AudioCapture
import com.alphasun.sonicanalyzer.camera.AlertMediaDir
import com.alphasun.sonicanalyzer.camera.DualCamera
import com.alphasun.sonicanalyzer.camera.WavWriter
import com.alphasun.sonicanalyzer.core.Classify
import com.alphasun.sonicanalyzer.core.Dsp
import com.alphasun.sonicanalyzer.core.Features
import com.alphasun.sonicanalyzer.core.GuardEngine
import com.alphasun.sonicanalyzer.core.LocatorEngine
import com.alphasun.sonicanalyzer.core.NoiseEval
import com.alphasun.sonicanalyzer.core.SelfCheck
import com.alphasun.sonicanalyzer.core.SeparationEngine
import com.alphasun.sonicanalyzer.core.Stft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * 主 ViewModel：持有采集层与全部 DSP 引擎，向 Compose 暴露不可变状态。
 *
 * 引擎分工（与 Web v0.3.1 一一对应）：
 *  · Features.Analyzer  → P2 声波参数 23 项
 *  · Classify.Engine    → 智能分类（人声/音乐/其他/噪音）
 *  · NoiseEval          → P3 噪音评估（Leq/频段/类型/历史曲线）
 *  · SeparationEngine   → P4 前景背景分离（背景学习 + 模型质量）
 *  · LocatorEngine      → P5 声源定位（真阵列 + 仿真 + 操作指示）
 *  · GuardEngine        → P6 声波警戒（本底/预警/告警/事件/抓拍）
 *
 * 主循环 20Hz：采集线程独立阻塞读取，本循环只做 DSP + 发布状态。
 * 重活（录试听片段、离线分离分析、抓拍落盘）全部丢到 IO 线程，不阻塞 UI。
 *
 * ============================ v0.6.0 ============================
 * · 权限与启动解耦：start() 先查权限，无权限时置 needsMicPermission，
 *   由 Activity 授权回调触发 retryStart() —— 修掉"点开始没反应"。
 * · 主循环逐帧 try/catch + 连续失败自动重建采集（见 loop() 注释）。
 * · 新增 diag/lastError/loopErrors 三个可观测字段，让故障对用户可见。
 * · 改为 AndroidViewModel：之前是普通 ViewModel(Context)，
 *   AndroidViewModelFactory 无法构造，viewModel() 会抛 IllegalArgumentException
 *   或静默返回 null，导致界面上根本没有引擎实例 —— 这是"功能不可用"根因之一。
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Context get() = getApplication()

    // ================= UI 状态 =================

    data class UiState(
        val capturing: Boolean = false,
        val envDb: Double = -100.0,
        val levelText: String = "未采集",
        val statusText: String = "待机",
        val sampleRate: Int = 48000,
        val channelCount: Int = 1,
        val fftSize: Int = 2048,
        val spectrum: FloatArray = FloatArray(0),
        val bars: FloatArray = FloatArray(0),
        val bands: FloatArray = FloatArray(6),
        val refOffset: Double = 94.0,
        val appVersion: String = APP_VERSION,
        val peakDb: Double = -100.0,
        val crest: Double = 0.0,
        val meterPct: Float = 0f,
        val peakPct: Float = 0f,
        val frame: Features.Frame? = null,
        val conf: Classify.Conf? = null,
        val verdict: String = "—",
        val otherTop: String = "—",
        val otherScore: Double = 0.0,
        val voiceDetail: String = "—",
        val noise: NoiseSnapshot = NoiseSnapshot(),
        val sep: SepSnapshot = SepSnapshot(),
        val loc: LocSnapshot = LocSnapshot(),
        val guard: GuardSnapshot = GuardSnapshot(),
        val toast: String? = null,
        /** v0.6.0：启动失败根因，供 UI 给出可行动提示（而非"点开始没反应"） */
        val lastError: String? = null,
        /** v0.6.0：是否因为缺少麦克风权限而未启动 —— UI 据此显示"授权"按钮 */
        val needsMicPermission: Boolean = false,
        /** v0.6.0：采集链路诊断文本（音源/采样率/通道） */
        val diag: String = "未启动",
        /** v0.6.0：主循环异常计数，>0 说明引擎抛过异常（排障用） */
        val loopErrors: Int = 0,
        /** v0.6.0：采集增益（对齐 Web #sens，0.3~6.0×），实际作用于输入信号 */
        val inputGain: Float = 1.0f,
        /** v0.6.0：当前可视化名称（对齐 Web #vizNameM） */
        val vizName: String = "经典环谱",
        /** v0.6.0：时域波形（对齐 Web 的波形可视化 + 警戒波形复用） */
        val wave: FloatArray = FloatArray(0),
        /** v0.6.2：应用内自检结果；null = 尚未运行 */
        val selfCheck: SelfCheck.Report? = null,
        /** v0.6.2：自检正在跑（算法层约需数百毫秒，故放到后台线程） */
        val selfChecking: Boolean = false
    ) {
        // FloatArray/含数组的字段不适合 data class 相等比较
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /** 噪音评估快照（避免 UI 直接持有引擎） */
    data class NoiseSnapshot(
        val running: Boolean = false,
        val cur: Double = -999.0,
        val leq: Double = Double.NaN,
        val lmax: Double = Double.NaN,
        val lmin: Double = Double.NaN,
        val peakHold: Double = -999.0,
        val lo: Double = 0.0,
        val mid: Double = 0.0,
        val hi: Double = 0.0,
        val centroid: Double = 0.0,
        val stability: Double = 0.0,
        val type: String = "—",
        val level: String = "—",
        val advice: String = "—",
        val windowSec: Int = 60,
        val durSec: Double = 0.0,
        val curve: FloatArray = FloatArray(0)
    ) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /** 分离快照 */
    data class SepSnapshot(
        val state: SeparationEngine.State = SeparationEngine.State.IDLE,
        val progress: Float = 0f,
        val learnFrames: Int = 0,
        val cover: Double = 0.0,
        val noiseDb: Double = Double.NaN,
        val flat: Double = 0.0,
        val peakHz: Double = 0.0,
        val stability: Double = 0.0,
        val profileFeature: String = "—",
        val profilePeak: String = "—",
        val profileJudge: String = "—",
        val fgRatio: Double = Double.NaN,
        val fgLevel: Double = Double.NaN,
        val bgLevel: Double = Double.NaN,
        val suppress: Double = Double.NaN,
        val busy: Boolean = false,
        val analysis: SeparationEngine.Analysis? = null,
        val profile: FloatArray = FloatArray(0)
    ) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /** 定位快照 */
    data class LocSnapshot(
        val result: LocatorEngine.Result? = null,
        val guidance: LocatorEngine.Guidance? = null,
        val chLevels: List<Float> = emptyList(),
        val simOn: Boolean = true,
        val spacing: Double = 1.0,
        val reverb: Boolean = false
    ) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /** 警戒快照 */
    data class GuardSnapshot(
        val enabled: Boolean = false,
        val stateText: String = "未值守",
        val level: GuardEngine.Level = GuardEngine.Level.NORMAL,
        val cur: Double = -999.0,
        val floor: Double = -60.0,
        val thrWarn: Double = -30.0,
        val thrAlarm: Double = -25.0,
        val peak: Double = -999.0,
        val evalProgress: Float = 0f,
        val evalFrames: Int = 0,
        val paused: Boolean = false,
        val durText: String = "0.0s",
        val eventCount: Int = 0,
        val wave: FloatArray = FloatArray(0),
        val logs: List<Pair<String, Int>> = emptyList(),
        val events: List<GuardEngine.Event> = emptyList(),
        val camNote: String = "未检测",
        val flash: Boolean = false
    ) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    // ================= 引擎 =================

    private val cap = AudioCapture(ctx)
    private val cam = DualCamera(ctx)

    private var analyzer: Features.Analyzer? = null
    private val classifier = Classify.Engine()
    private var noise: NoiseEval? = null
    private var sep: SeparationEngine? = null
    private var locator: LocatorEngine? = null
    val guard = GuardEngine()

    private var loopJob: Job? = null
    private val envHistory = ArrayList<Double>()      // BPM 用
    private var lastTickMs = 0L
    private var lastAnalysisMs = 0L
    private var peakHoldDb = -100.0
    private var peakHoldAt = 0L
    private val logs = ArrayDeque<Pair<String, Int>>()
    private var flashUntil = 0L

    companion object {
        const val APP_VERSION = "v1.3.0"   // 与 gradle versionName / index.html APP_VER 统一（见 bump-version.js 第 6 同步点）
        private const val TICK_MS = 50L          // 20Hz
        private const val ANALYSIS_MS = 200L     // 5Hz 特征分析

        /** 可视化模式（与 Web VIZ 列表对齐），first=显示名，second=是否环形 */
        val VIZ_MODES = listOf(
            "经典环谱" to true,
            "线性频谱" to false,
            "六段能量" to false,
            "波形时域" to false
        )
    }

    // ================= 采集控制 =================

    fun start() {
        if (loopJob != null) return
        if (!cap.hasPermission()) {
            // v0.6.0 关键修复：旧版 start() 失败后 _state 停在"启动失败"，
            // 且没有任何重试路径 → 用户点开始没反应，表现为"功能不可用"。
            // 现在明确区分「权限问题」与「设备问题」，并保留可重试状态。
            _state.value = _state.value.copy(
                capturing = false,
                statusText = "需要麦克风权限",
                levelText = "待授权",
                needsMicPermission = true
            )
            return
        }
        _state.value = _state.value.copy(statusText = "正在启动麦克风…", needsMicPermission = false)
        val ok = cap.start { err ->
            _state.value = _state.value.copy(
                capturing = false,
                statusText = "启动失败：$err",
                levelText = "不可用",
                lastError = err
            )
        }
        if (!ok) return

        val binHz = cap.sampleRate.toDouble() / cap.fftSize
        if (analyzer == null) {
            analyzer = Features.Analyzer(cap.sampleRate, cap.fftSize)
            noise = NoiseEval(binHz)
            sep = SeparationEngine(cap.fftSize / 2 + 1)
            locator = LocatorEngine(cap.sampleRate)
        }
        /**
         * 【v0.6.0】真阵列优先；【v0.6.1】但**必须排除伪立体声**。
         *
         * `simOn` 默认 true，若不处理，真机拿到 2 路却一直跑仿真，
         * 用户会觉得"声源定位是假的"。
         * 反过来，若只看通道数就切真阵列，单麦克风机型上
         * `CHANNEL_IN_STEREO` 也会初始化成功（两路数据完全相同），
         * TDOA 时差恒为 0 → 方位角恒指 0°，这是**更糟的假结果**。
         *
         * 所以判定条件是「≥2 通道 **且** 不是伪立体声」。
         */
        val loc = locator
        if (loc != null && cap.channelCount >= 2 && !cap.fakeStereo && loc.simOn) {
            loc.toggleSim()
            pushLog("检测到 ${cap.channelCount} 路独立麦克风输入，声源定位已切换为真实阵列", 0)
        } else if (loc != null && cap.channelCount >= 2 && cap.fakeStereo) {
            pushLog("设备为单麦克风（立体声为复制通道），声源定位保持阵列仿真", 1)
        }
        wireGuard()

        _state.value = _state.value.copy(
            capturing = true,
            statusText = if (cap.channelCount >= 2) "采集中（多通道）" else "采集中（单声道）",
            sampleRate = cap.sampleRate,
            channelCount = cap.channelCount,
            fftSize = cap.fftSize,
            refOffset = cap.refOffset,
            lastError = null,
            diag = cap.diagText()
        )
        lastTickMs = System.currentTimeMillis()
        lastAnalysisMs = 0L
        lastLocMs = 0L
        arrayDecided = false    // 每次启动重新判定真阵列 / 伪立体声
        loopJob = viewModelScope.launch(Dispatchers.Default) { loop() }
    }

    /** 采集诊断信息（音源/参数/通道），供 UI 排障展示 */
    fun diagText(): String = cap.diagText()

    /** 权限被拒绝：给出可行动提示，而不是让用户对着一句"启动失败"发呆 */
    fun onPermissionDenied() {
        _state.value = _state.value.copy(
            capturing = false,
            statusText = "麦克风权限被拒绝",
            levelText = "待授权",
            needsMicPermission = true,
            lastError = "麦克风权限被拒绝，请在系统设置中为本应用开启麦克风权限"
        )
    }

    fun stop() {
        loopJob?.cancel(); loopJob = null
        cap.stop()
        guard.stop()
        _state.value = _state.value.copy(
            capturing = false, statusText = "已停止", levelText = "已停止",
            spectrum = FloatArray(0), bars = FloatArray(0), diag = "已停止"
        )
    }

    /**
     * 【v0.6.0 修复】旧版只在 capturing 为 true 时 start()，
     * 但启动失败后 loopJob 为 null、capturing 为 false，
     * 点「开始」会再次进入 start() —— 这条路径本身是对的，
     * 问题在于失败原因（无权限）没被区分，用户反复点也没用。
     * 现在 needsMicPermission 会在 UI 上给出「去授权」按钮，点击后由
     * requestPermissions() 重新拉起系统框，授权回调后自动重试 start()。
     */
    fun toggleCapture() {
        if (_state.value.capturing) stop() else start()
    }

    /** 权限授予后由 Activity 回调触发，自动重试启动 */
    fun retryStart() {
        if (!cap.hasPermission()) {
            _state.value = _state.value.copy(
                statusText = "麦克风权限未授予", levelText = "待授权", needsMicPermission = true
            )
            return
        }
        // 若上次是异常自愈残留的半死状态，先彻底清理
        if (loopJob != null) {
            loopJob?.cancel(); loopJob = null
            cap.stop()
        }
        start()
    }

    /**
     * 主循环（20Hz）。
     *
     * 【v0.6.0 重写】旧版循环体**没有任何 try/catch**：
     * Dsp.fft 的 require、引擎里的数组下标、除零 —— 任一处抛异常，
     * viewModelScope 的协程立即取消，界面永久停在"采集中"却再无数据更新，
     * 且按钮再点也没用（loopJob 已非 null，start() 直接 return）。
     * 这正是用户反馈"功能不可用"的最隐蔽成因。
     *
     * 现在：每帧独立 try/catch，单帧失败只累加 loopErrors 并跳过本帧，
     * 协程绝不退出；连续失败超过阈值才判定采集层真的挂了。
     */
    private suspend fun loop() {
        val power = DoubleArray(cap.fftSize / 2 + 1)
        var consecutiveErrors = 0
        while (true) {
            val now = System.currentTimeMillis()
            val dt = (now - lastTickMs).coerceIn(1, 500).toDouble()
            lastTickMs = now

            try {
                val raw = cap.frame
                if (raw.size > 32) {
                    /**
                     * 【v0.6.0】采集增益（对齐 Web #sens GainNode）在 FFT 前施加。
                     * 用 tanh 软限幅到 ±0.98：高增益下不会削顶产生宽带伪信号。
                     */
                    val gain = _state.value.inputGain
                    val frame = if (gain == 1.0f) raw else FloatArray(raw.size) {
                        val v = raw[it] * gain
                        (tanh(v.toDouble()) * 0.98).toFloat()
                    }
                    // v0.3.0 优化：一次 FFT 同时产出功率谱(power)与峰值归一化幅度谱(spec)，
                    // 替代原先 magnitude()+powerSpectrum() 两次独立 FFT，20Hz 热路径开销减半。
                    val spec = Dsp.spectrum(frame, power)
                    // 电平用增益后的帧计算，否则调增益时界面读数不动
                    val rms = Dsp.rms(frame)
                    val db = Dsp.dbfs(rms)
                    if (rms > 1e-5) {
                        envHistory.add(rms)
                        if (envHistory.size > 90) envHistory.removeAt(0)
                    }
                    if (db > peakHoldDb) { peakHoldDb = db; peakHoldAt = now }
                    if (now - peakHoldAt > 2000) peakHoldDb = db   // 峰值保持 2s

                    val bars = logBars(spec, 72, cap.sampleRate.toDouble() / cap.fftSize)

                    // P2 特征：5Hz
                    var f: Features.Frame? = null
                    var conf: Classify.Conf? = null
                    if (now - lastAnalysisMs >= ANALYSIS_MS) {
                        lastAnalysisMs = now
                        val a = analyzer ?: Features.Analyzer(cap.sampleRate, cap.fftSize).also { analyzer = it }
                        // analyze 需要 DoubleArray 幅度谱；由 powerSpectrum 的 sqrt 派生，
                        // 避免同一帧做两次 FFT
                        val mag = DoubleArray(power.size) { kotlin.math.sqrt(maxOf(power[it], 0.0)) }
                        val base = a.analyze(frame, mag)
                        val withBpm = if (envHistory.size > 20)
                            base.copy(bpm = Features.estimateBpm(envHistory, ANALYSIS_MS.toDouble()))
                        else base
                        f = withBpm
                        conf = classifier.tick(withBpm)
                        noise?.let { n ->
                            n.calibrateRef(cap.refOffset)
                            n.tick(Dsp.dbfs(rms), withBpm.bandLin, now)
                        }
                        sep?.tick(power, cap.sampleRate.toDouble() / cap.fftSize, rms)
                    }

                    // 定位：5Hz
                    val loc = locator
                    var locSnap = _state.value.loc
                    if (loc != null && now - lastLocMs >= 200) {
                        lastLocMs = now
                        val chN = cap.channelCount
                        // 【v0.6.1】伪立体声探测约需 1.7s，结论出来后再做一次
                        // 阵列/仿真的最终判定（start() 时点太早，探测还没结果）。
                        if (!arrayDecided && chN >= 2 && cap.fakeProbeDone) {
                            arrayDecided = true
                            if (!cap.fakeStereo && loc.simOn) {
                                loc.toggleSim()
                                pushLog("两路输入相互独立，声源定位已切换到真实阵列 TDOA", 0)
                            } else if (cap.fakeStereo && !loc.simOn) {
                                loc.toggleSim()
                                pushLog("两路输入完全相同（单麦克风复制通道），已切回阵列仿真", 1)
                            }
                        }
                        val res = if (loc.simOn) loc.solveSim(now) else loc.solveReal(cap.multi)
                        locSnap = LocSnapshot(
                            result = res,
                            guidance = loc.guidance(chN, res, loc.simReverb),
                            chLevels = loc.channelLevels(cap.multi),
                            simOn = loc.simOn, spacing = loc.spacing, reverb = loc.simReverb
                        )
                    }

                    // 警戒
                    guard.tick(db + cap.refOffset, frame, cap.sampleRate, now, dt)

                    _state.value = _state.value.copy(
                        envDb = db,
                        levelText = levelText(db + cap.refOffset),
                        spectrum = spec,
                        bars = bars,
                        bands = f?.bands ?: _state.value.bands,
                        peakDb = peakHoldDb,
                        crest = f?.crest ?: _state.value.crest,
                        wave = downsample(frame, 240),
                        meterPct = (((db + 60.0) / 60.0).coerceIn(0.0, 1.0) * 100).toFloat(),
                        peakPct = (((peakHoldDb + 60.0) / 60.0).coerceIn(0.0, 1.0) * 100).toFloat(),
                        frame = f ?: _state.value.frame,
                        conf = conf ?: _state.value.conf,
                        verdict = if (conf != null) Classify.verdictText(conf) else _state.value.verdict,
                        noise = snapNoise(),
                        sep = snapSep(),
                        loc = locSnap,
                        guard = snapGuard(now)
                    )
                }
                consecutiveErrors = 0
            } catch (ce: kotlinx.coroutines.CancellationException) {
                // 【v0.6.1】停止采集是正常流程，绝不能被下面的 catch 吞掉：
                // 一旦吞掉，后续 delay() 会立刻再抛 CancellationException，
                // 形成「catch → 计数 → 再次 catch」的空转死循环（CPU 打满、界面卡死）。
                throw ce
            } catch (e: Throwable) {
                // 单帧失败不致命：记录并继续，让用户看到界面还活着
                consecutiveErrors++
                val n = _state.value.loopErrors + 1
                if (n == 1 || n % 25 == 0) {
                    android.util.Log.e("AlphaSunLoop", "主循环第 $consecutiveErrors 次异常", e)
                    _state.value = _state.value.copy(
                        loopErrors = n,
                        statusText = if (consecutiveErrors < 30) st_statusWithErr(e) else _state.value.statusText
                    )
                }
                // 连续失败过久 → 采集层多半已死，退出本循环并**由外部**重建。
                //
                // 【v0.6.1 致命修复】旧代码在这里调 restartCapture()，
                // 而 restartCapture() 内部 `loopJob?.cancel()` 取消的正是**当前协程自己**。
                // 结果：本循环退出、却没有新的循环被拉起 → capturing 停在 false、
                // 界面显示"正在自动重启…"却永远不会重启 —— 这就是"功能不可用"的一个真因。
                if (consecutiveErrors >= 60) {
                    android.util.Log.e("AlphaSunLoop", "连续 $consecutiveErrors 帧失败，重建采集层", e)
                    scheduleRestart()
                    return   // 必须 return：协程已由 scheduleRestart 置空并重新调度
                }
            }
            delay(TICK_MS)
        }
    }

    private fun st_statusWithErr(e: Throwable): String =
        "运行异常（已跳过 ${_state.value.loopErrors} 帧）：${e.javaClass.simpleName}"

    /**
     * 采集层异常自愈：彻底停掉，再**由新的协程**拉起 start()。
     *
     * 【v0.6.1】关键约束：**绝不能在本协程内 cancel 自己**。
     * 旧实现 `loopJob?.cancel()` 取消的就是调用它的那个协程，
     * 于是"重启"永远只是自杀，界面停在"正在自动重启…"。
     *
     * 现在：只做清理 + 置空 loopJob，再另起一个协程延迟调 start()。
     * start() 里 `if (loopJob != null) return` 的守卫因此不会拦住重启。
     */
    private fun scheduleRestart() {
        try {
            cap.stop()
            guard.stop()
            analyzer = null   // 强制重建引擎实例（采样率可能变了）
            loopJob = null    // 注意：不 cancel，只是解引用；当前协程由调用方 return 结束
            _state.value = _state.value.copy(
                capturing = false, statusText = "采集异常，正在自动重启…"
            )
        } catch (_: Throwable) { }
        viewModelScope.launch(Dispatchers.Main) {
            delay(800)
            if (loopJob == null && !_state.value.capturing) {
                android.util.Log.w("AlphaSunLoop", "自动重启采集")
                start()
            }
        }
    }

    private var lastLocMs = 0L
    /** v0.6.1：真阵列/仿真的自动判定只做一次，避免来回抖动 */
    private var arrayDecided = false

    // ================= 引擎回调接线 =================

    private fun wireGuard() {
        guard.onLog = { text, kind ->
            synchronized(logs) {
                logs.addLast(text to kind)
                while (logs.size > 200) logs.removeFirst()
            }
        }
        guard.onFlash = { level ->
            if (guard.flashOnAlarm) {
                flashUntil = System.currentTimeMillis() + 1200
                if (_state.value.guard.flash != (level == GuardEngine.Level.ALARM)) {
                    _state.value = _state.value.copy(
                        guard = _state.value.guard.copy(flash = level == GuardEngine.Level.ALARM)
                    )
                }
            }
        }
        guard.onBeep = { beep() }
        guard.onNotify = { e ->
            val n = guard.notifyTargets()
            pushLog(
                "告警推送：事件 #${e.id}（${e.level.text}）" +
                    if (n > 0) " · 已配置 $n 个通道，原生暂未实现网络发送" else " · 未配置推送通道",
                2
            )
        }
        guard.onMediaReady = { e -> writeEventMedia(e) }
        guard.onShot = GuardEngine.ShotListener { level, e ->
            requestShot(e)
        }
    }

    /** 抓拍：优先并发双摄，失败自动降级串行；结果回填事件记录。 */
    private fun requestShot(e: GuardEngine.Event) {
        val dir = AlertMediaDir.today(ctx)
        val base = GuardEngine.fmtName(e, "shot")
        viewModelScope.launch(Dispatchers.IO) {
            val capsNote = cam.caps().note
            cam.capture(dir, base, preferFrontFirst = !guard.dualCamera) { shots ->
                var f: String? = null; var b: String? = null
                var fe: String? = null; var be: String? = null
                for (s in shots) {
                    if (s.side == "front") { f = s.file?.absolutePath; if (f == null) fe = s.error ?: "未出图" }
                    else { b = s.file?.absolutePath; if (b == null) be = s.error ?: "未出图" }
                }
                viewModelScope.launch(Dispatchers.Main) {
                    guard.fillShot(e, f, b, fe, be)
                    val okN = listOfNotNull(f, b).size
                    pushLog(
                        "抓拍：$okN/2 路成功（$capsNote）",
                        if (okN == 2) 0 else 1
                    )
                }
            }
        }
    }

    /** 事件音频落盘（WAV） */
    private fun writeEventMedia(e: GuardEngine.Event) {
        if (!guard.captureAudio || e.pcm.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val dir = AlertMediaDir.today(ctx)
            val pcm = WavWriter.concat(e.pcm)
            val f = File(dir, GuardEngine.fmtName(e, "audio") + ".wav")
            val ok = WavWriter.write(f, pcm, cap.sampleRate, 1)
            viewModelScope.launch(Dispatchers.Main) {
                if (ok) {
                    e.wavPath = f.absolutePath
                    pushLog("事件 #${e.id} 音频已存：${f.name}", 0)
                }
                _state.value = _state.value.copy(guard = snapGuard(System.currentTimeMillis()))
            }
        }
    }

    private fun pushLog(text: String, kind: Int) {
        synchronized(logs) {
            logs.addLast(text to kind)
            while (logs.size > 200) logs.removeFirst()
        }
        _state.value = _state.value.copy(guard = snapGuard(System.currentTimeMillis()))
    }

    private fun beep() {
        try {
            val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            v.vibrate(VibrationEffect.createOneShot(220, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) { }
    }

    // ================= 快照 =================

    private fun snapNoise(): NoiseSnapshot {
        val n = noise ?: return NoiseSnapshot()
        val (mx, mn, avg) = n.stats()
        val (lo, mid, hi) = n.bandShares()
        return NoiseSnapshot(
            running = n.frames > 0,
            cur = n.current(), leq = n.leq(), lmax = mx, lmin = mn,
            peakHold = n.peakHold, lo = lo, mid = mid, hi = hi,
            centroid = n.centroid, stability = n.stability,
            type = n.type(), level = n.levelText(), advice = n.advice(),
            windowSec = n.windowSec, durSec = n.durSec, curve = n.curve()
        )
    }

    private fun snapSep(): SepSnapshot {
        val s = sep ?: return SepSnapshot()
        val tpl = s.template
        val prof = if (tpl == null) FloatArray(0) else {
            var mx = 1e-12
            for (v in tpl) if (v > mx) mx = v
            FloatArray(tpl.size) { (10.0 * log10(1.0 + tpl[it] / mx)).toFloat() }
        }
        return SepSnapshot(
            state = s.state, progress = s.learnProgress(), learnFrames = s.learnFrames,
            cover = s.coverRatio, noiseDb = s.noiseDb, flat = s.profileFlat,
            peakHz = s.profilePeakHz, stability = s.stability,
            profileFeature = s.profileFeature(), profilePeak = s.profilePeakText(),
            profileJudge = s.profileJudge(),
            fgRatio = s.fgRatio, fgLevel = s.fgLevel, bgLevel = s.bgLevel,
            suppress = s.suppressAmt, analysis = s.lastAnalysis, profile = prof
        )
    }

    private fun snapGuard(now: Long): GuardSnapshot {
        val c = _state.value.guard
        val flashOn = now < flashUntil
        val ev = guard.current
        val dur = if (ev != null) "%.1fs".format((now - ev.tStartMs) / 1000.0) else "0.0s"
        val synced = synchronized(logs) { logs.toList() }
        return GuardSnapshot(
            enabled = guard.enabled, stateText = guard.stateText(),
            level = guard.stateLevel(), cur = guard.lastDb, floor = guard.floor,
            thrWarn = guard.thrWarn, thrAlarm = guard.thrAlarm,
            peak = guard.sessionPeak, evalProgress = guard.evalProgress(),
            evalFrames = guard.evalFrames(), paused = guard.paused,
            durText = dur, eventCount = guard.events.size,
            wave = guard.waveOrdered(), logs = synced,
            events = guard.events.toList(), flash = flashOn
        )
    }

    // ================= 对外操作 =================

    /**
     * 【v0.6.0】采集增益（对齐 Web #sens）。
     * Web 端是 GainNode，这里在主循环里对每帧时域样本做同样的缩放。
     * 必须在 FFT **之前**乘，否则功率/幅度谱都要重算归一，代价更大。
     * 超过 0.98 时做软限幅，避免削顶产生 Broadband 噪声。
     */
    fun setInputGain(g: Float) {
        val gg = g.coerceIn(0.3f, 6.0f)
        _state.value = _state.value.copy(inputGain = gg)
    }

    /** 可视化切换（对齐 Web #vizPrevM / #vizNextM）。 */
    fun cycleViz(dir: Int) {
        val i = VIZ_MODES.indexOfFirst { it.first == _state.value.vizName }.let { if (it < 0) 0 else it }
        val n = (i + dir + VIZ_MODES.size) % VIZ_MODES.size
        _state.value = _state.value.copy(vizName = VIZ_MODES[n].first)
    }

    fun calibrateRef(targetDb: Double) {
        val off = targetDb - Dsp.dbfs(cap.rms)
        cap.refOffset = off
        noise?.calibrateRef(off)
        _state.value = _state.value.copy(refOffset = off)
    }

    // —— 噪音评估 ——
    fun noiseReset() {
        noise?.let { n ->
            n.calibrateRef(cap.refOffset)
            n.reset()
        }
    }

    fun noiseWindow(sec: Int) {
        noise?.setWindow(sec)
        _state.value = _state.value.copy(noise = snapNoise())
    }

    // —— 分离 ——
    fun sepStartLearn() {
        sep?.startLearn()
        _state.value = _state.value.copy(sep = snapSep())
    }

    fun sepReset() {
        sep?.reset()
        _state.value = _state.value.copy(sep = snapSep())
    }

    /** 录一段混合音频并做离线分离分析（试听前景/背景共用）。 */
    fun sepAnalyseClip(sec: Int = 6) {
        if (_state.value.sep.busy) return
        _state.value = _state.value.copy(sep = _state.value.sep.copy(busy = true))
        viewModelScope.launch(Dispatchers.IO) {
            val clip = grabClip(sec)
            val s = sep
            val res = if (clip == null || s == null) null
            else if (s.state != SeparationEngine.State.READY) null
            else s.analyze(clip.pcm)
            viewModelScope.launch(Dispatchers.Main) {
                s?.lastAnalysis = res
                _state.value = _state.value.copy(
                    sep = snapSep().copy(busy = false),
                    toast = if (clip == null) "无法获取音频，请先启动采集"
                    else if (res == null) "请先完成背景学习"
                    else "分析完成：抑制 ${"%.1f".format(res.suppressPct * 100)}%"
                )
            }
        }
    }

    /**
     * 取一段待处理音频：**优先从实时流快照**，不另开 AudioRecord。
     *
     * 【v0.6.0 修复 —— "试听前景/背景没声音"的真因】
     * 旧实现调 `cap.recordClip()` 另开一路 AudioRecord。
     * 试听时主采集正在跑，绝大多数手机上第二路初始化直接失败（麦克风独占），
     * clip 恒为 null → 弹出"录音失败：麦克风被占用" → 用户看到的就是功能不可用。
     * 现在走环形缓冲快照，与实时采集共存。
     */
    private fun grabClip(sec: Int = 4): AudioCapture.Clip? =
        cap.liveClip(sec) ?: cap.recordClip(sec)

    /**
     * 试听前景 / 背景 / 原声（用当前背景模型做谱减后播放）。
     *
     * 【v0.6.1 修复】SepPanel 上有「试听原声」按钮传的是 `"mix"`，
     * 而这里的判断只写了 `if (which == "fg") res.fg else res.bg` ——
     * 点「试听原声」播出来的其实是**背景**，用户会以为分离功能全错。
     * 现在三路各自返回真正对应的 PCM。
     */
    fun sepPreview(which: String) {
        if (which == "mix") { previewMix(); return }
        if (sep?.state != SeparationEngine.State.READY) {
            _state.value = _state.value.copy(toast = "请先完成背景学习")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val clip = grabClip(4)
            if (clip == null) {
                viewModelScope.launch(Dispatchers.Main) {
                    _state.value = _state.value.copy(
                        toast = if (_state.value.capturing) "音频缓冲尚未就绪，请稍候再试"
                        else "请先开始采集"
                    )
                }
                return@launch
            }
            val res = Stft.subtract(clip.pcm, sep?.template)
            val pcm = if (which == "fg") res.fg else res.bg
            // 【v0.6.0】响度归一化：分离结果往往远轻于原声（尤其前景），
            // 不归一化时用户会以为"没声音"。tanh 软限幅保证永不削顶。
            if (Dsp.peak(pcm) > 1e-6) Stft.normalize(pcm)
            val sr = clip.sampleRate
            viewModelScope.launch(Dispatchers.Main) {
                Player.play(pcm, sr)
                _state.value = _state.value.copy(
                    toast = if (which == "fg") "试听前景（已扣除背景 · 响度已归一化）"
                    else "试听背景（模型输出 · 响度已归一化）"
                )
            }
        }
    }

    /** 试听原声（不分离，仅做响度归一化，便于与前景/背景对照）。 */
    private fun previewMix() {
        viewModelScope.launch(Dispatchers.IO) {
            val clip = grabClip(4)
            if (clip == null) {
                viewModelScope.launch(Dispatchers.Main) {
                    _state.value = _state.value.copy(
                        toast = if (_state.value.capturing) "音频缓冲尚未就绪，请稍候再试" else "请先开始采集"
                    )
                }
                return@launch
            }
            val pcm = clip.pcm.copyOf()
            if (Dsp.peak(pcm) > 1e-6) Stft.normalize(pcm)
            val sr = clip.sampleRate
            viewModelScope.launch(Dispatchers.Main) {
                Player.play(pcm, sr)
                _state.value = _state.value.copy(toast = "试听原声（未分离 · 响度已归一化）")
            }
        }
    }

    // —— 定位 ——
    fun locCycleSpacing() {
        locator?.cycleSpacing()
        _state.value = _state.value.copy(loc = _state.value.loc.copy(spacing = locator?.spacing ?: 1.0))
    }

    fun locToggleSim() {
        locator?.toggleSim()
        _state.value = _state.value.copy(loc = _state.value.loc.copy(simOn = locator?.simOn ?: true))
    }

    fun locToggleReverb() {
        locator?.toggleReverb()
        _state.value = _state.value.copy(loc = _state.value.loc.copy(reverb = locator?.simReverb ?: false))
    }

    fun locPlaceSim(az: Double, dist: Double) {
        locator?.placeSim(az, dist)
    }

    // —— 警戒 ——
    fun guardStart() {
        guard.start(System.currentTimeMillis())
        /**
         * 【v0.6.0】拉起前台服务：息屏/切后台仍持续采集与抓拍。
         * 此前 Manifest 声明了前台服务权限却没有 Service，后台采集会被系统掐断，
         * 值守等于不可用。无通知权限时 GuardService 内部静默降级不抛异常。
         */
        GuardService.start(ctx)
        val noNotify = !GuardService.hasNotifyPermission(ctx)
        pushLog(
            if (noNotify) "开始值守（后台持续采集已启用；未授予通知权限，状态栏不显示）"
            else "开始值守（后台持续采集已启用）",
            if (noNotify) 1 else 0
        )
    }

    fun guardStop() {
        guard.stop()
        GuardService.stop(ctx)
        pushLog("停止值守（已退出后台采集）", 0)
    }

    fun guardReEval() {
        guard.beginEval(System.currentTimeMillis())
    }

    fun guardTogglePause() {
        guard.togglePause(!guard.paused)
        _state.value = _state.value.copy(guard = snapGuard(System.currentTimeMillis()))
    }

    fun guardClearEvents() {
        guard.clearEvents()
        _state.value = _state.value.copy(guard = snapGuard(System.currentTimeMillis()))
    }

    fun guardDeleteEvent(e: GuardEngine.Event) {
        guard.deleteEvent(e)
        _state.value = _state.value.copy(guard = snapGuard(System.currentTimeMillis()))
    }

    /** 导出值守日志到应用目录（对应 Web alertMask 的「导出值守日志」）。 */
    fun guardExportLog() {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = AlertMediaDir.today(ctx)
            val ts = GuardEngine.fmtTime(System.currentTimeMillis()).replace(" ", "_").replace(":", "-")
            val f = File(dir, "guard-log_$ts.txt")
            val lines = synchronized(logs) { logs.toList() }
            val sb = StringBuilder()
            sb.append("AlphaSun 声波警戒值守日志\n导出时间：")
                .append(GuardEngine.fmtTime(System.currentTimeMillis())).append("\n共 ").append(lines.size).append(" 条\n\n")
            for ((t, k) in lines) sb.append("[").append(logKind(k)).append("] ").append(t).append("\n")
            val ok = try { f.writeText(sb.toString()); true } catch (e: Exception) { false }
            viewModelScope.launch(Dispatchers.Main) {
                pushLog(
                    if (ok) "值守日志已导出：${f.absolutePath}" else "值守日志导出失败",
                    if (ok) 3 else 2
                )
            }
        }
    }

    /** 清空值守日志（对应 Web alertMask 的「清空日志」）。 */
    fun guardClearLog() {
        synchronized(logs) { logs.clear() }
        _state.value = _state.value.copy(guard = snapGuard(System.currentTimeMillis()))
        pushLog("值守日志已清空", 0)
    }

    /** 保存告警推送配置（对应 Web alertMask 的「保存推送配置」；原生仅持久化，网络发送为预留接口）。 */
    fun guardSaveNotify() {
        val n = guard.notifyTargets()
        pushLog("推送配置已保存到本机（已配置 $n 个通道）", 3)
    }

    /** 发送推送测试（对应 Web alertMask 的「发送测试」；原生暂未实现网络发送）。 */
    fun guardTestNotify() {
        pushLog("推送测试：原生暂未实现网络发送（配置已在本机）", 1)
    }

    private fun logKind(k: Int): String = when (k) {
        2 -> "告警"; 1 -> "预警"; 3 -> "系统"; else -> "信息"
    }


    /** 手动触发一次抓拍（用于验证前/后摄是否都工作）。 */
    fun guardTestShot() {
        val e = guard.current ?: GuardEngine.Event().also {
            it.id = guard.events.size + 1
            it.level = GuardEngine.Level.ALARM
            it.tStartMs = System.currentTimeMillis()
        }
        requestShot(e)
    }

    fun camCapsNote(): String = cam.caps().note

    // ================= 应用内自检（v0.6.2） =================

    /**
     * 采集层 + 摄像头 + 存储的实时快照，供 `SelfCheck` 设备层使用。
     *
     * 为什么做成接口而不是直接引 ViewModel：
     * `SelfCheck` 在 core 包（纯 Kotlin），不能依赖 Android 框架，
     * 否则 JVM 单元测试跑不了 —— 这是 v0.6.0 已经踩过一次的坑
     * （`NoiseEval.levelText()` 反向依赖 Compose，导致算法层无法单测）。
     */
    private val selfCheckDevice = object : SelfCheck.Device {
        override val hasMicPermission: Boolean get() = cap.hasPermission()
        override val capturing: Boolean get() = _state.value.capturing
        override val sampleRate: Int get() = cap.sampleRate
        override val channelCount: Int get() = cap.channelCount
        override val fftSize: Int get() = cap.fftSize
        override val frame: FloatArray get() = cap.frame
        override val multi: Array<FloatArray>? get() = cap.multi
        override val fakeStereo: Boolean get() = cap.fakeStereo
        override val loopErrors: Int get() = _state.value.loopErrors
        override val diag: String get() = cap.diagText()
        override val cameraCount: Int get() = try {
            val c = cam.caps()
            (if (c.hasFront) 1 else 0) + (if (c.hasBack) 1 else 0)
        } catch (_: Throwable) { 0 }
        override val cameraDetail: String get() = try { cam.caps().note } catch (e: Throwable) { "读取失败：${e.message}" }
        override val storageOk: Boolean get() = try {
            val d = AlertMediaDir.today(ctx)
            val t = File(d, ".selftest")
            t.createNewFile()
            val ok = t.exists()
            t.delete()
            ok
        } catch (_: Throwable) { false }
        override val storageDetail: String get() = try {
            val d = AlertMediaDir.today(ctx)
            "可写：${d.absolutePath}"
        } catch (e: Throwable) { "不可写：${e.message}" }
    }

    /**
     * 运行自检。算法层（14 项）为纯 Kotlin 计算，约需数百毫秒，
     * 故放到 Default 线程；设备层在完成后同步取快照。
     */
    fun runSelfCheck() {
        if (_state.value.selfChecking) return
        _state.value = _state.value.copy(selfChecking = true, toast = "自检运行中…")
        viewModelScope.launch(Dispatchers.Default) {
            val report = try {
                SelfCheck.run(selfCheckDevice)
            } catch (e: Throwable) {
                android.util.Log.e("AlphaSunSelfCheck", "自检异常", e)
                SelfCheck.Report(
                    listOf(
                        SelfCheck.Item("自检", "自检过程本身", false, "${e.javaClass.simpleName}: ${e.message}")
                    ), 0
                )
            }
            val text = report.text()
            android.util.Log.i("AlphaSunSelfCheck", text)
            _state.value = _state.value.copy(
                selfChecking = false,
                selfCheck = report,
                toast = report.title()
            )
        }
    }

    val selfCheckText: String get() = _state.value.selfCheck?.text() ?: "尚未运行自检"

    fun clearToast() {
        _state.value = _state.value.copy(toast = null)
    }

    // ================= 工具 =================

    /** 等间隔降采样到 n 点（波形显示用，避免每帧搬 2048 个样本）。 */
    private fun downsample(src: FloatArray, n: Int): FloatArray {
        if (src.size <= n) return src
        val out = FloatArray(n)
        val step = src.size.toDouble() / n
        for (i in 0 until n) {
            val a = (i * step).toInt()
            val b = ((i + 1) * step).toInt().coerceIn(a + 1, src.size)
            var mx = 0f
            for (j in a until b) { val v = src[j]; if (kotlin.math.abs(v) > kotlin.math.abs(mx)) mx = v }
            out[i] = mx
        }
        return out
    }

    /** 把线性频谱压成对数频段柱（40Hz~16kHz），供主界面可视化。 */
    private fun logBars(spec: FloatArray, n: Int, binHz: Double): FloatArray {
        if (spec.size < 2) return FloatArray(n)
        val out = FloatArray(n)
        val fMin = 40.0; val fMax = 16000.0
        val k = Math.log(fMax / fMin) / n
        for (m in 0 until n) {
            val f0 = fMin * Math.exp(k * m)
            val f1 = fMin * Math.exp(k * (m + 1))
            var i0 = (Math.log(f0 / binHz) / Math.log(2.0)).toInt().coerceIn(0, spec.size - 1)
            val i1 = (Math.log(f1 / binHz) / Math.log(2.0)).toInt().coerceIn(i0 + 1, spec.size)
            var v = 0f
            while (i0 < i1) { if (spec[i0] > v) v = spec[i0]; i0++ }
            out[m] = v.coerceIn(0f, 1f)
        }
        return out
    }

    private fun levelText(db: Double): String = when {
        db >= 85 -> "吵杂（施工/道路）"
        db >= 70 -> "嘈杂（街道/商场）"
        db >= 55 -> "中等（办公室/交谈）"
        db >= 40 -> "安静（图书馆/卧室）"
        db >= 30 -> "很安静"
        else -> "近乎静音"
    }

    /** tanh 软限幅（输入增益用，防止削顶）。 */
    private fun tanh(x: Double): Double {
        if (x > 20) return 1.0
        if (x < -20) return -1.0
        val e2 = kotlin.math.exp(2 * x)
        return (e2 - 1) / (e2 + 1)
    }

    override fun onCleared() {
        super.onCleared()
        cap.stop()
        cam.release()
    }
}

/** 试听播放（AudioTrack 单次播放 PCM）。 */
object Player {
    fun play(pcm: FloatArray, sampleRate: Int) {
        if (pcm.isEmpty()) return
        try {
            val n = pcm.size
            val buf = ShortArray(n)
            for (i in 0 until n) {
                buf[i] = (pcm[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            }
            val minBuf = android.media.AudioTrack.getMinBufferSize(
                sampleRate, android.media.AudioFormat.CHANNEL_OUT_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT
            )
            val at = android.media.AudioTrack.Builder()
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    android.media.AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, n * 2))
                .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                .build()
            at.write(buf, 0, n)
            at.play()
            Thread {
                Thread.sleep((n.toLong() * 1000) / sampleRate + 200)
                try { at.stop() } catch (_: Exception) { }
                at.release()
            }.apply { isDaemon = true }.start()
        } catch (_: Exception) { }
    }
}
