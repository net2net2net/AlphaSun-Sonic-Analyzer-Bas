package com.alphasun.sonicanalyzer.ui.panel

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphasun.sonicanalyzer.MainViewModel
import com.alphasun.sonicanalyzer.core.SeparationEngine
import com.alphasun.sonicanalyzer.ui.PanelShell
import com.alphasun.sonicanalyzer.ui.component.*
import com.alphasun.sonicanalyzer.ui.theme.As

/**
 * P4 前景/背景分离面板。
 *
 * 关键设计（对应用户要求「加强学习背景的效果展示、模型情况展示分析」）：
 *  1. 学习是**有过程的**（15 帧 ≈ 3 秒），显示进度条而不是"点一下就完成"
 *  2. 学完立刻给出**模型质量分析**：谱覆盖率、背景电平、平坦度、主频峰、平稳性
 *  3. 试听按钮用**当前模型**做谱减输出，试听的是"模型算出来的结果"，不是原声
 *  4. 离线分析给出**抑制率/残留比/前景可听性**的量化结论
 */
@Composable
fun SepPanel(
    st: MainViewModel.UiState,
    onLearn: () -> Unit,
    onReset: () -> Unit,
    onAnalyse: () -> Unit,
    onPreview: (String) -> Unit,
    onClose: () -> Unit
) {
    val s = st.sep
    val ready = s.state == SeparationEngine.State.READY

    PanelShell(
        title = "🎯 前景 / 背景分离",
        subtitle = "学习背景模型 → 展示模型质量 → 谱减分离 → 量化评估",
        onClose = onClose,
        accent = As.Pu
    ) {
        // ---------- 1. 学习控制 ----------
        AsCard {
            CardTitle("① 学习背景模型", CardTone.I3)
            Text(
                if (s.state == SeparationEngine.State.LEARNING)
                    "正在采集背景…请保持环境安静，不要有前景声源发声。"
                else "选择一段只有背景、没有前景声源的 3 秒来学习。",
                color = As.Dim, fontSize = 11.sp, lineHeight = 15.sp
            )
            Spacer(Modifier.height(8.dp))
            ProgressTrack(s.progress, color = As.Pu)
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    when (s.state) {
                        SeparationEngine.State.LEARNING -> "收敛 ${s.learnFrames}/${SeparationEngine.LEARN_FRAMES} 帧"
                        SeparationEngine.State.READY -> "已收敛 ${s.learnFrames} 帧"
                        else -> "未学习"
                    },
                    color = As.Faint, fontSize = 10.sp
                )
                AsBadge(
                    when (s.state) {
                        SeparationEngine.State.LEARNING -> "学习中"
                        SeparationEngine.State.READY -> "模型可用"
                        else -> "待机"
                    },
                    if (s.state == SeparationEngine.State.READY) BadgeTone.B2 else BadgeTone.B1
                )
            }
            Spacer(Modifier.height(9.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SnapButton(
                    if (s.state == SeparationEngine.State.LEARNING) "重新学习" else "开始学习",
                    onLearn, Modifier.weight(1f), enabled = !s.busy
                )
                SnapButton("重置模型", onReset, Modifier.weight(1f), enabled = !s.busy, tint = As.Dim)
            }
        }

        // ---------- 2. 模型质量展示 ----------
        if (ready || s.state == SeparationEngine.State.LEARNING) {
            Spacer(Modifier.height(4.dp))
            AsCard {
                CardTitle("② 背景模型质量", CardTone.I2)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatRow(
                        "谱覆盖率", "%.1f%%".format(s.cover * 100),
                        modifier = Modifier.weight(1f),
                        vColor = coverColor(s.cover)
                    )
                    StatRow(
                        "背景电平", if (s.noiseDb.isNaN()) "—" else "%.1f".format(s.noiseDb),
                        modifier = Modifier.weight(1f), vColor = As.Am
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatRow(
                        "谱平坦度", "%.3f".format(s.flat),
                        modifier = Modifier.weight(1f), vColor = flatColor(s.flat)
                    )
                    StatRow(
                        "主频峰", if (s.peakHz > 0) "%.0f Hz".format(s.peakHz) else "—",
                        modifier = Modifier.weight(1f)
                    )
                }
                StatRow(
                    "背景平稳性", "%.0f%%".format(s.stability * 100),
                    vColor = if (s.stability > 0.6) As.Gn else As.Am
                )
                Spacer(Modifier.height(5.dp))
                StatRow("谱特征", s.profileFeature, vColor = As.Cy)
                StatRow("主频峰位置", s.profilePeak)
                Text(
                    s.profileJudge,
                    color = As.Dim, fontSize = 11.sp, lineHeight = 15.sp
                )
                if (s.profile.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("学到的背景噪声谱", color = As.Faint, fontSize = 9.sp)
                    NoiseProfileCurve(s.profile, Modifier.fillMaxWidth().height(56.dp))
                }
            }
        }

        // ---------- 3. 实时前景/背景占比 ----------
        if (ready) {
            Spacer(Modifier.height(4.dp))
            AsCard {
                CardTitle("③ 实时前景/背景构成", CardTone.I1)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatRow("前景占比", pct(s.fgRatio), modifier = Modifier.weight(1f), vColor = As.Gn)
                    StatRow("背景占比", pct(1.0 - s.fgRatio), modifier = Modifier.weight(1f), vColor = As.Am)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatRow("前景电平", pct(s.fgLevel), modifier = Modifier.weight(1f), vColor = As.Gn)
                    StatRow("背景电平", pct(s.bgLevel), modifier = Modifier.weight(1f), vColor = As.Am)
                }
                ProgressTrack(
                    ((s.fgRatio.takeIf { !it.isNaN() } ?: 0.0).toFloat()),
                    color = As.Gn
                )
                HintLine("前景 = 扣除学到的背景后剩下的部分。占比高说明当前以人声/前景声源为主。")
            }
        }

        // ---------- 4. 试听 ----------
        if (ready) {
            Spacer(Modifier.height(4.dp))
            AsCard {
                CardTitle("④ 试听（使用当前背景模型）", CardTone.I4)
                Text(
                    "三段取自同一段实时流快照（最近 4 秒，不打断采集）\n" +
                        "· 前景：用当前背景模型谱减后剩下的部分\n" +
                        "· 背景：模型判定的背景成分\n" +
                        "· 原声：未分离的对照基准\n" +
                        "三段都已做响度归一化，可直接 A/B 对比听差异。",
                    color = As.Dim, fontSize = 11.sp, lineHeight = 15.sp
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SnapButton("试听前景", { onPreview("fg") }, Modifier.weight(1f), tint = As.Gn)
                    SnapButton("试听背景", { onPreview("bg") }, Modifier.weight(1f), tint = As.Am)
                    SnapButton("试听原声", { onPreview("mix") }, Modifier.weight(1f), tint = As.Dim)
                }
                Spacer(Modifier.height(7.dp))
                SnapButton(
                    if (s.busy) "正在录 6 秒…" else "离线分析 6 秒（给量化结论）",
                    onAnalyse, Modifier.fillMaxWidth(), enabled = !s.busy, tint = As.Pu
                )
            }
        }

        // ---------- 5. 量化分析结论 ----------
        val a = s.analysis
        if (a != null) {
            Spacer(Modifier.height(4.dp))
            AsCard {
                CardTitle("⑤ 分离质量分析", CardTone.I3)
                StatRow("背景能量抑制率", "%.1f%%".format(a.suppressPct * 100), vColor = supColor(a.suppressPct))
                StatRow("残留背景比", "%.1f%%".format(a.residualPct * 100))
                StatRow("前景可听性", a.hearability)
                StatRow("前景 RMS", "%.4f".format(a.fgRms), vColor = As.Gn)
                StatRow("背景 RMS", "%.4f".format(a.bgRms), vColor = As.Am)
                StatRow("谱减参数", a.gate, sub = "过抑制因子 / 底噪因子")
                Spacer(Modifier.height(4.dp))
                Text(a.verdict, color = As.Txt, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

private fun pct(v: Double): String = if (v.isNaN()) "—" else "%.0f%%".format(v * 100)

private fun coverColor(c: Double) = when {
    c >= SeparationEngine.COVER_TARGET -> As.Gn
    c > 0.5 -> As.Am
    else -> As.Rd
}

private fun flatColor(f: Double) = when {
    f > 0.4 -> As.Am      // 平坦 = 白噪声型背景
    f > 0.15 -> As.Cy
    else -> As.Gn          // 尖峰 = 有明确噪声源（空调/风）
}

private fun supColor(s: Double) = when {
    s > 0.6 -> As.Gn
    s > 0.3 -> As.Am
    else -> As.Rd
}
