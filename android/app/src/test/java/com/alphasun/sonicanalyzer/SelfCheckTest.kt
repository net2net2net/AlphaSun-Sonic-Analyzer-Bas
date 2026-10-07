package com.alphasun.sonicanalyzer

import com.alphasun.sonicanalyzer.core.SelfCheck
import org.junit.Assert.*
import org.junit.Test

/**
 * v0.6.2 自检回归测试。
 *
 * 这一层是**最贴近用户感受**的门禁：它断言的不是某个函数的数值，
 * 而是「用户按下按钮后到底能不能得到结果」——
 * 采集出不出数、分离出不出声、定位出不出角度、警戒记不记事件。
 *
 * 只要 `SelfCheck` 的算法层有任一项变红，本测试立即失败，
 * 防止"功能看着都在、实际全废"的版本再次流出。
 */
class SelfCheckTest {

    /** 算法层 14 项必须全绿（不传 Device，只跑纯 Kotlin 部分）。 */
    @Test
    fun `自检算法层全部通过`() {
        val r = SelfCheck.run(null)
        val bad = r.items.filter { !it.ok }
        assertTrue(
            "以下自检项未通过：\n" + r.text(),
            bad.isEmpty()
        )
        assertEquals("算法层应为 15 项", 15, r.items.size)
        assertEquals(0, r.failed)
    }

    /** 自检必须覆盖这几个用户最关心的能力，少一项都不算完整。 */
    @Test
    fun `自检覆盖关键能力项`() {
        val names = SelfCheck.run(null).items.map { it.name }
        val must = listOf(
            "FFT 正/逆变换往返",
            "前景/背景分离",
            "试听响度归一化",
            "GCC-PHAT 时差估计",
            "23 项声波特征",
            "智能分类",
            "背景模型学习",
            "声波警戒状态机"
        )
        for (m in must) {
            assertTrue("自检缺少关键项：$m", names.any { it.contains(m) })
        }
    }

    /** 设备层：主帧全空的设备必须被判为异常（v0.6.1 头号根因的门禁）。 */
    @Test
    fun `设备层_主帧为空必须判异常`() {
        val r = SelfCheck.run(emptyDevice())
        val item = r.items.first { it.name == "主帧非空且有信号" }
        assertFalse("主帧为空却判为通过 —— v0.6.1 头号根因复发了", item.ok)
        assertTrue(item.detail.contains("头号根因"))
    }

    /** 设备层：正常出数的设备，主帧项必须通过。 */
    @Test
    fun `设备层_主帧有信号应判通过`() {
        val r = SelfCheck.run(goodDevice())
        val item = r.items.first { it.name == "主帧非空且有信号" }
        assertTrue("主帧有信号却判异常：${item.detail}", item.ok)
    }

    /** 设备层：权限/采集/循环异常这三项要能如实反映设备状态。 */
    @Test
    fun `设备层_权限与循环异常如实反映`() {
        val r = SelfCheck.run(emptyDevice())
        assertFalse(r.items.first { it.name == "麦克风权限" }.ok)
        assertFalse(r.items.first { it.name == "采集已启动" }.ok)
        assertFalse(r.items.first { it.name == "主循环无累计异常" }.ok)
        // 全静音设备：设备层 8 项里至少 5 项应为异常
        assertTrue("设备层异常项过少：\n" + r.text(), r.failed >= 5)
    }

    /** 报告文本必须可复制粘贴（含标题、分组、逐项结论）。 */
    @Test
    fun `自检报告文本可排障`() {
        val t = SelfCheck.run(emptyDevice()).text()
        assertTrue(t.contains("自检"))
        assertTrue("应含分组标题", t.contains("【算法层（离线）】"))
        assertTrue("应含分组标题", t.contains("【设备层（真机）】"))
        assertTrue("异常项应可辨识", t.contains("[异常]"))
        assertTrue("通过项应可辨识", t.contains("[通过]"))
    }

    // ==================== 桩设备 ====================

    private fun emptyDevice(): SelfCheck.Device = object : SelfCheck.Device {
        override val hasMicPermission = false
        override val capturing = false
        override val sampleRate = 48000
        override val channelCount = 1
        override val fftSize = 2048
        override val frame = FloatArray(0)          // ← 空帧：正是 v0.6.1 的病态
        override val multi = null
        override val fakeStereo = false
        override val loopErrors = 42                // ← 主循环一直在抛异常
        override val diag = "未启动"
        override val cameraCount = 0
        override val cameraDetail = "未授予摄像头权限"
        override val storageOk = false
        override val storageDetail = "不可写"
    }

    private fun goodDevice(): SelfCheck.Device = object : SelfCheck.Device {
        override val hasMicPermission = true
        override val capturing = true
        override val sampleRate = 48000
        override val channelCount = 2
        override val fftSize = 2048
        override val frame = FloatArray(2048) { i ->
            (0.3 * kotlin.math.sin(2.0 * kotlin.math.PI * 440.0 * i / 48000.0)).toFloat()
        }
        override val multi = null
        override val fakeStereo = true
        override val loopErrors = 0
        override val diag = "48000 Hz · 2ch · 音源 MIC（麦克风）"
        override val cameraCount = 2
        override val cameraDetail = "前置 1 · 后置 0"
        override val storageOk = true
        override val storageDetail = "可写"
    }
}
