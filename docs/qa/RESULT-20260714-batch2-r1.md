# RESULT-20260714-batch2-r1 · 第二批 8 项改动 第 1 轮真机(模拟器)执行结果

- 执行日期：2026-07-14
- 环境：emulator-5554 (wt_real, 1080x2400, 420dpi)，com.wordtaker.keyboard.debug v0.37.0-debug+8c86520 (172)
- 方法：adb 驱动（同点 50-80ms 短 swipe 代替 tap）+ 截图 Python 像素量化 + logcat 监控 + pid 轮询
- 证据截图：`/private/tmp/claude-502/.../scratchpad/qa_b2_*.png`（169 张）
- 总计：**PASS 21 / FAIL 1 / BLOCKED 3**（共 25 条）

## 判定明细

| 用例 | 判定 | 关键证据 |
|---|---|---|
| B2-001 版本行显示 | PASS | 设置页底部 `v0.37.0-debug+8c86520 (172) · 8c86520` 动态非占位 |
| B2-002 版本一致性 | PASS | dumpsys versionName=0.37.0-debug+8c86520 / versionCode=172，三字段零漂移 |
| B2-003 字母键 24sp | PASS | E/A/L/M 大写字高均 45px = 24.1sp@420dpi；26 键一致无溢出；主题 JSON 24sp |
| B2-004 特殊键 22sp 换行不截断 | PASS | 中 键墨高 49px≈22sp；符号层「换行」两字完整 (109x54px)、退格/符号/12·34 无截断 |
| B2-005 夜间同步生效 | PASS | night 下 E/A/M=45px、中=49px；floris_night.json 24/22sp 同源 |
| B2-006 四态顶边恒等 | PASS | 键盘/设置面板/历史/手写 四态 IME 顶边全部 y=1431，差 0px |
| B2-007 快速切换零跳动 | PASS | 连续 10 次切换逐帧 y=1431，pid 不变，无白屏/崩溃 |
| B2-008 120ms Crossfade | BLOCKED | 模拟器 screenrecord 输出 0 字节无法录屏；需真机逐帧确认 |
| B2-009 顶栏猫钮外观 | PASS | 猫头像白圈直径 79px=30.1dp，非旧齿轮 |
| B2-010 猫钮功能+面板猫钮 25dp | PASS | 点击开设置面板；面板内猫钮 66px=25.1dp，未被 48dp 撑大 |
| B2-011 录音猫垂直居中 | PASS | 猫中心 y=1827 vs 可用盒中心 1806 → 偏差 3.4%（≤5%）；vs 整面板中心 3.8% |
| B2-012 音符减半 | PASS | 音符团 67px、单音符≈32px（旧尺寸约 2 倍）；不遮猫；代码 NOTE_SIZE 5.5–7.5 确认 |
| B2-013 账户=App内子页 | PASS | 点击后 topResumedActivity 仍为 SettingsLauncherAlias（同 Activity），带返回箭头 |
| B2-014 返回箭头回设置 | PASS | 回设置页、滚动位置保持、无闪退 |
| B2-015 系统返回回设置 | PASS | keyevent 4 回设置页而非退出 App |
| B2-016 额度拉取 | BLOCKED | 需真机登录态；未登录态额度卡「2000 字」正常渲染、无崩溃 |
| B2-017 语音钮 26dp+功能 | PASS | 圆圈 68x68px=25.9dp、mic 墨迹 37px 合 20dp 图标；点击进录音面板 |
| B2-018 收起钮 26dp+功能 | PASS | 圆圈 68px；点击键盘收起，再点输入框重新拉起 |
| B2-019 连续听写不自动结束 | BLOCKED | 需真机麦克风；模拟器上录音面板保持「正在倾听」>1 分钟未自动结束（旁证） |
| B2-020 音量滑杆 | PASS(UI) | 提示音音量滑杆可拖 96%→48%，杀进程重启后保持 48%，已复原 95%；听感需真机 |
| B2-021 中文滑行 | PASS | n→i→h→a→o 轨迹出候选「你好」居首，点按上屏，无崩溃 |
| B2-022 手写输入 | PASS | 墨迹书写「十」出候选（含十）上屏成功；墨迹区高度与 keyboardUiHeight−48dp 换算吻合；顶边 1431 无跳动 |
| B2-023 云词库开关 | PASS | 关：0 次 CloudDict 请求、本地候选正常；开：'mao'→10 云候选恢复；状态持久；无崩溃 |
| B2-024 D-1 冷启动 0 ANR | PASS | 2 轮冷启动 0 ANR、键盘可交互；键盘可见 ≈3.4–4.0s（含 ime set + adb 开销，实际更快）|
| B2-025 D-2 候选快速点按 | **FAIL** | 见下 |

## FAIL 详情：B2-025

- 09:13:18 `ANR in com.wordtaker.keyboard.debug (pid 15356)`：Input dispatching timed out，等待 MotionEvent DOWN(750,1990) 8079ms；进程被杀重启（新 pid 18130）。
- ANR trace 主线程栈：卡在 `android.graphics.HardwareRenderer.syncAndDrawFrame`（正常绘制路径等待 GPU fence），非应用锁死/死循环。
- 同期模拟器整体停滞：adb 命令 58s 仅完成 1 个循环，Messages 占 CPU 28%、surfaceflinger 24% —— 强烈指向模拟器 GPU 管线整机卡顿连带。
- 复测：恢复后连续 30s 高频（93 循环 / 109 循环中文候选点按 + 翻页）两轮均无 ANR、无崩溃、pid 稳定、上屏与点按一致（你×N）。
- 结论：按「判定诚实」记 FAIL；**建议真机复核**，大概率为模拟器环境毛刺而非 D-2 回归。

## 需真机复核清单

B2-008（Crossfade 逐帧）、B2-016（登录态额度）、B2-019（连续听写）、B2-020（提示音听感）、B2-025（ANR 复核）。

## 额外观察（非用例内）

1. `am force-stop` 后系统默认 IME 自动切回 Gboard（mCurMethodId 变更），需 `ime set` 恢复 —— Android 对 force-stop 应用的标准处置，非 app bug，但真机崩溃场景下用户可能被切回系统键盘，值得知悉。
2. 模拟器 `input tap/swipe` 事件存在丢失与**延迟排队**两种毛刺：延迟的点击会在下一操作前才生效（造成面板被「多点开又关上」的假象），执行侧已用「二次确认稳定态」规避；与 app 无关。
3. 键盘面板内选「全拼/九宫格」不自动关面板、选「手写输入」自动回键盘 —— 与代码注释一致，属设计行为。

## 副作用复原

- 10086 草稿逐字复原为「你好哦看过你niê钱钱」✓；5556 输入框清空 ✓；搜索框清空 ✓
- 键盘布局复原全拼 ✓；夜间模式关 ✓；云词库开 ✓；提示音音量 96%→95%（±1%）
- 默认 IME 复原 FlorisImeService ✓；设备端 /data/local/tmp/qa_b2_trans.mp4 已删 ✓
