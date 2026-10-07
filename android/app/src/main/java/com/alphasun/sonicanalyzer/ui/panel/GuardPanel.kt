package com.alphasun.sonicanalyzer.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alphasun.sonicanalyzer.MainViewModel
import com.alphasun.sonicanalyzer.core.GuardEngine
import com.alphasun.sonicanalyzer.ui.PanelShell
import com.alphasun.sonicanalyzer.ui.SliderRow
import com.alphasun.sonicanalyzer.ui.ToggleRow
import com.alphasun.sonicanalyzer.ui.component.*
import com.alphasun.sonicanalyzer.ui.theme.As

/**
 * P6 声波警戒面板 —— 全屏值守台（对齐 v0.3.0 的 alertMask）。
 *
 * 结构逐行对齐 web-v0.3.1/index.html 的 #alertMask：
 *  · 手动阈值 dB / 静默回落(秒)
 *  · 自动评估本底 + 评估秒 + 预警余量 + 告警余量
 *  · 屏幕闪烁 / 提示音 / 事件最长(秒)
 *  · 摄像头：抓拍 / 录像 / 录(秒) / 前后双摄 / 预警也抓拍 / 识别前后摄
 *  · 开始值守 / 停止 / 重新评估本底 / 暂停 / 测试抓拍 / 告警推送设置
 *  · 当前 dB + 状态
 *  · 告警推送设置（企微/钉钉/飞书/Server酱/Webhook/阿里云短信）
 *  · 事件 / 值守日志 双标签 + 导出 / 清空
 *
 * 原生以全屏覆盖呈现（PanelShell full=true），所有判定口径沿用 GuardEngine（移植自 GUARD+alTick）。
 */
@Composable
fun GuardPanel(
    st: MainViewModel.UiState,
    g: GuardEngine,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onReEval: () -> Unit,
    onTogglePause: () -> Unit,
    onClear: () -> Unit,
    onTestShot: () -> Unit,
    onExportLog: () -> Unit,
    onClearLog: () -> Unit,
    onProbeCam: () -> Unit,
    onSaveNotify: () -> Unit,
    onTestNotify: () -> Unit,
    camNote: String,
    onClose: () -> Unit
) {
    val s = st.guard
    var showNotify by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) }   // 0=事件 1=日志

    PanelShell(
        title = "🛡️ 声波警戒",
        subtitle = "本底评估 → 黄色预警 / 红色告警 → 前后摄抓拍 + 事件音频",
        onClose = onClose,
        accent = if (s.level == GuardEngine.Level.ALARM) As.Rd
        else if (s.level == GuardEngine.Level.WARN) As.Am else As.Cy,
        full = true
    ) {
        // ---------- 状态灯 ----------
        AsCard {
            CardTitle(
                when (s.level) {
                    GuardEngine.Level.ALARM -> "值守中 · 红色告警"
                    GuardEngine.Level.WARN -> "值守中 · 黄色预警"
                    else -> if (s.enabled) "值守中 · 正常" else "未值守"
                },
                when (s.level) {
                    GuardEngine.Level.ALARM -> CardTone.I5
                    GuardEngine.Level.WARN -> CardTone.I4
                    else -> CardTone.I2
                }
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatRow("当前", if (s.cur <= -900) "--" else "%.1f".format(s.cur), modifier = Modifier.weight(1f), vColor = lvlColor(s.level))
                StatRow("本底", "%.1f".format(s.floor), modifier = Modifier.weight(1f), vColor = As.Am)
                StatRow("会话峰值", "%.1f".format(s.peak), modifier = Modifier.weight(1f), vColor = As.Rd)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatRow("预警阈值", "%.0f dB".format(s.thrWarn), modifier = Modifier.weight(1f), vColor = As.Am)
                StatRow("告警阈值", "%.0f dB".format(s.thrAlarm), modifier = Modifier.weight(1f), vColor = As.Rd)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Lamp("正常 ≤${s.thrWarn.toInt()}", s.level == GuardEngine.Level.NORMAL, As.Gn, Modifier.weight(1f))
                Lamp("预警 ${s.thrWarn.toInt()}", s.level == GuardEngine.Level.WARN, As.Am, Modifier.weight(1f))
                Lamp("告警 ${s.thrAlarm.toInt()}", s.level == GuardEngine.Level.ALARM, As.Rd, Modifier.weight(1f))
            }
            if (s.wave.isNotEmpty()) {
                Spacer(Modifier.height(7.dp))
                GuardWave(s.wave, s.level, Modifier.fillMaxWidth().height(38.dp))
            }
            if (s.stateText == "本底评估中") {
                Spacer(Modifier.height(7.dp))
                ProgressTrack(s.evalProgress, color = As.Am)
                Text("评估中 ${s.evalFrames} 帧…", color = As.Am, fontSize = 10.sp)
            }
        }

        // ---------- 阈值与回落（对齐 alertMask toolrow 1 & 2） ----------
        Spacer(Modifier.height(4.dp))
        AsCard {
            CardTitle("阈值与回落", CardTone.I1)
            SliderRow("手动阈值 dB", "%.0f".format(g.manualThr), -60.0, 0.0, 1.0, initial = g.manualThr) {
                g.manualThr = it; if (!g.enabled) onStart()
            }
            SliderRow("静默回落(秒)", "%.0f".format(g.relHoldSec), 0.0, 600.0, 1.0, initial = g.relHoldSec) { g.relHoldSec = it }
            ToggleRow(
                "自动评估本底", g.auto,
                hint = "开启后按本底 + 余量自动算阈值；关闭则用手动阈值"
            ) { v -> g.auto = v; if (v) onReEval() }
            if (g.auto) {
                SliderRow("评估时长", "${g.evalSec} 秒", 2.0, 60.0, 1.0, initial = g.evalSec.toDouble()) { g.evalSec = it.toInt() }
                SliderRow("预警余量", "%+.0f dB".format(g.warnMargin), 3.0, 20.0, 1.0, initial = g.warnMargin) { g.warnMargin = it }
                SliderRow("告警余量", "%+.0f dB".format(g.alarmMargin), 6.0, 40.0, 1.0, initial = g.alarmMargin) { g.alarmMargin = it }
            }
        }

        // ---------- 值守选项（对齐 alertMask toolrow 3：屏幕闪烁/提示音/事件最长） ----------
        Spacer(Modifier.height(4.dp))
        AsCard {
            CardTitle("值守选项", CardTone.I1)
            ToggleRow("屏幕闪烁", g.flashOnAlarm) { g.flashOnAlarm = it }
            ToggleRow("提示音 + 振动", g.beepOnAlarm) { g.beepOnAlarm = it }
            SliderRow("事件最长(秒)", "${g.maxEvSec.toInt()} 秒", 5.0, 600.0, 5.0, initial = g.maxEvSec) { g.maxEvSec = it }
            HintLine("全屏值守台：原生默认开启（对齐 v0.3.0 的 alertMask）")
        }

        // ---------- 摄像头（对齐 alertMask toolrow 4：抓拍/录像/录N秒/前后双摄/预警也抓拍） ----------
        Spacer(Modifier.height(4.dp))
        AsCard {
            CardTitle("摄像头", CardTone.I1)
            ToggleRow("抓拍（告警时启用摄像头）", g.capturePhoto) { g.capturePhoto = it }
            ToggleRow("录像", g.captureVideo) { g.captureVideo = it }
            if (g.captureVideo) {
                SliderRow("录(秒)", "${g.camRecSec.toInt()} 秒", 3.0, 120.0, 1.0, initial = g.camRecSec) { g.camRecSec = it }
            }
            ToggleRow("前后双摄", g.dualCamera, hint = "关闭则只抓后置") { g.dualCamera = it }
            ToggleRow("预警也抓拍", g.warnCamToo, hint = "黄色预警同样留下图像记录") { g.warnCamToo = it }
            Spacer(Modifier.height(5.dp))
            Row(Modifier.fillMaxWidth()) {
                SnapButton("识别前后摄", onProbeCam, Modifier.fillMaxWidth(), tint = As.Cy)
            }
            Text("摄像头：$camNote", color = As.Faint, fontSize = 9.sp, lineHeight = 13.sp)
        }

        // ---------- 控制（对齐 alertMask toolrow 5：开始/停止/告警推送设置 + 状态 + 当前） ----------
        Spacer(Modifier.height(4.dp))
        AsCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                if (!s.enabled) {
                    SnapButton("开始值守", onStart, Modifier.weight(1f), tint = As.Gn)
                } else {
                    SnapButton("重新评估本底", onReEval, Modifier.weight(1f), tint = As.Am)
                    SnapButton(
                        if (s.paused) "恢复" else "暂停",
                        onTogglePause, Modifier.weight(1f),
                        tint = if (s.paused) As.Gn else As.Dim
                    )
                    SnapButton("停止", onStop, Modifier.weight(1f), tint = As.Rd)
                }
            }
            Spacer(Modifier.height(7.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                SnapButton("测试抓拍", onTestShot, Modifier.weight(1f), tint = As.Pu)
                SnapButton("告警推送设置", { showNotify = !showNotify }, Modifier.weight(1f), tint = As.Cy)
            }
            Spacer(Modifier.height(7.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(As.RSm)).background(Color(0x0AFFFFFF)).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "当前 " + if (s.cur <= -900) "--" else "%.1f".format(s.cur) + " dB",
                    color = lvlColor(s.level), fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold
                )
                Text("·", color = As.Faint, fontSize = 12.sp)
                Text(s.stateText, color = As.Dim, fontSize = 11.sp)
            }
        }

        // ---------- 告警推送设置（对齐 alertMask alertNotifyCfg） ----------
        if (showNotify) {
            Spacer(Modifier.height(4.dp))
            AsCard {
                CardTitle("告警推送设置", CardTone.I1)
                NotifyField("企业微信机器人", g.ntWecom) { g.ntWecom = it }
                NotifyField("钉钉机器人", g.ntDing) { g.ntDing = it }
                NotifyField("飞书机器人", g.ntFeishu) { g.ntFeishu = it }
                NotifyField("Server酱 SendKey", g.ntSc) { g.ntSc = it }
                NotifyField("自定义 Webhook", g.ntHook) { g.ntHook = it }
                NotifyField("阿里云 AccessKeyId", g.ntAkId) { g.ntAkId = it }
                NotifyField("阿里云 AccessKeySecret", g.ntAkSec) { g.ntAkSec = it }
                NotifyField("阿里云短信签名", g.ntSign) { g.ntSign = it }
                NotifyField("阿里云模板 Code", g.ntTpl) { g.ntTpl = it }
                NotifyField("阿里云手机号", g.ntPhone) { g.ntPhone = it }
                Text("配置保存在本机内存；原生暂未实现网络发送（预留接口已接 onNotify）。", color = As.Faint, fontSize = 9.sp, lineHeight = 13.sp)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    SnapButton("保存推送配置", onSaveNotify, Modifier.weight(1f), tint = As.Gn)
                    SnapButton("发送测试", onTestNotify, Modifier.weight(1f), tint = As.Cy)
                }
            }
        }

        // ---------- 事件 / 日志（对齐 alertMask alertLog + 导出/清空） ----------
        Spacer(Modifier.height(4.dp))
        AsCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                TabBtn("事件 ${s.eventCount}", tab == 0, Modifier.weight(1f)) { tab = 0 }
                TabBtn("日志 ${s.logs.size}", tab == 1, Modifier.weight(1f)) { tab = 1 }
            }
            Spacer(Modifier.height(7.dp))
            if (tab == 0) {
                if (s.events.isEmpty()) {
                    HintLine("还没有记录到事件。黄色预警和红色告警都会记录在这里。")
                } else {
                    for (e in s.events) EventRow(e)
                    Spacer(Modifier.height(5.dp))
                    SnapButton("清空事件记录", onClear, Modifier.fillMaxWidth(), tint = As.Dim)
                }
            } else {
                if (s.logs.isEmpty()) {
                    HintLine("暂无日志。")
                } else {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        for ((t, k) in s.logs.asReversed()) {
                            Text(
                                t,
                                color = when (k) {
                                    2 -> As.Rd
                                    1 -> As.Am
                                    3 -> As.Cy
                                    else -> As.Dim
                                },
                                fontSize = 10.sp, lineHeight = 14.sp, fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    SnapButton("导出值守日志", onExportLog, Modifier.weight(1f), tint = As.Gn)
                    SnapButton("清空日志", onClearLog, Modifier.weight(1f), tint = As.Dim)
                }
            }
        }
    }
}

@Composable
private fun NotifyField(label: String, value: String, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = As.Dim, fontSize = 10.sp)
        Spacer(Modifier.height(2.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0x0EFFFFFF))
                .padding(horizontal = 8.dp, vertical = 7.dp),
            textStyle = TextStyle(color = As.Txt, fontSize = 11.sp),
            singleLine = true
        )
    }
}

@Composable
private fun Lamp(label: String, on: Boolean, c: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(As.RSm))
            .background(if (on) c.copy(alpha = 0.18f) else Color(0x0AFFFFFF))
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(9.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (on) c else Color(0x22FFFFFF))
        )
        Spacer(Modifier.height(3.dp))
        Text(label, color = if (on) c else As.Faint, fontSize = 9.sp, maxLines = 1)
    }
}

@Composable
private fun TabBtn(label: String, sel: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(As.RSm))
            .background(if (sel) As.Cy.copy(alpha = 0.14f) else Color(0x0AFFFFFF))
            .clickable { onClick() }
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (sel) As.Cy else As.Dim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun EventRow(e: GuardEngine.Event) {
    var open by remember { mutableStateOf(false) }
    val c = if (e.level == GuardEngine.Level.ALARM) As.Rd else As.Am
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(As.RSm))
            .background(Color(0x08FFFFFF))
            .clickable { open = !open }
            .padding(9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsBadge(
                e.level.text,
                if (e.level == GuardEngine.Level.ALARM) BadgeTone.B4 else BadgeTone.B3
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "#${e.id}  ${GuardEngine.fmtTime(e.tStartMs)}",
                color = As.Txt, fontSize = 10.sp, fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.weight(1f))
            Text(
                "%.1fs".format(e.durMs / 1000.0),
                color = c, fontSize = 11.sp, fontWeight = FontWeight.Black
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            "峰值 ${"%.1f".format(e.peak)} dB · 均值 ${"%.1f".format(e.avg)} dB · 超阈 ${"%.0f".format(e.overPct)}%",
            color = As.Dim, fontSize = 10.sp
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            MediaTag("前置图", e.frontPath != null, e.frontErr)
            MediaTag("后置图", e.backPath != null, e.backErr)
            MediaTag("音频", e.wavPath != null, null)
        }
        if (open) {
            Spacer(Modifier.height(5.dp))
            Text(
                e.analysis(-60.0, -30.0, -25.0),
                color = As.Faint, fontSize = 9.sp, lineHeight = 13.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
    Spacer(Modifier.height(5.dp))
}

@Composable
private fun MediaTag(label: String, ok: Boolean, err: String?) {
    val c = if (ok) As.Gn else As.Am
    Column(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(c.copy(alpha = 0.12f))
            .padding(horizontal = 5.dp, vertical = 3.dp)
    ) {
        Text(
            "$label ${if (ok) "✓" else "✗"}",
            color = c, fontSize = 9.sp, fontWeight = FontWeight.Bold
        )
        if (!ok && err != null) {
            Text(err, color = As.Faint, fontSize = 8.sp, maxLines = 2)
        }
    }
}

private fun lvlColor(l: GuardEngine.Level) = when (l) {
    GuardEngine.Level.ALARM -> As.Rd
    GuardEngine.Level.WARN -> As.Am
    else -> As.Gn
}
