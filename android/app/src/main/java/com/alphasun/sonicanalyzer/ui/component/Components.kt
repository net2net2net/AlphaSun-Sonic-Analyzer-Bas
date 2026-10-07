package com.alphasun.sonicanalyzer.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphasun.sonicanalyzer.ui.theme.As

/**
 * v0.4.0 通用组件层 —— 逐个对应 web-v0.3.1 的 CSS 类。
 *
 * 映射表：
 *   .card          → AsCard
 *   .card h3 .ic   → CardTitle 里的方形色块（i1~i5 分区配色）
 *   .row / .k / .v → StatRow
 *   .chip          → AsChip
 *   .badge.b1~b4   → AsBadge
 *   .tag           → AsTag
 *   .conf .lab/.bar→ ConfBar
 *   .bands .bb     → BandBars
 *   .sec           → SectionTitle
 *   .hintline      → HintLine
 *   .fnpop-bar     → FnSheet 的顶栏
 *   .mbar / .mbtn  → DockBar / DockButton
 *   .mbtn.primary  → DockButton(primary=true)
 */

/* ==================== 卡片 ==================== */

@Composable
fun AsCard(
    modifier: Modifier = Modifier,
    padH: Dp = As.CardPadH,
    padV: Dp = As.CardPadV,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(As.R)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(As.Panel, shape)
            .border(BorderStroke(1.dp, As.BrdSoft), shape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = padH, vertical = padV),
        content = content
    )
}

/** 卡片标题。tone 对应 CSS 的 .ic.i1~.i5 分区色。 */
enum class CardTone(val a: Color, val b: Color) {
    I1(Color(0xFF37E0FF), Color(0xFF9B6BFF)),
    I2(Color(0xFF3CE8A0), Color(0xFF37E0FF)),
    I3(Color(0xFF9B6BFF), Color(0xFFFF8BD0)),
    I4(Color(0xFFFFB454), Color(0xFFFF8BD0)),
    I5(Color(0xFF37E0FF), Color(0xFF9B6BFF))
}

@Composable
fun CardTitle(text: String, tone: CardTone = CardTone.I1, modifier: Modifier = Modifier) {
    Row(modifier = modifier.padding(bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Brush.verticalGradient(listOf(tone.a, tone.b)))
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            color = As.Dim,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
            maxLines = 2
        )
    }
}

/* ==================== 参数行 ==================== */

/**
 * 对应 .row：左键名右数值，虚线下划线。
 * sub 对应 <i class="pvsub">，用极淡的斜体小字补充取值范围。
 */
@Composable
fun StatRow(
    k: String,
    v: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    vColor: Color = As.Txt,
    vSize: Int = 13,
    onClick: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 4.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(k, color = As.Dim, fontSize = 12.5.sp, modifier = Modifier.weight(1f, false))
            Spacer(Modifier.width(8.dp))
            Text(
                v, color = vColor, fontSize = vSize.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.End
            )
        }
        if (sub != null) {
            Text(
                sub, color = As.Faint, fontSize = 9.5.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                modifier = Modifier.padding(top = 1.dp)
            )
        }
    }
}

/* ==================== 小部件 ==================== */

@Composable
fun AsChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    live: Boolean = false,
    lampColor: Color? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color(0x0DFFFFFF), Color(0x04FFFFFF))
                ),
                RoundedCornerShape(9.dp)
            )
            .border(
                BorderStroke(1.dp, if (live) As.Cy.copy(alpha = 0.55f) else As.Brd),
                RoundedCornerShape(9.dp)
            )
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (lampColor != null) {
            Box(
                Modifier.size(9.dp).clip(CircleShape).background(lampColor)
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(label, color = if (live) As.Gn else As.Dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.width(4.dp))
        Text(value, color = As.Txt, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}

enum class BadgeTone { B1, B2, B3, B4 }

@Composable
fun AsBadge(text: String, tone: BadgeTone, modifier: Modifier = Modifier) {
    val (bg, fg) = when (tone) {
        BadgeTone.B1 -> Color(0x293CE8A0) to As.Gn
        BadgeTone.B2 -> Color(0x2937E0FF) to As.Cy
        BadgeTone.B3 -> Color(0x2EFFB454) to As.Am
        BadgeTone.B4 -> Color(0x2EFF5D73) to As.Rd
    }
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .padding(horizontal = 9.dp, vertical = 2.dp)
    ) { Text(text, color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun AsTag(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x249B6BFF))
            .border(BorderStroke(1.dp, Color(0x409B6BFF)), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) { Text(text, color = Color(0xFFCDB6FF), fontSize = 10.sp) }
}

/**
 * 对应 v0.3.0 的 .conf：标签 + 百分比 + 渐变进度条。
 *
 * v0.6.1：改为直接收 [Brush]，以便逐条还原 v0.3.0 里写死在 HTML style 上的
 * 四条不同渐变（人声粉 / 音乐紫 / 其他绿 / 噪音青）；
 * 之前是传两个端点色自己拼渐变，四条看起来几乎一样。
 */
@Composable
fun ConfBar(
    label: String,
    pct: Int,
    brush: Brush,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = As.Dim, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
            Text("$pct%", color = As.Dim, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Color(0x12FFFFFF))
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth((pct / 100f).coerceIn(0f, 1f))
                    .background(brush)
            )
        }
    }
}

/** 对应 .sec：卡片内的小节标题。 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, color = As.Txt, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

/** 对应 .hintline / .heu：淡灰提示。 */
@Composable
fun HintLine(text: String, modifier: Modifier = Modifier, color: Color = As.Faint) {
    Text(
        text, color = color, fontSize = 10.sp, lineHeight = 14.sp,
        modifier = modifier.padding(top = 5.dp)
    )
}

/** 对应 .lab2-adv .advt。 */
@Composable
fun GroupTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, color = As.Cy, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp, modifier = modifier.padding(bottom = 3.dp)
    )
}
