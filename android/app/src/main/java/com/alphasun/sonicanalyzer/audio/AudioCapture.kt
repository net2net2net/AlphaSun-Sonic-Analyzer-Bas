package com.alphasun.sonicanalyzer.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.alphasun.sonicanalyzer.core.Dsp
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * 麦克风采集（AudioRecord）
 *
 * 与 Web 版的根本差异，也是原生重写的主要收益：
 *  · WebView 的 getUserMedia 只会给**单通道**（channelCount 恒为 1），
 *    声源定位永远跑不了；这里用 AudioRecord 直接拿原生 PCM。
 *  · 可请求原生采样率（48k/44.1k/96k…），不经过 Web Audio 重采样。
 *  · 阻塞读取在独立线程，无 GC 抖动。
 *
 * ============================ v0.6.0 可靠性重写 ============================
 * 上一版本（v0.5.0）在真机上「功能不可用」，根因三条，均已修复：
 *
 * ①【致命】音源写死 `SDK>=M ? UNPROCESSED : DEFAULT`。
 *    AudioSource.UNPROCESSED(9) 只有极少数机型声明支持，
 *    `AudioRecord` 构造直接抛 IllegalArgumentException，
 *    而 API 23+ **全部**走这一分支 → 几乎所有手机启动即失败。
 *    修法：probe 时探测 `PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED`，
 *    并按 UNPROCESSED → VOICE_RECOGNITION → MIC → DEFAULT 逐级降级，
 *    每个音源**实际构造 AudioRecord 并验证 state**，不靠猜。
 *
 * ②【致命】`AudioRecord.read()` 返回值不保证等于请求长度。
 *    旧代码直接把 `got` 当帧长用，一旦设备返回非 2 的幂（如 1280），
 *    `Dsp.fft` 的 `require(n and (n-1)==0)` 立刻抛异常，
 *    而 `MainViewModel.loop()` 没有 try/catch → 协程静默死亡、界面永久卡死。
 *    修法：采集层内部**只发布固定 fftSize 的帧**，
 *    不足则累积、补齐才发（环形缓冲），永远不把可变长度抛给上层。
 *
 * ③【严重】getMinBufferSize 只查「缓冲大小」，不保证该 采样率×声道 真正可初始化。
 *    旧代码据此直接认定 channelCount=2。真机大量机型 stereo 初始化失败。
 *    修法：probe 阶段**逐个候选组合真正构造** AudioRecord，
 *    只有 state==STATE_INITIALIZED 才采纳，降级顺序 立体声→单声道。
 */
class AudioCapture(private val ctx: Context) {

    companion object {
        private const val TAG = "AlphaSunAudio"
        const val DEFAULT_CLIP_SEC = 6

        /** 候选采样率，按优先级（Web 版默认 48k） */
        private val RATES = intArrayOf(48000, 44100, 96000, 16000, 32000, 22050)

        /**
         * 候选音源，按优先级。
         * UNPROCESSED 最接近 Web getUserMedia 的原始输入（无 AGC/降噪），
         * 但绝大多数手机不声明支持，故必须逐级降级而非写死。
         */
        private val SOURCES = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,      // 9  原始，最忠实
            MediaRecorder.AudioSource.VOICE_RECOGNITION, // 1  语音识别，绕过 AGC
            MediaRecorder.AudioSource.MIC,              // 1?
            MediaRecorder.AudioSource.DEFAULT           // 0  兜底
        )
    }

    var sampleRate: Int = 48000; private set
    var channelCount: Int = 1; private set
    /** 实际生效的音源（AudioSource 常量），用于 UI 诊断展示 */
    var sourceName: String = "—"; private set
    val isRecording: Boolean get() = rec?.recordingState == AudioRecord.RECORDSTATE_RECORDING

    private var rec: AudioRecord? = null
    private var worker: Thread? = null
    @Volatile private var running = false

    /** 最近一帧时域数据 —— **长度恒为 fftSize**（v0.6.0 保证，见类注释根因②） */
    @Volatile var frame: FloatArray = FloatArray(0); private set
    /** 多通道原始数据（chN 个通道，各长 fftSize）；单声道时为 null */
    @Volatile var multi: Array<FloatArray>? = null; private set
    /** 电平（RMS，线性） */
    @Volatile var rms: Double = 0.0; private set
    @Volatile var peak: Double = 0.0; private set
    @Volatile var envDb: Double = -100.0; private set

    /** 参考偏移（dB），用户可点柱状图校准 */
    @Volatile var refOffset: Double = 94.0

    var fftSize: Int = 2048

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // ==================== 参数探测 ====================

    private fun supportsUnprocessed(): Boolean = try {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // 有该属性且值为 "true" 才敢用 UNPROCESSED
        am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
    } catch (_: Throwable) {
        false
    }

    /**
     * 探测可用的采集参数。
     *
     * 【v0.6.0 变更】不再依赖 getMinBufferSize 猜测，而是**真正构造 AudioRecord**
     * 并检查 state==STATE_INITIALIZED。返回 null 表示该组合不可用。
     * 降级顺序：采样率由高到低 → 立体声优先 → 音源由优到劣。
     */
    private fun tryOpen(rate: Int, ch: Int, src: Int): AudioRecord? = try {
        val minBuf = AudioRecord.getMinBufferSize(rate, ch, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) null else {
            val bufSize = maxOf(minBuf * 4, fftSize * ch * 4)
            @Suppress("DEPRECATION")
            val r = AudioRecord(src, rate, ch, AudioFormat.ENCODING_PCM_16BIT, bufSize)
            if (r.state == AudioRecord.STATE_INITIALIZED) r else {
                try { r.release() } catch (_: Throwable) { }
                null
            }
        }
    } catch (_: SecurityException) {
        null // 无权限
    } catch (_: IllegalArgumentException) {
        null // 该音源/声道不支持 —— 正是 UNPROCESSED 的常见结局
    } catch (_: IllegalStateException) {
        null
    } catch (_: Throwable) {
        // 厂商 ROM 上 AudioRecord 可能抛各类未文档化异常，必须吞掉继续降级
        null
    }

    /** 探测设备最佳参数，并直接产出一个已初始化的 AudioRecord。 */
    private fun openBest(): AudioRecord? {
        val srcList = if (supportsUnprocessed()) SOURCES else SOURCES.drop(1).toIntArray()
        // 第一轮：立体声（真阵列，声源定位必需）
        for (src in srcList) {
            for (rate in RATES) {
                val r = tryOpen(rate, AudioFormat.CHANNEL_IN_STEREO, src)
                if (r != null) {
                    sampleRate = rate; channelCount = 2; sourceName = srcName(src)
                    return r
                }
            }
        }
        // 第二轮：单声道（保底可用）
        for (src in srcList) {
            for (rate in RATES) {
                val r = tryOpen(rate, AudioFormat.CHANNEL_IN_MONO, src)
                if (r != null) {
                    sampleRate = rate; channelCount = 1; sourceName = srcName(src)
                    return r
                }
            }
        }
        channelCount = 1
        sourceName = "无可用音源"
        return null
    }

    private fun srcName(src: Int): String = when (src) {
        MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED（原始）"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION（语音识别）"
        MediaRecorder.AudioSource.MIC -> "MIC（麦克风）"
        MediaRecorder.AudioSource.DEFAULT -> "DEFAULT（系统默认）"
        else -> "source=$src"
    }

    /** 诊断文本：给 UI 显示「为什么用这个参数」 */
    fun diagText(): String {
        val un = if (supportsUnprocessed()) "支持" else "不支持"
        val fs = if (channelCount >= 2 && fakeStereo) " · 伪立体声(单麦复制)" else ""
        return "$sampleRate Hz · ${channelCount}ch · 音源 $sourceName · 设备$un UNPROCESSED$fs"
    }

    // ==================== 启停 ====================

    @SuppressLint("MissingPermission")
    fun start(onError: ((String) -> Unit)? = null): Boolean {
        if (running) return true
        if (!hasPermission()) {
            onError?.invoke("麦克风权限未授予")
            return false
        }
        val r = openBest()
        if (r == null) {
            val msg = "未能初始化麦克风：设备所有采样率/声道/音源组合均失败（可能被其它应用独占）"
            Log.w(TAG, msg)
            onError?.invoke(msg)
            return false
        }
        rec = r
        multi = if (channelCount >= 2) Array(channelCount) { FloatArray(fftSize) } else null
        // 每次启动都重新探测伪立体声（切换音源/采样率后结论可能不同）
        fakeProbeN = 0
        fakeProbeHit = 0
        fakeStereo = false
        running = true
        try {
            r.startRecording()
            // 少数机型 startRecording() 不抛异常但实际未启动，必须验证
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                running = false
                onError?.invoke("麦克风启动失败：设备未进入录音状态")
                try { r.stop() } catch (_: Throwable) { }
                try { r.release() } catch (_: Throwable) { }
                rec = null
                return false
            }
        } catch (e: IllegalStateException) {
            running = false
            onError?.invoke("启动录音失败：${e.message}")
            try { r.release() } catch (_: Throwable) { }
            rec = null
            return false
        } catch (e: SecurityException) {
            running = false
            onError?.invoke("麦克风被系统拒绝：${e.message}")
            try { r.release() } catch (_: Throwable) { }
            rec = null
            return false
        }
        worker = thread(name = "alphasun-audio", isDaemon = true) { loop(r) }
        return true
    }

    /**
     * 采集线程。
     *
     * 【v0.6.0 重写】不再假设 read() 一定返回请求长度：
     * 用**每通道独立环形累积缓冲**，攒满 fftSize 才发布一帧，
     * 余量留到下一帧继续累积。上层拿到的 frame 长度因此**恒为 fftSize**，
     * Dsp.fft 的 require(2 的幂) 永远满足。
     */
    private fun loop(r: AudioRecord) {
        val ch = channelCount
        val readShorts = fftSize * ch
        val rb = ShortArray(readShorts)
        // 每通道累积缓冲
        val acc = Array(ch) { FloatArray(fftSize) }
        var accLen = 0
        val tmp = FloatArray(readShorts)
        var errCount = 0
        try {
            while (running) {
                val need = fftSize * ch - accLen * ch
                val got = try {
                    r.read(rb, 0, minOf(rb.size, need.coerceAtLeast(1)))
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "read 中断", e); -1
                }
                if (got <= 0) {
                    if (!running) break
                    // 连续过多错误视为设备异常，退出让上层显示"启动失败"
                    if (++errCount > 200) {
                        Log.e(TAG, "连续读取失败，停止采集")
                        break
                    }
                    continue
                }
                errCount = 0
                /**
                 * 【v0.6.2 修复 —— 潜伏的越界崩溃】
                 * 旧代码 `val samples = got / ch` 直接用，
                 * 一旦 read() 返回**非 ch 整数倍**的长度（部分 ROM 确实会），
                 * accLen 可能越过 fftSize，下面的余量前移就会执行
                 * `System.arraycopy(acc[c], fftSize, acc[c], 0, leftover)` ——
                 * 源下标 fftSize 对长度为 fftSize 的数组**必越界**，
                 * 抛 ArrayIndexOutOfBoundsException，被循环外的 catch 吞掉，
                 * 采集线程**静默死亡** → 上层 frame 永久停更 → 又是"功能全不可用"。
                 *
                 * 修法：把 samples 夹到本帧剩余容量内，余量恒为 0，
                 * 越界分支永远不会被执行（最多丢掉不足一个采样帧的尾巴，48k 下可忽略）。
                 */
                val samples = minOf(got / ch, fftSize - accLen)
                if (samples <= 0) continue
                // short → float（交错）
                for (i in 0 until samples) {
                    for (c in 0 until ch) {
                        val idx = i * ch + c
                        tmp[i * ch + c] = if (idx < got) rb[idx] / 32768f else 0f
                    }
                }
                // 解交错进累积缓冲
                for (c in 0 until ch) {
                    for (i in 0 until samples) {
                        val p = accLen + i
                        if (p < fftSize) acc[c][p] = tmp[i * ch + c]
                    }
                }
                accLen += samples
                // 攒满一帧才发布（samples 已夹过容量，accLen 不会越过 fftSize）
                if (accLen >= fftSize) {
                    publishFixed(acc, ch)
                    accLen = 0
                }
            }
        } catch (e: Throwable) {
            // 绝不让采集线程静默死掉后上层还在转（v0.6.0 根因③的另一半）
            Log.e(TAG, "采集循环异常", e)
        } finally {
            running = false
        }
    }

    /**
     * 发布一帧：长度恒为 fftSize。
     *
     * 【v0.6.1 —— "各个功能不可用"的头号根因】
     * 旧代码把 `frame` 的赋值放在 `if (ch == 1)` 分支里；ch>=2 时只更新 `multi`。
     * 而 openBest() 的策略是**立体声优先**，绝大多数手机的
     * `AudioRecord(CHANNEL_IN_STEREO)` 都能成功初始化（单麦机型也接受该参数，
     * 只是把同一路数据复制到第二通道）。
     * 于是真机上 `frame` 永远停留在 `FloatArray(0)`，
     * MainViewModel 主循环的 `if (raw.size > 32)` 恒为 false ——
     * 频谱、电平、分类、噪音、警戒、波形**全部不更新**，
     * 界面却显示「采集中（多通道）」，看上去就是"功能全不可用"。
     *
     * 修法：无论单声道还是多通道，都先把 ch0 发布到 `frame`，
     * 多通道时**额外**再发布 `multi`。
     */
    private fun publishFixed(acc: Array<FloatArray>, ch: Int) {
        ringEnsure()
        // 环形缓冲只记 ch0（单声道即可满足试听/分离需求）
        ringPush(acc[0], fftSize)

        // —— ① 单声道主帧：任何通道数都必须发布 ——
        val src0 = acc[0]
        val f = if (frame.size == fftSize) frame else FloatArray(fftSize)
        System.arraycopy(src0, 0, f, 0, fftSize)
        frame = f

        // —— ② 多通道原始数据（声源定位用） ——
        if (ch >= 2) {
            val m = multi ?: Array(ch) { FloatArray(fftSize) }
            for (c in 0 until ch) {
                val dst = if (c < m.size && m[c].size == fftSize) m[c] else FloatArray(fftSize)
                System.arraycopy(acc[c], 0, dst, 0, fftSize)
                m[c] = dst
            }
            multi = m
            probeFakeStereo(m, ch)
        }

        // —— ③ 电平取 ch0（与 Web 版口径一致） ——
        var s = 0.0; var p = 0.0
        for (i in 0 until fftSize) {
            val v = src0[i].toDouble(); s += v * v
            val a = abs(v); if (a > p) p = a
        }
        rms = Math.sqrt(s / fftSize); peak = p
        envDb = Dsp.envDb(rms, refOffset)
    }

    /**
     * 伪立体声检测。
     *
     * 单麦克风机型上 `CHANNEL_IN_STEREO` 也会初始化成功，但两路数据完全相同。
     * 若此时切到"真阵列"做 TDOA，时差恒为 0 → 方位角恒为 0°，
     * 用户会看到"定位一直指正前方"，是比仿真更糟的假结果。
     *
     * 这里在前 [FAKE_PROBE_FRAMES] 帧内统计两路归一化相关系数，
     * 若持续 >0.995 则判定为伪立体声，上层据此保持仿真模式并给出说明。
     */
    private val FAKE_PROBE_FRAMES = 40
    private var fakeProbeN = 0
    private var fakeProbeHit = 0

    /** 真机实测为"复制单声道"时置 true —— UI/定位据此降级，不做假定位。 */
    @Volatile var fakeStereo: Boolean = false; private set

    /** 探测是否已出结论（40 帧 ≈ 1.7s @48k/2048）。未出结论前不做阵列/仿真切换。 */
    val fakeProbeDone: Boolean get() = fakeProbeN >= FAKE_PROBE_FRAMES

    private fun probeFakeStereo(m: Array<FloatArray>, ch: Int) {
        if (ch < 2 || fakeProbeN >= FAKE_PROBE_FRAMES) return
        val a = m[0]; val b = m[1]
        if (a.size != fftSize || b.size != fftSize) return
        // 相关系数计算下沉到 core.Dsp（纯 Kotlin，可 JVM 单测）
        val corr = Dsp.correlation(a, b)
        // 静音帧不参与判定（能量太低相关系数无意义）
        if (corr.isNaN()) return
        fakeProbeN++
        if (corr > 0.995) fakeProbeHit++
        if (fakeProbeN >= FAKE_PROBE_FRAMES) {
            fakeStereo = fakeProbeHit >= (FAKE_PROBE_FRAMES * 3) / 4
            Log.i(TAG, "伪立体声探测：$fakeProbeHit/$fakeProbeN 帧相关 → fakeStereo=$fakeStereo")
        }
    }

    fun stop() {
        running = false
        try { rec?.stop() } catch (_: Throwable) { }
        try { rec?.release() } catch (_: Throwable) { }
        rec = null
        worker?.interrupt()
        worker = null
        /**
         * 停采后立刻发布一帧**真正的静音**，保证 UI 不停留在上一次的陈旧数据上。
         *
         * 【v0.6.2 修复】旧代码只判断 `if (frame.size != fftSize) frame = FloatArray(fftSize)`，
         * 即尺寸已对时**什么都不做** —— frame 仍保留着停止前的最后一帧，
         * 注释声称"发布静音"却根本没清零。上层 stop() 后主循环虽已取消，
         * 但任何重新读取 cap.frame 的路径（如试听、分离）拿到的都是陈旧信号。
         */
        frame = FloatArray(fftSize)
        multi = null
        rms = 0.0; peak = 0.0; envDb = -100.0
    }

    /** 录制一段 PCM（分离试听用）。采样期间不干扰实时 frame。 */
    class Clip(val pcm: FloatArray, val sampleRate: Int, val channels: Int) {
        val seconds: Double get() = if (sampleRate > 0) pcm.size.toDouble() / (sampleRate * channels) else 0.0
    }

    // ==================== 实时流环形缓冲 ====================
    //
    // 【v0.6.0 新增 —— 修掉"试听前景/背景没声音"】
    // 旧实现 `recordClip()` 是**另开一路 AudioRecord**。
    // 但调用试听时主采集线程正在跑，绝大多数手机上第二路 AudioRecord
    // 初始化直接失败（麦克风被独占）→ clip == null → UI 弹"录音失败：麦克风被占用"。
    // 这就是用户反馈"试听功能不可用"的真正原因，且此前被误判为算法问题。
    //
    // 现在：采集线程顺手把 ch0 写进环形缓冲，试听时直接快照最近 N 秒，
    // 完全不碰 AudioRecord，也不需要暂停主采集。

    private val RING_SEC = 12
    @Volatile private var ring: FloatArray? = null
    @Volatile private var ringPos = 0
    @Volatile private var ringFilled = 0

    private fun ringEnsure() {
        val need = sampleRate * RING_SEC
        val r = ring
        if (r == null || r.size != need) {
            ring = FloatArray(need)
            ringPos = 0
            ringFilled = 0
        }
    }

    private fun ringPush(src: FloatArray, n: Int) {
        val r = ring ?: return
        val cap = r.size
        var i = 0
        while (i < n) {
            val chunk = minOf(n - i, cap - ringPos)
            System.arraycopy(src, i, r, ringPos, chunk)
            ringPos = (ringPos + chunk) % cap
            i += chunk
        }
        ringFilled = minOf(ringFilled + n, cap)
    }

    /**
     * 从实时流取最近 [sec] 秒的**单声道** PCM（ch0）。
     * 返回 null 表示还没有足够数据。
     */
    fun liveClip(sec: Int = 4): Clip? {
        if (!running) return null
        val r = ring ?: return null
        val want = sampleRate * sec
        if (ringFilled < sampleRate / 4) return null     // 至少要有 0.25s
        val n = minOf(want, ringFilled)
        val out = FloatArray(n)
        // 从 ringPos 往前回溯（ring 是循环写，ringPos 指向下一个写入点）
        var src = (ringPos - n + r.size * 2) % r.size
        for (i in 0 until n) {
            out[i] = r[src]
            src = (src + 1) % r.size
        }
        return Clip(out, sampleRate, 1)
    }

    /**
     * 录 [sec] 秒。
     * 【v0.6.0】沿用上一版行为：另开一路 AudioRecord。
     * 若采集线程正在跑，两路会争抢麦克风导致失败 —— 故调用方（ViewModel）
     * 应在录音期间提示用户，这是已知限制而非崩溃。
     */
    @SuppressLint("MissingPermission")
    fun recordClip(sec: Int = DEFAULT_CLIP_SEC): Clip? {
        if (!hasPermission()) return null
        val ch = if (channelCount >= 2) channelCount else 1
        val sr = sampleRate
        val src = when {
            supportsUnprocessed() -> MediaRecorder.AudioSource.UNPROCESSED
            else -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
        val minBuf = AudioRecord.getMinBufferSize(sr, ch, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null
        val r = try {
            @Suppress("DEPRECATION")
            AudioRecord(src, sr, ch, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 4, 8192))
        } catch (_: Throwable) {
            return null
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) { try { r.release() } catch (_: Throwable) { }; return null }
        val total = sr * ch * sec
        val out = FloatArray(total)
        var filled = 0
        val b = ShortArray(4096)
        try {
            r.startRecording()
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) return null
            while (filled < total) {
                val want = minOf(b.size, total - filled)
                val got = r.read(b, 0, want)
                if (got <= 0) break
                for (i in 0 until got) out[filled + i] = b[i] / 32768f
                filled += got
            }
        } catch (e: Throwable) {
            Log.e(TAG, "录制异常", e)
        } finally {
            try { r.stop() } catch (_: Throwable) { }
            try { r.release() } catch (_: Throwable) { }
        }
        if (filled < total / 4) return null
        return Clip(out.copyOf(filled), sr, ch)
    }
}
