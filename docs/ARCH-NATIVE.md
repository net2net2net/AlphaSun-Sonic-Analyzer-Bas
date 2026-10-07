# 全原生重写架构（v0.4.0 起）

> 决策：2026-10-04。放弃 Capacitor/WebView，改为 **Kotlin + Jetpack Compose** 原生。
> 签名配置、applicationId、Gradle wrapper 版本、release keystore 全部沿用，不动发布链路。

---

## 一、为什么放弃 WebView（不是"偏好"，是硬限制）

| 需求 | WebView 实测/已知限制 | 原生方案 |
|---|---|---|
| **前后摄像头同时抓拍** | 第二路 `getUserMedia` 拿到**同一路画面**或 `NotReadableError` | `CameraManager` 并发模式（API 31+）/ CameraX `Camera2Interop` |
| **多麦克风 TDOA 定位** | `track.getSettings().channelCount` 在 Android WebView **恒为 1** → 定位永远退回仿真 | `AudioRecord` + `AudioFormat.CHANNEL_IN_STEREO` / 双实例 |
| **长期值守稳定性** | WebView GC 会断音频流，息屏/后台即中断 | 前台服务（`FOREGROUND_SERVICE_MICROPHONE`）+ `WorkManager` |
| **音频延迟/稳定性** | Web Audio 主线程回调，抖动明显 | `AudioRecord` 阻塞读 + `Dispatchers.Default` |
| 功耗 | 后台被系统回收 | 前台服务 +  wakelock |

> 关键证据：`index.html:4761` 读 `channelCount`，≥2 才启用真阵列，
> 否则 `GC.chN=1` 走仿真。**真机上从未真正跑过 TDOA。**

---

## 二、工程结构

```
android/app/src/main/java/com/alphasun/sonicanalyzer/
├── MainActivity.kt              入口（Compose）
├── core/                        DSP 核心（纯 Kotlin，无 Android 依赖，可单测）
│   ├── Dsp.kt                   FFT / Hann / 频谱 / RMS / dB
│   ├── Stft.kt                  STFT + 谱减（分离用）
│   ├── GccPhat.kt               广义互相关（定位用）
│   └── TdoaSolver.kt            时差 → 方位解算
├── audio/                       采集层
│   ├── AudioCapture.kt          AudioRecord 封装（单声道/立体声自适应）
│   ├── MicArray.kt              多麦克风枚举与同步采集
│   └── Recorder.kt              6s 片段录制（试听/分离用）
├── camera/
│   ├── DualCamera.kt            Camera2 前后摄并发
│   └── MediaStore.kt            抓拍/录像落盘
├── feature/                     各功能模块（对应原 6 个底栏按钮）
│   ├── capture/  params/  separate/  locate/  guard/  noise/
└── ui/                          Compose 主题与通用组件
```

---

## 三、迁移映射（Web → 原生）

| Web 端 | 原生替代 | 备注 |
|---|---|---|
| `AudioContext + AnalyserNode` | `AudioRecord` 阻塞读 | 直接拿 PCM，延迟更低 |
| `getByteFrequencyData` | 自写 FFT | 复用 `core/Dsp.kt` |
| `OfflineAudioContext` 谱减 | `core/Stft.kt` | 逻辑照搬，数值已验证 |
| `getUserMedia({video})` | Camera2 / CameraX | 支持并发 |
| `MediaRecorder` | `MediaRecorder`（原生） | 无双重消费崩溃 |
| Canvas 频谱/雷达图 | Compose `Canvas` | |
| `localStorage` 模型 | DataStore / Room | |
| `MediaStore` 保存 | `MediaStore` | Android 10+ 分区存储 |

---

## 四、实施顺序（每步都可独立验证）

| 阶段 | 内容 | 验收标准 | 状态 |
|---|---|---|---|
| **P0** | 工程换壳：去 Capacitor，接 Compose，跑通空壳 APK | 能安装、能启动、不崩 | ✅ 完成 |
| **P1** | 采集 + DSP + 实时频谱（垂直切片） | 真机有真实电平与频谱 | ✅ 完成 |
| **P2** | 声波参数（23 项） | 与旧版逐项对齐 | ✅ 完成 `Features.kt` |
| **P3** | 噪音评估 | 分贝表/柱状/曲线/成分识别 | ✅ 完成 `NoiseEval.kt` |
| **P4** | 前景背景分离（含学习背景 + 谱减 + 试听） | 抑制率、残余比可量化 | ✅ 完成 `Separation.kt` |
| **P5** | **多麦克风 TDOA 定位**（原生最大价值） | 真机多通道，真 TDOA | ✅ 完成 `Locator.kt` |
| **P6** | 声波警戒（阈值/事件/双摄抓拍录像） | 前后摄**真并发** | ✅ 完成 `Guard.kt` + `DualCamera.kt` |
| **P7** | iOS 原生（SwiftUI + AVAudioEngine + AVCaptureSession） | | ⬜ 未开始 |

> P5 是原生重写最大的理由：**只有原生才能让声源定位在真机上工作。**

### v0.5.0 实际落地结构

```
core/                                ui/
├── Dsp.kt          FFT/Hann/功率谱    theme/AsTheme.kt      Web CSS token → Compose
├── Stft.kt         STFT 谱减          component/Components.kt  .card/.chip/.badge
├── GccPhat.kt      GCC-PHAT           component/Charts.kt   频谱/环谱/雷达/表盘
├── TdoaSolver.kt   时差解算           Widgets.kt            坞按钮/弹层/开关
├── Features.kt     P2 23 项参数       panel/ParamsPanel.kt  P2
├── Classify.kt     智能分类+24 模板   panel/SepPanel.kt     P4
├── NoiseEval.kt    P3 噪音评估        panel/LocPanel.kt     P5
├── Separation.kt   P4 背景学习/分离   panel/GuardPanel.kt   P6
├── Locator.kt      P5 定位+操作指示   panel/NoisePanel.kt   P3
└── Guard.kt        P6 警戒引擎        AlphaSunApp.kt        主界面
camera/
├── DualCamera.kt   Camera2 前后摄
└── WavWriter.kt    事件音频落盘
```

### 双摄降级策略（P6 关键）

1. **API 31+ 且厂商声明 `concurrentCameraIds`**：一个 `CaptureSession`
   同时挂前后两个 `ImageReader` 的 Surface，一张 request 驱动两路 —— 真并发。
2. **其余机型**：串行抓拍（后置优先，前置超时更宽松），单路失败只记 `frontErr/backErr`，
   **不崩溃、不阻塞值守**。
3. 前后摄识别**不依赖 label 字符串**，用 `CameraCharacteristics.LENS_FACING` 硬件判定
   —— 这正是 Web 版"前置无画面"的根因（未授权时 label 为空，两路都退化到 `devs[0]`）。

---

## 五、迁移期双轨策略

- 旧 Web 版冻结在 `web-v0.3.1/`（保留作算法参考与数值对照基准）
- `index.html` 的 DSP 参数**逐项抄录并做数值对拍**，确保结果一致
- 原生每个算法模块都写**单元测试**，用已知输入验证（如 3kHz 正弦 → 峰值 bin 精确）

---

## 六、保留不动

- `gradle/wrapper`（Gradle 8.2.1，腾讯镜像）
- `android/app/build.gradle` 的 **signingConfigs / versionCode / versionName**
- `applicationId = com.alphasun.sonicanalyzer`（不换包名，用户无感升级）
- keystore 与口令（`alphasun-release.jks`，已 gitignore）
