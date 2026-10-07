package com.alphasun.sonicanalyzer.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * P6 声波警戒引擎
 *
 * 移植自 Web v0.3.1 的 `GUARD` + `alTick()`，保持完全一致的判定口径：
 *  · 本底评估取窗口内 **L90**（避开偶发峰）
 *  · 自动模式：thrWarn = floor + warnMargin，thrAlarm = floor + alarmMargin
 *  · 手动模式：thrWarn = 手动阈值，thrAlarm = thrWarn + (alarmMargin - warnMargin)
 *  · 回落保持：低于预警阈值后连续累计 relHold 秒才结束事件（抗抖动）
 *  · 事件内累计 sum/cnt/over/peak，结束算出均值、超阈占比
 *  · 最长时长保护，防止持续噪声无限占用录音缓冲
 *  · 预警升级为告警时再触发一次提示与抓拍（两套抓拍分别记录）
 *
 * 与 Web 版的差异：
 *  1. Web 用固定 0.15s 累加回落计时（假设 6~7fps tick），这里改用真实 dt，
 *     避免不同刷新率下回落时间失真。
 *  2. Web 把事件音频编码成 WAV Blob，这里 PCM 交给 [AlertMediaWriter] 落盘，
 *     录音格式/路径与 UI 解耦。
 *  3. 抓拍/录像不直接调 getUserMedia，而是通过 [onShot] 回调交给 Camera2 层，
 *     因为 Android 上并发双摄只能走 CameraManager concurrentCameraIds。
 */
class GuardEngine {

    enum class State { IDLE, EVAL, NORMAL, WARN, ALARM }

    enum class Level(val text: String) {
        NORMAL("正常"), WARN("预警"), ALARM("告警")
    }

    /** 一次事件 */
    class Event {
        var id: Int = 0
        var level: Level = Level.NORMAL
        var tStartMs: Long = 0L
        var tEndMs: Long = 0L
        var peak: Double = -120.0
        var sum: Double = 0.0
        var cnt: Int = 0
        var over: Int = 0
        /** 预警升级为告警的时刻；0 表示未升级 */
        var upgradedAtMs: Long = 0L

        val durMs: Long get() = (tEndMs - tStartMs).coerceAtLeast(0L)
        val avg: Double get() = if (cnt > 0) sum / cnt else -120.0
        val overPct: Double get() = if (cnt > 0) over * 100.0 / cnt else 0.0

        /** 事件内累积的 PCM（单声道混合，由 tick 推入） */
        val pcm: MutableList<FloatArray> = ArrayList()
        var pcmLen: Int = 0

        var wavPath: String? = null
        var frontPath: String? = null
        var backPath: String? = null
        var frontErr: String? = null
        var backErr: String? = null

        fun analysis(floor: Double, thrWarn: Double, thrAlarm: Double): String {
            val sb = StringBuilder()
            sb.append("事件 #").append(id).append("  级别：").append(level.text).append('\n')
            sb.append("开始：").append(fmtTime(tStartMs)).append('\n')
            sb.append("结束：").append(fmtTime(tEndMs)).append('\n')
            sb.append("时长：").append("%.1f".format(durMs / 1000.0)).append(" 秒")
            if (pcmLen > 0) {
                sb.append("（录音 ").append("%.1f".format(pcmLen / 48000.0)).append(" 秒）")
            }
            sb.append('\n')
            sb.append("峰值声级：").append("%.1f".format(peak)).append(" dB\n")
            sb.append("平均声级：").append("%.1f".format(avg)).append(" dB\n")
            sb.append("相对本底：+").append("%.1f".format(max(0.0, avg - floor))).append(" dB")
                .append("（本底 ").append("%.1f".format(floor)).append(" dB）\n")
            sb.append("超阈时间占比：").append("%.1f".format(overPct)).append("%\n")
            if (upgradedAtMs > 0L) {
                sb.append("升级：预警 → 告警 于 ")
                    .append("%.1f".format((upgradedAtMs - tStartMs) / 1000.0)).append(" 秒\n")
            }
            sb.append("阈值：预警 ").append("%.1f".format(thrWarn)).append(" dB / 告警 ")
                .append("%.1f".format(thrAlarm)).append(" dB\n")
            if (frontPath != null) sb.append("前置抓拍：").append(frontPath).append('\n')
            if (frontErr != null && frontPath == null) sb.append("前置抓拍失败：").append(frontErr).append('\n')
            if (backPath != null) sb.append("后置抓拍：").append(backPath).append('\n')
            if (backErr != null && backPath == null) sb.append("后置抓拍失败：").append(backErr).append('\n')
            if (wavPath != null) sb.append("事件音频：").append(wavPath).append('\n')
            sb.append("（声级为相对满量程数字电平，未经声级计校准，不作合规判定）")
            return sb.toString()
        }
    }

    fun interface ShotListener {
        /** level 用于决定是否仅在告警抓拍；返回的 File 路径由调用方异步回填 */
        fun onShot(level: Level, event: Event)
    }

    // ---------- 可调参数（对应 Web alertMask 表单项） ----------
    var enabled: Boolean = false
        private set
    var auto: Boolean = true
    var manualThr: Double = -30.0
    var evalSec: Int = 5
    var warnMargin: Double = 8.0
    var alarmMargin: Double = 15.0
    var relHoldSec: Double = 2.0
    var maxEvSec: Double = 120.0
    var captureAudio: Boolean = true
    var flashOnAlarm: Boolean = true
    var beepOnAlarm: Boolean = true
    var capturePhoto: Boolean = true
    var captureVideo: Boolean = false
    var dualCamera: Boolean = true
    var warnCamToo: Boolean = true
    /** 告警录像时长（秒），对应 Web alertMask 的 alertCamSec */
    var camRecSec: Double = 15.0
    /** 抓拍时距事件开始的最小间隔（秒），防止同一事件反复抓 */
    var shotGapSec: Double = 3.0

    // ---------- 告警推送配置（对应 Web alertMask 的 alertNotifyCfg） ----------
    // 原生仅持久化配置，网络发送为预留接口（onNotify 已接线）；字段与 Web 表单项一一对应。
    var ntWecom: String = ""      // 企业微信机器人 webhook
    var ntDing: String = ""       // 钉钉机器人 webhook
    var ntFeishu: String = ""     // 飞书机器人 webhook
    var ntSc: String = ""         // Server 酱 SendKey
    var ntHook: String = ""       // 自定义 Webhook
    var ntAkId: String = ""       // 阿里云短信 AccessKeyId
    var ntAkSec: String = ""      // 阿里云短信 AccessKeySecret
    var ntSign: String = ""       // 阿里云短信签名
    var ntTpl: String = ""        // 阿里云短信模板 Code
    var ntPhone: String = ""      // 阿里云短信手机号

    /** 已配置的推送通道数量（不含阿里云短信，需 AK+签名+模板+手机号齐全才算） */
    fun notifyTargets(): Int {
        val bots = listOf(ntWecom, ntDing, ntFeishu, ntSc, ntHook).count { it.isNotBlank() }
        val aliyun = if (ntAkId.isNotBlank() && ntAkSec.isNotBlank() && ntSign.isNotBlank()
            && ntTpl.isNotBlank() && ntPhone.isNotBlank()) 1 else 0
        return bots + aliyun
    }
    var paused: Boolean = false
    var manualFloor: Double = -60.0
    var manualUseFloor: Boolean = true

    // ---------- 运行时状态 ----------
    var state: State = State.IDLE
        private set
    var floor: Double = -60.0
        private set
    var thrWarn: Double = -30.0
        private set
    var thrAlarm: Double = -25.0
        private set
    var lastDb: Double = -120.0
        private set
    var sessionPeak: Double = -120.0
        private set
    val events: MutableList<Event> = ArrayList()
    val current: Event? get() = cur

    private var cur: Event? = null
    private var seq: Int = 0
    private var evalBuf = ArrayList<Double>()
    private var evalT0: Long = 0L
    private var relHeld: Double = 0.0
    private var lastShotMs: Long = Long.MIN_VALUE

    /** 值守期间的滚动波形（供 Canvas 波形图） */
    val wave = FloatArray(600)
    var wavePos: Int = 0
        private set

    var onLog: ((String, Int) -> Unit)? = null        // 文本, 0=普通 1=预警 2=告警 3=系统
    var onShot: ShotListener? = null
    var onBeep: (() -> Unit)? = null
    var onFlash: ((Level) -> Unit)? = null
    var onNotify: ((Event) -> Unit)? = null
    var onMediaReady: ((Event) -> Unit)? = null
    var shotInFlight: Boolean = false
        private set

    // ---------- 生命周期 ----------

    fun start(nowMs: Long) {
        if (enabled) return
        enabled = true
        paused = false
        seq = 0
        events.clear()
        relHeld = 0.0
        cur = null
        sessionPeak = -120.0
        wave.fill(0f)
        lastShotMs = Long.MIN_VALUE
        if (auto) {
            beginEval(nowMs)
        } else {
            thrWarn = manualThr
            thrAlarm = manualThr + (alarmMargin - warnMargin)
            floor = if (manualUseFloor) manualFloor else thrAlarm - alarmMargin
            state = State.NORMAL
            onLog?.invoke(
                "手动阈值：预警 ${"%.1f".format(thrWarn)} / 告警 ${"%.1f".format(thrAlarm)} dB", 3
            )
        }
    }

    fun beginEval(nowMs: Long) {
        auto = true
        evalBuf = ArrayList()
        evalT0 = nowMs
        state = State.EVAL
        onLog?.invoke("本底评估中（${evalSec} 秒，请保持环境安静）", 3)
    }

    fun stop() {
        enabled = false
        paused = false
        // 停止时把进行中的事件收尾，避免事件卡在"进行中"永不结算
        if (cur != null) finishEvent(manualEnd = true)
        state = State.IDLE
        cur = null
    }

    fun togglePause(p: Boolean) {
        if (paused == p) return
        paused = p
        if (cur != null) relHeld = 0.0   // 恢复后重新累计回落，暂停时长不计入事件
        onLog?.invoke(if (p) "已暂停值守（麦克风保持工作）" else "已恢复值守", 3)
    }

    // ---------- 主循环 ----------

    /**
     * 每帧调用。
     *
     * @param db 当前电平（dB，相对满量程）
     * @param time 当前帧时域（可选，用于录制事件音频与波形）
     * @param sampleRate 采样率（录音用）
     * @param dtMs 距上次 tick 的真实毫秒（用于回落计时）
     */
    fun tick(db: Double, time: FloatArray?, sampleRate: Int, nowMs: Long, dtMs: Double) {
        if (db <= -119.0) return
        lastDb = db
        if (db > sessionPeak) sessionPeak = db
        pushWave(((db + 60.0) / 60.0).coerceIn(-1.0, 1.0).toFloat())

        if (paused) return

        // 本底评估
        if (state == State.EVAL) {
            evalBuf.add(db)
            if (nowMs - evalT0 >= evalSec * 1000L) finishEval()
            return
        }
        if (state == State.IDLE || !enabled) return

        val lv = levelOf(db)

        // 事件内统计
        val e = cur
        if (e != null) {
            e.sum += db; e.cnt++
            if (db >= thrWarn) e.over++
            if (db > e.peak) e.peak = db
            if (captureAudio && time != null && time.isNotEmpty()) {
                val need = sampleRate * maxEvSec
                if (e.pcmLen < need) {
                    e.pcm.add(time)
                    e.pcmLen += time.size
                }
            }
        }

        if (lv == Level.NORMAL) {
            if (e != null) {
                relHeld += dtMs / 1000.0
                if (relHeld >= relHoldSec) finishEvent()
            }
            state = State.NORMAL
            return
        }

        relHeld = 0.0
        if (e == null) {
            startEvent(lv, db, nowMs)
            state = if (lv == Level.ALARM) State.ALARM else State.WARN
        } else {
            if (lv == Level.ALARM && e.level != Level.ALARM) {
                e.level = Level.ALARM
                e.upgradedAtMs = nowMs
                onLog?.invoke("事件 #${e.id} 升级为告警（${"%.1f".format(db)} dB）", 2)
                if (beepOnAlarm) onBeep?.invoke()
                if (flashOnAlarm) onFlash?.invoke(Level.ALARM)
                if (capturePhoto) requestShot(Level.ALARM, e, nowMs)
                onNotify?.invoke(e)
            }
            state = if (lv == Level.ALARM) State.ALARM else State.WARN
        }

        // 最长时长保护
        if (cur != null && (nowMs - cur!!.tStartMs) / 1000.0 >= maxEvSec) {
            onLog?.invoke("事件 #${cur!!.id} 达到最长时长 ${maxEvSec.toInt()}s，自动结束", 1)
            finishEvent()
        }
    }

    private fun levelOf(db: Double): Level = when {
        db >= thrAlarm -> Level.ALARM
        db >= thrWarn -> Level.WARN
        else -> Level.NORMAL
    }

    private fun finishEval() {
        val a = evalBuf.sorted()
        floor = if (a.isEmpty()) -60.0 else a[min(a.size - 1, (a.size * 0.9).toInt())]
        if (auto) {
            thrWarn = floor + warnMargin
            thrAlarm = floor + alarmMargin
        } else {
            thrWarn = manualThr
            thrAlarm = thrWarn + (alarmMargin - warnMargin)
        }
        state = State.NORMAL
        onLog?.invoke(
            "本底评估完成：本底 ${"%.1f".format(floor)} dB → 预警 ${"%.1f".format(thrWarn)} / 告警 ${"%.1f".format(thrAlarm)} dB",
            3
        )
    }

    private fun startEvent(lv: Level, db: Double, nowMs: Long) {
        val e = Event()
        e.id = ++seq
        e.level = lv
        e.tStartMs = nowMs
        e.peak = db
        cur = e
        relHeld = 0.0
        onLog?.invoke(
            "事件 #${e.id} 开始（${lv.text}，${"%.1f".format(db)} dB）", if (lv == Level.ALARM) 2 else 1
        )
        when (lv) {
            Level.ALARM -> {
                if (flashOnAlarm) onFlash?.invoke(Level.ALARM)
                if (beepOnAlarm) onBeep?.invoke()
                if (capturePhoto) requestShot(Level.ALARM, e, nowMs)
                onNotify?.invoke(e)
            }
            Level.WARN -> {
                // v0.07 需求⑤：黄色预警同样要抓拍（可关）
                if (capturePhoto && warnCamToo) requestShot(Level.WARN, e, nowMs)
            }
            Level.NORMAL -> Unit
        }
    }

    private fun requestShot(lv: Level, e: Event, nowMs: Long) {
        if (shotInFlight) return
        if (lastShotMs != Long.MIN_VALUE && (nowMs - lastShotMs) / 1000.0 < shotGapSec) return
        lastShotMs = nowMs
        onShot?.onShot(lv, e)
    }

    private fun finishEvent(manualEnd: Boolean = false) {
        val e = cur ?: return
        cur = null
        e.tEndMs = System.currentTimeMillis()
        if (e.tEndMs < e.tStartMs) e.tEndMs = e.tStartMs + 1
        val lvText = e.level.text
        onLog?.invoke(
            "事件 #${e.id} 结束（$lvText${if (manualEnd) "，手动停止" else ""}，时长 " +
                "${"%.1f".format(e.durMs / 1000.0)}s，峰值 ${"%.1f".format(e.peak)} dB，均值 ${"%.1f".format(e.avg)} dB）",
            if (e.level == Level.ALARM) 2 else 1
        )
        events.add(0, e)
        while (events.size > MAX_EVENTS) events.removeAt(events.size - 1)
        onMediaReady?.invoke(e)
    }

    /** 由 Camera2 层回填抓拍结果 */
    fun fillShot(e: Event, front: String?, back: String?, frontErr: String? = null, backErr: String? = null) {
        if (front != null) e.frontPath = front else e.frontErr = frontErr
        if (back != null) e.backPath = back else e.backErr = backErr
        onMediaReady?.invoke(e)
    }

    fun clearEvents() {
        events.clear()
    }

    fun deleteEvent(e: Event) {
        events.remove(e)
    }

    fun stateText(): String = when {
        paused -> "已暂停"
        state == State.EVAL -> "本底评估中"
        state == State.IDLE -> "未值守"
        state == State.NORMAL -> "正常"
        state == State.WARN -> "预警"
        state == State.ALARM -> "告警"
        else -> "未值守"
    }

    fun stateLevel(): Level = when (state) {
        State.WARN -> Level.WARN
        State.ALARM -> Level.ALARM
        else -> Level.NORMAL
    }

    fun evalProgress(): Float =
        if (state != State.EVAL || evalSec <= 0) 0f
        else ((System.currentTimeMillis() - evalT0).toDouble() / (evalSec * 1000.0)).coerceIn(0.0, 1.0).toFloat()

    fun evalFrames(): Int = evalBuf.size

    fun normText(): String = "≤ ${thrWarn.roundToInt()} dB"

    /** 往滚动波形缓冲推一帧（环形覆盖）。 */
    private fun pushWave(v: Float) {
        wave[wavePos] = v
        wavePos = (wavePos + 1) % wave.size
    }

    /** 按时间顺序取回波形（旧 → 新），供 Canvas 绘制。 */
    fun waveOrdered(): FloatArray {
        val out = FloatArray(wave.size)
        val n = wave.size - wavePos
        System.arraycopy(wave, wavePos, out, 0, n)
        System.arraycopy(wave, 0, out, n, wavePos)
        return out
    }

    companion object {
        const val MAX_EVENTS = 60

        private fun p(n: Int): String = n.toString().padStart(2, '0')

        fun fmtTime(ms: Long): String {
            val c = java.util.Calendar.getInstance()
            c.timeInMillis = ms
            val y = c.get(java.util.Calendar.YEAR)
            val mo = p(c.get(java.util.Calendar.MONTH) + 1)
            val d = p(c.get(java.util.Calendar.DAY_OF_MONTH))
            val h = p(c.get(java.util.Calendar.HOUR_OF_DAY))
            val mi = p(c.get(java.util.Calendar.MINUTE))
            val s = p(c.get(java.util.Calendar.SECOND))
            return "$y-$mo-$d $h:$mi:$s"
        }

        fun fmtName(e: Event, kind: String): String {
            val c = java.util.Calendar.getInstance(); c.timeInMillis = e.tStartMs
            val y = c.get(java.util.Calendar.YEAR)
            val mo = p(c.get(java.util.Calendar.MONTH) + 1)
            val d = p(c.get(java.util.Calendar.DAY_OF_MONTH))
            val h = p(c.get(java.util.Calendar.HOUR_OF_DAY))
            val mi = p(c.get(java.util.Calendar.MINUTE))
            val s = p(c.get(java.util.Calendar.SECOND))
            return "事件${p(e.id)}_${e.level.text}_${y}${mo}${d}-${h}${mi}${s}_$kind"
        }
    }
}

/** 线性 dB 判定辅助：用于把任意标量映射到 [-60,0] 的表盘位置 */
fun guardDialPos(db: Double): Float =
    ((max(-60.0, min(0.0, db)) + 60.0) / 60.0).toFloat()

internal fun absGuard(x: Double) = abs(x)
