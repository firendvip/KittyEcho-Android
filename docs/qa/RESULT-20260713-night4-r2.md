# RESULT — night4 r2（D-1 修复复验 + 核心回归抽查）

- 日期：2026-07-13 12:13–13:00
- 被测：com.wordtaker.keyboard.debug v0.37.0-debug+8c86520（装机 12:02:50，含修复：APK 晚于修复文件 mtime 12:01）
- 环境：emulator-5554（1080x2400，2.5G RAM，过载）；宿主 App：Google 联系人编辑器
- 修复者主张：① Zipformer/RealSpeechEngine 加载线程降 THREAD_PRIORITY_BACKGROUND（已核实在源码 ZipformerController.kt:91 / RealSpeechEngine.kt:94）② ExtensionManager.getExtensionById 直读源索引 StateFlow（已核实 ExtensionManager.kt:147-162）
- 证据截图：/private/tmp/claude-502/-Users-Admin-Documents-CC-All-Project-CAT-MAC/c075b464-87c3-4062-9c07-a5cb054a8a1c/scratchpad/qa_r2_*.png

## 一、D-1 回归用例判定（N4-D1-01~06）

| 用例 | 判定 | 证据 |
|---|---|---|
| N4-D1-01 冷启动立即输入不 ANR | **FAIL** | 3 轮有效冷启动 2 轮 IME 服务 ANR：12:30:31 pid21718、12:38:56 pid22215，均 `bg anr: executing service FlorisImeService`，ANR 报告：进程 101% CPU（60%user+41%kernel）、94891 minor faults——与 r1 签名一致；进程被杀自动重启。键盘白屏 66~118s 才渲染（qa_r2_d1r2_render/d1r3b_render）。渲染后打字响应正常 |
| N4-D1-02 初始化期点中/英不误开历史 | **PASS** | r1/r3 轮渲染后立即连点中/英共 6 次：仅键面高亮切换，全程无历史面板弹出、无历史条目上屏（qa_r2_d1r1b_zhen1/2、d1r3b_zhen1） |
| N4-D1-03 初始化期话筒可用或明确置灰 | **FAIL** | r3 渲染后约 10s 点话筒：1s 内无任何响应、无置灰/加载提示（qa_r2_d1r3b_mic）；约 2~36s 后才延迟弹出录音界面。属「看似可点但无反馈」 |
| N4-D1-04 无 Extension not found 覆盖层 | **PASS** | 5 次进程启动 + 全部操作期间 logcat 0 次 `Extension .* not found`、0 FATAL；无 devtools 覆盖层、无手写面板错版。修复②有效 |
| N4-D1-05 force-stop 连杀 3 轮稳定 | **FAIL** | 3 轮中 2 轮服务 ANR（同 D1-01）。60 秒连续点击锤测单独 PASS：pid 22288 稳定、0 ANR/FATAL、键盘状态正常（qa_r2_hammer60_end） |
| N4-D1-06 初始化完成后全功能正常 | **PASS** | 中文候选上屏、英文、符号、录音进出均正常（见回归表） |

**D-1 补充证据（设置 App 面）：** 设置界面（SettingsLauncherAlias）冷启动 3/3 全部 ANR 被杀：12:53:23 pid22288、12:55:49 pid3935、12:57:48 pid4078，均 `Input dispatching timed out … Waited ~5s for FocusEvent`——与 r1 的 11:21 ANR 签名完全一致。第三次静置 30s 不触碰仍白屏后被杀。设置 App 在此负载下完全打不开。

本场 /data/anr：46 → 52（新增均对应上述 wordtaker ANR）；exit-info 中修复后 ANR 死亡共 5 次。

## 二、核心回归抽查

| 项 | 判定 | 证据 |
|---|---|---|
| 中文打字上屏 | PASS | nihao→候选（你好/你/拟/尼…）→点选「你好」上屏（qa_r2_reg_zh1） |
| 英文输入 | PASS | 切英后 hello 直接上屏（字段=你好hello） |
| 符号/数字页（含换行键） | PASS | ，？正确上屏；「换行」键完整渲染不截断（qa_r2_reg_sym）；返回字母页正常 |
| 音量滑杆存在且可拖 | **BLOCKED** | 滑杆在设置 App 内，设置 App 3/3 ANR 打不开（见上）。键盘面板「提示音」仅有开关（ON、在位，qa_r2_panel_s3） |
| 录音界面 | PASS | 点说话→「正在倾听」+小猫比例/动画正常（qa_r2_rec3）→静置 4s 不退出（qa_r2_rec4_idle4s）→点结束正常返回；静音识别约 1-2 分钟后自行结束返回、不卡死不误上屏 |
| 设置面板/手写入口 | PASS | 键盘内设置面板：历史记录/账户与额度/AI 角色（常规✓）/提示音开关/键盘管理（全拼✓/九宫格/手写输入）；手写入口在位（qa_r2_panel_s4） |
| 云词库开关 | **BLOCKED** | 键盘面板内未见；预期在设置 App，无法打开验证 |

## 三、低严重度观察（不计入判定）

1. 面板宫格按钮偶发点击无响应（连续 2 次无响应，第 3 次成功打开）。
2. 字段类型切换后键盘布局暂态残留：Phone 数字键盘在文本字段聚焦后 >4s 仍显示，多切一次焦点才恢复拼音布局。
3. force-stop 后系统 3 次中 2 次回退 Gboard，需重新选择输入法（系统行为，真机杀进程场景可能同样发生）。
4. 话筒首击若存在拼音合成串，会先提交合成串而不进录音（可能为既定行为，建议确认）。

## 四、环境注记

模拟器过载明显（ANR 报告 TOTAL 91% 含 34% irq；Messaging 同期白屏），放大了症状；但 wordtaker 进程自身 101% CPU + 9.5 万 minor faults、5 次 ANR 签名与 r1 完全一致，属应用侧事实。真机复测仍必要（确认真实设备上白屏时长与 ANR 概率）。

## 五、计数与结论

- D-1 用例：3 PASS / 3 FAIL
- 核心回归：5 PASS / 0 FAIL / 2 BLOCKED
- 合计 13 条：**8 PASS / 3 FAIL / 2 BLOCKED**

**结论：未收敛。** 修复②（getExtensionById 竞态）验证有效——全程 0 例 Extension not found/错路由/历史面板误开；但修复①（加载线程降优先级）不足以消除 D-1 的 ANR 面：IME 冷启动服务 ANR 2/3、设置 App 首屏 ANR 3/3（共 5 次）。主线程在服务/Activity 生命周期期间仍被阻塞或饿死 >5-20s。建议：初始化重活彻底移出主线程关键路径（延迟/异步化，onCreate 与首帧必须轻量），并对初始化期输入给出明确加载态。
