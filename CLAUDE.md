# 弦外小猫 (KittyEcho) — 项目规则

本工程 = FlorisBoard 基底改造的安卓中文语音输入法「弦外小猫」(applicationId `com.wordtaker.keyboard`)。

# 沟通规则（强制）

每次对话/任务完成后，用最简洁的语言汇报结果；需要协助或提问时同样极简。

# 子 agent 编排纪律（强制 — 防 token 空耗）

源于一次"递归转派"导致大量 token 被白白消耗（agent 不停派子 agent 却不干活、同一调研重复多次）。强制：

1. **子 agent 是叶子工人**：每个子 agent 提示词必须写明"**禁止使用 Agent 工具/禁止再派子 agent**，所有事自己用 Read/Edit/Write/Bash 完成并给出具体结果"。
2. **小事主循环直接做**：git status、读版本/几行文件、查文件存在、单次构建、打开文件夹、改配置——不要包成子 agent。
3. **调研只做一次**：结论喂给实现 agent；实现 agent 不得重复调研。
4. **构建串行、用前台**：同一 Gradle 工程禁止两个 agent 并发构建（撞构建目录）；只有真正独立的工作才放后台。
5. **一个 agent 一个明确范围**，以"构建绿 + 自测 + 具体回报"收尾，拒绝"准备中/等待中/已转派"空结果。
6. **发现空转立即接管**：若 agent 只在"已派后台 agent、稍后汇报"而无产出，停止转派、亲自接管、改派带"禁止再转派"约束的单一叶子 agent。

# 大任务清单的完成纪律（强制）

1. **落成显式可追踪清单**（TodoWrite/TaskCreate），逐条编号、完成即勾。
2. **分阶段流水线**：调研 → 实现（按模块串行，每阶段构建绿）→ 一次统一全面测试 → 打包。
3. **末尾验收闸**：逐条 1:1 验过，缺一不可；只能真机验证的项要标注。
4. **阶段间把进度写入记忆**，遇中断不丢状态。
5. **按清单逐条汇报**（已完成/待办/需真机）。

# 测试闭环流程（强制 — 每次处理完 bug 或新增需求等事项后，必须走完这一套）

处理完任何事项（bug 修复 / 新增需求 / 改动）后，必须执行以下测试闭环。**每个事项、每个步骤都启动一个全新的子 agent 来处理（不复用旧 agent）**：

1. **生成用例**：启动一个全新子 agent，为本次对话/本次改动生成专业测试用例。
2. **执行用例**：启动另一个全新子 agent，在真实运行环境去跑这些测试用例（本项目为安卓：模拟器/真机；若被测对象为网页/服务则在网页端真实环境执行）。
3. **修复问题**：若发现问题，启动一个全新子 agent 进行修复。
4. **再生成 → 再执行**：修复后，再用一个全新子 agent 重新生成专业测试用例，再用另一个全新子 agent 执行测试。
5. **循环**：如此循环，直至完全没有 bug。
6. **上限与终止**：最多循环 10 次。若循环 10 次后问题仍未解决，则终止流程，并用**极简**风格向用户汇报。

（子 agent 仍须遵守上文"子 agent 编排纪律"：叶子工人、禁止再转派、构建串行等。）

# 构建要点

- `export JAVA_HOME=/opt/homebrew/opt/openjdk@17`
- `export ANDROID_HOME=/Users/Admin/Library/Android/sdk`
- Debug: `./gradlew :app:assembleDebug`；Release: `./gradlew :app:assembleRelease`
- 签名 keystore: `app/wordtaker-release.jks`（alias wordtaker / 口令 wordtaker2026）
- 版本单一真源: `gradle.properties` 的 `projectVersionName` / `projectVersionCode`；每次出包按 SemVer 升 versionName 并 +1 versionCode，更新 CHANGELOG。
- ABI 仅 arm64-v8a；模型/词库/`.dat`/`.sqlite3`/`.hwr` 须 `noCompress`（native fd/mmap 加载）。
- APK 输出名 `KittyEcho-<ver>-<buildType>.apk`。
- 注意：模拟器合成触摸不触发 FlorisBoard 的 Compose 键盘候选管线，拼音/形码/手写"可视上屏"、真实中文语音 ASR 都须**真机**最终验证。
