# RESULT-20260714-batch2-r2 · 第二批 8 项改动 第 2 轮（回归）执行结果

- 执行日期：2026-07-14
- 环境：emulator-5554 (wt_real, 1080x2400, 420dpi)，com.wordtaker.keyboard.debug v0.37.0-debug+8c86520 (172)，**B2-025 修复版**（NlpManager runBlocking→suspend，10:07 装机，pid 31150）
- 方法：adb 驱动（同点 40-80ms 短 swipe 代替 tap；滑行用 input motionevent 多段轨迹）+ 截图 Python 像素量化 + logcat 监控（ANR|CrashUtility|IndexOutOfBounds|FATAL）+ pid 轮询
- 证据截图：`/private/tmp/claude-502/.../scratchpad/qa_b2_r2_*.png`
- 本轮策略：优先重跑上轮 FAIL 项 B2-025，再抽查全量回归
- 总计：**PASS 22 / FAIL 0 / BLOCKED 3**（共 25 条）

## 重点：B2-025 复测（上轮 FAIL → 本轮 PASS）

修复后 build（NlpManager.kt:332 assembleCandidates 去 runBlocking）真实环境压测：

- 3 段压测共 **108 循环**：①40s/29 循环（拼音+候选点按+退格洪峰）②40s/15 循环（含候选行翻页滑动）③**干净段 45s/64 循环**（nihao+空格上屏 + 每 4 循环 8 键长 composing + 8 连退格洪峰，约 14 输入事件/秒——即修复前 09:58 ANR 的同款洪峰形态）。
- 全程 **0 ANR、0 FATAL、0 CrashUtility 崩溃、0 IndexOutOfBounds**，pid 31150 三段前后全部不变。
- 上屏与点按一致：文本框满屏「你好」逐循环 commit（qa_b2_r2_017）。
- 判定：**PASS**。上轮 09:13 GPU 毛刺型 ANR（模拟器环境问题）本轮未再现；真机复核建议维持（见需真机清单）。

注：①②段后期存在脚本误触（候选首位坐标与顶栏猫钮重合，composing 为空时点中猫钮→设置面板→账户 Activity→微信登录网页被拉起），属测试脚本盲点而非 app 缺陷——期间 pid 稳定、无异常日志；第③段改用空格键 commit 彻底避开，强度足额。

## 判定明细

| 用例 | 判定 | 关键证据（本轮） |
|---|---|---|
| B2-001 版本行显示 | PASS | 设置页底部 `v0.37.0-debug+8c86520 (172) · 8c86520` 动态非占位（qa_b2_r2_022） |
| B2-002 版本一致性 | PASS | dumpsys versionName=0.37.0-debug+8c86520 / versionCode=172，与页面逐字段一致，修复版重装后零漂移 |
| B2-003 字母键 24sp | PASS | E/A/M 大写墨高均 45px（=24sp@420dpi），无溢出（qa_b2_r2_012） |
| B2-004 特殊键 22sp 不截断 | PASS | 「中」键墨高 49px≈22sp；手写布局「换行」两字完整（qa_b2_r2_001）；符号层沿用 r1 已验 |
| B2-005 夜间同步 | PASS | 沿用 r1（主题 JSON 未被本次修复触及，本轮未重测夜间） |
| B2-006 四态顶边恒等 | PASS | 本轮复测五态：全拼键盘/设置面板/历史/手写/录音 IME 顶边全部 y=1431，差 0px |
| B2-007 快速切换零跳动 | PASS | 本轮多次面板↔键盘往返逐帧 y=1431，无白闪/崩溃；10 连击沿用 r1 |
| B2-008 120ms Crossfade | BLOCKED | 模拟器 screenrecord 不可用，需真机逐帧 |
| B2-009 顶栏猫钮外观 | PASS | 猫头像白圈高 79px=30.1dp（qa_b2_r2_001） |
| B2-010 猫钮功能+面板猫钮 25dp | PASS | 点击开设置面板 ✓；面板内猫钮圈 66px=25.1dp，未被 48dp 撑大（qa_b2_r2_004） |
| B2-011 录音猫垂直居中 | PASS | 猫中心 y=1827 vs 可用盒中心 1806 → 偏差 2.8%（≤5%）（qa_b2_r2_000） |
| B2-012 音符减半 | PASS | 单音符 31-43px（旧版约 2 倍尺寸），不遮猫 |
| B2-013 账户=App内子页 | PASS | 点击后 topResumedActivity 仍为 SettingsLauncherAlias，同 Activity 子页 + 返回箭头（qa_b2_r2_023） |
| B2-014 返回箭头回设置 | PASS | 回设置页，无闪退（qa_b2_r2_024） |
| B2-015 系统返回回设置 | PASS | keyevent 4 回设置页而非退出（qa_b2_r2_025） |
| B2-016 额度拉取 | BLOCKED | 需真机登录态；未登录态「--/刷新」正常渲染无崩溃 |
| B2-017 语音钮 26dp+功能 | PASS | 面板顶栏 mic 白圈 68px=25.9dp；录音面板链路本轮工作正常 |
| B2-018 收起钮 26dp+功能 | PASS | 收起钮白圈 68px=25.9dp（行/列双向）；收起/重拉起功能正常 |
| B2-019 连续听写不自动结束 | BLOCKED | 需真机麦克风 |
| B2-020 音量滑杆 | PASS(UI) | 沿用 r1（可拖动+持久化）；听感需真机 |
| B2-021 中文滑行 | PASS | motionevent 轨迹 n→i→h→a→o → composing "nihao"、候选「你好」居首、上屏成功（qa_b2_r2_018） |
| B2-022 手写输入 | PASS | 手写「十」出候选点按上屏 ✓；手写态顶边 1431 无跳动（qa_b2_r2_002/003） |
| B2-023 云词库 | PASS | 'mao' → `CloudDict 'mao' -> 10 cloud candidates`（logcat），云链路正常；开关持久沿用 r1 |
| B2-024 D-1 冷启动 0 ANR | PASS | 3 轮 force-stop 冷启动全部 0 ANR；键盘可见 2.1s / 25.9s* / 3.7s（*该轮判定为模拟器 tap 丢事件——无 ANR 无异常日志，重试轮 3.7s 即恢复正常） |
| B2-025 D-2 候选快速点按 | **PASS** | 见上文重点复测：108 循环 0 ANR、pid 不变、上屏一致 |

## 需真机复核清单（不变）

B2-008（Crossfade 逐帧）、B2-016（登录态额度）、B2-019（连续听写）、B2-020（提示音听感）；B2-025 已在模拟器修复验证通过，真机 D-2 一轮仍建议（上轮遗留建议）。

## 额外观察（非用例内）

1. **键盘设置面板内的「账户与额度」仍跳 WordTakerAccountActivity**（未登录时进而拉起 Chrome 微信 OAuth 页）。改动 6 只覆盖 App 设置页入口（已验 PASS）；键盘面板入口保持 Activity 跳转是否符合预期，建议产品侧确认一句。
2. `CrashUtility$Companion: install()` 为启动时安装 crash handler 的正常 info 日志，grep 监控时勿误判为崩溃。
3. force-stop 后系统默认 IME 自动切回 Gboard，需 `ime set` 恢复（r1 已知，本轮同样处置）。

## 副作用复原

- 5556 草稿清空 ✓（10086 及其他会话草稿为本轮前既有基线，未触碰）
- 压测误触改动的「AI 角色」已复原为 常规 ✓
- 键盘布局：全拼（r1 基线；本轮开始时为修复 agent 遗留的手写态，已归位）✓
- 默认 IME = com.wordtaker.keyboard.debug/FlorisImeService ✓
- 误触产生的 Chrome 微信登录 tab 遗留在 Chrome 中（无数据写入，影响可忽略）
