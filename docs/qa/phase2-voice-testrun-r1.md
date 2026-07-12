# 阶段2（语音一体化）测试执行报告 · 第1轮（模拟器）

- **执行日期**：2026-07-10
- **被测包**：KittyEcho-0.35.5-debug.apk（`./gradlew :app:clean :app:assembleDebug` 全量出包，151MB，含 zipformer 模型）
- **包名**：`com.wordtaker.keyboard.debug`（debug 后缀）
- **环境**：AVD `wt_real`（API 34, google_apis）；测试载体 = 系统设置搜索框（SettingsIntelligence SearchActivity）
- **判定依据**：截图 + uiautomator dump + `logcat -s VoiceVM/RealSpeechEngine/ModelAssetInstaller` + `dumpsys audio/input_method` + 必要处源码核验
- **音频限制说明**：模拟器已尝试 `-allow-host-audio` + `adb emu avd hostmicon` + macOS 中文 TTS 注入，因 qemu 进程无 macOS 麦克风 TCC 权限（需人工授权）注入失败——所有**依赖真实语音出字**的验证点标 BLOCKED/待真机，未造假数据。

## 统计

| 结果 | 数量 | 用例 |
|---|---|---|
| 执行 | 25 | 全部【模拟器可测】 |
| PASS | 16 | P2-001/010/102/108/201/202/203/209/210/211/301/302/304/305/306/307 |
| FAIL | 5 | P2-009 / P2-011 / P2-106 / P2-205 / P2-303 |
| BLOCKED | 4 | P2-006 / P2-012 / P2-206 / P2-208 |
| 待真机 | 18 | P2-002/003/004/005/007/008/101/103/104/105/107/109/110/111/204/207/212/308 |

## 逐条判定

### 一、正向流程

| 用例 | 判定 | 关键证据 |
|---|---|---|
| P2-001 点击说话进入录音态 P0 | **PASS** | 点药丸→`VoiceVM: startRecording phase=Recording`；录音界面出现（「正在倾听...点击结束」+黑猫+音符动画多帧位移+「取消」），顶条候选/工具栏隐藏；开始音有 AudioFlinger 混音爆发佐证；无 ANR/崩溃 |
| P2-006 role→mode 契约 P1 | **BLOCKED** | 运行时需真实语音+抓包，模拟器无音频通道且 RelayClient 无请求日志、无单测。源码核验 `RealPolisher.modeFor`：normal→normal / gaoeq→gaoeq / 其余→copywriting，与 Mac 契约一致（RealPolisher.kt:30-34） |
| P2-009 提示音成对 P1 | **FAIL** | b) 取消路径：开始/结束各一次 AudioFlinger 爆发（19:03:10 与 19:03:14）成对 ✓；提示音关闭后同流程 0 爆发（全程静音）✓；a) 正常完成需语音→待真机；**c) 切走路径失败**：录音中回桌面根本不停止录音（见 P2-106 幽灵录音缺陷），结束音无从谈起 |
| P2-010 首启模型安装 P1 | **PASS** | 全新安装+全程断网（Active default network: none）：唤起键盘后 ~25s 内 `files/zipformer-zh/` 4 文件齐（encoder 70,109,350B）+ `.installed_v2`；logcat `ModelAssetInstaller: 模型已从 assets 安装到 ...`；期间用 `.part` 临时文件原子落盘 |
| P2-011 外部入口触发录音 P1 | **FAIL** | b) 长按空格→startRecording ✓（两次复现）；**a) 工具栏语音图标 2 次复测均不触发录音**（面板切回 TEXT 但无 startRecording 日志）——根因：`VoiceTrigger` 为 replay=0 的 SharedFlow，从设置面板点击时 `CatKeyboardLayout` 收集器随面板切换重建，`tryEmit` 无收集者事件被丢（VoiceTrigger.kt:17 / ImeToolbar.kt:79）；**c) CAT_VOICE 键（KeyCode -214）不在任何布局 JSON 中**，UI 不可达 |
| P2-012 成功动效与提示 P2 | **BLOCKED** | 需一次成功语音输入（sparkle+「已写入历史」toast），模拟器无音频注入通道 |

### 二、边界

| 用例 | 判定 | 关键证据 |
|---|---|---|
| P2-102 极短点击 P1 | **PASS** | 5 轮快速开录/结束（静音）：每轮 `ASR result: 0 chars` → `blank result -> Idle, showed hint`（「未识别到语音」提示）；无空文本上屏；历史页无空条目；无状态卡死 |
| P2-106 录音中切回键盘 P1 | **FAIL** | ① 用例路径不存在：录音界面无「键盘图标」，`VoiceViewModel.stopRecordingAndEndTone()`（专为该路径设计）**全工程 0 调用点=死代码**；② 用最近路径（录音中 Home 键切走）复测：IME 窗口已全隐藏（mWindowVisible=false）但**麦克风持续占用 1 分钟+**（dumpsys audio 活跃 MIC session:225 pack=com.wordtaker.keyboard.debug；appops RECORD_AUDIO running）=幽灵录音；重新唤起键盘后录音界面残留继续录（不符合"丢弃回 Idle"）。此问题同样命中 P2-110（真机用例）的"幽灵录音"红线，模拟器已可复现 |
| P2-108 处理中背景点击守卫 P1 | **PASS** | stop 后 busy 期连点录音区背景 5 次：无任何新 startRecording/隐藏动作，流程走完一次（blank→Idle）；与源码 `onTap: if (current.isProcessing) return` 一致 |

### 三、异常

| 用例 | 判定 | 关键证据 |
|---|---|---|
| P2-201 无权限首次引导 P0 | **PASS** | 撤销权限后：录音→结束→`ASR: MicPermissionRequired`→透明 MicPermissionActivity 承载系统弹窗「Allow 弦外小猫 Debug to record audio?」；点允许后回原 App、granted=true；再点说话正常进入录音态（注：本实现是"先录后弹"，弹窗出现在结束时而非点击时） |
| P2-202 拒绝权限不卡死 P0 | **PASS** | 点拒绝→「需要麦克风权限」猫 toast→UI 回待机（药丸恢复）；再次触发→弹窗再次出现；无崩溃 |
| P2-203 永久拒绝 P1 | **PASS** | 两次拒绝（USER_FIXED）后再触发：MicPermissionActivity 静默启动即结束（logcat 可见 OPEN→CLOSE，无可见弹窗）、NotificationService 有 toast 记录、无崩溃、回待机 |
| P2-205 模型缺失 P0 | **FAIL（部分不满足）** | 删除模型目录后触发：无崩溃 ✓、toast ✓、`ASR: ModelNotReady`→ModelRequired 拉起 WordTakerSettingsActivity ✓；**但**：① 引导页（皮肤/AI角色/通用）无任何模型下载/重装内容；② **进程存活期间不会自动重装**（等待 1 分钟+ 无 ModelAssetInstaller 动作，`ensureInstalled` 仅在 RealSpeechEngine 构造时跑一次）——语音持续不可用，直到进程重启才恢复（重启后 19:18:36 自动重装成功） |
| P2-206 下载兜底源切换 P1 | **BLOCKED** | 需"assets 安装不可用+hf-mirror 不可达"注入环境，模拟器无法构造（assets 恒可用）；无 ModelDownloader 单测。代码核验：`MIN_ENCODER_BYTES=30MB` 尺寸校验、失败清 `.part`（ModelDownloader.kt:40,93,194）；尺寸校验行为已在 P2-211 实测 |
| P2-208 润色超时降级 P1 | **BLOCKED** | 需真实语音出字+可控挂起 relay，模拟器均无通道 |
| P2-209 录音中取消 P0 | **PASS** | 录音中点「取消」→`cancel: aborted, reset to Idle`（立即）、播放结束音（AudioFlinger 爆发）、不上屏（输入框不变）、不写历史；「已出字幕」前置因无音频未达成（字幕态取消待真机复验） |
| P2-210 识别/润色中取消 P1 | **PASS** | 识别中取消 5/5：每轮 `cancel: aborted` + `processing coroutine cancelled (scope teardown)`，绝无上屏/重复文本/崩溃（cancelled 守卫生效）；润色窗口需语音+慢网未构造（该分支待真机） |
| P2-211 半截/损坏模型 P2 | **PASS** | encoder 截断至 10MB（保留 marker）+重启进程：isDownloaded 判 false → 自动从 assets 重装（`模型已从 assets 安装到...`，encoder 恢复 70MB）→ 语音链路恢复（再次录音走完 blank 流程）；无崩溃/闪退循环 |

### 四、回归

| 用例 | 判定 | 关键证据 |
|---|---|---|
| P2-301 拼音打字与候选 P0 | **PASS** | 键位逐击 nihao → 候选「你好 你会 你还 你和 你很 拟合 霓虹…」→ 点候选上屏中文；gg→「广告 刚刚 哥哥 改革…」；候选/选字/出字正常。观察项：组合串在输入框显示为「nïha / nihæo」样式（拼音 composing 渲染带变音符），建议真机人工确认观感；候选翻页未自动化覆盖 |
| P2-302 符号/数字键盘 P1 | **PASS** | 数字面板输入「，。？！12」上屏正确；「符号」进全符号面板（√π£¢€${}等）渲染正常；←/123 切回字母面板无布局错乱 |
| P2-303 主题不受影响 P1 | **FAIL** | 仅 1 款皮肤（小猫）无法做多主题切换；系统深色模式下：**键区仍浅色而顶条药丸/语音面板已变深（主题不统一）**；深色录音界面「正在倾听...点击结束」为深灰字叠深底，**对比度极低几乎不可见**（截图 p303b/p303c） |
| P2-304 APK 无 SenseVoice 残留 P1 | **PASS**（以 debug 包代 release） | `unzip -l`：0 个 sensevoice 条目；assets/models/zipformer-zh/ 4 文件在包内（encoder 70,109,350B ≈ 模型合计 72.5MB）；APK 151MB（debug 未压缩、含调试符号，release 体积另核） |
| P2-305 升级清理旧模型 P1 | **PASS** | 伪造 `files/sensevoice/model.onnx`(50MB) → `adb install -r` 覆盖安装 → 键盘启动后 `已清理旧 SenseVoice 模型目录`，sensevoice 目录消失、zipformer-zh 保留、语音直接可用（无重新下载） |
| P2-306 历史页与设置页 P2 | **PASS** | 历史页（键盘内）正常打开：2 条示例记录含润色文+原文+时间+清空/单删；点条目→全文上屏输入框 ✓；设置面板 AI 角色切「高情商改写」✓ 即时打勾；提示音开关关闭后录音开始/取消全程无 AudioFlinger 爆发（即时生效）✓；极简模式开关存在于 WordTakerSettingsActivity（其效果需成功语音验证，随 P2-012 待真机） |
| P2-307 长按空格与语音入口不冲突 P2 | **PASS** | 短按空格→上屏空格；长按空格→startRecording；取消回待机后再短按→再上屏空格（字段最终恰为两个空格）；语义清晰无误触 |

## FAIL 缺陷摘要（按严重度）

1. **【高】幽灵录音（P2-106，连带 P2-110/P2-009c）**：录音中键盘窗口隐藏（Home/切走）后录音不终止，麦克风持续占用（dumpsys audio MIC session 持续 1 分钟+，appops running），重新唤起键盘录音界面残留。`FlorisImeService.onWindowHidden` 的 cancel 逻辑未被触发/未生效；且用例设计的「键盘图标切走丢弃」入口已不存在（`stopRecordingAndEndTone` 死代码）。
2. **【中】工具栏语音图标失灵（P2-011a）**：从设置面板点「语音输入」图标不开始录音（2 次复现）。根因：`VoiceTrigger` SharedFlow replay=0，面板切换瞬间无收集者，事件被丢弃。CAT_VOICE 键（c 入口）不在任何布局中，不可达。
3. **【中】模型缺失运行中不自愈（P2-205）**：删除模型后进程存活期间永不重装（安装器只在进程启动跑一次），引导页也无模型相关内容；用户将持续「模型正在准备」直到进程被杀。
4. **【低】深色模式错色（P2-303）**：键区不随深色变化而周边组件变深（不统一）；深色录音界面状态文字对比度过低几乎不可见。
5. **【低】提示音切走不对称（P2-009c）**：由缺陷 1 连带——切走既不停录也无结束音。

## 附加观察（不计入判定）

- 纯静音下录音**永不自动定稿**（挂 3 分钟+仍在录）：与 P2-104 设计一致（endpoint 需已出文字），但意味着 rule1/rule3 对纯静音 UI 不生效，真机验证 P2-101/104 时注意。
- 拼音 composing 在输入框内渲染为「nïha/nihæo」带变音符样式，真机确认是否预期。
- 历史页两条「今天 14:40/昨天 17:40」为内置示例数据（全新安装即存在），首启体验可留意。
- `adb uninstall com.wordtaker.keyboard`（非 debug 包名）会误判成功，脚本化时注意包名带 `.debug`。

## 待真机（18 条）

P2-002（流式 partial）、P2-003（endpoint 1.2s）、P2-004（手点结束完整性）、P2-005（云端润色成功）、P2-007（历史写入）、P2-008（语音↔键盘衔接）、P2-101（60s 上限）、P2-103（短停顿抗误触）、P2-104（纯静音守卫）、P2-105（连续多轮）、P2-107（字幕2行截断）、P2-109（冷启动兜底）、P2-110（锁屏/切App——**模拟器已发现幽灵录音，真机必验**）、P2-111（endpoint 竞争）、P2-204（麦克风被占用）、P2-207（断网降级raw）、P2-212（焦点丢失）、P2-308（发热与内存）。

> 环境已复原：网络已恢复（airplane off, network active）；提示音已恢复开启；AI 角色留在「高情商改写」（如需复原请在键盘设置面板切回「常规」）。模拟器 wt_real 保持运行（带 -allow-host-audio）。
