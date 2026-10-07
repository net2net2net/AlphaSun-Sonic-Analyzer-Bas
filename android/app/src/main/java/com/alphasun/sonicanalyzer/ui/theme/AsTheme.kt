package com.alphasun.sonicanalyzer.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.alphasun.sonicanalyzer.core.Nz
import com.alphasun.sonicanalyzer.core.NzLevel as CoreNzLevel

/**
 * v0.4.0 原生视觉 token —— 逐项对齐 web-v0.3.1/index.html 的 :root CSS 变量。
 *
 * 对照表（CSS → Kotlin）：
 *   --bg0 #04060d → Bg0     --txt  #eaf1ff → Txt
 *   --bg1 #0a0f1e → Bg1     --dim  #8a9bc4 → Dim
 *   --line rgba(90,130,220,.10) → Line
 *   --panel rgba(13,20,38,.70) → Panel
 *   --brd  rgba(110,150,255,.20) → Brd
 *   --faint #54618a → Faint
 *   --cy #37e0ff → Cy   --pu #9b6bff → Pu
 *   --gn #3ce8a0 → Gn   --am #ffb454 → Am   --rd #ff5d73 → Rd
 *   --r 14px → R
 *
 * ⚠ 不要"顺手"改这些值：原生版与 Web 版的观感一致性依赖它们逐项相等。
 *   改动请同步回 web-v0.3.1/index.html 的 :root，否则两端会漂。
 */
object As {

    /* ---------- 底色 ---------- */
    val Bg0 = Color(0xFF04060D)
    val Bg1 = Color(0xFF0A0F1E)
    val Line = Color(0x1A5A82DC)          // rgba(90,130,220,.10)
    val Panel = Color(0xB30D1426)         // rgba(13,20,38,.70)
    val PanelSolid = Color(0xFF0D1426)
    val Brd = Color(0x336E96FF)           // rgba(110,150,255,.20)
    val BrdSoft = Color(0x221E96FF)       // .card 用的更淡描边 rgba(110,150,255,.13)
    val BrdHot = Color(0x4737E0FF)        // hover/激活 rgba(55,224,255,.28)

    /* ---------- 文字 ---------- */
    val Txt = Color(0xFFEAF1FF)
    val Dim = Color(0xFF8A9BC4)
    val Faint = Color(0xFF54618A)

    /* ---------- 主色 ---------- */
    val Cy = Color(0xFF37E0FF)
    val Pu = Color(0xFF9B6BFF)
    val Gn = Color(0xFF3CE8A0)
    val Am = Color(0xFFFFB454)
    val Rd = Color(0xFFFF5D73)
    /** v0.3.0 .brand .sub.sonic 的副标题青 */
    val Sonic = Color(0xFF8FD8F5)

    /* ---------- 品牌荧光黄（需求①：S = Sun = 阳光） ---------- */
    val HotTop = Color(0xFFFFFDE8)
    val HotMid = Color(0xFFFFE95C)
    val HotLow = Color(0xFFFFC531)

    /* ---------- 尺寸 ---------- */
    val R = 14.dp            // --r
    val RSm = 10.dp
    val RSq = 3.dp
    val Gap = 10.dp          // .stage / .panel gap
    val PadH = 12.dp         // clamp(12px,2vw,24px) 的移动端取值
    val CardPadH = 11.dp     // .card padding 10px 11px
    val CardPadV = 10.dp
    val MbtnH = 46.dp        // .mbtn height
    val DockInfoH = 37.dp    // --dockInfoH
    val FnBarH = 48.dp       // .fnpop-bar 最小高度

    /* ---------- 渐变（复用 CSS 里的 linear-gradient） ---------- */
    val titleBrush get() = Brush.linearGradient(
        listOf(Color(0xFFE8F7FF), Cy, Color(0xFFA78BFA))
    )
    val hotBrush get() = Brush.verticalGradient(listOf(HotTop, HotMid, HotLow))
    val latBrush get() = Brush.verticalGradient(listOf(Color(0xFFF2FDFF), Color(0xFF6FE6FF)))
    val barBrush get() = Brush.verticalGradient(listOf(Cy, Pu))

    /**
     * v0.3.0 `.brand h1` 的中文标题渐变：linear-gradient(96deg,#e8f7ff 0%,var(--cy) 42%,#a78bfa 100%)
     * （titleBrush 是同一组色，这里保留命名以对应 CSS 的 96deg 方向）
     */
    val cnBrush get() = Brush.linearGradient(listOf(Color(0xFFE8F7FF), Cy, Color(0xFFA78BFA)))

    /** v0.3.0 .conf 四条置信度条各自的渐变（人声/音乐/其他/噪音） */
    val confVoice get() = Brush.horizontalGradient(listOf(Color(0xFFFF8BD0), Color(0xFFFF5D9E)))
    val confMusic get() = Brush.horizontalGradient(listOf(Color(0xFF9B6BFF), Color(0xFFC9A6FF)))
    val confOther get() = Brush.horizontalGradient(listOf(Color(0xFF3CE8A0), Color(0xFF8BF5C8)))
    val confNoise get() = Brush.horizontalGradient(listOf(Color(0xFF37E0FF), Color(0xFF7FE9FF)))

    /** 页面底：CSS body 的 radial + linear 叠加，用 Brush.radialGradient 近似两次光晕。 */
    fun pageBrush(): Brush = Brush.linearGradient(
        0f to Bg0, 0.55f to Color(0xFF060A14), 1f to Bg1
    )

    /** 底坞背景：linear-gradient(180deg, rgba(8,13,26,.72), rgba(6,10,20,.96)) */
    fun dockBrush(): Brush = Brush.verticalGradient(
        listOf(Color(0xB80D1A2A), Color(0xF5060A14))
    )

    /** 卡片高光：inset 0 1px 0 rgba(255,255,255,.05) */
    val cardSheen = Color(0x0DFFFFFF)
}

/**
 * 噪音评估配色的 UI 侧映射。
 *
 * 【v0.6.0】等级定义与文案已下沉到 `core.Nz`（纯 Kotlin，可 JVM 单测），
 * 这里只负责「分贝 → 颜色」，不再持有文案，避免 core 反向依赖 Compose。
 */
data class NzLevel(val d: Int, val c: Color, val t: String)

object NzLevels {
    private val COLORS = mapOf(
        30 to Color(0xFF3CE8A0),
        40 to Color(0xFF5CD6A0),
        50 to Color(0xFFA8D84C),
        60 to Color(0xFFE8C84C),
        70 to Color(0xFFF09A3C),
        80 to Color(0xFFFF6B3C),
        90 to Color(0xFFFF4D6A)
    )
    val UNKNOWN = NzLevel(0, As.Dim, Nz.UNKNOWN_TEXT)

    fun of(db: Double?): NzLevel {
        if (db == null || !db.isFinite()) return UNKNOWN
        val r: CoreNzLevel = Nz.of(db)
        return NzLevel(r.db, COLORS[r.db] ?: As.Am, r.text)
    }
}
