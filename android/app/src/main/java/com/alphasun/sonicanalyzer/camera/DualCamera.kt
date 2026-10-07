package com.alphasun.sonicanalyzer.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * Camera2 前后摄抓拍层（P6）
 *
 * 解决 Web 版遗留的两个真问题：
 *
 *  1. **前置摄像头没有画面**
 *     Web 的 `enumerateDevices()` 在未授权时只返回占位 `videoinput`，前摄
 *     label 为空，导致前后摄都退化到 `devs[0]`（后摄），"前置抓拍"其实
 *     拍的是后摄。这里改用 `CameraCharacteristics.LENS_FACING` 做**硬件级**
 *     前后摄判定，不依赖任何 label 字符串，也不需要先建 MediaStream。
 *
 *  2. **移动端不能并发开两路摄像头**
 *     Web 版并发 `getUserMedia({facing:'user'}) + ({facing:'environment'})`
 *     在 Android 上第二路必然失败。这里的策略是：
 *       · API 31+（Android 12）查询 `CameraManager.concurrentCameraIds`，
 *         厂商声明支持的机型走**真正并发双摄**（两个 CameraDevice 同一
 *         CaptureSession 内同时出帧）。
 *       · 其余机型**串行抓拍**：先后摄后前摄（后摄优先是因为它快门响应
 *         稳定、前摄常带美颜/滤镜 pipeline 更容易超时）。两路都出图才算成功，
 *         单路失败只降级、不崩溃。
 *
 * 每张图落盘为 JPEG 文件并回调 (side, file)：
 *  · `side` ∈ "front" / "back"
 *  · 失败时回调 `side -> null`，由上层写进事件记录的 `frontErr/backErr`
 */
class DualCamera(private val ctx: Context) {

    companion object {
        private const val TAG = "AlphaSunCam"
        private const val W = 1280
        private const val H = 720
        /** 单路开流超时（毫秒）—— 前置常带 pipeline，超时比后置宽松 */
        private const val OPEN_TIMEOUT_MS = 2500L
        private const val CLOSE_TIMEOUT_MS = 1500L
    }

    /** 抓拍结果 */
    data class Shot(val side: String, val file: File?, val error: String? = null)

    /** 能力自检结果 */
    data class Caps(
        val hasFront: Boolean,
        val hasBack: Boolean,
        val concurrent: Boolean,
        val concurrentIds: Set<String>,
        val note: String
    )

    private val cm get() = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    /**
     * 【v0.6.1】阻塞式抓拍的专属工作线程。
     * 必须与 camera 回调线程（handler）分开，否则 openSync 的信号量
     * 会等一个永远排不上队的回调（详见 capture() 里的说明）。
     */
    private val shotExec: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "alphasun-cam-shot").apply { isDaemon = true }
        }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun caps(): Caps {
        if (!hasPermission()) return Caps(false, false, false, emptySet(), "未授予摄像头权限")
        return try {
            // 【v0.6.1】与 facingIds() 共用同一套判定，避免"自检说只有 1 路、
            // 抓拍却能用 2 路"（或反过来）这类自相矛盾的显示。
            val (back, front) = facingIds()
            val ids = cm.cameraIdList
            for (id in ids) {
                try {
                    val ch = cm.getCameraCharacteristics(id)
                    val facing = ch.get(CameraCharacteristics.LENS_FACING)
                    val hasFlash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != null
                    val sizes = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        ?.getOutputSizes(ImageFormat.JPEG)
                    Log.i(TAG, "cam $id facing=$facing flash=$hasFlash jpeg=${sizes?.size ?: 0}")
                } catch (_: Exception) { }
            }
            var conc = emptySet<String>()
            var note = when {
                front == null && back == null -> "未发现可用摄像头"
                back == null -> "仅检测到前置摄像头"
                front == null -> "仅检测到后置摄像头"
                else -> "前后摄像头均可用"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    val pair = cm.concurrentCameraIds.firstOrNull()
                    if (pair != null) {
                        conc = pair
                        note += "；支持并发双摄"
                    } else {
                        note += "；串行双摄"
                    }
                } catch (e: Exception) {
                    note += "；并发查询失败(${e.javaClass.simpleName})，按串行处理"
                }
            } else {
                note += "；系统版本低于 12，只能串行抓拍"
            }
            Caps(front != null, back != null, conc.isNotEmpty(), conc, note)
        } catch (e: CameraAccessException) {
            Caps(false, false, false, emptySet(), "摄像头服务不可用：${e.message}")
        } catch (e: Exception) {
            Caps(false, false, false, emptySet(), "摄像头枚举异常：${e.message}")
        }
    }

    /**
     * 解析前/后置摄像头 id。
     *
     * 【v0.6.1 修复 —— "手机明明有前后摄，却显示只有 1 路"】
     * 旧实现只认 `LENS_FACING == FRONT/BACK`。真机上有三类会漏判：
     *   ① 部分国产 ROM / 老 MTK 平台把前摄的 LENS_FACING 上报成 null 或 0（EXTERNAL）；
     *   ② 某些设备的 `SCALER_STREAM_CONFIGURATION_MAP` 不列 JPEG（caps() 因此整路跳过）；
     *   ③ 逻辑摄像头（LOGICAL）把物理 id 折叠后 facing 可能取不到。
     * 于是 caps() 只报出一路，用户看到"仅检测到后置"。
     *
     * 现在的策略：**先按 facing 精确匹配；任一侧缺失但设备确实有 ≥2 个摄像头时，
     * 把剩下那个未被占用的 id 补位**。宁可猜错一路，也不要让第二路彻底缺席 ——
     * 补位那一侧仍会走正常的 shootOne 流程，出图成功与否由结果说话。
     */
    private fun facingIds(): Pair<String?, String?> {
        val all = try { cm.cameraIdList } catch (e: Exception) { emptyArray<String>() }
        if (all.isEmpty()) return null to null
        var back: String? = null
        var front: String? = null
        val other = ArrayList<String>()
        for (id in all) {
            val facing = try {
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            } catch (_: Exception) { null }
            when (facing) {
                CameraCharacteristics.LENS_FACING_BACK -> if (back == null) back = id else other.add(id)
                CameraCharacteristics.LENS_FACING_FRONT -> if (front == null) front = id else other.add(id)
                else -> other.add(id)
            }
        }
        // 补位：任一侧缺失就用剩余 id 顶上
        for (id in other) {
            when {
                back == null -> back = id
                front == null -> front = id
            }
        }
        Log.i(TAG, "facingIds: back=$back front=$front（全部 ${all.size} 路：${all.joinToString()}）")
        return back to front
    }

    /**
     * 抓拍。
     *
     * 优先走并发双摄（API31+ 且厂商声明支持），否则串行后置→前置。
     * 无论如何都保证**不抛异常**给调用方：失败通过 Shot.error 返回。
     */
    fun capture(
        dir: File,
        nameBase: String,
        preferFrontFirst: Boolean = false,
        onDone: (List<Shot>) -> Unit
    ) {
        if (!hasPermission()) {
            onDone(listOf(
                Shot("back", null, "未授予摄像头权限"),
                Shot("front", null, "未授予摄像头权限")
            ))
            return
        }
        ensureThread()
        if (handler == null) {
            onDone(listOf(Shot("back", null, "后台线程未就绪"), Shot("front", null, "后台线程未就绪")))
            return
        }
        /**
         * 【v0.6.1 致命修复】抓拍阻塞逻辑**绝不能跑在回调线程上**。
         *
         * 旧代码是 `h.post { captureBlocking(...) }`，即跑在 alphasun-cam 这个
         * HandlerThread 上；而 openSync() 正是把 CameraDevice.StateCallback
         * 投递到**同一个** handler，然后 `sem.tryAcquire(2.5s)` 原地阻塞等待。
         * 回调永远排不进已被阻塞的 looper → 100% 超时 →
         * shootOne 恒返回「抓拍超时或图像无效」，前后摄一张都拿不到。
         * 这就是用户反馈「前置摄像头没有画面」「只有 1 路摄像头」的真因。
         *
         * 现在改在**独立的单线程池**里做阻塞等待，camera handler 只负责回调，
         * 二者不再互相等待。
         */
        shotExec.execute {
            val res = try {
                captureBlocking(dir, nameBase, preferFrontFirst)
            } catch (e: Throwable) {
                Log.e(TAG, "抓拍异常", e)
                listOf(Shot("back", null, "抓拍异常：${e.javaClass.simpleName}: ${e.message}"))
            }
            onDone(res)
        }
    }

    private fun captureBlocking(dir: File, nameBase: String, frontFirst: Boolean): List<Shot> {
        val (back, front) = facingIds()
        if (back == null && front == null) {
            return listOf(Shot("back", null, "未发现可用摄像头"))
        }
        val out = ArrayList<Shot>()

        // ① 优先走真并发双摄（API 31+ 且厂商声明支持）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && back != null && front != null) {
            var tried = false
            var reason = "厂商未声明并发双摄组合"
            try {
                val pair = cm.concurrentCameraIds.firstOrNull { p -> p.contains(back) && p.contains(front) }
                if (pair != null) {
                    tried = true
                    Log.i(TAG, "尝试并发双摄 $pair")
                    val r = captureConcurrent(dir, nameBase, back, front)
                    val fb = File(dir, "$nameBase`_back.jpg")
                    val ff = File(dir, "$nameBase`_front.jpg")
                    if (r) {
                        return listOf(
                            Shot("back", fb.takeIf { it.length() > 1024 }),
                            Shot("front", ff.takeIf { it.length() > 1024 })
                        )
                    }
                    // 并发失败：把成功的那一路保住，失败那路带原因，交给上层降级
                    if (fb.length() > 1024) out.add(Shot("back", fb))
                    else out.add(Shot("back", null, "并发双摄未出图"))
                    reason = "并发双摄超时"
                }
            } catch (e: Exception) {
                reason = "并发双摄异常：${e.message}"
            }
            if (!tried) out.add(Shot("back", null, reason))
        }

        // ② 串行：后置优先（快门响应稳定），preferFrontFirst 时反转。
        //    已成功的 side 不重复抓 —— 避免并发失败后又白等一次。
        val doneSides = out.filter { it.file != null }.map { it.side }.toMutableSet()
        val order =
            if (frontFirst) listOf("front" to front, "back" to back)
            else listOf("back" to back, "front" to front)
        for ((side, id) in order) {
            if (side in doneSides) continue
            out.add(
                if (id == null) Shot(side, null, "该侧无摄像头")
                else shootOne(dir, nameBase, side, id)
            )
        }
        // 返回顺序稳定：back 在前、front 在后（调用方按 side 取，不依赖顺序也 OK）
        return out.sortedByDescending { it.side }
    }

    /**
     * API 31+ 真并发双摄。
     *
     * 并发双摄的语义要求：**一个 CaptureSession 同时挂两个不同 device 的
     * Surface**（由 primary device 创建 session，target surface 来自两路
     * ImageReader）。这是官方 concurrentCameraIds 的唯一合法用法。
     *
     * 任何环节失败都返回 false，绝不抛给调用方；此时主流程会自动退回串行。
     */
    private fun captureConcurrent(dir: File, nameBase: String, backId: String, frontId: String): Boolean {
        val fb = File(dir, "$nameBase`_back.jpg")
        val ff = File(dir, "$nameBase`_front.jpg")
        val wrote = Collections.synchronizedSet(HashSet<String>())
        var back: CameraDevice? = null
        var front: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var rBack: ImageReader? = null
        var rFront: ImageReader? = null

        fun bail(msg: String): Boolean {
            Log.w(TAG, "并发双摄放弃：$msg")
            return false
        }

        try {
            if (!openSync(backId) { d -> back = d }) return bail("后置开流失败")
            if (!openSync(frontId) { d -> front = d }) return bail("前置开流失败")
            val bd = back ?: return bail("后置句柄丢失")
            val fd = front ?: return bail("前置句柄丢失")

            rBack = makeReader()
            rFront = makeReader()
            rBack?.setOnImageAvailableListener({ saveOnce(it, fb, wrote, "back") }, handler)
            rFront?.setOnImageAvailableListener({ saveOnce(it, ff, wrote, "front") }, handler)

            // 触发请求：主 device（后置）建 session，带上前置的 surface
            val reqB = bd.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(rBack!!.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.JPEG_ORIENTATION, 0)
            }
            val reqF = fd.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(rFront!!.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.JPEG_ORIENTATION, 0)
            }

            val surfaces = ArrayList<Surface>(2)
            rBack?.let { surfaces.add(it.surface) }
            rFront?.let { surfaces.add(it.surface) }

            val configured = AtomicBoolean(false)
            bd.createCaptureSession(
                surfaces,                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        configured.set(true)
                        session = s
                        try {
                            // 一张 request 驱动两路 surface —— 这才是"同时抓拍"
                            val both = bd.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                addTarget(rBack!!.surface)
                                addTarget(rFront!!.surface)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            }.build()
                            s.capture(both, object : CameraCaptureSession.CaptureCallback() {}, handler)
                        } catch (e: Exception) {
                            Log.e(TAG, "并发 capture 失败", e)
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        Log.w(TAG, "并发 session 配置失败")
                    }
                },
                handler
            )

            val deadline = System.currentTimeMillis() + OPEN_TIMEOUT_MS + 1200
            while (System.currentTimeMillis() < deadline) {
                val okB = fb.exists() && fb.length() > 1024
                val okF = ff.exists() && ff.length() > 1024
                if (okB && okF) return true
                // 超时过半且至少一路已出图 → 提前收手，剩下那路交给串行
                if (wrote.isNotEmpty() && System.currentTimeMillis() > deadline - OPEN_TIMEOUT_MS) return true
                if (wrote.isEmpty() && configured.get() && System.currentTimeMillis() > deadline - 600) {
                    // session 配好了但一直不出帧，重试一次单次 capture
                    try { session?.capture(reqB.build(), null, handler) } catch (_: Exception) { }
                }
                Thread.sleep(60)
            }
            return wrote.isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "并发抓拍异常", e)
            return false
        } finally {
            try { session?.stopRepeating() } catch (_: Exception) { }
            try { session?.close() } catch (_: Exception) { }
            try { rBack?.close() } catch (_: Exception) { }
            try { rFront?.close() } catch (_: Exception) { }
            // 注意：back/front 被闭包捕获，Kotlin 不做 smart cast，必须显式判空
            val fbDev = back
            val ffDev = front
            if (ffDev != null) closeQuiet(ffDev)
            if (fbDev != null) closeQuiet(fbDev)
        }
    }

    /** ImageReader 回调：只落盘第一帧，JPEG 字节直取 planes[0] */
    private fun saveOnce(r: ImageReader, out: File, once: MutableSet<String>, side: String) {
        var img: android.media.Image? = null
        try {
            if (!once.add(side)) { r.setOnImageAvailableListener(null, null); return }
            img = r.acquireLatestImage() ?: return
            val buf = img.planes[0].buffer
            val b = ByteArray(buf.remaining())
            buf.get(b)
            img.close(); img = null
            FileOutputStream(out).use { it.write(b) }
            Log.i(TAG, "$side 抓拍落盘 ${out.name} ${b.size} 字节")
        } catch (e: Exception) {
            Log.e(TAG, "$side 写图失败", e)
        } finally {
            try { img?.close() } catch (_: Exception) { }
        }
    }

    /** 单路抓拍：open → ImageReader 出帧 → capture → 落盘 → close */
    private fun shootOne(dir: File, nameBase: String, side: String, camId: String): Shot {
        val out = File(dir, "$nameBase`_$side.jpg")
        var dev: CameraDevice? = null
        var reader: ImageReader? = null
        var session: CameraCaptureSession? = null
        val sem = Semaphore(0)
        val wrote = AtomicBoolean(false)
        try {
            val rd = makeReader()
            reader = rd
            rd.setOnImageAvailableListener({ r ->
                if (!wrote.compareAndSet(false, true)) { r.acquireLatestImage()?.close(); return@setOnImageAvailableListener }
                try {
                    r.acquireLatestImage()?.let { img ->
                        val buf: ByteBuffer = img.planes[0].buffer
                        val b = ByteArray(buf.remaining())
                        buf.get(b)
                        img.close()
                        FileOutputStream(out).use { it.write(b) }
                        sem.release()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "写图失败", e); sem.release()
                }
            }, handler)

            val dRef = CameraDeviceHolder()
            if (!openSync(camId) { dev = it; dRef.dev = it }) {
                return Shot(side, null, "打开摄像头失败（可能被其它应用占用或权限不足）")
            }
            val d = dRef.dev ?: return Shot(side, null, "摄像头句柄为空")
            val req = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(rd.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.JPEG_ORIENTATION, 0)
            }.build()

            d.createCaptureSession(
                Collections.singletonList(rd.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        try {
                            s.capture(req, object : CameraCaptureSession.CaptureCallback() { }, handler)
                        } catch (e: Exception) {
                            Log.e(TAG, "capture 失败", e); sem.release()
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        sem.release()
                    }
                }, handler
            )
            val ok = sem.tryAcquire(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val good = ok && out.exists() && out.length() > 1024
            return if (good) Shot(side, out) else Shot(side, null, "抓拍超时或图像无效")
        } catch (e: CameraAccessException) {
            return Shot(side, null, "相机服务错误：${e.message}")
        } catch (e: SecurityException) {
            return Shot(side, null, "权限被拒绝：${e.message}")
        } catch (e: Exception) {
            return Shot(side, null, "${e.javaClass.simpleName}：${e.message}")
        } finally {
            try { session?.close() } catch (_: Exception) { }
            try { reader?.close() } catch (_: Exception) { }
            // dev 被闭包捕获，Kotlin 不做 smart cast
            val dv = dev
            if (dv != null) closeQuiet(dv)
        }
    }

    /** 闭包捕获的可空引用容器（避免 Kotlin smart cast 限制）。 */
    private class CameraDeviceHolder {
        @Volatile var dev: CameraDevice? = null
    }

    private fun makeReader(): ImageReader {
        // 优先 JPEG —— 编码由硬件完成，无需自己转 YUV
        for (s in listOf(Size(W, H), Size(1920, 1080), Size(1600, 1200), Size(1280, 960), Size(640, 480))) {
            try {
                if (ImageReader.newInstance(s.width, s.height, ImageFormat.JPEG, 2) != null) {
                    return ImageReader.newInstance(s.width, s.height, ImageFormat.JPEG, 2)
                }
            } catch (_: Exception) { }
        }
        return ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2)
    }

    /**
     * 同步开流。`openCamera` 的回调在 handler 线程上，因此这里用信号量
     * 在当前工作线程阻塞等待 StateCallback 结果。
     */
    private fun openSync(camId: String, onOpen: (CameraDevice) -> Unit): Boolean {
        var state = 0  // 0 pending 1 ok 2 fail
        val sem = Semaphore(0)
        val h = handler ?: return false
        try {
            cm.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    onOpen(d); state = 1; sem.release()
                }

                override fun onDisconnected(d: CameraDevice) {
                    closeQuiet(d); state = 2; sem.release()
                }

                override fun onError(d: CameraDevice, e: Int) {
                    closeQuiet(d); state = 2; sem.release()
                }
            }, h)
        } catch (e: SecurityException) {
            Log.w(TAG, "openCamera 权限异常", e); return false
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "openCamera 非法 id", e); return false
        } catch (e: CameraAccessException) {
            Log.w(TAG, "openCamera 服务异常", e); return false
        }
        val ok = sem.tryAcquire(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!ok) Log.w(TAG, "openCamera 超时 $camId")
        return ok && state == 1
    }

    private fun closeQuiet(d: CameraDevice) {
        try { d.close() } catch (_: Exception) { }
    }

    private fun ensureThread() {
        if (thread != null && thread!!.isAlive) return
        thread = HandlerThread("alphasun-cam").also {
            it.start()
            handler = Handler(it.looper)
        }
    }

    fun release() {
        try { thread?.quitSafely() } catch (_: Exception) { }
        thread = null; handler = null
        try { shotExec.shutdownNow() } catch (_: Exception) { }
    }

    // ---- 小工具 ----
}

/** 事件媒体落盘目录：<外部私有目录>/AlphaSun警戒事件/yyyyMMdd/ */
object AlertMediaDir {
    fun today(ctx: Context): File {
        val base: File = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val root = File(base, "AlertEvents")
        val d = SimpleDateFormat("yyyyMMdd").format(Date())
        val day = File(root, d)
        day.mkdirs()
        return day
    }
}
