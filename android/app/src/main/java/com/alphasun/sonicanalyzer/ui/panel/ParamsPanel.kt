package com.alphasun.sonicanalyzer.ui.panel

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphasun.sonicanalyzer.MainViewModel
import com.alphasun.sonicanalyzer.core.Classify
import com.alphasun.sonicanalyzer.core.Features
import com.alphasun.sonicanalyzer.ui.PanelShell
import com.alphasun.sonicanalyzer.ui.component.*
import com.alphasun.sonicanalyzer.ui.theme.As

/**
 * P2 声波参数面板 —— 23 项，对齐 Web v0.3.0：
 *   频谱域 7 项 + 时域/电平 9 项 + 音高/节奏 7 项。
 */
@Composable
fun ParamsPanel(
    st: MainViewModel.UiState,
    onClose: () -> Unit,
    onSelfCheck: () -> Unit = {}
) {
    PanelShell(
        title = "📊 声波参数",
        subtitle = "频谱域 7 项 · 时域电平 9 项 · 音高节奏 7 项 = 23 项实时特征",
        onClose = onClose,
        accent = As.Cy
    ) {
        // ============ 应用自检（任何状态下都可运行） ============
        GroupTitle("自检")
        SelfCheckCard(
            report = st.selfCheck,
            running = st.selfChecking,
            onRun = onSelfCheck
        )
        Spacer(Modifier.height(10.dp))

        val f = st.frame
        if (f == null) {
            HintLine("尚未采集到音频。点底部「开始采集」后本面板实时刷新 23 项参数。")
            return@PanelShell
        }

        // ============ 频谱域 7 项 ============
        GroupTitle("频谱域 · 7 项")
        AsCard {
            StatRow("频谱质心", "%.0f Hz".format(f.centroid), sub = centroidAdvice(f.centroid))
            StatRow("频谱扩散", "%.0f Hz".format(f.spread), sub = "衡量频率成分分散程度")
            StatRow("谱平坦度", "%.3f".format(f.flat), sub = flatAdvice(f.flat))
            StatRow("滚降频率 85%", "%.0f Hz".format(f.rolloff), sub = "85% 能量以下的频率上限")
            StatRow("频谱通量", "%.0f".format(f.flux), sub = "相邻帧能量上升量，测瞬态")
            StatRow("优势频率", "%.0f Hz".format(f.dominantHz), sub = f.dominantLabel)
            StatRow("频谱倾斜", "%+.2f dB/oct".format(f.tilt), sub = "负值=高频衰减型（自然声/语音）")
        }

        // ============ 时域/电平 9 项 ============
        GroupTitle("时域与电平 · 9 项")
        AsCard {
            StatRow("RMS 均方根", "%.4f".format(f.rms), sub = "有效值，恒能量指标")
            StatRow("峰值", "%.4f".format(f.peak))
            StatRow(
                "波峰因子", "%.2f dB".format(f.crest),
                vColor = crestColor(f.crest),
                sub = crestAdvice(f.crest)
            )
            StatRow("过零率", "%.4f".format(f.zcr), sub = "每秒过零次数，识别高频成分")
            StatRow("动态范围", "%.1f dB".format(f.dr), sub = "本段峰值与均值的差")
            StatRow("信噪比", "%.1f dB".format(f.snr), vColor = snrColor(f.snr), sub = snrAdvice(f.snr))
            StatRow("dBFS", "%.1f dB".format(f.dbfs), sub = "相对满量程，0 = 削顶")
            StatRow("dB(A) 计权", "%.1f dB".format(f.dbA), sub = "A 计权：模拟人耳灵敏度曲线")
            StatRow("dB(C) 计权", "%.1f dB".format(f.dbC), sub = "C 计权：平直，低频更突出")
        }

        // ============ 音高/节奏 7 项 ============
        GroupTitle("音高与节奏 · 7 项")
        AsCard {
            StatRow(
                "基频 F0", if (f.f0 > 20) "%.1f Hz".format(f.f0) else "未检出",
                vColor = if (f.f0 > 20) As.Gn else As.Faint,
                sub = if (f.f0 > 20) "自相关法，搜索范围 80~400Hz" else "可能是无调噪声或纯持续音"
            )
            StatRow("谐波度", "%.3f".format(f.harmonicity), sub = "越接近 1 越像乐音/语音")
            StatRow(
                "F0 稳定度", "%.1f%%".format(f.f0Span * 100),
                vColor = if (!f.wobble) As.Gn else As.Am,
                sub = if (f.wobble) "音高有明显抖动（颤音/滑音）" else "音高平稳"
            )
            StatRow(
                "节奏 BPM", if (f.bpm > 0) "%.0f".format(f.bpm) else "未检出",
                vColor = if (f.bpm > 0) As.Pu else As.Faint,
                sub = if (f.bpm > 0) "由能量包络自相关估计" else "无明显周期性节拍"
            )
            StatRow("六段能量", bandText(f.bands), sub = "30-150 / 150-400 / 400-1k / 1k-2.5k / 2.5k-6k / 6k-16k")
            StatRow("录音质量评分", "${qualityScore(f)} / 25", vColor = qualityColor(f))
            StatRow("舒适度", comfortText(f), vColor = comfortColor(f))
        }

        // 六段能量可视化
        Spacer(Modifier.height(6.dp))
        BandBars(f.bands, Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        WaveScope(st.spectrum, Modifier.fillMaxWidth().height(64.dp))

        // ============ 智能分类 ============
        GroupTitle("智能分类")
        val c = st.conf
        if (c != null) {
            AsCard {
                Text(
                    st.verdict,
                    color = As.Txt, fontSize = 14.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                ConfBar("人声 Voice", (c.voice * 100).toInt(), As.confVoice)
                ConfBar("音乐 Music", (c.music * 100).toInt(), As.confMusic)
                ConfBar("其他声音 Other", (c.other * 100).toInt(), As.confOther)
                ConfBar("噪音 Noise", (c.noise * 100).toInt(), As.confNoise)
                Spacer(Modifier.height(6.dp))
                if (c.voice >= 0.4) {
                    StatRow("人声细分", st.voiceDetail, sub = "由基频、谐波度、人声连续性判定")
                }
                if (c.other >= Classify.OTHER_MIN_CLS) {
                    StatRow("其他声音候选", st.otherTop, vColor = As.Am)
                }
            }
        }
    }
}

// ================= 辅助判定 =================

private fun centroidAdvice(c: Double): String = when {
    c < 300 -> "声音极低沉（男声/空调/车辆）"
    c < 800 -> "偏低沉（男声为主）"
    c < 1800 -> "中频突出（女声/乐器/环境）"
    c < 5000 -> "明亮（女声/清脆撞击）"
    else -> "高频尖锐（金属/鸟鸣/摩擦）"
}

private fun flatAdvice(f: Double): String = when {
    f > 0.55 -> "接近白噪声，能量分布很平"
    f > 0.30 -> "有一定噪声成分"
    f > 0.10 -> "以乐音/语音谐波为主"
    else -> "纯谐波，非常有调"
}

private fun crestAdvice(c: Double): String = when {
    c >= 14 -> "冲击性强（敲击/瞬态）"
    c >= 10 -> "有瞬态（鼓点/脚步）"
    c >= 6 -> "较平稳（持续声）"
    else -> "极平稳，接近稳态噪声"
}

private fun snrAdvice(s: Double): String = when {
    s.isNaN() -> "无法估计"
    s >= 20 -> "信噪比优秀"
    s >= 10 -> "信噪比良好"
    s >= 3 -> "信噪比一般，背景较明显"
    else -> "信噪比偏低，背景干扰强"
}

private fun bandText(b: FloatArray): String {
    if (b.isEmpty()) return "—"
    val i = b.indices.maxByOrNull { b[it] } ?: return "—"
    val names = listOf("30-150Hz", "150-400Hz", "400-1kHz", "1k-2.5kHz", "2.5k-6kHz", "6k-16kHz")
    return names.getOrElse(i) { "—" } + " 为主"
}

private fun qualityScore(f: Features.Frame): Int =
    Features.qualityScore(f.snr, f.dbfs, 0.0, false)

private fun qualityColor(f: Features.Frame) = when {
    qualityScore(f) >= 20 -> As.Gn
    qualityScore(f) >= 13 -> As.Am
    else -> As.Rd
}

private fun comfortText(f: Features.Frame): String {
    val (tone, label) = Features.comfort(f.rms, f.crest)
    return "$label（$tone）"
}

private fun comfortColor(f: Features.Frame) = when (Features.comfort(f.rms, f.crest).first) {
    "danger" -> As.Rd
    "warn" -> As.Am
    "ok" -> As.Gn
    else -> As.Cy
}

private fun crestColor(c: Double) = when {
    c >= 14 -> As.Am
    c >= 6 -> As.Txt
    else -> As.Dim
}

private fun snrColor(s: Double) = when {
    s.isNaN() -> As.Faint
    s >= 20 -> As.Gn
    s >= 10 -> As.Txt
    s >= 3 -> As.Am
    else -> As.Rd
}
