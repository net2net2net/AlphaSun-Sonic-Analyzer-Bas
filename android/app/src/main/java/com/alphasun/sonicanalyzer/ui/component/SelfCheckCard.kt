package com.alphasun.sonicanalyzer.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphasun.sonicanalyzer.core.SelfCheck
import com.alphasun.sonicanalyzer.ui.noRippleClick
import com.alphasun.sonicanalyzer.ui.theme.As

/**
 * 应用内自检卡片（v0.6.2）
 *
 * 存在的理由：`功能不可用` 这类问题在开发环境里查不到（编译通过、单测也过），
 * 只有真机才暴露。与其让用户描述"好像没反应"，不如把「某一路到底有没有数据」
 * 变成手机上可一键执行、可整段复制的结果清单。
 *
 * 关键项是「主帧非空且有信号」——它是 v0.6.1 头号根因（立体声设备上主帧恒空）
 * 的直接验收点：只要这一项红，其余都不用看了。
 */
@Composable
fun SelfCheckCard(
    report: SelfCheck.Report?,
    running: Boolean,
    onRun: () -> Unit,
    modifier: Modifier = Modifier
) {
    AsCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "🩺 应用自检",
                color = As.Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            RunButton(
                text = if (running) "运行中…" else if (report == null) "运行自检" else "重新自检",
                enabled = !running,
                onClick = onRun
            )
        }
        Spacer(Modifier.height(6.dp))
        HintLine(
            if (report == null)
                "14 项离线算法 + 8 项真机探测。建议先点「开始采集」再自检，" +
                    "这样「主帧是否有信号」才有意义。"
            else report.title()
        )

        if (report != null) {
            Spacer(Modifier.height(8.dp))
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                var g = ""
                for (it in report.items) {
                    if (it.group != g) {
                        g = it.group
                        Spacer(Modifier.height(6.dp))
                        Text(g, color = As.Cy, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(3.dp))
                    }
                    CheckLine(it)
                }
            }
        }
    }
}

@Composable
private fun CheckLine(it: SelfCheck.Item) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            if (it.ok) "✅" else "❌",
            fontSize = 11.sp,
            modifier = Modifier.padding(end = 5.dp, top = 1.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(it.name, color = if (it.ok) As.Txt else As.Rd, fontSize = 11.5.sp)
            Text(
                it.detail,
                color = if (it.ok) As.Dim else As.Am,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 13.sp
            )
        }
    }
}

@Composable
private fun RunButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val bg = if (enabled) Color(0x1A37E0FF) else Color(0x0D8A9BC4)
    val bd = if (enabled) As.BrdHot else As.BrdSoft
    val fg = if (enabled) As.Cy else As.Faint
    Box(
        modifier = Modifier
            .border(1.dp, bd, RoundedCornerShape(As.RSq))
            .background(bg, RoundedCornerShape(As.RSq))
            .noRippleClick { if (enabled) onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}
