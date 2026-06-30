# Changelog

All notable changes to WordTaker (FlorisBoard fork) are documented here.
This project adheres to [Keep a Changelog](https://keepachangelog.com/) and
[Semantic Versioning](https://semver.org/).

## [0.33.1] - 2026-07-01

### Fixed
- 启动图标小猫居中 (item1): 自适应图标前景 `ic_app_icon_foreground.xml` 中，小猫头主体
  (耳朵+头，排除右上角装饰星) 的几何中心未对齐画布中心，启动器/商店图标里猫头偏上。
  按主体包围盒重算：水平中心已在 54、垂直中心由 translateY 49→55.76 校正，scale 0.84→0.88
  让主体更好填充安全区。三个源集 (main/debug/beta) 同步一致。

### Changed
- 设置界面配色对齐微信「+」面板 (item6): IME 内设置面板 `ImeSettingsLayout.kt` 与宿主设置页
  `MinimalSettingsScreen.kt` 改为浅灰底 (#F2F3F5) + 分组白卡 (RoundedCornerShape)，干净统一。
  深色模式协调深灰 (底 #1C1D1F / 卡 #2A2B2E)。内容/功能/文案完全不变，仅调背景与卡片观感。

## [0.33.0] - 2026-07-01

### Changed
- 顶条改为「一行」(item2): 设置图标 + 中间睡猫 + 语音(麦克风) + 折叠箭头处于同一行。
  原来工具栏图标 (ImeToolbar) 单独一条、睡猫在其下方另一条，语音图标离猫垂直距离过高。
  现把三枚图标合并进 `CatKeyboardLayout` 顶条 Row：设置在左、语音/折叠在右、睡猫由 Layer 3
  居中绘制于同一行，语音与猫垂直对齐、间距一致。`ImeWindow` 在 TEXT/CAT_VOICE 下不再额外渲染
  `ImeToolbar`（其余 媒体/剪贴/历史/设置 模式仍保留以做导航）。ZZZ 仍从猫头顶自然冒出
  (CatSkin demo-world 头顶定位不变)。猫尺寸盒宽度 0.42→0.34，与 28dp 图标在同一行里协调。
- 设置图标改回常规齿轮 (item3): `ic_wt_settings.xml` 由「笑脸小猫头」改为标准极简单色齿轮 (gear)。
- 工具栏白色圆形底缩小、对齐微信 (item4): 类设置按钮白圆 34→28dp、图标 20→17dp，
  比例协调更干净和谐。`ToolbarIconButton` 提为公开、与顶条共用同一按钮样式。
- 点猫出场更平滑 (item5): 点击瞬间即开始录音(触发不变)，但放大走出动画由「直接跳出」改为
  「由小变大、缓缓出现」—— `activeAnim` 改用 FastOutSlowIn 缓出曲线，进入 520ms / 退出 360ms，
  渐进放大、平滑淡入，观感自然不突兀。

## [0.32.2] - 2026-07-01

### Fixed
- 键盘按键没反应、打不出字 (item7, 主流程致命 bug): `CatKeyboardLayout` 待机态在整面板
  挂了一层 `Modifier.clickable(enabled = false)` 的全屏猫覆盖层 (Layer 3, `matchParentSize`)。
  Compose 中 `clickable(enabled = false)` 仍占据 pointerInput 节点、仍消费 down/up 触摸事件
  (只是不回调 onClick)，把键盘所有按键的点击全部吞掉 → 按键无效、不上屏。修复：仅在 active
  (录音/处理) 时才组合这层全屏点击 (用 `conditional(active)`)；待机时点猫开录改由 Layer 4
  顶条专属点击区负责，键盘按键的触摸因此正常落到下面的 `TextInputLayout`，打字恢复。

## [0.32.1] - 2026-07-01

### Changed
- 启动图标小猫垂直更居中、更集中 (item1): 自适应图标前景 `ic_app_icon_foreground.xml`
  (main / debug / beta 三个源集同步) 的 `cat` group `translateY 54→49`、`scale 0.7776→0.84`，
  使猫头在 108×108 安全区(中心 72×72)内上下居中并适当放大填充。
- 主页头像下文案 (item2/item3): 去掉单独的 "KittyEcho" 文本行；副标题首行 "弦外小猫…"
  去掉加粗、字号由 15sp 统一为 13sp，与第二行一致(常规字重、居中两行)；"KittyEcho" 字样一并移除。
- "启用与授权" 区块条目文案 "语音输入（麦克风）授权" → "麦克风授权" (item2)。
- 关于区块删除 "弦外小猫 / KittyEcho" 应用名行，其余(副标题/二维码/数据安全/版本行)不变 (item4)。

## [0.32.0] - 2026-07-01

### Changed
- 键盘视觉 1:1 对齐微信输入法 (item5): 键盘主题 (`floris_day.json` / `floris_night.json`)
  按微信观感重做配色 —— 浅色：键盘底 `#ECECEC` 冷调浅灰、字母键纯白 `#FFFFFF` 带柔和阴影
  (`shadow-elevation 0→1dp`)、功能键 (Shift/退格/123/中英/换行) 由偏暖米灰 `#C7C0BB`
  改为冷调浅灰 `#D2D6DB` 并加 1dp 阴影、按下态 `#C2C7CD`；主色/回车可发送态/候选词/弹窗聚焦
  统一为微信绿 `#07C160`(按下 `#06AD56`)；`--on-surface-variant` 调冷 `#8A9099`。
  深色沿用冷灰 `#2C2C2C/#656565/#3C3C3C`，主色同步微信绿。全拼 QWERTY 与九宫格共用此主题，
  深浅两套均协调。猫单界面顶条/录音纯色底 `WT_PANEL_GRAY` 由 `#F4F4F5` 改为微信底色 `#ECECEC`，
  整体不发暖。工具栏图标(笑脸猫/语音/折叠)与空格话筒、候选条机制保持不变。

## [0.31.0] - 2026-07-01

### Changed
- 单界面合并 (item6): 撤销「默认进 CAT_VOICE 全屏猫 / 点按钮切 TEXT 键盘」的双界面切换，
  改为**同一界面内的状态过渡**。新增 `CatKeyboardLayout` 统一面板：待机 = 键盘上方一条
  顶条里小猫趴着睡觉(Zzz) + 键盘按键区正常显示；点击小猫(或工具栏语音/长按空格)开始录音 →
  键盘按键淡出露出纯色底、同一只猫由小变大走到中间来回走动；停止→识别→润色→commit→写历史
  (复用 `VoiceViewModel` 链路)→ 猫缩回顶条、键盘恢复。全程**面板高度恒定**(单 Box + matchParentSize
  叠层，过渡为纯 cross-fade + 猫缩放，无垂直跳变)。`ImeWindow` 中 TEXT/CAT_VOICE 同渲染
  `CatKeyboardLayout`。
- 外部触发改为请求录音：新增 `VoiceTrigger` 进程内通道 + `VoiceViewModel.startFromExternal`，
  工具栏语音图标 / 长按空格 / `IME_UI_MODE_CAT_VOICE` 键不再「切屏」，而是在单界面上直接开录。
- 音符/灯泡定位修正 (item6): `CatSkin` FX 锚点收到「头部左上/右上稍近处」——
  `FRONT_SIDE_X` 22→13、`FRONT_UP_Y` -10→-16、`NOTE_FRONT_BIAS` 8→4，
  使效果随 `lastDir` 朝向出现在猫头上方、靠近头部的左/右上角。

### Removed
- 删除 `CatVoicePanelLayout`（旧全屏猫语音界面 + 切键盘按钮），其面板灰色常量
  (`WT_PANEL_GRAY`/`DARK_PANEL_BG`) 迁入 `CatKeyboardLayout`。

## [0.30.0] - 2026-06-30

### Changed
- 润色一律走云端中继 (item3): 安卓端不再内置任何提示词文本，润色请求体只发 `{ text, mode }`，
  系统提示词全部由云端中继按 `mode` 选取。AI 角色改为 常规 / 高情商改写 / VibeCoding专用 三项，
  **默认=常规**；角色→mode 映射：常规→`normal`、高情商→`gaoeq`、VibeCoding专用→`copywriting`。
  `RelayClient.polish` 新增 `mode` 参数（不再硬编码 `copywriting`）。

### Removed
- 删除客户端润色后处理 `RealPolisher.applyRoleTone`（高情商 您→你 本地替换），改为完全信任
  云端按 mode 返回的结果，避免本地提示词/改写逻辑泄密与误判。

## [0.29.0] - 2026-06-30

### Changed
- 主页品牌区 (item2): removed the large "弦外小猫KittyEcho" headline under the cat avatar;
  the subtitle now spans two lines — a bolded/enlarged brand-name line ("弦外小猫KittyEcho是一款
  AI 语音输入法，") plus a centered second line "能听你弦外，说你未说。".
- 键盘管理 (item4): replaced the text/checkbox switch with two side-by-side shrunken mini-preview
  cards (全拼 / 九宫格), mirroring onboarding step-1 cards; tapping a card writes
  selectedKeyboardStyle and switches immediately, selected card highlighted with a check.
- 关于页 (item5): moved the 版本 X.Y.Z row to the very bottom of the 关于 page, below the 数据安全
  block; the app-name line (弦外小猫 / KittyEcho) now uses the same size as the 功能建议 / bug反馈
  section title (15sp SemiBold).

### Added
- 主页启用与授权 (item6): the three onboarding actions — 启用输入法 (system IME settings),
  切换输入法 (showInputMethodPicker), 语音输入麦克风授权 (RECORD_AUDIO request) — are now also
  available directly on the home page with 已完成 / 去开启 status, so users can complete them
  anytime. The first-launch SetupScreen flow is retained.

## [0.28.0] - 2026-06-30

### Changed
- 猫语音界面尺寸自适应 (item1): the cat draw box is now a WeChat-voice-area-like proportion of
  the panel (`CAT_HEIGHT_FRACTION` 0.92 → 0.46, clamped 120–168dp) instead of nearly filling
  it. The cat sprite drops from ~131dp to ~65dp tall, sits centred, and no longer dominates /
  overflows the panel or visually crowds the host content. Panel height stays at
  `keyboardUiHeight() + 44dp` (parity with the keyboard界面, zero-jump on switch); sizing is now
  fraction + dp based so it self-adapts across screen sizes / densities. CatSkinFx head-top
  effects (♪ notes / 💡bulb / ✨sparkle / Zzz) stay anchored to the cat at the smaller scale.

## [0.27.0] - 2026-06-30

### Added
- 键盘管理 (#2/#22): a 全拼(全键盘拼音) / 九宫格(T9拼音) switcher in both the in-IME settings
  panel and the host app settings. Selecting a style writes
  `internal.selectedKeyboardStyle`, so SubtypeManager re-seeds the active subtype and the
  keyboard layout switches in place — no Activity, no navigation. Placed directly above 关于.
- 历史记录 entry inside settings (#12): a "查看历史记录" item opens the in-IME history view
  (ImeUiMode.HISTORY) in place; the toolbar no longer carries a history icon.
- History hint (#11): the in-IME history header now shows "点按任意一条即可上屏" next to the title.
- Clear-all confirmation (#13): tapping 清空 shows an inline "清空全部历史记录？/此操作不可恢复。"
  confirm overlay (取消/确认). Rendered inside the keyboard window — no platform Dialog, so no
  BadTokenException. Per-item delete stays one-tap.

### Changed
- 关于 page redesign (#19/#20): 应用信息 (弦外小猫 / KittyEcho + 版本, real app version),
  功能建议 / bug反馈 with a centered WeChat QR (drawable/feedback_qr.png), and 数据安全 two lines
  (🔒本地 / 🗑删除). Brand subtitle updated to
  "弦外小猫KittyEcho是一款 AI 语音输入法，能听你弦外，说你未说。"

### Removed
- 极简模式 toggle hidden from settings (#17); underlying `minimal` value stays default false.
- 皮肤 row hidden from settings (#18).

## [0.26.0] - 2026-06-30

### Added
- 中/英 toggle on the main keyboard (#16). The language-switch key (-227) now flips
  between Chinese (pinyin candidates) and English (latin letters commit directly, no
  Chinese candidates). Toggling finalizes any pending composition and clears the
  candidate bar. The key shows a live "中"/"英" label instead of a globe icon.

### Changed
- Keyboard aligned to WeChat (微信) look (#16, #12): light gray keyboard background and a
  warm-gray function-key fill (#C7C0BB day / #3C3C3C night) matching the WeChat reference,
  white key faces, brand-green enter/accent retained. Applies to full-pinyin QWERTY and
  T9 nine-grid, both day/night themes.
- Top toolbar (#12, #8): leftmost = settings (now a self-drawn极简 smiling cat-head icon,
  ic_wt_settings.xml); right side = voice + collapse only. History icon removed from the
  toolbar. All three icons sit on white circular buttons.
- Spacebar (#14): removed the "拼音罗马字" subtype/language label; spacebar now shows a mic
  icon in the main keyboard. Short-press still types a space / commits the first candidate.

## [0.25.0] - 2026-06-30

### Changed
- Cat animation now a 1:1 port of the Mac CatSkinFx. The whole scene (cat + bulb/sparkle/
  sweat/notes/Zzz) is drawn in one flat "demo world" coordinate space (height DEMO_H=72,
  bottom-anchored) and mapped onto the panel with a single uniform scale. Sleep and walk now
  share one sprite size, and every head-top effect stays rigidly attached to the cat.
- Music notes spawn from the correct position (#10). Note/Zzz/FX anchors (FRONT_SIDE_X,
  FRONT_UP_Y, NOTE_FRONT_BIAS, ZZZ_BOTTOM) now apply in the same flat space as the cat, so
  notes emit from just above the cat's head, biased toward its facing direction — matching Mac.
- Cat enlarged ~2x (#9). The CatSkin draw box is ~92% of the panel height; the previous
  independent 3x graphicsLayer + per-sprite fill-scale (which detached the FX anchor) was removed.
- Panel background unified to a light keyboard-gray (#7), one step lighter than the prior
  WeChat draft (0xFFEDEDED -> 0xFFF4F4F5). Applied to both the cat voice界面 and the keyboard
  panel; dark mode uses a coordinated dark gray.

## [0.24.0] - 2026-06-30

### Added
- Microphone-permission onboarding step. Granting RECORD_AUDIO is now the final (3rd)
  onboarding step, requested directly from the host Activity; granting or skipping both
  complete onboarding (mic can be granted later from the cat panel).
- Cat voice界面 keyboard-switch button. The cat voice surface now carries its own
  right-side button to switch to the keyboard界面.

### Changed
- Dual parallel main surfaces. Voice input is no longer an overlay stacked on the keyboard;
  it is a standalone CAT_VOICE界面 (default on open) switched in-place with the keyboard界面
  via `imeUiMode`. The IME opens on the cat voice界面 (sleeping cat, no keyboard); tap the cat
  to record → walk → process → commit → return to sleep. The keyboard界面 toolbar's voice
  icon switches back to the cat界面. Panel heights are kept equal (cat panel adds the 44dp
  toolbar height it hides) so switching never jumps vertically.
- Onboarding step 1 title changed to "请选择键盘"; the keyboard-style page now offers only
  全键盘拼音 (全拼) and 九宫格拼音 (T9). 双拼/五笔/笔画/手写 are down-lined from the selection
  page (layouts/subtypes retained in code for later re-enable).

### Fixed
- Removed the lingering "躺猫一闪而过" flash after voice processing. The old cat voice
  OVERLAY (a second CatSkin instance scrimmed on top of the keyboard) is no longer rendered;
  the single in-place cat panel ends cleanly on the sleeping cat with no second sprite.

## [0.23.1] - 2026-06-30

### Fixed
- Onboarding keyboard-style selection had no effect on the active keyboard. The host app
  constructs `SubtypeManager` (and eagerly seeds the default qwerty-pinyin subtype) before
  onboarding writes the user's chosen style, and on a fresh install the persisted subtype
  list can stay empty while the keyboard already runs on the empty-list fallback. As a
  result, picking 九宫格/双拼/五笔/笔画/手写 still booted into full-keyboard pinyin. Two
  changes in `SubtypeManager`: (1) the empty-list fallback in `evaluateActiveSubtype` now
  uses `Subtype.pinyinDefaultFor(selectedKeyboardStyle)` instead of the hardcoded
  qwerty-pinyin default, so the chosen style drives the layout immediately; (2) a
  reconciliation collector on `internal.selectedKeyboardStyle` re-evaluates the active
  subtype and, while the list is still the pristine two-item default seed, swaps the Chinese
  subtype to match the chosen style (idempotent, never touches a user-curated list).
- 五笔 / 笔画 produced zero candidates ("no such table: wubi/bihua"). The shape-based
  language pack's SQLite DB was never opened against the bundled `han.sqlite3`: the
  asset→working-dir copy could yield a missing/empty file, and the active-language-pack set
  was derived only from the (often empty) persisted subtype list, so the pack never loaded.
  In `LanguagePackExtension.onAfterLoad`, validate the working-dir DB actually contains
  tables and otherwise fall back to copying the bundled `han.sqlite3` straight out of APK
  assets into the files dir. In `HanShapeBasedLanguageProvider`, include the active subtype's
  locale (not just the persisted subtype list) when computing the active language packs, so
  the pack loads even when the list is still empty.

## [0.23.0] - 2026-06-29

### Added
- 手写 (Handwriting) input method — the sixth and final Chinese style. Fully offline,
  no Google Play Services / ML Kit. A Compose handwriting pad
  (`wordtaker/handwriting/HandwritingPad.kt`) captures multi-stroke ink via
  `detectDragGestures`, renders it live on a `Canvas`, and (after a short pen-lift
  debounce) recognizes it on a background thread, pushing candidates to the standard
  candidate bar; tapping a candidate commits it and clears the pad. The pad replaces the
  key grid for the handwriting subtype and keeps a bottom function row (退格/清除/空格/
  中英/话筒/换行).
- Offline recognizer (`ime/nlp/handwriting/HandwritingRecognizer.kt`): loads a bundled
  template DB and matches user ink with an independent analyzed-sub-stroke algorithm
  (normalize to unit box → resample → corner-split into sub-strokes → score by
  direction + length + center difference with a stroke-count prior + banded DP
  alignment). New `HandwritingLanguageProvider` (push-driven; registered in `NlpManager`).
- New `zh-CN-handwriting` routing: `Subtype.HANDWRITING_DEFAULT`,
  `pinyinDefaultFor("handwriting")`, and a placeholder `handwriting_pad` layout. The
  handwriting onboarding card is now enabled (six styles all selectable) with a "写"
  preview; "敬请期待" badge removed for it.
- Recognition template data `assets/handwriting/mmah.hwr` (~827 KB, JSON content),
  derived from "Make Me a Hanzi" stroke medians via HanziLookupJS's mmah packing —
  Arphic Public License; attribution in `assets/handwriting/NOTICE.txt`. No third-party
  recognizer code is bundled, only the permissively-licensed stroke data. versionCode 148.

### Changed
- `androidResources.noCompress` now includes `hwr` so the handwriting template DB is
  stored uncompressed in the APK (mmah ships as `.hwr` to avoid forcing all layout
  `.json` assets uncompressed).

## [0.22.0] - 2026-06-27

### Added
- 五笔 (Wubi-86) input method: shape-code typed on the standard qwerty letter keys,
  decoded by the shared `HanShapeBasedLanguageProvider` against a new `wubi` table
  (88,356 rows) merged into `han.sqlite3`. Source code table from `missdeer/wubi-tables`
  (Apache-2.0). New `zh-CN-wubi` preset + hardcoded `Subtype.WUBI_DEFAULT`.
- 笔画 (Stroke) input method: five basic strokes 横/竖/撇/捺/折 encoded as h/s/p/n/z,
  decoded by `HanShapeBasedLanguageProvider` against a new `bihua` table (6,938 rows)
  merged into `han.sqlite3`. Stroke-order data from `cnchar` (MIT) + Jun Da frequency
  (MIT). New 5-key `stroke_5key` layout, `zh-CN-stroke` preset + `Subtype.STROKE_DEFAULT`.
- Both methods are selectable in onboarding (五笔 / 笔画 cards now enabled; 手写 still
  "敬请期待"). 笔画 card shows a 5-key stroke preview. versionCode 147.

### Changed
- `androidResources.noCompress` now includes `sqlite3` so the shape-code database is
  stored uncompressed in the APK.

## [0.21.0] - 2026-06-27

### Added
- 双拼(小鹤) input method (`ShuangpinLanguageProvider`, `ShuangpinConverter`): Xiaohe
  two-key syllables are expanded to full Hanyu Pinyin and decoded by the shared AOSP
  pinyin engine. New `shuangpin_qwerty` layout and `zh-CN-shuangpin` preset.
- 九宫格拼音 T9 input method (`T9LanguageProvider`, `pinyin_t9` nine-key grid layout):
  digit keys expand (bounded cartesian product, cap 256) into letter-strings searched
  against the shared pinyin engine; candidates merged + de-duped. New `zh-CN-t9` preset.
- Both new methods are selectable in onboarding setup (双拼 / 九宫格拼音 cards now enabled).
- `PinyinNativeBridge`: single shared, mutex-serialized gateway to the AOSP pinyin
  decoder, reused by the QWERTY-pinyin, Shuangpin and T9 providers (one open, one lock;
  no provider closes the shared decoder). versionCode 146.

## [0.20.0] - 2026-06-27

### Added
- In-IME settings panel (`ImeUiMode.SETTINGS`, `ImeSettingsLayout`): the toolbar
  Settings icon now opens an in-place极简 panel instead of launching the Activity.
  Contains only AI role (VibeCoding专用 / 高情商改写, names only) and prompt-tone
  controls; no descriptions, no "关于", no "极简模式". Tapping the icon again returns
  to the keyboard.
- "喵" prompt tone ported from the macOS client (`res/raw/meow.mp3`), played via
  SoundPool on record start (1.0×) and end (0.85×). New `toneStyle` setting
  (default `meow`; `beep` keeps the original synthesized tones).

### Changed
- In-IME history (`ImeHistoryLayout`) now shows both the original ASR transcript
  ("原文：…") and the AI-polished result ("润色：…") per entry, like the desktop
  client. Tapping still commits the polished text (versionCode 145).
- `ToneController` now takes a `Context` and supports meow/beep styles; default
  prompt tone is 喵 for new installs.

## [0.19.0] - 2026-06-27

### Added
- Cat voice-input OVERLAY: a centred medium walking-cat overlay rendered on top of
  the keyboard during recording/recognition/polishing, auto-dismissing back to the
  keyboard on completion (`CatVoiceOverlay`, `VoiceOverlayController`).
- New minimal thin-line toolbar icons (`ic_wt_settings/history/voice/collapse`).
- `SwipeAction.TRIGGER_VOICE_INPUT` — space long-press now triggers the voice overlay.

### Changed
- The keyboard is now the single main IME surface: a fresh input session defaults to
  the QWERTY/pinyin keyboard instead of the cat voice panel (versionCode 144).
- Toolbar reduced to four controls — Settings (left); History, Voice, Collapse (right);
  Voice shows the overlay (no longer a separate mode).
- WeChat-style minimal keyboard: letter keys render uppercase (cosmetic only; pinyin
  decoding unaffected), bottom row simplified to [123][，][space][中/英][enter], and the
  1-0 hinted number row is disabled by default.

### Fixed
- Space long-press no longer opens the system IME picker / subtype switch.

## [0.18.0] - 2026-06-26

### Changed
- Signed release build packaging the 10-item rework (versionCode 139).

### Fixed
- Release build R8 stripped sherpa-onnx JNI config fields (e.g. `blankPenalty`) causing `NoSuchFieldError` and SenseVoice local STT init failure; added ProGuard keep rules for `com.k2fsa.sherpa.onnx.**` and `org.florisboard.libnative.PinyinDecoderKt` (versionCode 140).
- Fixed AGP 9 source-set asset declaration that broke runtime asset directory enumeration, restoring pinyin keyboard layout indexing and Chinese candidate generation (versionCode 141).
- Enlarged idle/sleeping cat so it fills the voice panel (versionCode 142).
- Fixed pinyin keyboard producing no candidates (composing region was gated off when global suggestions disabled; now honors CJK provider forcesSuggestionOn) (versionCode 143).
- Sleeping cat now scales to fill the voice panel based on panel height (versionCode 143).

## [0.17.0] - 2026-06-26

### Added
- 键盘上方「统一工具栏」，键盘态 / 语音态 / 历史态共用同一条：最左为设置（唯一跳出，打开宿主统一极简设置页），右侧依次为语音输入 / 历史记录 / 键盘 / 折叠箭头，右侧四项全部在 IME 窗口内原地切换，不再跳 Activity。
- 原创「语音输入」矢量图标 `ic_voice_input`（对话气泡内含声波，区别于普通话筒）。
- IME 窗口内历史视图（新增 `ImeUiMode.HISTORY`），复用 Room 历史数据源，点击某条将润色文 commit 到当前输入框，支持就地删除 / 清空。

### Changed
- 猫语音面板移除自带的右上四图标行，统一交由新工具栏承载，避免两套不一致。
- 历史入口由启动 `WordTakerHistoryActivity` 改为打开 IME 内历史视图（Activity 代码保留）。

### Fixed
- 引导步骤一「九宫格拼音」卡片改为「敬请期待」禁用态（其底层与全键盘拼音一致，标为可选属不诚实），仅保留「全键盘拼音」可选并默认。

## [0.16.0] - 2026-06-26

### Added
- 全新两步首启引导：第一步「选择你喜欢的中文键盘」六张卡片（九宫格拼音 / 全键盘拼音可选，手写 / 笔画 / 五笔 / 双拼标注「敬请期待」并禁用），Compose 绘制迷你键盘预览，默认选中全键盘拼音；第二步「启用 + 切换」猫头像 + 两个单动作按钮（启用 → 自动检测 → 切换选择器自动弹出）。
- 新增偏好 `internal__selected_keyboard_style`（qwerty_pinyin / t9_pinyin），驱动首启种入的拼音 subtype。

### Changed
- 移除旧三步引导（欢迎 / 启用 / 麦克风权限），改为「选键盘 → 启用并切换」两步。
- 引导完成后直接进入统一极简设置页（顶部品牌 + 设置项），无中间首页层。

## [0.15.0] - 2026-06-26

### Added
- 离线语音模型随 APK 打包，安装即用，无需联网下载。

### Changed
- 首启从 assets 静默安装模型到 filesDir。

### Removed
- 引导页与设置页的语音模型下载/状态 UI。

## [0.14.3] - 2026-06-26

### Fixed
- 录音结束后面板卡死在"处理中"、猫消失、无历史无上屏 (最高优先): `VoiceViewModel.stopAndProcess` 的处理链缺少终态保证。根因: polish 网络调用无独立超时可挂死、异常被 runCatching 静默吞掉后 phase 停留在 Polishing/Recognizing、history 写入未 await、`_committed` 在 history 之后发射导致 scope 取消时丢字。修复: 整条链用 `try/finally + reachedTerminalState` 包裹，任何异常路径都 reset 回 Idle (彻底消除卡死)；`withTimeout(10s)` 包裹 `polisher.polish`，超时/失败均 fallback 到 raw 原文并照常上屏+写历史；调换顺序为先 `_committed.tryEmit(polished)` (非挂起) 再 `historyRepository.add` (挂起、runCatching)，确保 scope 被取消也不丢文字；空识别复用已有 `_toast` 显示"未识别到语音"后回 Idle；`stopRecordingAndEndTone` 在处理中为 no-op，不再中途拆面板；全链路加 `VoiceVM` 日志。
- 睡眠猫动画错误 (上下抖动 + 偏小): `CatDraw.drawSleepCat` 移除基于 cos 的 `sx/sy` 呼吸缩放 (`sy=1-0.06*phase` 造成身体上下抖动)，睡眠时猫身体完全静止 (Zzz 浮字仍漂浮)；`CatSkin.CAT_SCALE` 2.5f → 3f，与 demo `scale(3)` 一致，猫更大居中。

## [0.14.2] - 2026-06-26

### Fixed
- 首启默认中文拼音未生效 (最高优先): 全新安装后键盘仍是 en-US、输入 nihao 上屏字面无候选。根因是 `SubtypeManager` 的默认 subtype 种入依赖异步 `subtypePresets` flow，IME 取当前 subtype 时种入往往尚未完成，active 落回 en-US 的 `Subtype.DEFAULT`，拼音 NLP provider 不加载。修复: 新增硬编码 `Subtype.PINYIN_DEFAULT`（镜像 `zh-CN-pinyin` preset，含拼音 nlpProvider + `pinyin_qwerty` 布局），`activeSubtypeFlow` 初值与空列表兜底均改为该拼音默认（不再 en-US），种入改为不依赖 preset flow 的 eager 同步种入 [拼音(active), en-US]，保留 TOCTOU 防覆盖。首启即中文拼音、nihao→你好 可上屏，地球键仍可切英文。
- IME 内设置页标题: `WordTakerSettingsActivity` 顶栏标题由 "WordTaker 设置" 改为 "弦外小猫"。

## [0.14.1] - 2026-06-26

### Fixed
- 下载进度回调跨线程崩溃: `SetupScreen.kt` 的 `ModelDownloader.download` onProgress 回调在 `Dispatchers.IO` 线程直接写 Compose `mutableStateOf`（`indeterminate`/`progress`），非线程安全且引导首步几乎必触发。改为在回调内 `scope.launch(Dispatchers.Main)` 切回主线程更新 UI 状态。
- SubtypeManager 种入默认 subtype 的 TOCTOU 竞态: `seedDefaultSubtypes` 写 prefs 前再次从 prefs 实读 `localization.subtypes`，若非空则放弃 seed 并释放 `didSeedDefaults` 守卫，避免进程重启时把用户已有 subtype 列表覆盖为默认。
- 拼音 preset 大小写脆弱匹配: 默认中文拼音 preset 改为按 language/country/variant 分字段比较，variant 用 `equals("pinyin", ignoreCase=true)`，避免系统返回大写 variant 时匹配不到、首启无中文默认。
- `isDownloaded` 主线程磁盘 IO (ANR 风险): `SetupScreen.kt` 改用 `LaunchedEffect + Dispatchers.IO` 异步检测 228MB 模型文件，检测期间显示加载态，避免阻塞主线程。
- 下载 readTimeout 过短: `ModelDownloader` 的 OkHttp `readTimeout` 由 60s 提升到 300s，降低 228MB 模型在网络抖动下中途超时失败的概率。

### Changed
- 角色常量去重: `MinimalSettingsScreen.kt` 不再各自定义 `ROLE_VIBECODING`/`ROLE_GAOEQ`，统一引用 `wordtaker.ui` 中的共享常量，避免手误导致角色切换静默失效。

## [0.14.0] - 2026-06-26

### Added
- Release signing config in `app/build.gradle.kts` (`signingConfigs.release`, keystore `wordtaker-release.jks`); `release` buildType now signs with it instead of being unsigned. Native arm64-v8a abiFilters, `noCompress += "dat"` (dict_pinyin.dat stays Stored), and the `KittyEcho-<versionName>-<buildType>.apk` output pattern preserved unchanged; R8 (`isMinifyEnabled` / `isShrinkResources`) left enabled.
- 模型下载引导化: 语音模型下载流程改为引导式（download guidance）入口，明确已下载/未下载状态。

### Changed
- 默认中文拼音: keyboard now defaults to the Chinese Pinyin subtype out of the box.
- 统一极简设置页: settings unified into a single minimal settings screen.
- 关于页 Mac 式: About screen restyled to match the Mac-app look.
- 角色 / 极简模式小字副标题: role selection and 极简模式 rows now carry small-text subtitles.
- 微信式极简键盘: keyboard restyled toward a WeChat-style minimal look.
- 猫面板高度对齐 + 放大: cat panel height aligned and enlarged.
- 版本号产品化: dropped the `-alpha01` pre-release suffix; bumped version 0.13.1-alpha01 → 0.14.0 (versionCode 131 → 132).

## [0.13.1-alpha01] - 2026-06-26

### Changed
- Unified the in-app brand icon to a dedicated full-canvas black-cat vector (`drawable/ic_brand_cat.xml`, white rounded-rect + cat with yellow eyes). Welcome (SetupScreen), Home header (HomeScreen), and About (AboutScreen) now reference `R.drawable.ic_brand_cat` instead of the launcher adaptive icon `R.mipmap.floris_app_icon`, so the brand renders edge-to-edge instead of shrunk into the launcher safe zone.
- Normalized brand icon contentDescription to "弦外小猫" across Setup/Home/About.
- Bumped version 0.13.0-alpha01 → 0.13.1-alpha01 (versionCode 130 → 131).

## [0.13.0-alpha01] - 2026-06-26

### Added
- Minimal 3-step onboarding (welcome / enable IME / microphone permission) replacing the FlorisBoard multi-step setup.
- Minimal Settings screen (MinimalSettingsScreen) bound to the WordTaker SettingsRepository: AI 角色 (VibeCoding专用 / 高情商改写), 提示音, 极简模式, 皮肤 (小猫, read-only), 语音模型下载 (local 3-state stub), 关于.
- `modelDownloaded` flag in SettingsRepository backing the voice-model download stub.

### Changed
- Simplified Home to a brand header + ready status + a try-it hint, keeping IME enable/select guard cards; reduced navigation to 设置 and 关于 only.
- Bumped version 0.12.2-alpha01 → 0.13.0-alpha01 (versionCode 129 → 130).

## [0.12.2-alpha01] - 2026-06-25

### Fixed
- PinyinLanguageProvider: use `detachFd()` instead of `.fd` when passing the dict file descriptor to the native decoder, preventing fdsan SIGABRT crash (`fdopen()` on a FD still owned by ParcelFileDescriptor) that caused FlorisImeService to crash on every keyboard show attempt.
- Bumped version 0.12.1 → 0.12.2 (versionCode 128 → 129).

## [0.12.1-alpha01] - 2026-06-25

### Fixed
- dict_pinyin.dat now stored uncompressed in APK (noCompress += "dat") so the native mmap decoder can open it via fd; previously caused zero candidates.
- Space key during pinyin composing now commits the first Hanzi candidate instead of inserting an ASCII space.
- Enter key during pinyin composing now commits the first Hanzi candidate instead of committing raw ASCII pinyin (e.g. "nihao").
- TOCTOU race in PinyinLanguageProvider.suggest: isDecoderReady check moved inside nativeLock to prevent calls on a destroyed decoder.
- destroyIfNecessary in NlpManager.ProviderInstanceWrapper: getAndSet(true) corrected to getAndSet(false) so destroy is called when alive, not when already dead.
- VoiceViewModel.onCleared: stopRecordingAndEndTone() now called before toneController.release() to stop mic when panel is dismissed.
- FlorisImeService.onWindowHidden: speechEngine.cancel() called when window is hidden while in CAT_VOICE mode.
- FlorisApplication: loadLibrary failures now logged via android.util.Log instead of silently swallowed.
- Bumped version 0.12.0 → 0.12.1 (versionCode 127 → 128).

## [0.12.0-alpha01] - 2026-06-25

### Added
- Cat-voice mic entry on the keyboard: new `IME_UI_MODE_CAT_VOICE` (-214) key code +
  handler switches the keyboard back to the WordTaker cat-voice panel directly. Placed on
  the shared bottom row (replacing the emoji/media key) with the mic icon. Reverse of the
  panel's existing Keyboard button.

### Changed
- Chinese keyboard UI/interaction aligned to the YuyanIme hi-fi prototype (Phase 2 polish):
  - Snygg day/night themes recalibrated to prototype tokens: keyboard bg `#eceef2` / `#14161b`,
    white/`#2a2e37` letter keys, lighter `--background-variant` function keys, candidate blue
    `#2563cf` / `#6ea8ff`, dark/light Enter accent key, corner radii 7dp/14dp.
  - Candidate bar: first (auto-commit) candidate now bold + blue, larger 18sp candidates with
    rounded pressed state; pinyin composing string (first-candidate secondary text) shown bold
    in candidate blue.
  - Shift key visual states distinguished: manual/auto shift = primary blue, caps-lock = dark
    accent with yellow `#fcd34d` glyph.
- Bumped version 0.11.0 → 0.12.0 (versionCode 126 → 127).

## [0.11.0-alpha01] - 2026-06-25

### Added
- Chinese Pinyin input engine (Phase 1): real offline pinyin→Hanzi candidate conversion.
  - Vendored the AOSP Google PinyinIME native decoder (Apache-2.0) into `:lib:native`
    as a new `libpinyinime.so` CMake target (`src/main/cpp/pinyinime`), arm64-v8a packaged.
  - Bundled `dict_pinyin.dat` (~1.02 MB) at `app/src/main/assets/ime/dict/`.
  - New self-contained JNI bridge (`pinyinime_jni.cpp`) + Kotlin declarations
    (`org.florisboard.libnative.PinyinDecoder`): open/search/getCandidate/choose/reset.
  - New `PinyinLanguageProvider` (`ime/nlp/pinyin`) implementing `SuggestionProvider`,
    registered in `NlpManager`; serializes native calls and loads the dict via asset fd.
  - New `zh-CN-pinyin` subtype + `pinyin_qwerty` 26-key layout; candidates surface through
    the existing Smartbar candidate row (first candidate emphasized, paging supported).
  - Verified: `nihao`→你好, `fang`→放/方/房, `zhongguo`→中国, `woaizhongguo`→我爱中国.

## [0.10.0-alpha01] - 2026-06-25

### Added
- Mic icon in cat panel icon row (left of Keyboard icon): tapping starts recording when idle.
- Mic/Keyboard toggle: tapping Keyboard icon stops any active recording (with end tone) then switches to TEXT keyboard.
- `SpeechEngine.cancel()` API for abandoning a recording without processing.
- `VoiceViewModel.stopRecordingAndEndTone()` — plays end beep and resets state when switching away mid-recording.

### Changed
- App display name changed to `弦外小猫` (all locales including zh-rCN, debug, beta variants).
- APK output filename renamed to `KittyEcho-<versionName>-<buildType>.apk`.
- Cat visual size enlarged 3x (size modifier 240×288 dp, panel height 360 dp).
- Settings icon confirmed as rightmost icon in cat panel action row; order is [Mic][Keyboard][History][Settings].
- Removed "WordTaker" brand text block from bottom of settings screen.
- Vibecoding role display renamed from "通用润色" to "VibeCoding专用".
- Removed subtitle/description text from role selection rows (vibecoding and gaoeq).
- Minimal mode now also hides the "已写入历史" success toast (only text suppressed; cat and icons unchanged).
- `DEFAULT_MINIMAL` confirmed false; no change needed.
- End tone plays on all recording-stop paths including Keyboard-icon cancel.
- versionCode 124 → 125, versionName 0.9.1-alpha01 → 0.10.0-alpha01.

## [0.9.1] - 2026-06-25

### Changed
- 去 FlorisBoard 品牌 + 内部包名重构 (de-brand + package rename):
  - User-visible "FlorisBoard"/"Florisboard" brand text in all `strings.xml`
    (main + ~40 locale variants) replaced with "WordTaker".
  - Kotlin package `dev.patrickgold.florisboard.*` → `com.wordtaker.keyboard.*`
    (app + benchmark): source dirs moved, all `package`/`import`, AndroidManifest
    component names, Room schema dirs, and baseline profile updated.
  - Library module packages `org.florisboard.lib.*` → `com.wordtaker.lib.*`
    (snygg/android/color/compose/kotlin), including module `namespace` in each
    `build.gradle.kts`.
  - `settings.gradle.kts` `rootProject.name` "FlorisBoard" → "WordTaker".
  - versionCode 123 → 124, versionName 0.9.0 → 0.9.1.

### Preserved (intentional)
- `LICENSE` (Apache-2.0) and all `Copyright (C) ... The FlorisBoard Contributors`
  headers — legal attribution, required by the license.
- Native JNI bridge package `org.florisboard.libnative` and the Rust export
  `Java_org_florisboard_libnative_TestKt_dummyAdd` — renaming would require
  rebuilding the prebuilt Rust `.a`/`.so` and risks `UnsatisfiedLinkError`;
  this is an internal, non-user-visible bridge.
- Internal functional identifiers kept for compatibility: custom URI scheme
  `florisboard://` (`SCHEME_FLORIS`), extension MIME `application/vnd.florisboard.extension+zip`,
  and bundled extension asset IDs `org.florisboard.layouts` / `org.florisboard.currencysets`
  (cross-referenced by hundreds of layout configs; not user-facing).
- Third-party dependency `dev.patrickgold.jetpref.*` (separate published library).

## [0.9.0] - 2026-06-25

### Added
- 真实离线语音识别 (sherpa-onnx SenseVoice): replaced the mock ASR with an
  on-device `RealSpeechEngine` (`PcmRecorder` 16kHz mono PCM capture +
  `SenseVoiceController` sherpa-onnx decode). Ported from the WTAndroid project.
  Wired in `AppGraph` behind a `USE_REAL_ASR` flag (default true; flip to false
  to fall back to `MockSpeechEngine`).
- 语音模型下载入口: new "语音模型" section in `WordTakerSettingsActivity` showing
  已下载/未下载 status, a 下载模型 button, and a live progress bar. The ~228MB
  SenseVoice int8 model is fetched on demand by `ModelDownloader` (hf-mirror →
  huggingface fallback) into `filesDir/sensevoice/`; never bundled in the APK.
- 缺模型 / 缺权限 兜底: the voice panel now consumes `VoiceViewModel.event` —
  `ModelRequired` routes to the settings download screen, `PermissionRequired`
  launches a transparent `MicPermissionActivity` to request RECORD_AUDIO (an IME
  service cannot request runtime permissions itself). Toasts surface the reason.

### Changed
- `app/build.gradle.kts`: bundle `libs/sherpa-onnx-1.13.3.aar`, restrict packaged
  ABIs to `arm64-v8a`, keep `.onnx` uncompressed, and `pickFirst` the sherpa-onnx
  / onnxruntime `.so` libs to avoid duplicate-native merge failures.

## [0.8.0] - 2026-06-25

### Added
- WordTaker 设置界面 (`WordTakerSettingsActivity`): standalone Compose Material3
  screen with AI role (通用润色/高情商改写), 提示音 toggle, 极简模式 toggle, and a
  read-only 小猫 skin row. Backed by `SettingsRepository`, follows system dark mode.
- WordTaker 历史界面 (`WordTakerHistoryActivity`): list of records (润色文/原文/
  时间), live search, per-item copy & delete, clear-all with confirm dialog.
  Timestamps formatted as 今天/昨天/M月D日 HH:mm. Backed by `HistoryRepository`.
- 极简模式 UI: when `minimal` is on, the cat panel shows only the cat + the three
  top-right icons and hides all text hints; when off, shows the idle guidance
  "点击小猫开始语音输入" and the recording hint.

### Changed
- Cat panel 历史/设置 icons now launch the new WordTaker activities (with
  `FLAG_ACTIVITY_NEW_TASK`) instead of opening the FlorisBoard settings host.

## [0.7.0] - 2026-06-25

### Added
- 猫语音面板 (cat voice panel) as a new IME UI mode `CAT_VOICE`, shown by default
  when the keyboard opens. The whole panel is one big voice-input region.
- Ported WordTaker voice pipeline under `dev.patrickgold.florisboard.wordtaker.*`:
  cat skin (verbatim drawing logic), `VoiceViewModel`, mock ASR, real relay-backed
  polisher, Room history (入库), DataStore settings, and tone controller.
- Tap-to-record interaction (Idle → Recording → Recognizing/Polishing → Success),
  walking-cat animation, "正在倾听...点击结束" hint above the cat while recording,
  and commit of polished text into the focused field via `EditorInstance.commitText`.
- Top-right panel actions: expand keyboard, history, settings.
- 高情商 (gaoeq) client-side tone softening: 您们 → 你们, then 您 → 你.
- Reserved `minimal` settings flag (no UI yet).

### Changed
- `onStartInputView` now defaults `imeUiMode` to `CAT_VOICE`.
- Added Room ktx, DataStore preferences, OkHttp, lifecycle viewmodel/runtime compose
  dependencies. Added `INTERNET` and `RECORD_AUDIO` permissions.

### Notes
- ASR is currently a mock (fixed Chinese transcript). Real on-device sherpa-onnx /
  SenseVoice ASR is not yet wired.
