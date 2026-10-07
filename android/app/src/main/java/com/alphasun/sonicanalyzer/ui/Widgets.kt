package com.alphasun.sonicanalyzer.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphasun.sonicanalyzer.MainViewModel
import com.alphasun.sonicanalyzer.ui.component.*
import com.alphasun.sonicanalyzer.ui.theme.As

/** 底部功能弹层类型（对应 Web 的 fnpop / mask 面板）。 */
enum class Panel { NONE, PARAMS, SEP, LOC, GUARD, NOISE }

/** 通用开关行（对应 Web alertMask 的 checkbox 行）。
 *  onChange 放在最后，使 trailing lambda 语法可用。 */
@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    hint: String? = null,
    modifier: Modifier = Modifier,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(As.RSm))
            .clickable { onChange(!checked) }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(17.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (checked) As.Cy else Color(0x1AFFFFFF))
                .border(
                    BorderStroke(1.dp, if (checked) As.Cy else As.Brd),
                    RoundedCornerShape(5.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Text("✓", color = Color(0xFF04060D), fontSize = 12.sp, fontWeight = FontWeight.Black)
            }
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                color = As.Txt,
                fontSize = 12.sp
            )
            if (hint != null) {
                Text(hint, color = As.Faint, fontSize = 9.sp, lineHeight = 12.sp)
            }
        }
    }
}

/**
 * 滑块行（对应 Web range input）。onChange 在最后以支持 trailing lambda。
 *
 * 【v0.6.1 修复】旧实现的内部状态 `v` 初值写死为量程中点 `(from+to)/2`，
 * 与引擎里的真实当前值（如 `g.maxEvSec` 默认 60、`g.evalSec` 默认 10）无关。
 * 结果：用户第一次点滑块，参数会**跳到量程中点**而不是从当前值调整 ——
 * 例如「事件最长时长」一碰就变成 305 秒。现在由 [initial] 显式传入当前值。
 */
@Composable
fun SliderRow(
    label: String,
    value: String,
    from: Double,
    to: Double,
    step: Double,
    modifier: Modifier = Modifier,
    initial: Double = (from + to) / 2,
    onChange: (Double) -> Unit
) {
    var v by remember(label, initial) { mutableStateOf(initial.coerceIn(from, to)) }
    Column(modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = As.Txt, fontSize = 12.sp)
            Text(value, color = As.Cy, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
            val n = (((to - from) / step).toInt() + 1).coerceAtLeast(2)
            val idx = ((v - from) / step).toInt().coerceIn(0, n - 1)
            for (i in 0 until n) {
                val sel = i <= idx
                Box(
                    Modifier
                        .weight(1f)
                        .height(if (sel) 5.dp else 3.dp)
                        .padding(horizontal = 1.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (sel) As.Cy else As.Brd)
                        .clickable { v = from + i * step; onChange(v) }
                )
            }
        }
    }
}

/** 弹层外壳：底部固定坞上方的面板，含标题栏 + 关闭。
 *  full=true 时改为全屏值守台样式（四角圆角、铺满父容器、内容区填满高度可滚动），
 *  对应 Web v0.3.0 的 alertMask 全屏覆盖。 */
@Composable
fun PanelShell(
    title: String,
    subtitle: String,
    onClose: () -> Unit,
    accent: Color = As.Cy,
    full: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = if (full) RoundedCornerShape(14.dp) else RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
    Column(
        Modifier
            .then(if (full) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(As.PanelSolid, As.Bg1)
                ), shape
            )
            .border(BorderStroke(1.dp, As.Brd), shape)
    ) {
        // 标题栏
        Row(
            Modifier
                .fillMaxWidth()
                .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.16f), Color.Transparent)))
                .padding(start = 14.dp, end = 6.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(width = 3.dp, height = 26.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Brush.verticalGradient(listOf(accent, As.Pu)))
            )
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = As.Cy, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                Text(subtitle, color = As.Dim, fontSize = 10.sp)
            }
            Box(
                Modifier
                    .height(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(BorderStroke(1.dp, As.Brd), RoundedCornerShape(10.dp))
                    .clickable { onClose() },
                contentAlignment = Alignment.Center
            ) {
                Text("✕ 关闭", color = As.Dim, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(As.Brd))
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (full) Modifier.fillMaxHeight() else Modifier.heightIn(max = 460.dp))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = As.PadH, vertical = 10.dp),
            content = content
        )
    }
}

/** 无涟漪点击（用于遮罩层，避免水波纹干扰视觉）。 */
@Composable
fun Modifier.noRippleClick(onClick: () -> Unit): Modifier = composed {
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick
    )
}

/**
 * 顶部品牌行 —— 逐字对齐 v0.3.0 的 `.brand`：
 *
 * ```
 * <h1><span class="lat">Alpha<b class="hot">S</b>un</span>声波分析仪</h1>
 * <div class="sub sonic">Alpha<b class="hot2">S</b>un Sonic Analyzer</div>
 * <div class="author"><span id="appVer">v0.3.0</span> · 作者：阳光 net2net2net（ VX: net2net ）</div>
 * ```
 *
 * 上一版把整串 "AlphaSun" 刷成一个颜色，荧光黄的 S 完全没体现出来，
 * 副标题也写成了 "SONIC SPECTRUM ANALYZER"、缺了作者行 —— 这三点都是
 * 「界面没对齐」里最容易被一眼看出来的差异。
 */
@Composable
fun BrandRow(version: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AsLogo(Modifier.size(40.dp))
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            // —— 主标题：Alpha【S】un 声波分析仪 ——
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandText("Alpha", As.latBrush, 17, FontWeight.W900)
                BrandText("S", As.hotBrush, 19, FontWeight.W900)
                BrandText("un", As.latBrush, 17, FontWeight.W900)
                Spacer(Modifier.width(3.dp))
                BrandText("声波分析仪", As.cnBrush, 15, FontWeight.W900)
            }
            // —— 英文副标题：Alpha【S】un Sonic Analyzer ——
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandText("Alpha", As.latBrush, 9, FontWeight.W700)
                BrandText("S", As.hotBrush, 10, FontWeight.W900)
                BrandText("un ", As.latBrush, 9, FontWeight.W700)
                BrandText("Sonic Analyzer", Brush.verticalGradient(
                    listOf(Color(0xFFEAFCFF), Color(0xFFBEE9FA))), 9, FontWeight.W900)
            }
            // —— 作者行 ——
            Text(
                "$version · 作者：阳光 net2net2net（ VX: net2net ）",
                color = As.Dim,
                fontSize = 8.5.sp,
                letterSpacing = 0.3.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 渐变文字。
 *
 * Compose 里 `color` 与 `brush` 互斥：只有把 color 置为 Unspecified 时
 * brush 才生效 —— 这里统一封装，避免每个调用点各写一遍。
 */
@Composable
private fun BrandText(
    text: String,
    brush: Brush,
    sizeSp: Int,
    weight: FontWeight
) {
    Text(
        text,
        style = androidx.compose.ui.text.TextStyle(
            brush = brush,
            fontSize = sizeSp.sp,
            fontWeight = weight,
            letterSpacing = 0.5.sp,
            fontFamily = FontFamily.Monospace
        )
    )
}

/**
 * v0.3.0 的手绘 SVG 徽标（外环光晕 / 36 条旋转刻度环 / 24 条径向频谱柱 / 中央波形环）。
 *
 * 原生端没有 SVG 解析器，这里用 Canvas **逐坐标 1:1 复刻** web-v0.3.1/index.html
 * 的 `<svg class="logo">`：
 *  · lgHalo：径向光晕（#37C8FF .26 → 0），用半透明填充圆近似
 *  · lg-bars：24 条径向频谱柱，颜色按参考的真实色环（青→紫→粉），26s 反向匀速旋转
 *  · lg-tick：36 条刻度，9s 匀速旋转（spinning=false 时停转省电）
 *  · 中央：内环（#7DE2FF）+ 实心点（#E0FAFF）
 * 坐标直接取自 SVG（viewBox 48×48，中心 24,24），按比例映射到 Canvas，保证任意尺寸不糊。
 */
private data class BarSeg(
    val x1: Float, val y1: Float, val x2: Float, val y2: Float,
    val color: Long, val w: Float
)

/** 24 条径向频谱柱（与参考 SVG lg-bars 完全一致：端点 + 颜色 + 线宽）。 */
private val BARS = listOf(
    BarSeg(24.00f, 16.80f, 24.00f, 2.16f, 0xFF22D3EE, 1.44f),
    BarSeg(25.86f, 17.05f, 29.63f, 2.98f, 0xFF27C2F0, 1.43f),
    BarSeg(27.60f, 17.76f, 34.76f, 5.36f, 0xFF2CB1F1, 1.41f),
    BarSeg(29.09f, 18.91f, 38.96f, 9.04f, 0xFF32A0F3, 1.38f),
    BarSeg(30.24f, 20.40f, 41.90f, 13.67f, 0xFF3790F5, 1.34f),
    BarSeg(30.95f, 22.14f, 43.42f, 18.80f, 0xFF3E80F6, 1.29f),
    BarSeg(31.20f, 24.00f, 43.50f, 24.00f, 0xFF4F78F6, 1.24f),
    BarSeg(30.95f, 25.86f, 43.42f, 29.20f, 0xFF6071F6, 1.29f),
    BarSeg(30.24f, 27.60f, 41.90f, 34.33f, 0xFF7069F6, 1.34f),
    BarSeg(29.09f, 29.09f, 38.96f, 38.96f, 0xFF8161F6, 1.38f),
    BarSeg(27.60f, 30.24f, 34.76f, 42.64f, 0xFF8D5BF6, 1.41f),
    BarSeg(25.86f, 30.95f, 29.63f, 45.02f, 0xFF935AF6, 1.43f),
    BarSeg(24.00f, 31.20f, 24.00f, 45.84f, 0xFF9A58F6, 1.44f),
    BarSeg(22.14f, 30.95f, 18.37f, 45.02f, 0xFFA057F7, 1.43f),
    BarSeg(20.40f, 30.24f, 13.24f, 42.64f, 0xFFA656F7, 1.41f),
    BarSeg(18.91f, 29.09f, 9.04f, 38.96f, 0xFFB053EB, 1.38f),
    BarSeg(17.76f, 27.60f, 6.10f, 34.33f, 0xFFBF51D8, 1.34f),
    BarSeg(17.05f, 25.86f, 4.58f, 29.20f, 0xFFCD4EC4, 1.29f),
    BarSeg(17.05f, 22.14f, 4.58f, 18.80f, 0xFFDB4BB0, 1.24f),
    BarSeg(16.80f, 24.00f, 4.50f, 24.00f, 0xFFE9499D, 1.24f),
    BarSeg(17.05f, 20.40f, 6.10f, 13.67f, 0xFFEE5489, 1.34f),
    BarSeg(18.91f, 18.91f, 9.04f, 9.04f, 0xFFF26476, 1.38f),
    BarSeg(20.40f, 17.76f, 13.24f, 5.36f, 0xFFF57363, 1.41f),
    BarSeg(22.14f, 17.05f, 18.37f, 2.98f, 0xFFF8834F, 1.43f)
)

/** 36 条刻度（与参考 SVG lg-tick 完全一致：端点）。 */
private val TICKS = listOf(
    floatArrayOf(24.00f, -0.34f, 25.78f, -0.27f),
    floatArrayOf(28.23f, 0.03f, 29.97f, 0.41f),
    floatArrayOf(32.32f, 1.13f, 33.98f, 1.80f),
    floatArrayOf(36.17f, 2.92f, 37.68f, 3.87f),
    floatArrayOf(39.64f, 5.36f, 40.97f, 6.55f),
    floatArrayOf(42.64f, 8.36f, 43.74f, 9.76f),
    floatArrayOf(45.08f, 11.83f, 45.91f, 13.41f),
    floatArrayOf(46.87f, 15.68f, 47.42f, 17.37f),
    floatArrayOf(47.97f, 19.77f, 48.21f, 21.54f),
    floatArrayOf(48.34f, 24.00f, 48.27f, 25.78f),
    floatArrayOf(47.97f, 28.23f, 47.59f, 29.97f),
    floatArrayOf(46.87f, 32.32f, 46.20f, 33.98f),
    floatArrayOf(45.08f, 36.17f, 44.13f, 37.68f),
    floatArrayOf(42.64f, 39.64f, 41.45f, 40.97f),
    floatArrayOf(39.64f, 42.64f, 38.24f, 43.74f),
    floatArrayOf(36.17f, 45.08f, 34.59f, 45.91f),
    floatArrayOf(32.32f, 46.87f, 30.63f, 47.42f),
    floatArrayOf(28.23f, 47.97f, 26.46f, 48.21f),
    floatArrayOf(24.00f, 48.34f, 22.22f, 48.27f),
    floatArrayOf(19.77f, 47.97f, 18.03f, 47.59f),
    floatArrayOf(15.68f, 46.87f, 14.02f, 46.20f),
    floatArrayOf(11.83f, 45.08f, 10.32f, 44.13f),
    floatArrayOf(8.36f, 42.64f, 7.03f, 41.45f),
    floatArrayOf(5.36f, 39.64f, 4.26f, 38.24f),
    floatArrayOf(2.92f, 36.17f, 2.09f, 34.59f),
    floatArrayOf(1.13f, 32.32f, 0.58f, 30.63f),
    floatArrayOf(0.03f, 28.23f, -0.21f, 26.46f),
    floatArrayOf(-0.34f, 24.00f, -0.27f, 22.22f),
    floatArrayOf(0.03f, 19.77f, 0.41f, 18.03f),
    floatArrayOf(1.13f, 15.68f, 1.80f, 14.02f),
    floatArrayOf(2.92f, 11.83f, 3.87f, 10.32f),
    floatArrayOf(5.36f, 8.36f, 6.55f, 7.03f),
    floatArrayOf(8.36f, 5.36f, 9.76f, 4.26f),
    floatArrayOf(11.83f, 2.92f, 13.41f, 2.09f),
    floatArrayOf(15.68f, 1.13f, 17.37f, 0.58f),
    floatArrayOf(19.77f, 0.03f, 21.54f, -0.21f)
)

@Composable
fun AsLogo(modifier: Modifier = Modifier, spinning: Boolean = true) {
    val inf = rememberInfiniteTransition(label = "logo")
    val deg by inf.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(
                durationMillis = 9000,
                easing = androidx.compose.animation.core.LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "logoSpin"
    )
    val a = if (spinning) deg else 0f
    Canvas(modifier) {
        val cx = center.x
        val cy = center.y
        val k = size.minDimension / 48f   // SVG viewBox 48×48 → 当前尺寸
        fun X(v: Float) = cx + (v - 24f) * k
        fun Y(v: Float) = cy + (v - 24f) * k
        // 外环光晕（lgHalo：径向渐变 #37C8FF .26 → 0，用半透明填充圆近似）
        drawCircle(color = Color(0x4200C8FF), radius = 14.5f * k)
        // 刻度环（9s 匀速旋转）
        rotate(a, center) {
            for (t in TICKS) {
                drawLine(
                    color = Color(0xFF78C8FF).copy(alpha = 0.5f),
                    start = Offset(X(t[0]), Y(t[1])),
                    end = Offset(X(t[2]), Y(t[3])),
                    strokeWidth = 1.1f * k,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }
        }
        // 24 条径向频谱柱（26s 反向慢转，复刻 lg-bars）
        rotate(-a * (9f / 26f), center) {
            for (b in BARS) {
                drawLine(
                    color = Color(b.color),
                    start = Offset(X(b.x1), Y(b.y1)),
                    end = Offset(X(b.x2), Y(b.y2)),
                    strokeWidth = b.w * k,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }
        }
        // 中央：内环（#7DE2FF）+ 实心点（#E0FAFF），不旋转
        drawCircle(
            color = Color(0xFF7DE2FF), radius = 6.77f * k,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f * k)
        )
        drawCircle(color = Color(0xFFE0FAFF), radius = 3.94f * k)
    }
}

/**
 * 底部固定坞按钮（对应 v0.3.0 `.mbtn`）。
 *
 * v0.3.0 的布局是**图标在上 / 文字在下**（`.gi` + `.tx`），
 * 五个按钮挤在 46px 高的条里，横向排文字会挤爆。
 * 上一版原生是纯文字 + 副标题，与 v0.3.0 观感明显不同，这里补齐图标层。
 */
@Composable
fun DockButton(
    label: String,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    icon: String? = null,
    on: Boolean = false
) {
    val shape = RoundedCornerShape(12.dp)
    val bg = when {
        !enabled -> Brush.verticalGradient(listOf(Color(0x0AFFFFFF), Color(0x06FFFFFF)))
        primary -> As.latBrush
        on -> Brush.verticalGradient(listOf(Color(0x33145A8C), Color(0x1A0E2A44)))
        else -> Brush.verticalGradient(listOf(Color(0x0DFFFFFF), Color(0x05FFFFFF)))
    }
    Box(
        modifier
            .height(As.MbtnH)
            .clip(shape)
            .background(bg)
            .border(
                BorderStroke(
                    1.dp,
                    if (on) As.Cy.copy(alpha = 0.75f)
                    else if (primary) As.Cy.copy(alpha = 0.45f)
                    else As.Brd
                ),
                shape
            )
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (icon != null) {
                Text(icon, fontSize = 15.sp, maxLines = 1)
                Spacer(Modifier.height(1.dp))
            }
            Text(
                label,
                color = when {
                    !enabled -> As.Faint
                    primary -> Color(0xFF03202B)
                    else -> As.Dim
                },
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 11.sp
            )
            if (badge != null && icon == null) {
                Text(badge, color = if (primary) Color(0xFF03202B) else As.Faint, fontSize = 8.sp, maxLines = 1)
            }
        }
    }
}
