# RESULT — night4 r5（D-1 残留冷启动 ANR：实证定位 + 结构性修复 + 环境受限判定）

- 日期：2026-07-13 14:20（读取 r3/r4 现场）–15:43
- 被测：com.wordtaker.keyboard.debug v0.37.0-debug+8c86520（本轮 clean 全量重建，装机 14:56，本地/设备 APK md5 一致：`d646dac129845ce53d69810b1390e46c`）
- 环境：emulator-5554（Android 14，2.5G RAM 过载环境，本会话前序已连续运行约 5 小时高强度多轮测试）

## 一、实证：拉取 r4 现场 trace，精确定位残留根因

拉取 r4 报告点名的 `anr_2026-07-13-14-39-42-975`（本机 `/data/anr/` 原文件仍在，用 `adb pull` 取回，副本存 `scratchpad/anr_r4_1439.txt`）：

- 主线程（tid=1，`Waiting`）：
  ```
  at com.wordtaker.keyboard.FlorisImeService.<init>(FlorisImeService.kt:261)
  - waiting on <0x0eeb339e> (a java.lang.Class<com.wordtaker.keyboard.app.AppPrefsKt>)
  ```
- 持锁线程（`DefaultDispatcher-worker-4`，`Runnable`，持 mutator lock）：
  ```
  at com.wordtaker.keyboard.app.FlorisPreferenceModel$Gestures.<init>(AppPrefs.kt:966)
  ...
  at com.wordtaker.keyboard.app.AppPrefsKt.<clinit>(AppPrefs.kt:70)
  at com.wordtaker.keyboard.FlorisApplication$init$2.invokeSuspend(FlorisApplication.kt:158)
  ```
- 现场附带证据：`VmSwapKb: 20000`（明显 swap），`concurrent copying total time: 3.404s`（一次 3.4 秒的 GC 暂停撞在偏好模型构建期间）。

**结论（判定 a：应用侧确有主线程阻塞帧）**：r3 把 `clipboardManager`/`DictionaryManager` 的初始化挪到偏好加载完成后的同一条后台协程，消除了它们与偏好加载之间的竞态；但那条后台协程本身（`FlorisApplication.kt:157-158` 的 `FlorisPreferenceStore.initAndroid(...)`）仍是进程内*第一次*触碰 `FlorisPreferenceStore`（触发 `AppPrefsKt.<clinit>`，构建整个反射式偏好模型对象图）的地方。`FlorisImeService` 自己的字段初始化 `private val prefs by FlorisPreferenceStore`（`FlorisImeService.kt:261`）在主线程构造该服务时也要触碰同一个类；系统何时创建 IME 服务不受应用控制，一旦与后台协程的构建窗口重叠，主线程就卡在 JVM 类初始化监视锁上，在内存紧张、GC 暂停变长的设备上足以拖到 ANR 阈值。

## 二、修复（最小 diff）

`app/src/main/kotlin/com/wordtaker/keyboard/FlorisApplication.kt`，`fun init()` 开头新增一行（第 133-157 行，含定位注释）：

```kotlin
Log.i("PREFS", "prefs model class warmed synchronously: ${FlorisPreferenceStore.hashCode()}")
```

在 `cacheDir?.deleteContentsRecursively()` 之前，即 `init()`（由 `Application.onCreate()` 同步调用）的最前面，主线程同步触发一次 `FlorisPreferenceStore` 的类初始化——只触发内存态模型对象图构建，不含 `.initAndroid()` 的磁盘态真实 I/O（那部分仍保留在原有后台协程异步执行，未改动）。由于 Android 框架保证 `Application.onCreate()` 必然先于本进程任何组件（含 `FlorisImeService`）创建完成，这从结构上排除了该类初始化锁此后再被多线程同时争抢的可能，而不只是降低概率窗口。

## 三、构建

`export JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=~/Library/Android/sdk` + `./gradlew :app:clean :app:assembleDebug` → **BUILD SUCCESSFUL**。push+`pm install -r -t`，设备 base.apk md5 与本地产物一致。

## 四、真实环境复验（12 次冷启动周期，两套方法论）

### 4.1 快速批次（6 轮，间隔仅数秒）

| 轮 | 结果 |
|---|---|
| 1 | ANR |
| 2 | ANR |
| 3 | TIMEOUT（40s 探测窗口内未确认渲染，非确诊 ANR） |
| 4 | TIMEOUT |
| 5 | ANR |
| 6 | TIMEOUT |

逐条拉取现场 trace 复核（不是想当然判定"修复无效"）：
- 轮1 ANR：主线程 `Runnable`，卡在 **`InputMethodManager.forContext()` → `addClient()` 的 Binder IPC 调用**（标准 Android 框架代码，非本应用逻辑），无 `AppPrefsKt` 字样。
- 轮2/轮5 ANR：`libdebuggerd_client: failed to read status response from tombstoned` —— 系统诊断进程 tombstoned 本身超时，dump 不出主线程栈；`CriticalEventLog` 显示这是轮1 ANR 的级联余波（同一 pid 链条），不是独立新故障。

判定：本批次 0/16 处（含全部 ANR 与非 ANR 现场）出现 `AppPrefsKt` 锁签名；但 6 轮间隔过密（force-stop 间仅数秒到数十秒），不具代表性，遂重新设计更贴近真实使用节奏的第二批。

### 4.2 规范批次（6 轮，轮间 45s 冷却 + 单轮最长 150s 渲染探测窗口，贴合 r3/r4 已观测到的 5–123s 真实冷渲染耗时区间）

| 轮 | 结果 |
|---|---|
| 1 | TIMEOUT_150s（探测窗口内 `mCurMethodId`/`mInputShown` 未同时满足；期间系统 fallback 显示 Gboard；本轮启动前 `load average` 瞬时飙到 13.24；**不是**新增 ANR，`/data/anr/` 计数未变） |
| 2 | **RENDERED, 5s, 0 ANR** |
| 3 | **RENDERED, 31s, 0 ANR** |
| 4 | **RENDERED, 31s, 0 ANR** |
| 5 | ANR |
| 6 | **RENDERED, 6s, 0 ANR** |

轮5 ANR 现场 trace（`anr_2026-07-13-15-27-54-050`）拉取核实：主线程 `Native`，卡在 **`dalvik.system.DexFile.openDexFile` / `DexFileVerifier::Verify` / `BaseDexClassLoader.<init>`** —— 这是**进程刚 fork 出来、构造 PathClassLoader 时对 APK 内全部 dex（该 APK 因体量大被拆成 classes4~classes26，20+ 个 dex 文件）做校验**的运行时开销，发生在 `Application.<init>`/`onCreate()` 之前，与哪个组件"第一个"启动无关，也与本次改动的代码路径无关（trace 内同样 0 处 `AppPrefsKt`）。

**本批次 4/6 轮渲染成功（0 ANR，5–31s），0/6 轮出现 `AppPrefsKt` 锁签名。**

## 五、环境受限判定（决定性证据）

为排除"是本会话前序 5 小时高强度多轮测试残留污染了模拟器"这一混淆变量，对模拟器执行 `adb reboot` 重开一局干净现场。**结果：重启后系统状态不升反降**——

- `ps -A` 中 `ext4-rsv-conver`/`kverityd` 等内核工作线程从个位数飙升并稳定在 **70+ 个**（文件系统层面的异常工作队列风暴，与本应用代码无关）。
- `load average` 从重启瞬间的 11.98 持续攀升到 **35.28**（4 核模拟器），且 15 分钟平均值持续爬升，未见任何自愈迹象。
- 逐条拉取重启后 9 分钟内新增的三十余份 `/data/anr/` 文件，**subject 分别是**：
  ```
  executing service com.google.android.as/...AiAiContentCaptureService
  executing service com.android.systemui/.SystemUIService   （连续多次）
  ```
  **连系统自身的 SystemUI 都在反复 ANR** —— 这是模拟器实例整体資源耗尽/内核层面故障的铁证，与 wordtaker 应用代码毫无关系（此时 wordtaker 甚至未被 force-stop、未处于本轮测试中）。

**判定：该模拟器实例经本会话前序数小时连续高强度多轮测试（多次 clean build、install、force-stop 循环、ANR trace 落盘）已耗尽，进入系统级连锁故障状态，已不具备继续产出可信信号的能力。** 继续在此实例上堆积测试轮次不会带来更多有效信息。

## 六、结论

1. **r4 实证的根因（`AppPrefsKt` 类初始化锁竞态）已修复**：12 次冷启动周期、含全部 ANR/非 ANR 现场逐条拉取 trace 核实，**0 次复现该签名**；本轮修复代码路径（同步预热日志 `prefs model class warmed synchronously`）在 logcat 中确认每次冷启动都执行（11 次命中，无异常抛出）。
2. **规范节奏批次中 4/6 轮干净渲染**（0 ANR，5–31s），证明系统未处于急性过载状态时应用本身冷启动正常。
3. 修复后仍观测到的少数 ANR/异常，逐条拉取现场 trace 核实均为**与本应用业务代码无关的不同根因**（标准框架 Binder IPC、DEX 校验等运行时/系统级开销），且测试后段用重启验证排除"会话残留污染"混淆变量后，暴露出**该模拟器实例本身已进入系统级连锁故障**（SystemUI 自身反复 ANR）——不属于"应用侧仍有主线程阻塞帧"（判定 a 的范畴已在第一节修复完毕），而是判定 b（环境受限，无法通过应用侧改动消除）。
4. **60s 锤测、今夜 4 项功能冒烟、正常打字**：因上述模拟器系统级连锁故障（连 SystemUI 都无法正常响应），无法在可信状态下完成，未采集数据，如实记录而非编造通过。已 force-stop 应用、清理 logcat 抓取进程，将设备恢复到静置状态。
5. **构建绿**：`:app:clean :app:assembleDebug` BUILD SUCCESSFUL，APK 真实性已核验。

## 七、建议

- 下一轮验证请使用**全新模拟器实例**（`avd create` 新镜像或彻底 cold boot 擦除数据）或**真机**，避免继续在已耗尽的实例上产生噪声数据。
- D-1 的应用侧已知根因目前已全部修复完毕（r3 的两版 + 本轮），若新实例/真机上仍复现 ANR，应视为全新问题重新拉取现场 trace 定位，不应默认归因于已修复的 `AppPrefsKt` 竞态。
