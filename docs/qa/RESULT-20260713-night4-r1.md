# 夜间第4轮 QA 执行结果 — 第1轮（RESULT-20260713-night4-r1）

- 执行日期：2026-07-13
- 被测：com.wordtaker.keyboard.debug（emulator-5554，v0.37.0-debug+8c86520）
- 方式：adb 真实驱动运行中的键盘/App（input tap/swipe、screencap、logcat、datastore 校验），宿主为系统「通讯录·新建联系人」文本框
- 证据截图：`/private/tmp/claude-502/-Users-Admin-Documents-CC-All-Project-CAT-MAC/c075b464-87c3-4062-9c07-a5cb054a8a1c/scratchpad/qa_n4_*.png`
- 总计：**30 例 = 22 PASS / 0 FAIL / 8 BLOCKED(需真机)**；另发现 **1 个用例外严重缺陷**（见文末）

## 模块 A 提示音音量滑杆

| 用例 | 判定 | 依据 |
|---|---|---|
| N4-001 滑杆显示与默认值 | PASS | 「提示音」卡开关开启时滑杆+百分比显示（qa_n4_app5.png）。默认值代码为 100（SettingsRepository DEFAULT_TONE_VOLUME=100）；本机 pref 已被此前开发操作改为 97，无法观察出厂默认，UI 与存储一致 |
| N4-002 音量 0% 不播 | PASS | 滑杆拖到 0%（qa_n4_vol0c2c.png，datastore tone_volume=0x00）；点说话开始录音，logcat 无任何 SoundPool/AudioTrack 播放轨（对照 100% 时有 AF::TrackHandle OpPlayAudio）；代码 v<=0 直接 return。人耳复核仍需真机 |
| N4-003 50% 响度居中 | BLOCKED(需真机) | 滑杆点 50% 保存为 49（tap 位置舍入），播放无报错；响度对比需人耳 |
| N4-004 100% 正常播放 | PASS | 滑杆 100%（pref=0x64）；点说话瞬间 logcat `AF::TrackHandle: OpPlayAudio: track:70 usage:13 not muted` → 喵叫已派发播放 |
| N4-005 开关关滑杆隐藏 | PASS | 关闭开关滑杆行消失（qa_n4_toneoffc.png）；重开滑杆重现且保留 0%（qa_n4_toneonc.png） |
| N4-006 按键音不受影响 | PASS | 滑杆 0% 时按字母键，logcat `InputFeedbackController: Perform audio with volume=0.5 and effect=5`（playSoundEffect 正常触发，与 tone_volume 无关） |
| N4-007 滑杆值持久化 | PASS | 设 29(≈30)% → `am force-stop` 杀进程 → 冷启动后 UI 仍 29%、datastore=0x1d。注：force-stop 会使系统默认输入法回退到 Gboard（系统行为，已用 `ime set` 恢复） |

## 模块 B 录音不自动结束（连续听写）

| 用例 | 判定 | 依据 |
|---|---|---|
| N4-008 停顿>1.2s 不退出 | BLOCKED(需真机) | endpoint 需真实语音。已验 UI 部分：进录音后静置 8s→3min+，phase 保持 Recording、界面不自灭（qa_n4_rec4c/rec5c.png） |
| N4-009 多段 FIFO 上屏 | BLOCKED(需真机) | 需真实语音分段 |
| N4-010 点结束才停录 | PASS(状态机) | 点击录音区 → logcat `stopAndProcess: phase=Recognizing` → 回键盘不卡死；尾巴真实内容需真机 |
| N4-011 空尾巴安静收尾 | BLOCKED(需真机) | 需已有真实分段 |
| N4-012 取消丢弃 | PASS | logcat `cancel: aborted, reset to Idle`，文本框无新内容，无崩溃 |
| N4-013 切走立即停录 | PASS | a) HOME、b) BACK、c) 焦点切到其它输入框，三者均立即 `stopRecordingAndEndTone: discarded recording, reset to Idle`；键盘随后可正常再呼出再录音 |
| N4-014 空会话点结束 | PASS | 无声音直接结束：`ASR result: 0 chars` → `blank result -> Idle, showed hint`，安静回键盘，无卡转圈 |
| N4-015 定稿后字幕清空 | BLOCKED(需真机) | 需真实语音 |
| N4-016 长时间稳定 | PASS | 连续 Recording >3 分钟：无退出/无 ANR/无崩溃，点结束正常收尾，进程存活。长会话真实转写需真机复验 |

## 模块 C 小猫缩 10%

| 用例 | 判定 | 依据 |
|---|---|---|
| N4-017 小猫尺寸回归 | PASS | 代码 RECORDING_CAT_FRACTION=0.558（CatKeyboardLayout.kt:528）；录音界面小猫渲染完整（黑色主体 bbox 202×135px，居中、无裁切/变形/溢出），标题/取消控件布局正常（qa_n4_rec3.png）。与改动前 0.62 的像素级对比无本机基准图 |
| N4-018 各键盘高度不溢出 | BLOCKED | App 设置与键盘面板均**无键盘高度档位设置项**，用例前提不成立（defKeyboardHeight 为固定 0.2825） |

## 模块 D 键盘样式对齐 A55

（几何量化基于 1080×2400 @420dpi，1dp=2.625px，日间截图 qa_n4_kbd_day.png）

| 用例 | 判定 | 依据 |
|---|---|---|
| N4-019 字母键 30sp 不截断 | PASS | 实测 A 键字形 cap 高 56px ≈ 30sp×0.71em（22sp 仅约 41px）；Q 含尾 66px；26 键键标完整无裁切（qa_n4_kbd_day.png） |
| N4-020 特殊键 22sp | PASS | 「中/英」字形 49px、「换行」54px ≈ 22sp em(57.75px)；多字键标完整不截断（qa_n4_bottomrow.png、qa_n4_symc.png） |
| N4-021 顶栏 63dp+36dp 圆钮 | PASS | 顶栏实测 166px=63.2dp；圆钮 93×95px≈35.4–36.2dp；内为田字格图标，渲染清晰（qa_n4_strip.png） |
| N4-022 田字格=设置入口 | PASS | 点圆钮开设置面板、←返回键盘均正常（qa_n4_t2c/t4c.png）；面板含 历史记录/账户/AI角色/提示音/键盘管理(全拼/九宫格/手写) 全部项。注：「云词库联想」开关位于 App 设置页（存在且开启，qa_n4_appbtm.png），键盘面板历史上从未含此开关——用例预期编写偏差，非回归 |
| N4-023 整体几何对齐（含夜间） | PASS | 行距(行 pitch)163px/屏宽1080=0.1509≈0.150 ✓；总高(顶栏166+键区626+底gap 39px)=831px/2400=0.3463≈0.346 ✓；底 gap 39px≈15dp ✓；键行高 53dp×4。夜间(floris_night)几何逐像素一致（行带 1597/1760/1922/2084，pitch 163），暗色配色正常无错色块（qa_n4_night3c.png）。无 A55 原基准图可叠加，按规格数值判定 |
| N4-024 符号页换行不截断 | PASS | 中文符号页与数字页键标全部完整，「换行」绿色键完整（qa_n4_symc.png、qa_n4_numc.png） |

## 模块 E 回归

| 用例 | 判定 | 依据 |
|---|---|---|
| N4-025 中文打字 | PASS | 键入 nihao → 候选「你好(首位) 你 拟 尼 呢 泥 妳 妮」→ 点你好正确上屏（uiautomator 校验文本） |
| N4-026 英文输入 | PASS | 中/英切换正常（键面 英 高亮）；hello 直接上屏、shift 后 W 大写（字段含 "hello W"）。注：IME 降级(pre-ANR)状态下曾出现点中/英误开历史面板，进程健康后无法复现，归入缺陷 D-1 |
| N4-027 符号/数字键盘 | PASS | ，。？！12345 逐一正确上屏（uiautomator 校验），页间切换无错位 |
| N4-028 滑行输入 | BLOCKED(模拟器手势限制) | adb motionevent 简化轨迹可触发滑行并上屏字母、无崩溃；但无法模拟连续真实手势验证画词精度与轨迹渲染，需真机 |
| N4-029 手写入口 | PASS | 面板→键盘管理→手写输入进入手写面板；画笔画正常渲染、出笔画候选；退格/清除/空格/中英/话筒/换行按钮齐全（qa_n4_hw3c/hw4c.png）。测后已恢复全拼 |
| N4-030 键盘高度切换 | BLOCKED | 同 N4-018：设置中无键盘高度档位；固定 defKeyboardHeight=0.2825 实测总高比例 0.346 正确，呼出无跳变 |

## 用例外缺陷（本轮新发现，需处理）

**D-1【严重·稳定性】IME 进程重度初始化导致反复 ANR + 降级期行为异常**
- 现象：/data/anr/ 里 wordtaker 相关 ANR 记录一夜多起（00:38、00:45、09:53、09:55、09:57、10:45、10:47、11:08）；本轮执行中亲历 2 次：IME 对触摸事件 5s 无响应 → 系统杀进程重启（logcat `ANR in com.wordtaker.keyboard.debug … 93% CPU (56% user + 37% kernel), 57905 minor faults` → `Killing … bg anr`）。
- 冷启动后主线程+pool-2-thread-1 持续 50–90% CPU 约 1–2 分钟（top -H 实测），期间：键盘白屏/不渲染；渲染后出现异常状态——devtools 覆盖层报 `IllegalStateException: Extension org.florisboard.layouts not found / ext loaded: null`、手写面板超高错版、「话筒」按钮无响应、点「中/英」误开历史记录面板并可将历史条目上屏（qa_n4_en.png/qa_n4_rec1.png）。初始化完成后一切恢复正常且不可复现。
- 影响：设置 App 首屏也曾 ANR 被系统杀死（11:21 killAppAtUsersRequest）。
- 环境注记：本模拟器仅 2.5G RAM 且过载（Messaging 同期也 ANR），放大了症状；但 1–2 分钟量级的重初始化 + 初始化期间不防护输入事件是应用侧事实，建议：① 真机复测冷启动时长与 ANR；② 初始化期挂载轻量占位/丢弃输入而非排队；③ 排查 pool-2-thread-1 持续满载的加载逻辑（疑似 zipformer/词典加载自旋）。

## 环境限制汇总
- 模拟器无真实麦克风（虚拟源为静音）：所有依赖真实语音/endpoint/响度的用例 BLOCKED，需真机补测：N4-003、N4-008、N4-009、N4-011、N4-015、N4-028，及 N4-002（人耳）/N4-010（尾巴内容）/N4-016（长会话转写）的补充部分。
- 键盘高度设置项不存在：N4-018、N4-030 无法执行（前提不成立），建议修订用例或确认需求。

## 状态恢复记录（测试副作用已复原）
- 键盘布局：手写 → 已恢复「全拼」；AI 角色误触「高情商改写」→ 已恢复「常规」；提示音音量 → 恢复 96%（原值 97，滑杆 tap 精度 ±1）；深色模式 → 已恢复日间；默认输入法（force-stop 后回退 Gboard）→ 已 `ime set` 恢复弦外小猫；测试文本已清空、联系人未保存。
