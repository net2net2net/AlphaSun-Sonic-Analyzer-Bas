package com.alphasun.sonicanalyzer.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphasun.sonicanalyzer.MainViewModel
import com.alphasun.sonicanalyzer.core.LocatorEngine
import com.alphasun.sonicanalyzer.ui.PanelShell
import com.alphasun.sonicanalyzer.ui.component.*
import com.alphasun.sonicanalyzer.ui.theme.As

/**
 * P5 声源定位面板。
 *
 * 重点不是"列出方法"，而是让用户**知道下一步该做什么**：
 *  · 状态卡实时显示通道数、孔径、相关峰、残差、有效时差对、置信度
 *  · 5 步操作指示，未达标的步骤标红并给出具体修复动作
 *  · 顶部一句话「下一步做什么」
 *  · 单声道自动降级为阵列仿真，点雷达图可放虚拟声源验证算法
 */
@Composable
fun LocPanel(
    st: MainViewModel.UiState,
    onCycleSpacing: () -> Unit,
    onToggleSim: () -> Unit,
    onToggleReverb: () -> Unit,
    onPlace: (Double, Double) -> Unit,
    onClose: () -> Unit
) {
    val l = st.loc
    val g = l.guidance
    val r = l.result

    PanelShell(
        title = "📡 声源定位",
        subtitle = "GCC-PHAT 时差 → TDOA 最小二乘 → 置信度与操作指示",
        onClose = onClose,
        accent = As.Gn
    ) {
        // ---------- 雷达图 ----------
        AsCard {
            CardTitle(
                if (l.simOn && st.channelCount < 2) "雷达图（阵列仿真）" else "雷达图（真实阵列）",
                CardTone.I2
            )
            RadarMap(
                azDeg = r?.azDeg,
                distM = r?.distM,
                conf = r?.conf ?: 0.0,
                chLevels = l.chLevels,
                micSpacing = l.spacing,
                onPick = if (l.simOn) { az, d -> onPlace(az, d) } else null,
                modifier = Modifier.fillMaxWidth().height(230.dp)
            )
            if (l.simOn && st.channelCount < 2) {
                HintLine("点击雷达图任意位置可放置虚拟声源，验证解算与置信度是否合理。")
            } else {
                HintLine("把声源放在正前方 0.5~8 m 内，角度越正置信度越高。")
            }
        }

        // ---------- 读数 ----------
        Spacer(Modifier.height(4.dp))
        AsCard {
            CardTitle("定位读数", CardTone.I1)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatRow("方位角", r?.azText() ?: "—", modifier = Modifier.weight(1f), vColor = As.Cy)
                StatRow("距离", r?.distText() ?: "—", modifier = Modifier.weight(1f), vColor = As.Gn)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatRow("置信度", r?.confText() ?: "—", modifier = Modifier.weight(1f), vColor = confColor(r?.conf ?: 0.0))
                StatRow("质量", r?.qualityText() ?: "—", modifier = Modifier.weight(1f), vColor = confColor(r?.conf ?: 0.0))
            }
            if (r?.residualUs != null) {
                StatRow("拟合残差", "%.1f μs".format(r.residualUs), vColor = if (r.residualUs < 300) As.Gn else As.Am)
            }
            if (r != null && r.usedPairs > 0) {
                StatRow("有效时差对", "${r.usedPairs} / ${r.totalPairs}", sub = r.tdoaText())
            }
            ConfBar("定位置信度", ((r?.conf ?: 0.0) * 100).toInt(), As.barBrush)
        }

        // ---------- 控制 ----------
        Spacer(Modifier.height(4.dp))
        AsCard {
            CardTitle("阵列控制", CardTone.I3)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SnapButton(
                    "阵列仿真 ${if (l.simOn) "开" else "关"}",
                    onToggleSim, Modifier.weight(1f),
                    tint = if (l.simOn) As.Gn else As.Dim
                )
                SnapButton("间距 ${"%.1f".format(l.spacing)} m", onCycleSpacing, Modifier.weight(1f), tint = As.Am)
                SnapButton(
                    "混响 ${if (l.reverb) "开" else "关"}",
                    onToggleReverb, Modifier.weight(1f), tint = if (l.reverb) As.Pu else As.Dim
                )
            }
            if (st.channelCount < 2) {
                HintLine(
                    "当前设备只提供 1 路麦克风输入，真实阵列定位无法工作。" +
                        "已自动启用阵列仿真，可先用模拟声源熟悉操作；" +
                        "真机双麦需硬件支持立体声采集。",
                    color = As.Am
                )
            } else {
                HintLine("已识别 ${st.channelCount} 路输入，使用真实阵列解算。")
            }
        }

        // ---------- 状态卡 ----------
        if (g != null) {
            Spacer(Modifier.height(4.dp))
            AsCard {
                CardTitle("状态卡", CardTone.I2)
                for (s in g.stats) {
                    StatRow(
                        s.label, s.value,
                        vColor = if (s.good) As.Gn else As.Am
                    )
                }
            }
        }

        // ---------- 5 步操作指示 ----------
        if (g != null) {
            Spacer(Modifier.height(4.dp))
            AsCard {
                CardTitle("操作指示", CardTone.I4)
                // 下一步高亮
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            androidx.compose.ui.graphics.Brush.horizontalGradient(
                                listOf(
                                    (if (g.nextIdx == -1) As.Gn else As.Am).copy(alpha = 0.16f),
                                    androidx.compose.ui.graphics.Color.Transparent
                                )
                            )
                        )
                        .padding(horizontal = 8.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("下一步", color = As.Am, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(7.dp))
                    Text(g.summary, color = As.Txt, fontSize = 11.sp, lineHeight = 15.sp)
                }
                Spacer(Modifier.height(7.dp))
                for (st5 in g.steps) {
                    StepRow(st5)
                }
            }
        }
    }
}

@Composable
private fun StepRow(s: LocatorEngine.Step) {
    val c = if (s.ok) As.Gn else As.Rd
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Box(
            Modifier
                .size(19.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                .background(c.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "${s.idx}",
                color = c, fontSize = 11.sp, fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.title, color = As.Txt, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(5.dp))
                Text(
                    if (s.ok) "✓" else "!",
                    color = c, fontSize = 11.sp, fontWeight = FontWeight.Black
                )
            }
            Text(s.desc, color = As.Dim, fontSize = 10.sp, lineHeight = 14.sp)
            Text("→ ${s.fix}", color = if (s.ok) As.Faint else As.Am, fontSize = 10.sp, lineHeight = 14.sp)
        }
    }
}

private fun confColor(c: Double) = when {
    c >= 0.6 -> As.Gn
    c >= 0.35 -> As.Am
    else -> As.Rd
}
