package com.alphasun.sonicanalyzer.ui.panel

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphasun.sonicanalyzer.MainViewModel
import com.alphasun.sonicanalyzer.core.NoiseEval
import com.alphasun.sonicanalyzer.ui.PanelShell
import com.alphasun.sonicanalyzer.ui.component.*
import com.alphasun.sonicanalyzer.ui.theme.As
import com.alphasun.sonicanalyzer.ui.theme.NzLevels

/**
 * P3 噪音评估面板：GB/T 3098 分级 + Leq/Lmax/Lmin + 频段 + 类型 + 历史曲线。
 */
@Composable
fun NoisePanel(
    st: MainViewModel.UiState,
    onReset: () -> Unit,
    onWindow: (Int) -> Unit,
    onCalib: (Int) -> Unit,
    onClose: () -> Unit
) {
    val n = st.noise
    val lv = NzLevels.of(if (n.cur > -900) n.cur else null)

    PanelShell(
        title = "🔊 噪音评估",
        subtitle = "GB/T 3098 分级 · Leq 等效声级 · 三频段分析 · 历史趋势",
        onClose = onClose,
        accent = As.Am
    ) {
        // ---------- 大表盘 ----------
        AsCard {
            CardTitle("声级表", CardTone.I4)
            AnalogMeter(n.cur, n.peakHold, Modifier.fillMaxWidth().height(112.dp))
            BigReading(if (n.cur > -900) n.cur else Double.NaN, "dB(A)")
            Spacer(Modifier.height(5.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                AsBadge(lv.t, when {
                    lv.d >= 85 -> BadgeTone.B4
                    lv.d >= 70 -> BadgeTone.B3
                    lv.d >= 55 -> BadgeTone.B2
                    else -> BadgeTone.B1
                })
                Text(n.level, color = As.Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
            }
        }

        // ---------- 统计量 ----------
        Spacer(Modifier.height(4.dp))
        AsCard {
            CardTitle("统计量", CardTone.I2)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatRow("Leq 等效", fmt(n.leq), modifier = Modifier.weight(1f), vColor = As.Cy)
                StatRow("Lmax 最大", fmt(n.lmax), modifier = Modifier.weight(1f), vColor = As.Rd)
                StatRow("Lmin 最小", fmt(n.lmin), modifier = Modifier.weight(1f), vColor = As.Gn)
            }
            StatRow("峰值保持", fmt(n.peakHold), vColor = As.Am)
            StatRow("频谱质心", "%.0f Hz".format(n.centroid), sub = centroidTxt(n.centroid))
            StatRow("平稳度", "%.0f%%".format(n.stability * 100), vColor = if (n.stability > 0.7) As.Gn else As.Am)
            StatRow("持续时长", "%.0f 秒".format(n.durSec))
        }

        // ---------- 柱状电平（点柱校准参考偏移） ----------
        Spacer(Modifier.height(4.dp))
        AsCard {
            CardTitle("柱状声级（点柱可快速校准）", CardTone.I1)
            NzLevelBars(
                db = n.cur,
                onPick = onCalib,
                modifier = Modifier.fillMaxWidth(),
                min = -20, max = 120, step = 5
            )
            HintLine("点某一根柱子即可把当前读数校准到该刻度（用于贴近实际分贝仪）。")
        }

        // ---------- 三频段 ----------
        Spacer(Modifier.height(4.dp))
        AsCard {
            CardTitle("三频段能量", CardTone.I3)
            ThreeBandBars(
                n.lo.toFloat(), n.mid.toFloat(), n.hi.toFloat(),
                Modifier.fillMaxWidth().height(52.dp)
            )
            StatRow("低频占比", "%.0f%%".format(n.lo * 100), vColor = As.Am)
            StatRow("中频占比", "%.0f%%".format(n.mid * 100), vColor = As.Cy)
            StatRow("高频占比", "%.0f%%".format(n.hi * 100), vColor = As.Pu)
        }

        // ---------- 历史曲线 ----------
        Spacer(Modifier.height(4.dp))
        AsCard {
            CardTitle("历史趋势", CardTone.I2)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (w in NoiseEval.CURVE_WINDOWS) {
                    SnapButton(
                        if (w >= 60) "${w / 60}分" else "${w}秒",
                        { onWindow(w) },
                        Modifier.weight(1f),
                        enabled = n.windowSec != w,
                        tint = if (n.windowSec == w) As.Cy else As.Dim
                    )
                }
            }
            Spacer(Modifier.height(7.dp))
            DbHistoryCurve(n.curve, Modifier.fillMaxWidth().height(78.dp))
        }

        // ---------- 结论 ----------
        Spacer(Modifier.height(4.dp))
        AsCard {
            CardTitle("评估结论", CardTone.I4)
            StatRow("噪音类型", n.type, vColor = As.Am)
            Text(n.advice, color = As.Txt, fontSize = 12.sp, lineHeight = 17.sp)
            Spacer(Modifier.height(6.dp))
            SnapButton("清空重新统计", onReset, Modifier.fillMaxWidth(), tint = As.Dim)
        }

        HintLine("声级为相对满量程的未校准数字电平，仅作趋势参考，不作合规判定依据。")
    }
}

private fun fmt(v: Double): String = if (v.isNaN() || v < -900) "—" else "%.1f".format(v)

private fun centroidTxt(c: Double): String = when {
    c < 300 -> "低频主导（空调/车辆/风声）"
    c < 1200 -> "中低频（机械/说话）"
    c < 4000 -> "中频（人声/环境）"
    else -> "高频（摩擦/鸟鸣/电子）"
}
