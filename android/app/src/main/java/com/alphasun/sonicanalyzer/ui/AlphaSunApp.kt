package com.alphasun.sonicanalyzer.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alphasun.sonicanalyzer.MainViewModel
import com.alphasun.sonicanalyzer.core.GuardEngine
import com.alphasun.sonicanalyzer.ui.component.*
import com.alphasun.sonicanalyzer.ui.panel.*
import com.alphasun.sonicanalyzer.ui.theme.As
import kotlinx.coroutines.delay

/**
 * v0.6.0 原生主界面 —— 视觉对齐 Web v0.3.0。
 *
 * ==================== v0.6.0 界面对齐说明 ====================
 * 用户反馈「界面和美观也没对齐 v0.3.0」。逐段比对 web-v0.3.1/index.html
 * 的真实 DOM/CSS 后，补齐了上一版**完全缺失**的 5 个标志性元素：
 *
 *  1. **DSP 处理链**（`.pipeline` / #plN0~#plN4）
 *     v0.3.0 有 5 节点竖排链路 + 流动连接线，采集时逐级点亮。
 *     上一版原生完全没有 → 视觉上"少了一大块"，这是不对齐的主因。
 *  2. **采集诊断行**：#micLamp 输入电平灯 + 舒适度灯 #cmfLamp（蓝/绿/黄/红四态）。
 *  3. **中央提示区**（#hint）：未采集时的大字操作指引。
 *  4. **状态与错误区分**：#status 元素在 v0.3.0 只显示错误与操作提示，
 *     正常状态（就绪/采集中）不进这里 —— 上一版把 statusText 混在一起显���。
 *  5. **故障可行动化**：权限缺失时显示「去授权」按钮（#startError 区），
 *     而不是让用户对着一句"启动失败"反复点「开始」。
 *
 * 布局顺序对齐 v0.3.0：品牌区 → 参数芯片行 → DSP 链 → 主可视化 →
 * 右侧卡片区 → 底部六按钮固定坞 → 五个功能弹层。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlphaSunApp(
    viewModel: MainViewModel,
    onRequestMicPermission: () -> Unit
) {
    val vm = viewModel
    val st by vm.state.collectAsStateWithLifecycle()
    var panel by remember { mutableStateOf(Panel.NONE) }
    var camNote by remember { mutableStateOf("未检测") }
    var showHelp by remember { mutableStateOf(false) }

    /**
     * v0.6.0：启动时序**全部交给 Activity**。
     *
     * Compose 侧不再发起权限请求、也不再调 start() ——
     * 因为权限框是异步的，Compose 侧无法知道用户何时点「允许」，
     * 任何 delay(400) 式的猜测都会在慢一点的机型上失败且无重试路径，
     * 表现为「功能不可用」。
     *
     * 现在：MainActivity.onCreate 决定是否弹框，授权回调 → vm.retryStart()。
     * 这里只保留摄像头能力探测这类纯查询。
     */
    LaunchedEffect(Unit) {
        camNote = vm.camCapsNote()
    }
    DisposableEffect(Unit) { onDispose { vm.stop() } }

    // toast 自动消失
    LaunchedEffect(st.toast) {
        if (st.toast != null) {
            delay(2400)
            vm.clearToast()
        }
    }
    // 摄像头能力异步探测（开一次即可）
    LaunchedEffect(panel) { if (panel == Panel.GUARD) camNote = vm.camCapsNote() }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = As.Cy,
            background = As.Bg0,
            surface = As.PanelSolid,
            onBackground = As.Txt
        )
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(As.pageBrush())
        ) {
            Column(Modifier.fillMaxSize()) {
                // ============ 顶部品牌 + 芯片行（对齐 v0.3.0 .toolbar） ============
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = As.PadH)
                        .padding(top = 8.dp, bottom = 5.dp)
                ) {
                    BrandRow(st.appVersion)
                    Spacer(Modifier.height(7.dp))
                    ChipRow(st)
                    Spacer(Modifier.height(6.dp))
                    // 【v0.6.0 新增】采集控制行（对齐 v0.3.0 #rowCtl）
                    CtlRow(st, vm)
                }

                // ============ DSP 处理链（v0.6.0 新增，对齐 #plBox） ============
                DspPipeline(st)

                // ============ 主可视化 ============
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = As.PadH)
                ) {
                    Spacer(Modifier.height(5.dp))
                    MainViz(st, vm)
                    Spacer(Modifier.height(As.Gap))
                    SideCards(st)
                    Spacer(Modifier.height(As.Gap))
                    // 【v0.6.0 新增】底部说明（对齐 v0.3.0 <footer>）
                    AsFooter(st.appVersion, onHelp = { showHelp = true })
                    Spacer(Modifier.height(As.Gap + 6.dp))
                }
            }

            // ============ 底部六按钮固定坞（对齐 .mbar） ============
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(As.dockBrush())
            ) {
                Column {
                    // 坞上信息条
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(As.DockInfoH)
                            .padding(horizontal = As.PadH),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (st.capturing) As.Gn else As.Faint)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            st.statusText,
                            color = if (st.lastError != null) As.Am else As.Dim,
                            fontSize = 10.sp,
                            maxLines = 1, modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${st.sampleRate / 1000}kHz · ${st.channelCount}ch · FFT${st.fftSize}",
                            color = As.Faint, fontSize = 9.sp, fontFamily = FontFamily.Monospace
                        )
                    }
                    // 采集诊断行（v0.6.0 新增）：音源/降级信息，排查"不可用"的关键线索
                    if (st.capturing) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = As.PadH)
                                .padding(bottom = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                st.diag, color = As.Faint, fontSize = 8.5.sp,
                                maxLines = 1, modifier = Modifier.weight(1f)
                            )
                            if (st.loopErrors > 0) {
                                Text(
                                    "⚠ 异常${st.loopErrors}帧",
                                    color = As.Am, fontSize = 8.5.sp, fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                    // 权限/故障的可行动提示（v0.6.0 新增）
                    if (!st.capturing && st.needsMicPermission) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = As.PadH)
                                .padding(bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "未获得麦克风权限",
                                color = As.Am, fontSize = 9.5.sp,
                                maxLines = 1, modifier = Modifier.weight(1f)
                            )
                            Text(
                                "去授权",
                                color = As.Bg0, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(As.Am)
                                    .noRippleClick { onRequestMicPermission() }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                    // 六按钮
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = As.PadH, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // v0.3.0 #capBtn：图标 ▶/⏸ + 文案 开始采集/停止采集
                        DockButton(
                            if (st.capturing) "停止采集" else "开始采集",
                            icon = if (st.capturing) "⏸" else "▶",
                            primary = true, enabled = true,
                            onClick = { vm.toggleCapture() },
                            modifier = Modifier.weight(1.35f)
                        )
                        // v0.3.0 #mParamBtn 📊 声波参数
                        DockButton("声波参数", icon = "📊", enabled = st.capturing,
                            onClick = { panel = Panel.PARAMS }, on = panel == Panel.PARAMS,
                            modifier = Modifier.weight(1f))
                        // v0.3.0 #mFgBtn 🎯 前景/背景分离（<br> 两行）
                        DockButton("前景/\n背景分离", icon = "🎯", enabled = st.capturing,
                            onClick = { panel = Panel.SEP }, on = panel == Panel.SEP,
                            modifier = Modifier.weight(1f))
                        // v0.3.0 #mLocBtn 📡 声源定位
                        DockButton("声源定位", icon = "📡", enabled = st.capturing,
                            onClick = { panel = Panel.LOC }, on = panel == Panel.LOC,
                            modifier = Modifier.weight(1f))
                        // v0.3.0 #mGuardBtn 🛡 声波警戒
                        DockButton("声波警戒", icon = "🛡", enabled = st.capturing,
                            onClick = { panel = Panel.GUARD }, on = panel == Panel.GUARD,
                            modifier = Modifier.weight(1f))
                        // v0.3.0 #mNoiseBtn 🔊 噪音评估（<br> 两行）
                        DockButton("噪音\n评估", icon = "🔊", enabled = st.capturing,
                            onClick = { panel = Panel.NOISE }, on = panel == Panel.NOISE,
                            modifier = Modifier.weight(1f))
                    }
                }
            }

            // ============ 功能弹层 ============
            if (panel != Panel.NONE) {
                val full = panel == Panel.GUARD   // 警戒面板 = 全屏值守台（对齐 v0.3.0 alertMask）
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xCC03060E))
                        .noRippleClick { panel = Panel.NONE },
                    contentAlignment = if (full) Alignment.Center else Alignment.BottomCenter
                ) {
                    Box(
                        Modifier
                            .then(if (full) Modifier.fillMaxSize().padding(10.dp) else Modifier.fillMaxWidth())
                            .noRippleClick { /* 吃掉点击，避免穿透关闭 */ }
                    ) {
                        when (panel) {
                            Panel.PARAMS -> ParamsPanel(
                                st = st,
                                onClose = { panel = Panel.NONE },
                                onSelfCheck = { vm.runSelfCheck() }
                            )
                            Panel.SEP -> SepPanel(
                                st,
                                onLearn = { vm.sepStartLearn() },
                                onReset = { vm.sepReset() },
                                onAnalyse = { vm.sepAnalyseClip() },
                                onPreview = { vm.sepPreview(it) },
                                onClose = { panel = Panel.NONE }
                            )
                            Panel.LOC -> LocPanel(
                                st,
                                onCycleSpacing = { vm.locCycleSpacing() },
                                onToggleSim = { vm.locToggleSim() },
                                onToggleReverb = { vm.locToggleReverb() },
                                onPlace = { az, d -> vm.locPlaceSim(az, d) },
                                onClose = { panel = Panel.NONE }
                            )
                            Panel.GUARD -> GuardPanel(
                                st, vm.guard,
                                onStart = { vm.guardStart() },
                                onStop = { vm.guardStop() },
                                onReEval = { vm.guardReEval() },
                                onTogglePause = { vm.guardTogglePause() },
                                onClear = { vm.guardClearEvents() },
                                onTestShot = { vm.guardTestShot() },
                                onExportLog = { vm.guardExportLog() },
                                onClearLog = { vm.guardClearLog() },
                                onProbeCam = { camNote = vm.camCapsNote() },
                                onSaveNotify = { vm.guardSaveNotify() },
                                onTestNotify = { vm.guardTestNotify() },
                                camNote = camNote,
                                onClose = { panel = Panel.NONE }
                            )
                            Panel.NOISE -> NoisePanel(
                                st,
                                onReset = { vm.noiseReset() },
                                onWindow = { vm.noiseWindow(it) },
                                onCalib = { vm.calibrateRef(it.toDouble()) },
                                onClose = { panel = Panel.NONE }
                            )
                            Panel.NONE -> Unit
                        }
                    }
                }
            }

            // ============ 说明 / 免责声明弹层（对齐 v0.3.0 <footer> 的 helpMask） ============
            if (showHelp) {
                HelpOverlay(st.appVersion, onClose = { showHelp = false })
            }

            // ============ toast ============
            st.toast?.let { t ->
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 126.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xEE1A2740))
                        .border(BorderStroke(1.dp, As.Brd), RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                ) {
                    Text(t, color = As.Txt, fontSize = 12.sp)
                }
            }

            // ============ 告警全屏红闪 ============
            if (st.guard.flash) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0x33FF2D46))
                )
            }
        }
    }
}

// ================= DSP 处理链（v0.6.0 新增，对齐 v0.3.0 #plBox） =================
//
// Web v0.3.0 的 .pipeline 是横排 5 节点（移动端由竖排改横排）：
//   🎙️ 声波采集 → 🧹 信号预处理 → 📈 特征提取 → 🧠 分类解码 → 📤 后处理输出
// 采集运行时逐级点亮，连接线有流动动画。
// 原生版上一版**完全没有**这块，是"视觉没对齐"的最主要差异之一。

private data class PipeNode(val icon: String, val label: String, val tip: String)

private val PIPE_NODES = listOf(
    PipeNode("🎙", "声波采集", "麦克风 AudioRecord 原生 PCM 输入"),
    PipeNode("🧹", "信号预处理", "Hann 窗 · FFT · 归一化 · 平滑"),
    PipeNode("📈", "特征提取", "六段频带 / 平坦度 / F0 / 波峰因子"),
    PipeNode("🧠", "分类解码", "启发式规则 + 置信度融合"),
    PipeNode("📤", "后处理输出", "事件日志 · 分离/定位快照 · 概要")
)

@Composable
private fun DspPipeline(st: MainViewModel.UiState) {
    // 点亮判定：采集=1，特征=2，分类=3，其余按采集态点亮
    val on = if (st.capturing) 5 else 0
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = As.PadH)
            .padding(bottom = 5.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(Color(0x6B080C18))
            .border(BorderStroke(1.dp, As.BrdSoft), RoundedCornerShape(11.dp))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        PIPE_NODES.forEachIndexed { i, node ->
            val lit = i < on
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (lit) As.Cy.copy(alpha = 0.16f) else Color(0x66141C2C))
                        .border(
                            BorderStroke(1.dp, if (lit) As.Cy else As.Brd),
                            RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(node.icon, fontSize = 12.sp, color = if (lit) As.Txt else As.Faint)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    node.label,
                    color = if (lit) As.Cy else As.Faint,
                    fontSize = 9.sp,
                    maxLines = 1,
                    lineHeight = 11.sp
                )
            }
            if (i < PIPE_NODES.lastIndex) PipeLink(lit)
        }
    }
}

/** 链路连接线：点亮时有横向流动光点（对齐 v0.3.0 .pl-link.on 的 plflowX 动画） */
@Composable
private fun PipeLink(lit: Boolean) {
    val trans = rememberInfiniteTransition(label = "pipe")
    val x by trans.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (lit) 900 else 2200, easing = { it }),
            repeatMode = RepeatMode.Restart
        ),
        label = "flow"
    )
    Box(
        Modifier
            .width(14.dp)
            .height(2.dp)
            .background(As.Brd),
        contentAlignment = Alignment.Center
    ) {
        if (lit) {
            Box(
                Modifier
                    .offset(x = (-5 + x * 16).dp)
                    .size(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(As.Cy)
            )
        }
    }
}

// ================= 采集控制行（v0.6.0 新增，对齐 v0.3.0 #rowCtl） =================
//
// v0.3.0 顶栏第 2 行有三件采集前就要调的东西：
//   #vizSwitch（可视化 ◀ 名称 ▶）· #micSel（输入设备）· #sens（灵敏度 0.3~6×）
// 原生版上一版完全没有。设备选择在原生端不需要（AudioRecord 走系统默认路由，
// 切换路由会打断采集），但可视化切换与灵敏度必须补 —— 且灵敏度要真正作用到 DSP。

@Composable
private fun CtlRow(st: MainViewModel.UiState, vm: MainViewModel) {
    Column(Modifier.fillMaxWidth()) {
        // —— 可视化切换 ——
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            VizBtn("◀") { vm.cycleViz(-1) }
            Box(
                Modifier
                    .weight(1f)
                    .height(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x66141C2C))
                    .border(BorderStroke(1.dp, As.BrdSoft), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(st.vizName, color = As.Txt, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            VizBtn("▶") { vm.cycleViz(1) }
        }
        Spacer(Modifier.height(5.dp))
        // —— 灵敏度 ——
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text("🎚 灵敏度", color = As.Faint, fontSize = 9.5.sp)
            MiniSlider(
                value = st.inputGain,
                range = 0.3f..6.0f,
                steps = 56,
                active = st.capturing,
                onChange = { vm.setInputGain(it) },
                modifier = Modifier.weight(1f)
            )
            Text(
                "%.1f×".format(st.inputGain),
                color = As.Cy, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(34.dp)
            )
        }
    }
}

@Composable
private fun VizBtn(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x66141C2C))
            .border(BorderStroke(1.dp, As.BrdSoft), RoundedCornerShape(8.dp))
            .noRippleClick { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = As.Cy, fontSize = 12.sp)
    }
}

/** 紧凑滑块（对齐 Web #sens 的 range input）：分段式，点击即设定。 */
@Composable
private fun MiniSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    active: Boolean,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val n = steps + 1
    val span = range.endInclusive - range.start
    val idx = (((value - range.start) / span) * (n - 1)).toInt().coerceIn(0, n - 1)
    Row(modifier.height(22.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(n) { i ->
            val sel = i <= idx
            Box(
                Modifier
                    .weight(1f)
                    .height(if (sel) 5.dp else 3.dp)
                    .padding(horizontal = 0.5.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (sel && active) As.Cy else As.Brd)
                    .noRippleClick { onChange(range.start + span * i / (n - 1)) }
            )
        }
    }
}

// ================= 底部说明（对齐 v0.3.0 <footer>） =================

@Composable
private fun AsFooter(version: String, onHelp: () -> Unit) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // v0.3.0 <footer> 的 .fmeta 一行：
        // 「AlphaSun Sonic Analyzer v— · 作者 阳光 net2net2net（VX：net2net）· <技术栈>」
        Text(
            "AlphaSun Sonic Analyzer $version · 作者 阳光 net2net2net（VX：net2net）· 原生 Kotlin/Compose · Camera2 · 无 WebView",
            color = As.Faint, fontSize = 8.sp, lineHeight = 11.sp,
            modifier = Modifier.padding(horizontal = 6.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(3.dp))
        Text(
            "全部参数由真实麦克风数据驱动 · 定位需多通道输入（单声道自动切阵列仿真）",
            color = As.Faint.copy(alpha = 0.7f), fontSize = 8.sp, lineHeight = 11.sp,
            modifier = Modifier.padding(horizontal = 6.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(5.dp))
        // v0.3.0 <footer> 的「ⓘ 说明」按钮：打开使用指引 / 技术说明 / 免责声明
        Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .border(BorderStroke(1.dp, As.Brd), RoundedCornerShape(8.dp))
                .noRippleClick { onHelp() }
                .padding(horizontal = 12.dp, vertical = 5.dp)
        ) {
            Text("ⓘ 说明", color = As.Cy, fontSize = 9.5.sp, fontWeight = FontWeight.Medium)
        }
    }
}

// ================= 说明 / 免责声明弹层（对齐 v0.3.0 helpMask） =================

@Composable
private fun HelpOverlay(version: String, onClose: () -> Unit) {
    val lines = listOf(
        "当前版本 $version · 作者：阳光 net2net2net（VX：net2net）· 主要组件：原生 Kotlin / Compose / Camera2 / AudioRecord（无 WebView）。",
        "顶部链路图示：🎙️ 声波采集 → 🧹 信号预处理 → 📈 特征提取 → 🧠 分类解码 → 📤 后处理输出，采集运行时逐级点亮，说明每一条结论从哪一步来。",
        "全部计算本地完成（原生 AudioRecord + FFT），不上传音频。",
        "声纹登记 / 标注纠正样本仅存本机，绝不上传。",
        "内置判别模型为离线启发式、非训练模型：动物 / 交通工具 / 自然环境的识别结果是基于频谱形状（分段能量、质心、平坦度、通量、周期性）的启发式匹配，不是物种分类器，可能严重误判，仅供参考。",
        "曲目识别需联网且默认关闭，不输出臆造的歌名 / 歌手。",
        "语音转写依赖系统 SpeechRecognizer（需网络）。",
        "需在 Android 授权麦克风方可采集。"
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC03060E))
            .noRippleClick { onClose() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.verticalGradient(listOf(As.PanelSolid, As.Bg1)),
                    RoundedCornerShape(16.dp)
                )
                .border(BorderStroke(1.dp, As.Brd), RoundedCornerShape(16.dp))
                .noRippleClick { /* 吃掉点击，避免穿透关闭 */ }
                .padding(16.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "AlphaSun 声波分析仪 · 说明与免责声明",
                    color = As.Txt, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .noRippleClick { onClose() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("✕", color = As.Dim, fontSize = 15.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Column(Modifier.verticalScroll(rememberScrollState())) {
                lines.forEach { t ->
                    Text(
                        t, color = As.Dim, fontSize = 11.sp, lineHeight = 16.sp,
                        modifier = Modifier.padding(bottom = 9.dp)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(As.Brd.copy(alpha = 0.18f))
                    .noRippleClick { onClose() }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("关闭", color = As.Cy, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ================= 芯片行（对齐 #rowParams） =================

@Composable
private fun ChipRow(st: MainViewModel.UiState) {
    val db = st.envDb + st.refOffset
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        // #chipEngine：引擎状态
        AsChip("引擎", if (st.capturing) "运行" else "待机", Modifier.weight(1.15f), live = st.capturing)
        // #micV：输入电平（带指示灯）
        AsChip(
            "输入", if (db < -99) "--" else "%.1f".format(db),
            Modifier.weight(1.05f), live = st.capturing,
            lampColor = when {
                !st.capturing -> As.Faint
                db >= 85 -> As.Rd
                db >= 70 -> As.Am
                db >= 45 -> As.Gn
                else -> As.Cy
            }
        )
        // #cmfV：舒适度四态（蓝/绿/黄/红）
        AsChip(
            "舒适", when {
                !st.capturing -> "—"
                db >= 85 -> "高危"
                db >= 70 -> "不适"
                db >= 45 -> "正常"
                else -> "平静"
            },
            Modifier.weight(1f), live = st.capturing,
            lampColor = when {
                !st.capturing -> As.Faint
                db >= 85 -> As.Rd
                db >= 70 -> As.Am
                db >= 45 -> As.Gn
                else -> As.Cy
            }
        )
        // #srate / FFT
        AsChip("SR", "${st.sampleRate / 1000}k", Modifier.weight(0.72f))
        AsChip("FFT", "${st.fftSize}", Modifier.weight(0.72f))
    }
}

// ================= 主可视化（对齐 v0.3.0 .viz） =================

@Composable
private fun MainViz(st: MainViewModel.UiState, vm: MainViewModel) {
    AsCard {
        // 【v0.6.0 新增】读数浮层行：时钟 · BPM · 状态灯（对齐 .ov.clock / .ov.bpm / .ov.ver）
        VizOverlayRow(st)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // 左：环谱（仅环形模式显示）
            if (st.vizName == "经典环谱") {
                Box {
                    RoundSpectrum(
                        bars = st.bars,
                        active = st.capturing,
                        level = (st.meterPct / 100f).coerceIn(0f, 1f),
                        // 【v0.6.0 修复】界面文案写着"点击环谱中心可开始/停止"，
                        // 而上一版这里传的是空 lambda —— 点了毫无反应，
                        // 正是"功能不可用"最直观的表现之一。
                        onTap = { vm.toggleCapture() },
                        modifier = Modifier.size(126.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            // 右：读数 + 电平表（对齐 v0.3.0 .ov.db：dbBig / dBFS·电平 / meter / mscale）
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                if (st.envDb < -99) "--" else "%.1f".format(st.envDb + st.refOffset),
                                color = As.Cy, fontSize = 28.sp,
                                fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace
                            )
                            Text(
                                "dBFS · 电平", color = As.Dim, fontSize = 9.5.sp,
                                modifier = Modifier.padding(bottom = 5.dp, start = 4.dp)
                            )
                        }
                    }
                    LevelBadge(st.frame?.rms ?: 0.0)
                }
                Spacer(Modifier.height(3.dp))
                MeterBar(st.meterPct, st.peakPct, Modifier.fillMaxWidth())
                // .mscale：-60 / -40 / -20 / -10 / 0
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("-60", "-40", "-20", "-10", "0").forEach {
                        Text(it, color = As.Faint, fontSize = 7.5.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    when {
                        !st.capturing && st.needsMicPermission -> "等待麦克风授权 · 点击底部「去授权」"
                        !st.capturing && st.lastError != null -> "启动失败：${st.lastError}"
                        !st.capturing -> "点击底部「开始」启动采集"
                        st.loopErrors > 0 -> "运行中（已跳过 ${st.loopErrors} 帧异常，正在自愈）"
                        else -> "全部参数由真实麦克风数据驱动 · 点击环谱中心可开始/停止"
                    },
                    color = if (!st.capturing && (st.needsMicPermission || st.lastError != null)) As.Am else As.Faint,
                    fontSize = 8.5.sp,
                    lineHeight = 11.sp
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        // 主图形区：按 st.vizName 切换（对齐 Web setViz）
        when (st.vizName) {
            "线性频谱" -> LinearSpectrum(
                st.spectrum,
                Modifier.fillMaxWidth().height(92.dp),
                barsN = 72,
                binHz = st.sampleRate.toDouble() / (st.fftSize * 2)
            )
            "六段能量" -> BandBars(st.bands, Modifier.fillMaxWidth(), height = 92.dp)
            "波形时域" -> WaveLine(
                st.wave,
                Modifier.fillMaxWidth().height(92.dp)
            )
            else -> LinearSpectrum(
                st.spectrum,
                Modifier.fillMaxWidth().height(54.dp),
                barsN = 72,
                binHz = st.sampleRate.toDouble() / (st.fftSize * 2)
            )
        }
        if (st.vizName != "六段能量") {
            Spacer(Modifier.height(5.dp))
            BandBars(st.bands, Modifier.fillMaxWidth(), height = 32.dp)
        }
        // 【v0.6.1】中央提示区（对齐 v0.3.0 #hint）：未采集时的大字操作指引。
        // v0.3.0 的 .hint 是压在可视化画面**正中**的两行大字，
        // 上一版原生只有一行 8.5sp 的小灰字，视觉权重完全不对。
        if (!st.capturing) {
            Spacer(Modifier.height(7.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x66060A14))
                    .border(BorderStroke(1.dp, As.BrdSoft), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "点击底部「开始采集」或中央环开始",
                    color = As.Txt, fontSize = 12.5.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "动画与全部专业参数由真实麦克风数据驱动 · 点击画面中央可暂停/继续",
                    color = As.Faint, fontSize = 9.sp, lineHeight = 12.5.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

/**
 * 【v0.6.0 新增】读数浮层行，对齐 v0.3.0 可视化区顶部的三个浮层：
 *   .ov.clock（毫秒级时钟）· .ov.ver（状态灯）· .ov.bpm（节拍）
 */
@Composable
private fun VizOverlayRow(st: MainViewModel.UiState) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(37)   // ≈27fps，够毫秒级跳动且不耗电
        }
    }
    val cal = remember(now / 1000L) { java.util.Calendar.getInstance() }
    val hh = "%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY))
    val mm = "%02d".format(cal.get(java.util.Calendar.MINUTE))
    val ss = "%02d".format(cal.get(java.util.Calendar.SECOND))
    val ms = "%03d".format(cal.get(java.util.Calendar.MILLISECOND))

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        // 时钟
        Row(
            Modifier
                .weight(1.25f)
                .clip(RoundedCornerShape(7.dp))
                .background(Color(0x59060A14))
                .padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("$hh:$mm:$ss", color = As.Txt, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text(".$ms", color = As.Cy, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        }
        // 状态灯
        Row(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(7.dp))
                .background(Color(0x59060A14))
                .padding(horizontal = 7.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        when {
                            st.guard.level == GuardEngine.Level.ALARM -> As.Rd
                            st.guard.level == GuardEngine.Level.WARN -> As.Am
                            st.capturing -> As.Gn
                            else -> As.Faint
                        }
                    )
            )
            Spacer(Modifier.width(5.dp))
            Text(
                if (st.capturing) st.verdictLabel() else "待机",
                color = As.Dim, fontSize = 9.sp, maxLines = 1
            )
        }
        // BPM
        Row(
            Modifier
                .clip(RoundedCornerShape(7.dp))
                .background(Color(0x59060A14))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("BPM", color = As.Faint, fontSize = 8.5.sp)
            Spacer(Modifier.width(4.dp))
            Text(
                st.frame?.bpm?.takeIf { it > 0 }?.let { "%.0f".format(it) } ?: "--",
                color = As.Pu, fontSize = 12.sp,
                fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace
            )
        }
    }
}

/** 状态灯文案：对齐 v0.3.0 #verTxt（采集中显示分类结论，异常时显示故障）。 */
private fun MainViewModel.UiState.verdictLabel(): String = when {
    lastError != null -> "异常"
    loopErrors > 0 -> "自愈中"
    verdict != "—" -> verdict
    else -> "采集中"
}

// ================= 右侧卡片区 =================

@Composable
private fun SideCards(st: MainViewModel.UiState) {
    // ① 六段能量（v0.3.0 主面板首卡：#bandBars + .bl 频段标签 + .hintline）
    AsCard {
        CardTitle("📊 低中高频谱（六段能量）", CardTone.I1)
        BandBars(st.bands, Modifier.fillMaxWidth(), height = 62.dp)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("30-150", "150-400", "400-1k", "1k-2.5k", "2.5k-6k", "6k-16k").forEach {
                Text(it, color = As.Faint, fontSize = 7.5.sp, fontFamily = FontFamily.Monospace)
            }
        }
        HintLine("分段相对能量（Hz）· 归一化显示 · 实时刷新")
    }
    Spacer(Modifier.height(As.Gap))

    // ② 智能分类（v0.3.0：verdict + 4 条 .conf 置信度条 + 人声细化 + .heu 免责）
    AsCard {
        CardTitle("🧭 智能分类（离线启发式）", CardTone.I2)
        val c = st.conf
        if (c == null) {
            HintLine(if (st.capturing) "等待信号…" else "未采集")
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            when {
                                st.verdict.contains("人声") -> Color(0xFFFF5D9E)
                                st.verdict.contains("音乐") -> As.Pu
                                st.verdict.contains("噪") -> As.Cy
                                else -> As.Gn
                            }
                        )
                )
                Spacer(Modifier.width(6.dp))
                Text(st.verdict, color = As.Txt, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            ConfBar("人声 Voice", (c.voice * 100).toInt(), As.confVoice)
            ConfBar("音乐 Music", (c.music * 100).toInt(), As.confMusic)
            ConfBar("其他声音 Other", (c.other * 100).toInt(), As.confOther)
            ConfBar("噪音 Noise", (c.noise * 100).toInt(), As.confNoise)
            // 人声细化（v0.3.0 的 .sec + 3 行）
            if (c.voice > 0.25 || st.voiceDetail != "—") {
                SectionTitle("🗣️ 人声细化")
                StatRow("子类型(启发式)", st.voiceDetail.take(18), vSize = 11)
                StatRow("最佳猜测(离线)", st.otherTop.take(18),
                    sub = if (st.otherScore > 0) "得分 ${"%.2f".format(st.otherScore)}" else null,
                    vSize = 11)
            }
            HintLine("※ 全部结果为「离线启发式推断」，非训练模型；物种 / 车型 / 曲目均不可作为确定性结论。")
        }
    }
    Spacer(Modifier.height(As.Gap))

    // 定位摘要
    AsCard {
        CardTitle("声源定位", CardTone.I2)
        val r = st.loc.result
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatRow("方位", r?.azText() ?: "—", modifier = Modifier.weight(1f), vColor = As.Cy)
            StatRow("距离", r?.distText() ?: "—", modifier = Modifier.weight(1f), vColor = As.Gn)
            StatRow("置信", r?.confText() ?: "—", modifier = Modifier.weight(1f))
        }
        if (st.channelCount < 2) {
            HintLine("单声道输入，已切换阵列仿真。点「定位」查看操作指示。", color = As.Am)
        }
    }
    Spacer(Modifier.height(As.Gap))

    // 分离摘要
    AsCard {
        CardTitle("前景 / 背景", CardTone.I3)
        val s = st.sep
        when (s.state) {
            com.alphasun.sonicanalyzer.core.SeparationEngine.State.LEARNING -> {
                ProgressTrack(s.progress, color = As.Pu)
                Text("学习中 ${s.learnFrames}/15 帧", color = As.Am, fontSize = 10.sp)
            }
            com.alphasun.sonicanalyzer.core.SeparationEngine.State.READY -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatRow("前景", if (s.fgRatio.isNaN()) "—" else "%.0f%%".format(s.fgRatio * 100),
                        modifier = Modifier.weight(1f), vColor = As.Gn)
                    StatRow("背景", if (s.fgRatio.isNaN()) "—" else "%.0f%%".format((1 - s.fgRatio) * 100),
                        modifier = Modifier.weight(1f), vColor = As.Am)
                    StatRow("抑制率", if (s.suppress.isNaN()) "—" else "%.0f%%".format(s.suppress * 100),
                        modifier = Modifier.weight(1f), vColor = As.Cy)
                }
                Text(
                    "模型覆盖率 ${"%.0f".format(s.cover * 100)}% · ${s.profileFeature}",
                    color = As.Faint, fontSize = 9.sp
                )
            }
            else -> HintLine("点「分离」学习背景模型。")
        }
    }
    Spacer(Modifier.height(As.Gap))

    // 警戒摘要
    AsCard {
        CardTitle(
            "声波警戒",
            when (st.guard.level) {
                GuardEngine.Level.ALARM -> CardTone.I5
                GuardEngine.Level.WARN -> CardTone.I4
                else -> CardTone.I1
            }
        )
        val g = st.guard
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatRow("状态", g.stateText, modifier = Modifier.weight(1f),
                vColor = when (g.level) {
                    GuardEngine.Level.ALARM -> As.Rd
                    GuardEngine.Level.WARN -> As.Am
                    else -> As.Gn
                })
            StatRow("事件", "${g.eventCount}", modifier = Modifier.weight(0.7f))
            StatRow("阈值", "${g.thrWarn.toInt()}/${g.thrAlarm.toInt()}", modifier = Modifier.weight(1.1f))
        }
        if (!g.enabled) HintLine("点「警戒」开始值守。")
        else HintLine("本底 ${"%.1f".format(g.floor)} dB · 峰值 ${"%.1f".format(g.peak)} dB")
    }
    Spacer(Modifier.height(As.Gap))

    // 噪音摘要
    AsCard {
        CardTitle("噪音评估", CardTone.I4)
        val n = st.noise
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatRow("Leq", if (n.leq.isNaN()) "—" else "%.1f".format(n.leq),
                modifier = Modifier.weight(1f), vColor = As.Am)
            StatRow("峰值", if (n.peakHold < -900) "—" else "%.1f".format(n.peakHold),
                modifier = Modifier.weight(1f), vColor = As.Rd)
            StatRow("类型", n.type, modifier = Modifier.weight(1.2f))
        }
        if (n.curve.isNotEmpty()) {
            DbHistoryCurve(n.curve, Modifier.fillMaxWidth().height(44.dp))
        } else {
            HintLine("点「噪音」开始评估。")
        }
    }
    Spacer(Modifier.height(As.Gap))

    // ⑦ 动作 / 事件日志（v0.3.0 #acts，最新 8 条）
    EventLogCard(st)
    Spacer(Modifier.height(As.Gap))

    // ⑧ 历史缓存 / 分析报告（v0.3.0 #histCount / #histSize）
    HistoryCard(st)
}

/** v0.3.0 `.card.wide > ul.acts`：动作/事件日志，最新在上。 */
@Composable
private fun EventLogCard(st: MainViewModel.UiState) {
    AsCard {
        CardTitle("🕒 动作 / 事件日志", CardTone.I4)
        val logs = st.guard.logs
        if (logs.isEmpty()) {
            HintLine(if (st.capturing) "暂无记录 · 开始值守后自动记录预警/告警事件" else "未采集")
        } else {
            // logs 是 append 顺序（旧→新），展示时倒序取最新 8 条
            val show = logs.takeLast(8).asReversed()
            Column(Modifier.fillMaxWidth()) {
                show.forEach { (text, kind) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            Modifier
                                .size(5.dp)
                                .offset(y = 5.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(
                                    when (kind) {
                                        2 -> As.Rd      // 告警
                                        1 -> As.Am      // 预警
                                        3 -> As.Gn      // 评估完成
                                        else -> As.Cy
                                    }
                                )
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text,
                            color = when (kind) { 2 -> As.Rd; 1 -> As.Am; else -> As.Dim },
                            fontSize = 9.5.sp,
                            lineHeight = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            if (logs.size > 8) HintLine("…共 ${logs.size} 条，仅显示最新 8 条")
        }
    }
}

/** v0.3.0 `🗄️ 历史缓存 / 分析报告`：本机事件媒体占用统计。 */
@Composable
private fun HistoryCard(st: MainViewModel.UiState) {
    val ev = st.guard.events
    val shots = ev.sumOf { e ->
        (if (e.frontPath != null) 1 else 0) + (if (e.backPath != null) 1 else 0)
    }
    val wavs = ev.count { it.wavPath != null }
    AsCard {
        CardTitle("🗄️ 历史缓存 / 分析报告", CardTone.I5)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatRow("事件记录", "${ev.size} 条", modifier = Modifier.weight(1f), vColor = As.Cy, vSize = 12)
            StatRow("抓拍图", "$shots 张", modifier = Modifier.weight(1f), vColor = As.Gn, vSize = 12)
            StatRow("事件音频", "$wavs 段", modifier = Modifier.weight(1f), vColor = As.Am, vSize = 12)
        }
        HintLine("存于 本机存储/Android/data/…/AlertEvents/<日期>/ · 清理请在系统设置的应用存储里操作")
    }
}

