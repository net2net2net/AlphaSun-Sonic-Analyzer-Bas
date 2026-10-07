package com.alphasun.sonicanalyzer.core

/**
 * 噪音评估 GB/T 3098 分级。
 *
 * 【v0.6.0】本表原先放在 `ui.theme.NzLevels`（含 Compose `Color`），
 * `NoiseEval.levelText()` 反向引用 UI 层，导致：
 *  ① 分层倒置 —— 纯算法层依赖 Compose，JVM 单元测试无法直接调用；
 *  ② 文案与颜色耦合 —— 改一次文案要动 UI 文件。
 *
 * 现在：等级定义与文案下沉到 core（纯 Kotlin，无 Android 依赖），
 * UI 侧 `NzLevels` 只保留颜色映射。
 */
data class NzLevel(val db: Int, val text: String)

object Nz {
    val LEVELS = listOf(
        NzLevel(30, "安静（图书馆/卧室）"),
        NzLevel(40, "安静（客厅/办公）"),
        NzLevel(50, "一般（图书馆/教室）"),
        NzLevel(60, "较差（空调/交谈）"),
        NzLevel(70, "较差（街道/公交内）"),
        NzLevel(80, "嘈杂（马路/工地旁）"),
        NzLevel(90, "很吵（工地/车间）")
    )
    const val UNKNOWN_TEXT = "未测量"

    fun of(db: Double?): NzLevel {
        if (db == null || !db.isFinite()) return NzLevel(0, UNKNOWN_TEXT)
        var r = LEVELS[0]
        for (l in LEVELS) if (db >= l.db) r = l
        return r
    }
}
