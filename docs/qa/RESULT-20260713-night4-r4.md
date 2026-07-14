# RESULT — night4 r4（D-2 修复独立复验 + 快速回归）

- 日期：2026-07-13 14:19–14:49
- 被测：com.wordtaker.keyboard.debug v0.37.0-debug+8c86520（本轮 clean 全量重建，装机 14:20:53）
- **APK 验真**：本地产物 md5 = 设备 base.apk md5（8893d6566bb5429c43ac5f575458a344）；源码核实 CandidatesRow.kt:120-138 onClick/onLongPress 均已改为 `candidates.getOrNull(n)?.takeIf { it.text == candidate.text }` 过期点按守卫——被测对象真实含 D-2 修复。
- 构建：`:app:clean :app:assembleDebug` BUILD SUCCESSFUL；push + pm install Success；default_input_method=FlorisImeService。
- 环境：emulator-5554（Android 14，1080x2400，持续过载）；宿主 App：Google 联系人编辑器
- 证据：scratchpad/qa_r4_*.png；全程 logcat：scratchpad/qa_r4_logcat_full.txt

## 一、D-2 定向复验（本轮主目标）

崩溃探测三管齐下：① `pidof` 轮询（独立 watcher，2~3s 间隔）；② logcat 关键字 `CrashUtility|IndexOutOfBounds|uncaughtException|has died|ANR in`；③ `dumpsys activity exit-info` 前后对照（基线=14:20:52 装机记录）。

| 用例 | 判定 | 证据 |
|---|---|---|
| D2-01 竞态锤压 200s（r3 为 60s 第 14s 崩） | **PASS** | 设备端脚本 299 轮、约 11,600 次点按（每轮：nihao→commit 后立刻机枪点候选 5 位；退格清拼音与点候选交替 ×2 组；空候选行过期点按），全程 **pid=15604 不变**、0 次 uncaughtException/IndexOutOfBounds、exit-info 无新记录；锤后键盘继续可用（qa_r4_hammer_end：字段已被候选提交塞满文本，证明点按真实命中候选） |
| D2-02 60s 全键盘随机锤测（同 r3 手法） | **PASS** | 592 次随机点按（键盘区含候选行/面板/工具条），pid=15604 不变、0 崩溃 0 ANR；期间随机点开了面板/链接把前台带去 Chrome（logcat 中 6 条 "has died" 全为 Chrome sandbox 进程，与被测无关），键盘随后恢复正常 |
| D2-03 正常点按不误吞 | **PASS** | 锤前与锤后各验一次：nihao→点「你好」→字段准确上屏「你好」（qa_r4_commit_1 / qa_r4_reg_cn）；守卫未吞正常提交 |
| D2-04 长按候选无异常 | **PASS** | nihao 组合态长按候选#2（900ms swipe），无崩溃无异常提交，组合与候选保持（qa_r4_reg_longpress），pid 不变 |

**D-2 结论：修复有效。** r3 在 60s 第 14s 必现的 `IndexOutOfBoundsException @ CandidatesRow.kt:122`，本轮 200s 定向竞态 + 60s 随机共约 12,200 次点按 0 复现，进程零重启。

## 二、快速回归

| 项 | 判定 | 证据 |
|---|---|---|
| 中文上屏 | PASS | nihao→「你好」上屏（qa_r4_reg_cn），冷启动#2 后再验一次仍 PASS（qa_r4_cold2_type） |
| 英文上屏 | PASS | 切英后 hello 直接上屏「你好hello」（qa_r4_reg_en） |
| 符号页 | PASS | 123→符号页全渲染（含「换行」键）→？上屏→返回字母页正常（qa_r4_reg_sym/sym2） |
| 录音进出 | PASS | 点话筒→「正在倾听...点击结束」+小猫动画，4s 不自动退（qa_r4_rec_4s）；点结束→识别（VoiceVM: blank result→Idle）→秒回键盘、无幻影上屏（qa_r4_rec_end） |
| 冷启动 ×1 无 ANR | **FAIL**（1/2） | 第 1 次：force-stop→ime set→点字段立即输入，14:39:51 `ANR in com.wordtaker.keyboard.debug`（exit-info reason=6，description="bg anr: executing service …FlorisImeService"，trace=anr_2026-07-13-14-39-42-975），进程被杀后自动重启并在 ~2min 内恢复可用；/data/anr 53→54。第 2 次同法复测：0 ANR、全渲染、打字正常。判 FAIL 如实记录，但属 D-1 冷启动类偶发（r3 遗留观察①也有一条修复者时段 ANR 记录），与本轮 D-2 修复无关 |

## 三、观察（不计判定）

1. 冷启动 ANR 为**偶发**（本轮 1/2；r3 窗口 3/3 无）：过载模拟器上 IME service 执行超时（"executing service"），非本轮改动引入；建议 D-1 后续在真机定量复测。
2. force-stop 后系统不再自动回退 Gboard 也曾出现（本轮 ime set 后 default 保持 wordtaker），行为与 r3 略有差异，不构成问题。
3. 随机锤测会点开面板内链接把前台带走（Chrome），恢复后键盘正常——与 r3 观察一致。

## 四、副作用复原

- 联系人草稿已 Discard（从未 Save）；设备 /data/local/tmp 下 hammer 脚本与 APK 副本已删除；后台 logcat 已停；IME 保持 wordtaker；已回桌面。

## 五、计数与结论

- D-2 四条：**4 PASS / 0 FAIL**
- 快速回归五条：**4 PASS / 1 FAIL**（冷启动 ANR 偶发，D-1 类，与 D-2 无关）
- 合计 9 条：**8 PASS / 1 FAIL**

**结论：D-2 已收敛（目标缺陷零复现），但未整体零 FAIL——冷启动 ANR（D-1 类）偶发复现 1/2，需另行跟进。**
