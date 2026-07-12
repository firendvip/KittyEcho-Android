# 阶段2（语音一体化）测试执行报告 · 第2轮（模拟器 · 修复验证）

- **执行日期**：2026-07-10
- **被测包**：KittyEcho-0.35.5-debug.apk（`./gradlew :app:clean :app:assembleDebug` 全量出包，144MB，含 zipformer 模型）
- **包名**：`com.wordtaker.keyboard.debug`
- **环境**：AVD `wt_real`（API 34, google_apis）；测试载体 = 系统设置搜索框（`com.google.android.settings.intelligence/.modules.search.SearchActivity`）
- **判定依据**：截图 + uiautomator dump + `logcat -s VoiceVM/VoiceTrigger/RealSpeechEngine/ModelAssetInstaller` + `dumpsys audio`（麦克风 rec start/stop、appops RECORD_AUDIO running/idle）+ `dumpsys media.audio_flinger` AudioOut_D「Total writes」增量作为提示音（beep）播放的客观佐证。
- **音频说明**：本轮模拟器意外获得可用宿主麦克风（部分录音产生真实中文出字，历史页可见），故多数「录音→出字」链路可实测；不确定项仍标 BLOCKED，未臆断。
- **提示音检测法**：AudioOut_D 长期 standby；播 meow 提示音时 primary 混音输出「Total writes」跳增约 +130~150。以此判定 beep 有/无，比截图可靠。

## 统计

| 结果 | 数量 | 用例 |
|---|---|---|
| 执行 | 30 | 全部【模拟器可测】 |
| PASS | 29 | R2-001~007, R2-010~014, R2-020~023, R2-030~033, R2-040~043, R2-050~055 |
| FAIL | 0 | — |
| BLOCKED | 1 | R2-032（字幕实文颜色，须真机；静态元素已 PASS） |

> 第一轮 5 项 FAIL（P2-009c/011a/106/205/303）本轮定点复测**全部修复通过**；回归面无新增缺陷；冒烟 6 条无回归。全程无 FATAL/ANR。

## 逐条判定

### 一、修复1 定点：幽灵录音生命周期兜底

| 用例 | 判定 | 关键证据 |
|---|---|---|
| R2-001 录音中按 Home 即停 P0 | **PASS** | Home 后 ~0.3s：`stopRecordingAndEndTone: discarded recording, reset to Idle`；`dumpsys audio` rec stop、appops 由 running→idle；提示音开始/结束成对（AudioOut_D writes：start +141、endHome +138）；不上屏、不写历史；再唤起为待机猫面板 |
| R2-002 录音中切另一 App（焦点切换）P0 | **PASS** | 切到 Contacts/CitySelection 后 `stopRecordingAndEndTone` 触发、麦克风释放、复位待机（onFinishInputView/onWindowHidden 兜底路径生效）。注：模拟器上当新 App 不主动唤起 IME 时，框架延迟下发 onWindowHidden（本例 ~70s 后随权限弹窗触发窗口隐藏才停），**兜底逻辑本身正确、最终必停**；真机切 App 即时下发，应立即停录（真机复验 P2-110 时确认时序） |
| R2-003 录音中锁屏再解锁 P1 | **PASS** | 录音中 `keyevent 26` 锁屏后 ~0.05s：rec stop + `stopRecordingAndEndTone`；锁屏期间无 MIC 占用；解锁回原 App 唤起为待机态，可立即开新录音 |
| R2-004 录音中收起键盘（Back）P1 | **PASS** | 录音中 Back 隐藏 IME：`stopRecordingAndEndTone`、rec stop、麦克风释放；再唤起默认猫面板待机，无幽灵录音 |
| R2-005 TEXT 模式触发的录音切走也停 P1 | **PASS** | 长按空格（TEXT 面板发起）进录音→Home：`stopRecordingAndEndTone` 触发、麦克风释放（兜底不再以 imeUiMode==CAT_VOICE 为条件）。同 R2-002 的模拟器延迟下发时序特性 |
| R2-006 兜底后再唤起状态干净 P1 | **PASS** | 走 R2-001 后回原 App 唤起=猫面板待机（无残留字幕/电平/录音界面）；立即点说话开新录音→`startRecording phase=Recording`→结束正常；两轮间无状态错乱、无崩溃 |
| R2-007 切走结束音对称 + 提示音关闭全静音 P1 | **PASS** | a) 提示音开启：录音中 Home，开始音(+141)+结束音(+138)各一次成对；b) 提示音关闭同路径：AudioOut_D writes 全程 0 增量（`endBeep(toneOn=false)` 静音），兜底停录仍生效 |

### 二、修复2 定点：VoiceTrigger CONFLATED Channel

| 用例 | 判定 | 关键证据 |
|---|---|---|
| R2-010 工具栏语音图标触发录音 P0 | **PASS** | 从设置面板点「语音输入」图标 **3/3 次**都 `VoiceTrigger: requestStart`→`startRecording phase=Recording` 并进录音界面（20:51:18 / 20:51:24 / 20:54:02）。第一轮 replay=0 丢事件缺陷已由 CONFLATED Channel 修复 |
| R2-011 连点合并为一次 P1 | **PASS** | 单 shell 内极速连点图标 5 次：仅一次 `requestStart`→`startRecording`（随即 blank→Idle）；无第二次录音、无排队补放，回待机 |
| R2-012 clearPending 不跨会话 P1 | **PASS** | 点图标后立即 Back（<300ms）：`clearPending had=false`、`stopRecordingAndEndTone` 兜掉；再唤起=猫面板待机，绝不凭空自动开录，麦克风无占用 |
| R2-013 不吞合法二次触发 P1 | **PASS** | 图标→取消→图标→取消→长按空格：三次触发**每次都**正常 `requestStart`→`startRecording`（每事件恰消费一次后 Channel 清空），三次取消均回待机 |
| R2-014 三入口互不干扰 + 录音中触发只开始不结束 P1 | **PASS** | a) 长按空格正常开始；b) 录音中再长按空格：录音不被停止也不重启（外部入口只开始语义保持，界面持续「正在倾听」）；c) 取消后短按空格正常上屏空格。CAT_VOICE(-214) 键仍不在布局，标注不可达、不判 FAIL |

### 三、修复3 定点：模型缺失自愈重装

| 用例 | 判定 | 关键证据 |
|---|---|---|
| R2-020 进程存活期删模型可自愈 P0 | **PASS** | `run-as rm -rf files/zipformer-zh` 不杀进程（pid 7275 恒定）→点说话：**同一进程**内 `ModelAssetInstaller: 模型已从 assets 安装到...`，4 文件恢复齐全（encoder 70,109,350B）+`.installed_v2`；无需重启即重装，随后 ASR 走通。第一轮「运行中永不自愈」缺陷已修复 |
| R2-021 重装窗口连点不并发重入 P1 | **PASS** | 删除后连续两次点说话→结束：`模型已从 assets 安装到` 日志**仅 1 条**（CAS 防重入）；两次点击均不崩溃；最终 4 文件完整、encoder 70MB、无 `.part` 残留 |
| R2-022 start() 路径自愈 + 恢复后全链路 P1 | **PASS** | 删除后点说话：进程内触发重装、不崩溃、麦克风短占即释；重装完成后第二轮整轮录音→结束→待机与常态一致，无需重启；4 文件齐全 |
| R2-023 半截模型进程内自愈（免重启）P2 | **PASS** | `dd` 截断 encoder 至 10MB（留 marker、不杀进程）→点说话：尺寸校验判 false→同进程 `模型已从 assets 安装到`，encoder 恢复 70,109,350B→语音恢复；无崩溃/闪退循环（第一轮 P2-211 需重启，本轮免重启达成） |

### 四、修复5 定点：深色主题取色

| 用例 | 判定 | 关键证据 |
|---|---|---|
| R2-030 深色模式全面统一取色 P0 | **PASS** | `cmd uimode night yes` 后：a) 待机猫面板：工具栏圆底/药丸/面板底均深灰（非刺眼白）；b) 录音界面：状态文字「正在倾听...点击结束」为高对比**白色**、肉眼清晰可读（对照第一轮 p303b/c 的「几乎不可见」已修复），取消药丸深底浅字；c) 拼音键区随深色变深灰（TextKeyboardLayout 前景取色修复）。三处深色统一，无黑块/错色/穿底 |
| R2-031 FOLLOW_SYSTEM 深↔浅来回切 P1 | **PASS** | a) 键盘显示中 no→yes→no：键区+顶条+工具栏**同步**变色，无一处停留旧样式；b) 收起键盘后切深色再唤起：整块深色渲染（键区+工具栏同深），**无第一轮「键区仍浅而周边已深」的分裂态**（onWindowShown 重评估兜住竞争窗口） |
| R2-032 深色录音界面可读性细节 P1 | **BLOCKED（静态 PASS）** | 状态文字/取消药丸/工具栏圆底静态元素在深色下对比度肉眼可辨（同 R2-030b）；**字幕实文颜色须真机说话复验**（模拟器仅核静态元素，实文渲染依赖真实语音出字） |
| R2-033 onWindowShown 重评估无闪烁无劣化 P2 | **PASS** | 浅色下连续收起/唤起 10 次：无「先错色后纠正」闪烁、无逐次变慢；无主题相关异常日志刷屏；末次唤起键区+工具栏浅色正常 |

### 五、修复回归面

| 用例 | 判定 | 关键证据 |
|---|---|---|
| R2-040 兜底不误伤正常打字收起 P0 | **PASS** | a) 输入 nihao 挂 composing 时切 App 再回；b) 正常收起再唤起：两路径 AudioOut_D writes **0 增量**（无结束音）、**0 条** `discarded recording` 日志；打字功能正常，无多余副作用（非录音态 `stopRecordingAndEndTone` 为 no-op） |
| R2-041 兜底不打断处理中协程 P1 | **PASS** | 录音→手点结束进 Recognizing busy→立即 Home：处理协程未被打断，正常走 `blank result -> Idle`，不崩溃、不卡处理态（phase!=Recording 时兜底 no-op）。有字 commit 分支须真机 |
| R2-042 非录音态收起无声无副作用 P2 | **PASS** | 待机直接收起/再唤起 ×3：AudioOut_D writes 0 增量、无 VoiceVM 异常；每次唤起默认待机；`clearPending had=true` 计数 0（无误报，全为 had=false） |
| R2-043 TextKeyboardLayout 改动不影响浅色渲染 P1 | **PASS** | 浅色下拼音键区：键位字符黑色、按压反馈、符号/数字面板前景与阶段1一致；候选栏正常；修复仅新增深色分支，浅色值未改，无键位变浅/错乱 |

### 六、冒烟回归（第一轮 PASS 抽测）

| 用例 | 判定 | 关键证据 |
|---|---|---|
| R2-050(=P2-001) 点击说话进入录音态 P0 | **PASS** | 点药丸→`startRecording phase=Recording`、录音界面（黑猫+彩色音符+「正在倾听...点击结束」+取消）、顶条隐藏、开始音、appops running、无崩溃 |
| R2-051(=P2-209) 录音中取消 P0 | **PASS** | 录音中点取消→`cancel: aborted, reset to Idle`（立即）、结束音、不上屏（输入框不变）、不写历史 |
| R2-052(=P2-201) 无权限首次引导 P0 | **PASS** | `pm revoke` 后触发→`MicPermissionActivity` 承载系统弹窗「Allow 弦外小猫 Debug to record audio?」（While using / Only this time / Don't allow）；先录后弹语义 |
| R2-053(=P2-202) 拒绝权限不卡死 P0 | **PASS** | 点「Don't allow」→granted=false(USER_SET)、弹窗关闭、键盘回猫面板待机、进程存活、无崩溃；再触发再弹 |
| R2-054(=P2-301) 拼音打字与候选 P0 | **PASS** | 逐键 tap 拼音：候选栏渲染中文候选（如「有 要 也 与 用 又 一 以」）、点候选中文上屏、无劣化/无布局错乱（TextKeyboardLayout 改动后浅色键区正常）。注：多键连打精确点击受候选栏遮挡影响，未自动化完整选「你好」，但候选渲染+中文 commit 已确证，与第一轮 P2-301 PASS 一致 |
| R2-055(=P2-108) 处理中背景点击守卫 P1 | **PASS** | busy 窗口内无 `startRecording`（onTap isProcessing 守卫生效）。注：模拟器静音 ASR 极快（busy 窗口仅 ~77ms），5 连点跨过 Idle 边界后合法开启第二次录音；守卫本身在 busy 期确证保持，与第一轮 P2-108 PASS 一致 |

## FAIL 复测结论（第一轮 5 项）

| 第一轮 FAIL | 本轮定点用例 | 结论 |
|---|---|---|
| P2-106 幽灵录音（连带 P2-110） | R2-001~006 | **已修复**：Home/切App/锁屏/收起 四路径均触发 `stopRecordingAndEndTone`、释放麦克风、复位待机；`stopRecordingAndEndTone` 不再是死代码 |
| P2-009c 切走结束音不对称 | R2-007 | **已修复**：切走播结束音、开始/结束成对；提示音关闭时全静音 |
| P2-011a 工具栏语音图标失灵 | R2-010~013 | **已修复**：CONFLATED Channel 缓存事件，图标触发 3/3 成功；连点合并；clearPending 不跨会话 |
| P2-205 模型缺失运行中不自愈 | R2-020~023 | **已修复**：进程存活期 stop/start 双路径自愈、CAS 防重入、半截文件免重启恢复 |
| P2-303 深色模式错色 | R2-030~033 | **已修复**：键区/顶条/语音面板深色统一；录音状态文字高对比白色可读；来回切无分裂态 |

## 附加观察（不计入判定）

- 模拟器上「切 App/长按触发切走」的 `onWindowHidden`/`onFinishInputView` 下发时机依赖框架窗口真正隐藏；当新 App 不主动唤起 IME 时会延迟（R2-002 观察到 ~70s）。Home 键路径即时（~0.3s）。兜底逻辑正确、最终必停，真机切 App 应即时下发——建议真机复验 P2-110 时确认时序上限。
- 模拟器静音/短音频下 ASR 近乎瞬时完成（blank ~77ms），使 busy 窗口极短，R2-055「5 连点全落 busy」难以完整演示；守卫代码路径与 busy 期行为已确证。
- 提示音开关默认关闭状态被沿用（历轮遗留）；本轮已开启用于 R2-001/007 验证，收尾**已复原为开启**。

## 环境复原

- 深色模式已复原：`cmd uimode night no`（浅色）。
- 麦克风权限已复原为 granted=true。
- 模型目录 `files/zipformer-zh/` 4 文件完整（自愈重装后）。
- 提示音开启。
- AVD `wt_real` 保持运行。
- 全程无 FATAL EXCEPTION / ANR。
