# RESULT-20260714-batch3-r2 · P0（设置页 ANR + 设置丢失）修复 独立复验

- 执行日期：2026-07-15（约 04:32–05:00）
- 环境：emulator-5554（1080x2400，**480dpi override** 确认），com.wordtaker.keyboard.debug **v0.37.0-debug+93540dd (172)**
- 装机版校验：修复源 `MinimalSettingsScreen.kt`(04:15) / `InputMethodUtils.kt`(04:13) → APK 构建 04:16 → 安装 04:21，**装机版确含 P0 修复**（源码已核：`MinimalSettingsScreen.kt:142` 为 `LazyColumn`；`InputMethodUtils.kt:150/164` 为 `withContext(Dispatchers.IO)`）
- 方法：adb 驱动（同点 55ms 短 swipe 代 tap；坐标原生 1080x2400）+ 截图像素判读 + uiautomator 读 EditText/字段文本 + run-as 读私有 pref（jetpref 文本 + protobuf 逐字节解码）+ `logcat -b events`(am_anr/am_kill/am_proc_died) + `/data/anr` 与 `procexitstore` 差量 + ANR trace 主线程栈(root)
- 证据截图：`/private/tmp/.../scratchpad/qa_b3r2_*.png`
- 结论：**P0 未真正收敛。滚动 ANR 已确修（P0-B 全绿），但设置页冷启动首帧组合仍阻塞主线程 >5s，现场复现了与原 P0 完全一致的「Waited 5002ms for FocusEvent」ANR + 进程被杀。回归 6/6 通过。**

---

## P0 逐条判定

### P0-A 设置 App 冷启动 ×3 —— **FAIL**

冷启动耗时（`am start -W` TotalTime / Displayed）与主线程掉帧：

| 轮 | pid | TotalTime | 该轮最大掉帧 | 本轮是否 ANR |
|---|---|---|---|---|
| 1 | 5981 | **8548ms** | Skipped 280（另 152/79/34） | 否 |
| 2 | 6115 | **13385ms** | Skipped 428 | 否 |
| 3 | 6184 | **3847ms** | Skipped 261 | 否 |

- 修复者自称「冷启动×3<1s」**与实测严重不符**：Displayed 8.5s/13.4s/3.8s，且每轮都触发一次 >200 帧的主线程掉帧（280/428/261，均命中监控模式 `Skipped [2-9][0-9][0-9] frames`）。R1 掉帧经 `Davey!` 追踪均为 `InputEventId=0`（冷启动软件 GL 着色器+首帧组合暖机，非输入阻塞），三条 P0-A 直启动本身未 ANR。
- **但在 P0-C 冷启动重开场景（pid 6593，Messages 在前台的焦点交接下），现场复现了原 P0 ANR：**
  - `am_anr [0,6593,...SettingsLauncherAlias (server) is not responding. Waited 5002ms for FocusEvent(hasFocus=true)]` @ 04:39:40 —— **与 R1 原 P0 签名一字不差**（R1 为「Waited 5052ms for FocusEvent」）。
  - 完整杀进程链：`am_proc_start 6593`(04:39:34 cold) → `am_anr`(04:39:40) → `am_kill 6593 "user request after error"`(04:39:57) → `am_proc_died`(04:39:57) → `am_proc_start 6731`(04:40:46 重启)。
  - **ANR trace 主线程栈（root 读 `/data/anr/anr_2026-07-15-04-39-41-319`）**：`"main" ... Runnable`，`utm=381`(烧 3.81s 用户态 CPU)，卡在设置页**首次组合**：
    `ViewCompat.getRootWindowInsets → WindowInsetsHolder.<init> → material3.Scaffold → FlorisScreenScopeImpl.Render(FlorisScreen.kt:147) → MinimalSettingsScreenKt.MinimalSettingsMain(MinimalSettingsScreen.kt:112)`。
  - 判定：**`LazyColumn` 只解决了「滚动时按可见区组合」，并未降低「首帧整屏 Scaffold+首屏组合」的主线程开销**；冷启动首帧仍阻塞主线程 >5s，任何待处理的焦点/输入事件 5s 超时即 ANR，并被系统 kill——正是原 P0 的 ANR+杀进程路径。**未收敛。**

### P0-B 设置页滚动压测（40 次快速 fling）—— **PASS**

- 40 次高频上下 fling（每次 60ms 长程），**pid 6184 全程不变**。
- logcat：**Skipped 帧事件 0 次**（一次都没有）、Skipped>200 帧 0、ANR 0、`not responding` 0、`Long monitor contention` 0；`/data/anr` 计数不变（65）、无新 `procexitstore` ANR。
- 截图证滚动真实生效（内容位移、页面可交互，非冻结）。
- 判定：**`LazyColumn` 修复对滚动确实有效，滚动触发的主线程阻塞已消除**（原 P0 主 repro「scroll→20933ms MotionEvent→ANR」不再复现）。

### P0-C 设置持久化（核心二次伤害）—— **PASS（有条件）**

- 操作：把「云词库联想」OFF→**ON**（精确复刻 R1 二次伤害「拨到开、pref 仍 false」的失败面）+ 音量滑杆 23%→**48%** → `am force-stop`（模拟 P0 杀进程）→ 冷启动重开。
- 结果两项均保留：
  - UI 重开后显示 云词库 **ON**、音量 **48%**（截图证）。
  - 磁盘落值一致：`jetpref: dict__cloud_enabled;true`；`wt_settings.preferences_pb` 解码 `tone_volume=0x30=48`。
- **本轮未复现二次伤害（设置丢失）。**
- ⚠️ 但持久化之所以成立，是因为写入在 force-stop **之前**已 flush；结合 P0-A 现场证到的「冷启动 ANR→被杀」路径，**若某次设置写入尚未落盘时进程被 ANR-kill，二次伤害仍可能发生**——该风险未被本次修复根除。

---

## 回归抽查（P0 只动 MinimalSettingsScreen + InputMethodUtils）—— 6/6 PASS

| 项 | 判定 | 证据 |
|---|---|---|
| 账户与额度子页 + 额度拉取 | PASS | 进入渲染正常（云端额度卡/登录区/邮箱·验证码·微信登录）；点「刷新」→ **云端额度 1962 字** 加载；← 返回设置不丢状态；无 ANR/crash |
| 版本行在设置底部 | PASS | 底部 **v0.37.0-debug+93540dd (172) · 93540dd**；且从顶到底全段可渲染（BrandHeader→授权→账户→AI角色→提示音/云词库→键盘管理→关于/二维码→数据安全→版本），**LazyColumn 全列表端到端无缺项** |
| 中文打字 nihao→你好 | PASS | 正文域组合「nihao」→候选栏 **你好**(绿·居首)｜你｜拟｜尼… → 点选后 uiautomator 实读 `compose_message_text="你好"`（注：Messages「收件人」域禁候选，须在正文域测） |
| 符号页(123) 能进能上屏 | PASS | 123 页四行布局正确（1-0 / -/:~()…@"" / 符号。,、?!. / ←·12·34·空格·换行）；点 5/0/@ → 实读 `50@` |
| 录音进出 | PASS | 点「点击说话」→ 录音态「正在倾听…点击结束」+猫+音符+取消（`PcmRecorder: first frame after 272ms`，麦克风指示灯亮）→ 静置 3s **无任何字幕/上屏** → 点结束 → 回键盘、正文域空、无文本注入；无 ANR/crash |
| 冷启动键盘 0 ANR | PASS | FlorisImeService 绑定/首显干净，pid 6731 稳定，0 ANR/0 crash/0 大掉帧（键盘 IME 本体不受 P0 影响，与 R1 一致） |

---

## 计数与结论

- **P0：P0-A FAIL｜P0-B PASS｜P0-C PASS(有条件) → P0 FAIL × 1（冷启动 ANR+杀进程未收敛）**
- **回归：PASS 6 / FAIL 0**
- 本会话 wordtaker `am_anr` 累计 **1 次**（即 04:39:40 pid 6593 的 FocusEvent 超时 ANR）；`/data/anr` 计数 65→65（该 ANR 落在 system-only 的 `procexitstore`/`anr_*` 而非 tombstoned trace）。

**一句话结论：P0 未真正收敛零 FAIL——滚动 ANR 已确修（P0-B 全绿是 `LazyColumn` 的真实收益），但设置页冷启动首帧组合仍把主线程阻塞 >5s，现场复现了与原 P0 一字不差的「Waited 5002ms for FocusEvent」ANR 并被系统杀进程（pid 6593，主线程栈实锤卡在 `MinimalSettingsScreen` 首次组合）；持久化本轮虽保住，但走的是「写入已 flush」的侥幸路径，杀进程隐患仍在。建议：把设置页首帧重活（Scaffold/WindowInsets/首屏 state 加载）移出主线程或延迟到首帧后，而非仅改滚动容器。**

---

## 观察 / 环境处置

- 修复只改滚动容器（`LazyColumn`）与轮询线程（`Dispatchers.IO`），**未触及首帧组合开销**，这正是 P0-A 仍 ANR 的根因位点（`MinimalSettingsScreen.kt:112` 首帧栈）。
- 版本号仍 172（分支只改代码不 bump，符合项目「发版权归主干」约定）。
- 冷启动前台切换偶发：`am start` 设置页有时不夺前台（Messages 残留在前），截图为准、`dumpsys window mCurrentFocus` 偶有滞后。
- 已知环境项：force-stop 后系统默认 IME 自动回落 Gboard，已 `ime set` 复原 Floris（与 R1 观察#4 同源）。
- **副作用复原**：density 480 ✓、云词库 ON ✓、默认 IME=Floris ✓、布局全拼 ✓、AI 角色=常规 ✓（测试中误触为高情商，已复原 `role=normal`）、提示音音量≈26%（原值 23%，滑杆拖拽精度所限 3% 漂移，语义已复原）。
