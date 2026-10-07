package com.alphasun.sonicanalyzer.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphasun.sonicanalyzer.ui.theme.As
import com.alphasun.sonicanalyzer.ui.theme.NzLevels
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/* ============================================================
 * 可视化组件 —— 对应 Web 版 Canvas 绘制
 *   RoundSpectrum → 经典环谱（data-shape=round 极坐标）
 *   LinearSpectrum→ 频谱柱
 *   BandBars      → .bands .bb 六段能量柱
 *   MeterBar      → .meter 分段色带电平表
 *   NzLevelBars   → .nzBars 柱状电平（GB/T 3098 分级）
 *   DbHistoryCurve→ .nzHistCv 历史曲线
 *   ThreeBandBars → .nzBands 三频段能量
 *   RadarMap      → #locCv 声源定位雷达图
 *   WaveScope     → 示波器（时域波形）
 *   FgBgBars      → .fgbars 前景/背景谱对比
 *   NoiseProfileCurve → #fgProfileCv 学到的背景噪声谱
 *   AnalogMeter   → #nzCv 指针式声级表
 * ============================================================ */

/** 经典环谱：24 条径向频谱柱 + 中心波形圈（对应 Web 版 lg-bars 的 24 条）。 */
@Composable
fun RoundSpectrum(
    bars: FloatArray,
    active: Boolean,
    modifier: Modifier = Modifier,
    level: Float = 0f,
    onTap: (() -> Unit)? = null
) {
    val n = bars.size
    Canvas(
        modifier = modifier.then(
            if (onTap != null) Modifier.pointerInput(Unit) {
                detectTapGestures { onTap() }
            } else Modifier
        )
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val R = min(size.width, size.height) * 0.46f

        for (k in 1..4) {
            drawCircle(
                color = Color(0x141E96FF), radius = R * k / 4f,
                center = Offset(cx, cy), style = Stroke(width = 1f)
            )
        }
        drawLine(Color(0x0F1E96FF), Offset(cx - R, cy), Offset(cx + R, cy), 1f)
        drawLine(Color(0x0F1E96FF), Offset(cx, cy - R), Offset(cx, cy + R), 1f)

        if (n < 2) return@Canvas

        val M = 24
        val step = (n - 2).toFloat() / M
        for (m in 0 until M) {
            val i0 = (m * step).toInt().coerceIn(0, n - 1)
            val i1 = ((m + 1) * step).toInt().coerceIn(i0 + 1, n)
            var v = 0f
            for (i in i0 until i1) v = max(v, bars[i])
            val hgt = R * (0.10f + 0.80f * v.coerceIn(0f, 1f))
            val a = (-PI / 2 + 2 * PI * m / M).toFloat()
            val t = m.toFloat() / M
            val c = Color(
                red = 0.22f + 0.62f * t,
                green = 0.88f - 0.30f * t,
                blue = 1.0f - 0.42f * t,
                alpha = if (active) 0.30f + 0.65f * v.coerceIn(0f, 1f) else 0.16f
            )
            drawLine(
                color = c,
                start = Offset(cx + cos(a) * R * 0.16f, cy + sin(a) * R * 0.16f),
                end = Offset(cx + cos(a) * (R * 0.16f + hgt), cy + sin(a) * (R * 0.16f + hgt)),
                strokeWidth = 5f, cap = StrokeCap.Round
            )
        }

        drawCircle(
            color = Color(0x2E7DE2FF), radius = R * 0.15f,
            center = Offset(cx, cy), style = Stroke(width = 1.6f)
        )
        if (active && level > 0.002f) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(As.Cy.copy(alpha = 0.30f * level.coerceIn(0f, 1f)), Color.Transparent)
                ),
                radius = R * 0.15f, center = Offset(cx, cy)
            )
        }
        drawCircle(color = Color(0xFFE0FAFF), radius = R * 0.055f, center = Offset(cx, cy))
    }
}

/**
 * 频谱柱状图（线性）。对数频率轴：40Hz~16kHz 分 barsN 段。
 * binHz 由 halfBins 与 24kHz 带宽反推（原生 fftSize=2048、sr=48k → binHz≈23.4）。
 */
@Composable
fun LinearSpectrum(
    bars: FloatArray,
    modifier: Modifier = Modifier,
    barsN: Int = 72,
    binHz: Double = 24000.0 / 1025
) {
    val n = bars.size
    Canvas(modifier) {
        if (n < 2) return@Canvas
        val w = size.width; val h = size.height
        for (k in 1..4) drawLine(Color(0x141E96FF), Offset(0f, h * k / 5f), Offset(w, h * k / 5f), 1f)
        val fMin = 40.0; val fMax = 16000.0
        val bw = w / barsN
        for (m in 0 until barsN) {
            val f0 = fMin * Math.pow(fMax / fMin, m.toDouble() / barsN)
            val f1 = fMin * Math.pow(fMax / fMin, (m + 1.0) / barsN)
            var v = 0f
            var c = 0
            var i = (ln(f0) / ln(2.0) / binHz).toInt().coerceIn(0, n - 1)
            val iEnd = (ln(f1) / ln(2.0) / binHz).toInt().coerceIn(i + 1, n)
            while (i < iEnd && i < n) { v = max(v, bars[i]); c++; i++ }
            val bh = if (c == 0) 0f else v.coerceIn(0f, 1f) * h
            drawRect(
                brush = Brush.verticalGradient(listOf(As.Cy, As.Pu)),
                alpha = 0.32f + 0.62f * v.coerceIn(0f, 1f),
                topLeft = Offset(m * bw, h - bh),
                size = Size(bw - 1f, max(1f, bh))
            )
        }
    }
}

/** 六段能量柱：.bands .bb + .bl 标签 */
@Composable
fun BandBars(bands: FloatArray, modifier: Modifier = Modifier, height: Dp = 42.dp) {
    val labels = listOf("30-150", "150-400", "400-1k", "1k-2.5k", "2.5k-6k", "6k-16k")
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().height(height),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            for (i in 0 until 6) {
                val v = bands.getOrElse(i) { 0f }.coerceIn(0f, 1f)
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .background(
                            Brush.verticalGradient(listOf(As.Cy, As.Pu)),
                            RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)
                        )
                        .fillMaxHeight(v.coerceAtLeast(0.02f))
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (l in labels) Text(l, color = As.Faint, fontSize = 8.sp)
        }
    }
}

/** 分段色带电平表（.meter + .mscale）。pct 0..100 */
@Composable
fun MeterBar(pct: Float, peakPct: Float, modifier: Modifier = Modifier) {
    Column(modifier) {
        Box(
            Modifier.fillMaxWidth().height(9.dp)
                .background(Color(0x1AFFFFFF), RoundedCornerShape(5.dp))
        ) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth((pct / 100f).coerceIn(0f, 1f))
                    .background(
                        Brush.horizontalGradient(listOf(As.Gn, As.Cy, Color(0xFFA78BFA), As.Am, As.Rd))
                    )
            )
            Box(Modifier.fillMaxWidth((peakPct / 100f).coerceIn(0f, 1f))) {
                Box(
                    Modifier.align(Alignment.CenterEnd)
                        .size(width = 2.dp, height = 9.dp)
                        .background(Color.White)
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (s in listOf("-60", "-40", "-20", "-10", "0")) {
                Text(s, color = As.Faint, fontSize = 8.sp)
            }
        }
    }
}

/** 噪音柱状电平（.nzBars）：点柱可快速校准参考偏移。 */
@Composable
fun NzLevelBars(
    db: Double,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    min: Int = -20,
    max: Int = 120,
    step: Int = 5
) {
    val n = (max - min) / step + 1
    Column(
        modifier.pointerInput(Unit) {
            detectTapGestures { off ->
                val w = size.width.toFloat()
                if (w <= 0f) return@detectTapGestures
                val i = ((off.x / w) * n).toInt().coerceIn(0, n - 1)
                onPick(min + i * step)
            }
        }
    ) {
        Row(
            Modifier.fillMaxWidth().height(56.dp),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            for (i in 0 until n) {
                val d = min + i * step
                val lv = NzLevels.of(d.toDouble())
                val lit = db >= d
                Box(
                    Modifier.weight(1f).fillMaxHeight(if (lit) 1f else 0.18f)
                        .background(
                            Brush.verticalGradient(listOf(lv.c.copy(alpha = 0.6f), lv.c)),
                            RoundedCornerShape(topStart = 1.dp, topEnd = 1.dp)
                        )
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (s in listOf("-20", "0", "20", "40", "60", "80", "100", "120")) {
                Text(s, color = As.Faint, fontSize = 8.sp)
            }
        }
    }
}

/** dB 历史曲线（.nzHistCv）。points 为时间升序的 dB 序列。 */
@Composable
fun DbHistoryCurve(
    points: FloatArray,
    modifier: Modifier = Modifier,
    dbMin: Float = 0f,
    dbMax: Float = 120f
) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        for (k in 0..4) {
            val y = h * k / 4f
            drawLine(Color(0x141E96FF), Offset(0f, y), Offset(w, y), 1f)
        }
        if (points.size < 2) return@Canvas
        val path = Path()
        val span = (dbMax - dbMin).coerceAtLeast(1f)
        for (i in points.indices) {
            val x = w * i / (points.size - 1f)
            val y = h * (1f - ((points[i] - dbMin) / span).coerceIn(0f, 1f))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val fill = Path().apply {
            addPath(path); lineTo(w, h); lineTo(0f, h); close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(As.Cy.copy(alpha = 0.28f), Color.Transparent)))
        drawPath(path, As.Cy, style = Stroke(width = 2f))
    }
}

/** 三频段能量（低<250 / 中250~2k / 高>2k），对应 .nzBands */
@Composable
fun ThreeBandBars(lo: Float, mid: Float, hi: Float, modifier: Modifier = Modifier) {
    val mx = max(lo, max(mid, hi)).coerceAtLeast(1e-6f)
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().height(38.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            listOf(
                Triple("低 <250Hz", lo, As.Am),
                Triple("中 250~2k", mid, As.Cy),
                Triple("高 >2kHz", hi, As.Pu)
            ).forEach { (lb, v, c) ->
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier.fillMaxWidth().fillMaxHeight((v / mx).coerceIn(0.02f, 1f))
                            .background(
                                Brush.verticalGradient(listOf(c.copy(alpha = 0.85f), c.copy(alpha = 0.35f))),
                                RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)
                            )
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(lb, color = As.Faint, fontSize = 8.sp)
                }
            }
        }
    }
}

/** 声源定位雷达图（#locCv）：设备在下中，两麦在底部左右，声源点 + 置信环。 */
@Composable
fun RadarMap(
    azDeg: Double?,
    distM: Double?,
    conf: Double,
    chLevels: List<Float>,
    micSpacing: Double,
    onPick: ((Double, Double) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier.then(
            if (onPick != null) Modifier.pointerInput(Unit) {
                detectTapGestures { off ->
                    val cx = size.width / 2f
                    val cy = size.height * 0.86f
                    val R = min(size.width / 2f, size.height * 0.80f) * 0.92f
                    val dx = off.x - cx
                    val dy = cy - off.y
                    if (dy <= 4f) return@detectTapGestures
                    val r = (hypot(dx, dy) / R).coerceIn(0.05f, 1f)
                    val az = Math.toDegrees(atan2(dx.toDouble(), dy.toDouble()))
                    onPick(az, r * 8.0)
                }
            } else Modifier
        )
    ) {
        val cx = size.width / 2f
        val cy = size.height * 0.86f
        val R = min(size.width / 2f, size.height * 0.80f) * 0.92f

        for (k in 1..4) {
            drawCircle(
                color = Color(0x1A37E0FF), radius = R * k / 4f,
                center = Offset(cx, cy), style = Stroke(width = 1f)
            )
        }
        for (deg in listOf(-60.0, -30.0, 0.0, 30.0, 60.0)) {
            val a = Math.toRadians(deg)
            drawLine(
                Color(0x1437E0FF), Offset(cx, cy),
                Offset(cx + sin(a).toFloat() * R, cy - cos(a).toFloat() * R), 1f
            )
        }
        val p = Path().apply {
            moveTo(cx, cy - 11f); lineTo(cx - 7f, cy + 3f); lineTo(cx + 7f, cy + 3f); close()
        }
        drawPath(p, As.Cy)

        val halfPx = (R * 0.22f) * (micSpacing / 2.0).toFloat()
        listOf(cx - halfPx, cx + halfPx).forEach { mx ->
            drawCircle(As.Am, 4.5f, Offset(mx, cy))
            drawCircle(As.Am.copy(alpha = 0.35f), 8f, Offset(mx, cy), style = Stroke(1.2f))
        }

        if (azDeg != null && distM != null) {
            val a = Math.toRadians(azDeg)
            val rr = R * (distM / 8.0).coerceIn(0.05, 1.0).toFloat()
            val px = cx + sin(a).toFloat() * rr
            val py = cy - cos(a).toFloat() * rr
            val cc = when {
                conf >= 0.6 -> As.Gn
                conf >= 0.35 -> As.Am
                else -> As.Rd
            }
            drawCircle(cc.copy(alpha = 0.20f), rr * 0.5f, Offset(px, py))
            drawCircle(cc, 6f, Offset(px, py))
            drawCircle(cc.copy(alpha = 0.7f), 11f, Offset(px, py), style = Stroke(1.6f))
        }

        chLevels.forEachIndexed { i, v ->
            val bx = cx + (i - (chLevels.size - 1) / 2f) * 13f
            val bh = size.height * 0.10f * v.coerceIn(0f, 1f)
            drawRect(
                Brush.verticalGradient(listOf(As.Gn, Color.Transparent)),
                topLeft = Offset(bx - 4f, size.height - bh),
                size = Size(8f, max(1f, bh))
            )
        }
    }
}

/** 示波器（时域波形）。 */
@Composable
fun WaveScope(samples: FloatArray, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        for (k in 1..4) drawLine(Color(0x141E96FF), Offset(0f, h * k / 5f), Offset(w, h * k / 5f), 1f)
        if (samples.size < 2) return@Canvas
        val path = Path()
        val step = max(1, samples.size / (w.toInt().coerceAtLeast(2)))
        var i = 0
        var first = true
        while (i < samples.size) {
            val x = w * i / samples.size
            val y = h * (0.5f - samples[i].coerceIn(-1f, 1f) * 0.47f)
            if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
            i += step
        }
        drawPath(path, As.Gn, style = Stroke(width = 1.8f))
    }
}

/** 前景 / 背景谱对比（.fgbars）。 */
@Composable
fun FgBgBars(fg: FloatArray, bg: FloatArray, modifier: Modifier = Modifier, barsN: Int = 48) {
    val n = min(fg.size, bg.size)
    Canvas(modifier) {
        if (n < 2) return@Canvas
        val w = size.width; val h = size.height
        val bw = w / barsN
        var mx = 1e-6f
        for (i in 0 until n) mx = max(mx, max(fg[i], bg[i]))
        for (m in 0 until barsN) {
            val i0 = m * n / barsN
            val i1 = max(i0 + 1, (m + 1) * n / barsN)
            var vf = 0f; var vb = 0f
            for (i in i0 until min(i1, n)) { vf = max(vf, fg[i]); vb = max(vb, bg[i]) }
            val hf = (vf / mx).coerceIn(0f, 1f) * h * 0.92f
            val hb = (vb / mx).coerceIn(0f, 1f) * h * 0.92f
            drawRect(
                As.Gn.copy(alpha = 0.75f),
                topLeft = Offset(m * bw, h - hf), size = Size(bw - 1f, max(1f, hf))
            )
            drawRect(
                Color(0xFF54618A).copy(alpha = 0.55f),
                topLeft = Offset(m * bw + bw * 0.42f, h - hb), size = Size(bw * 0.55f, max(1f, hb))
            )
        }
    }
}

/** 学到的背景噪声谱（#fgProfileCv）。 */
@Composable
fun NoiseProfileCurve(profile: FloatArray, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        for (k in 1..3) drawLine(Color(0x141E96FF), Offset(0f, h * k / 4f), Offset(w, h * k / 4f), 1f)
        val n = profile.size
        if (n < 2) return@Canvas
        var mx = 1e-9f
        for (v in profile) mx = max(mx, v)
        val path = Path()
        for (i in 0 until n) {
            val x = w * i / (n - 1f)
            val y = h * (1f - (profile[i] / mx).coerceIn(0f, 1f))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val fill = Path().apply { addPath(path); lineTo(w, h); lineTo(0f, h); close() }
        drawPath(fill, Brush.verticalGradient(listOf(As.Am.copy(alpha = 0.45f), Color.Transparent)))
        drawPath(path, As.Am, style = Stroke(width = 1.8f))
    }
}

/** 进度条（.fgbarwrap i）。 */
@Composable
fun ProgressTrack(pct: Float, modifier: Modifier = Modifier, color: Color = As.Cy) {
    Box(
        modifier.fillMaxWidth().height(5.dp)
            .background(Color(0x14FFFFFF), RoundedCornerShape(3.dp))
    ) {
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(pct.coerceIn(0f, 1f))
                .background(color, RoundedCornerShape(3.dp))
        )
    }
}

/** 指针式声级表（#nzCv）。0~120 dB 映射到半圆 180°~360°。 */
@Composable
fun AnalogMeter(db: Double, peakDb: Double, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height * 0.92f
        val R = min(size.width * 0.44f, size.height * 0.82f)
        val a0 = PI.toFloat()
        val a1 = (2 * PI).toFloat()
        // dB → 角度。名不能叫 val（Kotlin 关键字）
        val ang: (Double) -> Float = { v -> a0 + (v / 120.0).toFloat() * (a1 - a0) }

        var d = 0.0
        while (d < 120.0) {
            val lv = NzLevels.of(d)
            val sweep = Math.toDegrees((ang(d + 2.0) - ang(d)).toDouble()).toFloat() + 0.3f
            drawArc(
                color = lv.c.copy(alpha = 0.75f),
                startAngle = Math.toDegrees(ang(d).toDouble()).toFloat(),
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(cx - R, cy - R),
                size = Size(R * 2f, R * 2f),
                style = Stroke(width = 9f)
            )
            d += 2.0
        }
        for (k in 0..6) {
            val a = ang(k * 20.0)
            drawLine(
                Color(0x99C8DCFF),
                Offset(cx + cos(a) * R * 1.05f, cy + sin(a) * R * 1.05f),
                Offset(cx + cos(a) * R * 1.16f, cy + sin(a) * R * 1.16f), 1.4f
            )
        }
        val needle: (Double, Color, Float) -> Unit = { v, c, w ->
            val a = ang(v.coerceIn(0.0, 120.0))
            drawLine(
                c, Offset(cx, cy),
                Offset(cx + cos(a) * R * 0.88f, cy + sin(a) * R * 0.88f), w, cap = StrokeCap.Round
            )
        }
        if (peakDb > 0) needle(peakDb, As.Am.copy(alpha = 0.75f), 1.6f)
        if (db.isFinite() && db > 0) needle(db, As.Rd, 2.4f)
        drawCircle(As.Txt, 3.5f, Offset(cx, cy))
    }
}

/** 警戒滚动波形（对应 Web 的 alDrawWave）：颜色随等级变化。 */
@Composable
fun GuardWave(
    wave: FloatArray,
    level: com.alphasun.sonicanalyzer.core.GuardEngine.Level,
    modifier: Modifier = Modifier
) {
    val c = when (level) {
        com.alphasun.sonicanalyzer.core.GuardEngine.Level.ALARM -> As.Rd
        com.alphasun.sonicanalyzer.core.GuardEngine.Level.WARN -> As.Am
        else -> As.Cy
    }
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val mid = h / 2f
        drawLine(Color(0x141E96FF), Offset(0f, mid), Offset(w, mid), 1f)
        if (wave.size < 2) return@Canvas
        val path = Path()
        for (i in wave.indices) {
            val x = w * i / (wave.size - 1f)
            val y = mid - wave[i].coerceIn(-1f, 1f) * mid * 0.92f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val fill = Path().apply { addPath(path); lineTo(w, mid); lineTo(0f, mid); close() }
        drawPath(fill, Brush.verticalGradient(listOf(c.copy(alpha = 0.22f), Color.Transparent)))
        drawPath(path, c, style = Stroke(width = 1.6f))
    }
}

/** 分贝读数大字（.nzNum）。 */
@Composable
fun BigReading(db: Double, unit: String, modifier: Modifier = Modifier, color: Color = As.Cy) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(
            if (db <= -99 || !db.isFinite()) "--" else "%.1f".format(db),
            color = color, fontSize = 30.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.width(3.dp))
        Text(unit, color = As.Dim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
    }
}

/** 电平等级徽章（.noiseBadge）。 */
@Composable
fun LevelBadge(rms: Double, modifier: Modifier = Modifier) {
    val (txt, tone) = when {
        rms < 0.012 -> "静默" to BadgeTone.B1
        rms < 0.05 -> "安静" to BadgeTone.B1
        rms < 0.18 -> "正常交谈" to BadgeTone.B2
        rms < 0.45 -> "嘈杂/响亮" to BadgeTone.B3
        else -> "非常响亮" to BadgeTone.B4
    }
    AsBadge(txt, tone, modifier)
}

/** 通用小按钮（.snap）。 */
@Composable
fun SnapButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color? = null
) {
    val c = tint ?: As.Cy
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .background(c.copy(alpha = if (enabled) 0.12f else 0.05f), shape)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text, color = if (enabled) c else As.Faint,
            fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
        )
    }
}

/**
 * 【v0.6.0 新增】时域波形（对齐 v0.3.0 可视化列表中的波形模式）。
 * 中轴为 0，幅度按实际峰值自适应并限幅到 1.0，避免削顶后看不出波形。
 */
@Composable
fun WaveLine(wave: FloatArray, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (wave.size < 2) return@Canvas
        val w = size.width; val h = size.height
        val mid = h / 2f
        for (k in 1..3) {
            val y = h * k / 4f
            drawLine(Color(0x141E96FF), Offset(0f, y), Offset(w, y), 1f)
        }
        drawLine(As.Brd, Offset(0f, mid), Offset(w, mid), 1f)
        var pk = 1e-4f
        for (v in wave) { val a = abs(v); if (a > pk) pk = a }
        val sc = (1f / pk).coerceAtMost(1f / 0.02f)
        val dx = w / (wave.size - 1)
        var px = 0f; var py = mid - wave[0] * sc * mid * 0.92f
        for (i in 1 until wave.size) {
            val x = i * dx
            val y = mid - wave[i].coerceIn(-1f, 1f) * sc * mid * 0.92f
            drawLine(
                brush = Brush.horizontalGradient(listOf(As.Cy, As.Pu)),
                start = Offset(px, py), end = Offset(x, y),
                strokeWidth = 1.6f
            )
            px = x; py = y
        }
    }
}
