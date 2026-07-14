# RESULT — night4 r3（第3轮修复独立复验 + 核心回归）

- 日期：2026-07-13 13:40–14:15
- 被测：com.wordtaker.keyboard.debug v0.37.0-debug+8c86520（装机 13:13:38）
- **APK 验真**：设备 base.apk md5 = 本地产物 md5（f3e97197ca5ee21d5b3a053f800d44c3）；dex 内含第3轮修复标记三处（`语音正在准备`@classes20、`ASR warm-up failed`/`extensionManager init failed`@classes14）；源码核实 FlorisApplication.init() 已把 clipboardManager/DictionaryManager init 挪进 prefs 加载协程（后台、顺序、同线程触发 AppPrefsKt 类初始化），extensionManager 独立协程并行——修复者主张与装机产物一致，被测对象真实。
- 环境：emulator-5554（Android 14，1080x2400，持续过载：screencap 单次 5~13s）；宿主 App：Google 联系人编辑器
- 证据截图：scratchpad/qa_r3_*.png；全程 logcat：scratchpad/qa_r3_logcat_full.txt；崩溃堆栈副本：scratchpad/qa_r3_crash_stacktrace.txt

## 一、D-1 判定

| 用例 | 判定 | 证据 |
|---|---|---|
| N4-D1-01 冷启动立即输入不 ANR（×3） | **PASS** | 3 轮 force-stop 冷启动 **0 ANR**（r2 为 2/3 ANR）。R1: tap 13:42:49→全渲染 +59~72s→nihao 候选/「你好」上屏正常；R2: +79~87s；R3: +116~123s→切英 hello 直接上屏，渲染后打字即时响应。/data/anr 全程 53→53；logcat 0 次 `ANR in`/`bg anr`。渲染前白/黑屏期仍长（见遗留③），但进程不再被 ANR 杀 |
| N4-D1-03 初始化期话筒反馈 | **PASS** | R2 渲染出现后 2s 点话筒：**≤1s** 弹出真录音界面（正在倾听+小猫+状态栏麦克风绿标，qa_r3_r2_mic_3）。本机模型已在盘、isReady()=真，走真录音路径；「语音正在准备」toast 分支经源码核实存在（VoiceViewModel.kt:246-252 pre-flight）但本机无法自然触发（模型已下载），未实测该分支 |
| N4-D1-05 连杀稳定（3轮合并 + 60s 锤测） | **FAIL** | 3 轮冷启动稳定（0 ANR 0 崩溃），但 60s 随机点击锤测第 14s（13:58:47）**抓到新崩溃**：`IndexOutOfBoundsException: index: 3, size: 0` @ CandidatesRow.kt:122 —— 候选条 onClick 用合成期索引 `n` 取**活列表** `candidates[n]`，点按送达时列表已被清空（快速打字+点候选竞态）。CrashUtility 捕获后自杀 SIG9，进程 ~1s 重启恢复，键盘继续可用（qa_r3_hammer_end）。exit-info：reason=2 SIGNALED status=9 |

**设置 App 首屏 ×3：PASS（r2 3/3 ANR → 已收敛）。** 3/3 force-stop 后冷启动全部打开：`am start -W` 首帧超 15s 报 timeout，约 13~30s 后首屏完整可用（qa_r3_set_r1_b/r3_b：启用与授权/AI角色/提示音+音量滑杆全渲染），0 ANR、0 新 anr 文件。首屏慢但每次都能开、可操作。

## 二、核心回归抽查（7/7 PASS）

| 项 | 判定 | 证据 |
|---|---|---|
| 中文上屏 | PASS | nihao→候选（你好/你/拟/尼…）→「你好」上屏（qa_r3_r1_typed/committed） |
| 英文输入 | PASS | 切英后 hello 直接上屏（qa_r3_r3_hello） |
| 符号页（含换行键） | PASS | 「换行」键完整渲染；，？上屏；返回字母页正常（qa_r3_reg_sym/sym2） |
| 录音进出 | PASS | 录音 44s 不自动退、小猫+音符动画正常；点结束→正在识别→返回键盘、无幻影上屏（静默识别 ~109s 返回，与 r2 观察一致）（qa_r3_r2_rec_4s/rec_end/wait_1） |
| 音量滑杆可达可拖 | PASS（r2 BLOCKED→通过） | 设置 App 首屏「提示音音量」96%→68%→…拖动跟手（qa_r3_slider_*）；测后已复原 96% |
| 云词库开关 | PASS（r2 BLOCKED→通过） | 设置 App「云词库联想」开关在位、ON（qa_r3_set_scroll1） |
| 手写入口 | PASS | 键盘面板·键盘管理：全拼✓/九宫格/手写输入 三入口在位（qa_r3_panel_5） |

## 三、新缺陷（锤测发现，附定位）

**D-2 候选点按竞态崩溃**（严重度：高；触发：快速连续输入中点候选）
- 堆栈：`IndexOutOfBoundsException: index: 3, size: 0` → `CandidatesRow.kt:122`（onClick `keyboardManager.commitCandidate(candidates[n])`）；onLongPress 同型隐患（:124-129）。
- 根因：`n` 是组合时快照索引，`candidates` 是点击时的活列表；前一次 commit 清空候选后点击才送达即越界。修复建议：`candidates.getOrNull(n) ?: return` 兜底（onClick/onLongPress 双处）。
- 特别注意：该崩溃**不产生 AndroidRuntime FATAL 日志**（CrashUtility 自定义 handler 接管后 SIG9 自杀），logcat 只有 `CrashUtility$UncaughtExceptionHandler: uncaughtException()` 一行——按 FATAL grep 会漏检，需同时 grep `uncaughtException` 与 exit-info reason=2。
- 堆栈原件仍在设备 `no_backup/unhandled_stacktraces/1783922327698.stacktrace`（未删，留给修复者）；副本在 scratchpad/qa_r3_crash_stacktrace.txt。

## 四、遗留观察（不计判定）

1. **exit-info 有一条 13:14:44 的 ANR 退出记录**（reason=6，装机后 66s、本场窗口之前、无 trace 落盘）——发生在修复者自验时段，与「自验 0 ANR」表述不符，如实记录；本场窗口内（13:40 起）0 ANR。
2. force-stop 后系统 3/3 回退 Gboard，需 `ime set` 重设（系统行为，与 r2 观察一致，不计 FAIL）。
3. 冷启动键盘白/黑屏 59~123s 无任何加载指示（过载模拟器数据；温进程时 ~2s 即出，见 qa_r3_panel_kb_1）。ANR 面已收敛，「键盘可用耗时」与加载态提示仍是体验短板，建议真机复测定量。
4. 锤测期间随机点按开出面板/录音等均正常恢复，除 D-2 崩溃外无其它异常。

## 五、副作用复原

- 联系人编辑器垃圾输入已 Discard 未保存；提示音音量精确复原 96%；IME 保持 wordtaker；已回桌面；后台 logcat 已停；崩溃 stacktrace 设备原件保留（取证用）。

## 六、计数与结论

- D-1 三条：**2 PASS / 1 FAIL**（D1-01 PASS、D1-03 PASS、D1-05 FAIL）
- 设置 App 首屏：PASS
- 核心回归：7 PASS / 0 FAIL / 0 BLOCKED（r2 的 2 项 BLOCKED 本轮全部补验通过）
- 合计 11 条：**10 PASS / 1 FAIL / 0 BLOCKED**

**结论：未零 FAIL 收敛。** 第3轮修复对 D-1 的 ANR 面**确认有效**：IME 冷启动 3/3 无 ANR、设置 App 3/3 打开（r2 共 5 处 ANR 全消）、初始化期话筒 ≤1s 真反馈；但 60s 锤测揪出与 D-1 无关的**候选点按竞态崩溃（D-2）**，1 行兜底可修，修后需再跑一轮锤测回归。
