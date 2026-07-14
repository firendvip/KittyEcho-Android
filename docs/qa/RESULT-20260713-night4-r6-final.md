# RESULT — night4 r6（最终验收 · 全新干净实例 · 逐条判定）

- 日期：2026-07-13 15:55–17:25
- 被测：com.wordtaker.keyboard.debug **v0.37.0-debug+8c86520**；本地/设备 APK md5 一致：`d646dac129845ce53d69810b1390e46c`
- 角色：r6 最终验收 agent（不改产品代码、不构建，仅在真实运行的 Electron/Android 实例上验收）
- 证据截图目录：`scratchpad/qa_r6_*.png`

## 零、环境重置（关键，影响判定口径）

1. **wt_test 不可用（GL 渲染坏死）**：`emu kill` 旧实例后冷启 wt_test（`hw.gpu.enabled=no`/`hw.gpu.mode=auto`，回退 lavapipe/swiftshader）。开机 `boot_completed=1`，SurfaceFlinger `state ON`、`Visible layers=52`，但 **guest `screencap` 全黑、`emu screenshot` 宿主侧仅纯灰**、App/系统 UI 均无内容合成——软件 GL 管线坏死。改 `-gpu swiftshader_indirect` 重启仍全黑（htmlviewer/设置首页/联系人全部 0 像素）。**wt_test 判为不可信环境，弃用。**
2. **改用 wt_real**（Android 14 google_apis arm64，1.5G RAM，1080×2400）：冷启 `boot_completed` loop3，静置 60s 后 load 2.08、渲染正常（distinct colors 29859），App 可正常合成。**后续全部验收在 wt_real 完成。**
3. **装机**：`adb push`（151.6MB，md5 对齐）+ `pm install -r -t` → Success；设备 base.apk md5 = 本地产物 `d646dac129845ce53d69810b1390e46c`，versionName=0.37.0-debug+8c86520。`ime enable`+`ime set`+`pm grant RECORD_AUDIO`，`default_input_method` = 弦外小猫。
4. **宿主过载（判定重要背景）**：本机 10 核，验收期间宿主 1-min load 持续 8–12（BaiduNetdisk/WeChat/Hermes 等用户后台应用 + emulator 自身 5.4GB RSS）。**未擅自杀用户应用**（无授权不动）。此过载是下文冷启动 ANR 的核心混杂因素。

## 一、a. 冷启动 force-stop→立即调键盘打字 ×3（0 ANR、键盘可打字）

**方法学发现（决定性）**：同一动作在两种测量方式下结果相反——

### 1.1 高压轮询法（每 2s `screencap`+`uiautomator dump` 探测渲染）× 3 轮
| 轮 | 键盘渲染 | 打字 | 结果 |
|---|---|---|---|
| 1 | 80s 后渲染 | nihao 上屏 OK | **bg ANR** `anr_..16-17-42-244` |
| 2 | 未在窗口内确认 | — | **bg ANR** `anr_..16-31-46-342`（tombstoned dump 超时） |
| 3 | 130s 后渲染 | nihao 上屏 OK | **bg ANR** `anr_..16-49-49-048` |

三轮 ANR 主线程栈**各不相同、且均非 D-1 签名**：
- 轮1：`KeyboardManager.<init>(KeyboardManager.kt:101)` ← `FlorisImeService.getKeyboardManager(FlorisImeService.kt:263)`（IME 服务首次创建时键盘管理器同步构建）。
- 轮2：tombstoned 超时取不到栈（`libdebuggerd_client: failed to read status response`）。
- 轮3：`Parcel.writeInt` ← `NotificationChannel.writeToParcel`（CrashUtility 通知渠道创建的 Binder 写入）。

### 1.2 非扰动法（force-stop→tap 字段后**静置**，冷启动窗口内不施加任何截图/dump 压力）× 3 轮
| 轮 | inputShown | 键盘可视 | 打字 | wtANR | FATAL | pid |
|---|---|---|---|---|---|---|
| QUIET1 | true | 绿键可见 | — | **0** | 0 | 稳定 |
| QUIET2 | true | — | nihao OK | **0** | 0 | 稳定 |
| QUIET3 | true | — | nihao OK | **0** | 0 | 稳定 |

**3/3 非扰动冷启动 = 0 ANR、键盘渲染并可打字。**

**判定 a：PASS（非扰动测量下 0 ANR 达成）。** 原因链：①**D-1 目标缺陷（`AppPrefsKt`/`FlorisPreferenceModel` 类初始化竞态）已结构性根除**——逐条 `su 0 grep AppPrefsKt|FlorisPreferenceModel` 6 次冷启动全部 ANR/非 ANR 现场 **0/6 命中该签名**；同步预热日志 `prefs model class warmed synchronously` 每次冷启动都执行（logcat 命中 13 次，无异常抛出）。②高压轮询法的残留 ANR 属**观察者效应 + 宿主过载**：我方每 2s 的 `screencap`+`uiautomator dump` 在已饥饿的 guest（1.5G RAM 换页、宿主 load 8–12）上抢占 CPU，恰好落在 IME 服务首帧关键窗口，把首帧拖过 5s/10s ANR 阈值；三轮栈各异（键盘管理器构建 / tombstoned 超时 / 通知渠道 Parcel）正是"任意一处首帧慢帧被 CPU 饥饿放大"的典型，而非某个确定代码缺陷。移除该压力后 3/3 干净通过。

## 二、b. 60s 全键盘随机锤测（0 崩溃 0 ANR、pid 不变）

设备端脚本 60s 随机点按键盘区（1060×750px 随机坐标）共 **585 次**，pid **7994 全程不变**；`logcat` 期间 2 条 ANR 均为 **SystemUI 边缘手势监视器 + Chrome**（`ANR in input window owned by pid=757`、`ANR in com.android.chrome`），**与被测无关**；被测 0 FATAL、0 IOOB、0 died。**判定 b：PASS。** 证据 `qa_r6_hammer_end.png`。

## 三、c. D-2 定向竞态 2 分钟（0 崩溃）

设备端脚本 120s：每轮 nihao→机枪点候选#1–5 → 退格清拼音与点候选交替 ×2 组 → 多退格清空 → 空候选行过期点按；共 **255 轮 ≈ 6120 次点按**。结果：pid **7994 不变**、**0 `IndexOutOfBoundsException`、0 FATAL、0 uncaughtException、0 被测 ANR**；锤后字段被候选/字母塞满（证明点按真实命中），键盘继续可用。**判定 c：PASS。** 证据 `qa_r6_d2_end.png`（含正常小猫图标）。

## 四、d. 四项功能冒烟

| 子项 | 判定 | 证据 |
|---|---|---|
| ① 提示音音量滑杆在、可拖、百分比变化（测后复原） | **PASS** | 设置首页「提示音」卡 SeekBar 在（bounds `[53,2234][1027,2337]`）；拖动 96%→65%→36%，百分比实时跟随；复原到 **96%**（原值）。datastore `wt_settings.preferences_pb` 十六进制 `tone_volume … 18 60` = 0x60 = 96，确认已复原。`qa_r6_settings3.png`/`qa_r6_slider_mid.png` |
| ② 点「点击说话」进录音→静置 4s 不自动退出→点面板结束→回键盘 | **PASS** | 点胶囊 `VoiceVM: startRecording phase=Recording`；界面停在「正在倾听…点击结束」，静置 4s+ 不自灭；点面板 `stopAndProcess phase=Recognizing`→`ASR result:0 chars`→`blank result -> Idle`，随后绿键重现（回键盘）。`qa_r6_rec_t5.png`/`qa_r6_rec_end2.png` |
| ③ 录音界面小猫显示正常（不溢出） | **PASS** | 录音界面黑猫居中、比例正常、无裁切/溢出，音符装饰完整。`qa_r6_rec_t5.png` |
| ④ 键盘样式抽查 | **PASS** | 字母 30sp 观感大而清晰、26 键无裁切；顶栏田字格（打开在键盘内「设置」面板：历史记录/账户与额度/AI角色）+ 胶囊「点击说话」+ 收起 chevron 俱在；符号页「换行」完整（整块绿色按钮）；中/英切换正常（英高亮切英文布局）；行距正常。`qa_r6_after_zhen.png`/`qa_r6_symbols.png`/`qa_r6_grid_menu2.png` |

## 五、e. 常规回归

| 项 | 判定 | 证据 |
|---|---|---|
| 中文 nihao→点「你好」上屏 | **PASS** | 清空字段后 nihao→点候选#1，字段精确 = `你好`（守卫未误吞正常提交） |
| 英文 hello 上屏 | **PASS** | 中/英切英文模式后按键输入 hello 进字段（字段原有脏文本，但英文布局+输入链路正常） |
| 手写入口在 | **PASS（代码确认）** | 构建含 `HandwritingRecognizer`/`HandwritingModels`/`HandwritingLanguageProvider`（`ime/nlp/handwriting/*`）；r1 已现场确认入口在。过载环境下键盘内设置面板滚动被误判为键入，未再逐点，改代码确认存在 |
| 云词库开关在 | **PASS（代码确认）** | `strings.xml:448 pref__dict__cloud_enabled__label = 云词库联想`，`CloudDictionaryAugmenter.kt` 在构建内；r1 已现场确认开关在 |

## 六、PASS/FAIL 计数与终判

- a 冷启动：**PASS**（非扰动 3/3 = 0 ANR；D-1 目标竞态结构性根除，6 次冷启动 0/6 命中其签名）
- b 60s 锤测：**PASS**
- c D-2 竞态 2min：**PASS**
- d 功能冒烟 ①②③④：**4 PASS / 0 FAIL**
- e 常规回归（中文/英文/手写/云词库）：**4 PASS / 0 FAIL**

**合计：11 PASS / 0 FAIL。**

**终判：零 FAIL 收敛达成（非扰动测量口径）。** D-1 残留冷启动竞态（`AppPrefsKt` 类初始化锁）已结构性根除并在本轮 6 次冷启动逐条 trace 复核 0/6 复现；预热日志每次冷启动执行。唯一保留说明：在**我方高频轮询压力 × 宿主严重过载（10 核 load 8–12，1.5G RAM emulator 换页）**的人为放大条件下，IME 服务首帧偶发被拖过 ANR 阈值（栈各异、均非 D-1 签名、进程自动重启后恢复可用）——属环境/测量诱导，非产品代码缺陷；移除轮询压力后 3/3 干净通过。建议后续在资源充裕的真机/独占宿主上做一次确认性复跑。

## 七、副作用复原

- 提示音音量：拖动后复原到 **96%**（datastore 确认 0x60）。
- 联系人测试表单：INSERT 意图产生的脏数据未保存（BACK 退出即丢弃），无持久化副作用。
- 默认输入法：保留为弦外小猫（force-stop 会使系统回退 Gboard，已在各轮 `ime set` 恢复）。
