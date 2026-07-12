# 键盘按键样式「天花板」规格（Key Style Ceiling Spec）

> 审计日期：2026-07-11 · 版本基线：v0.35.5-debug+e359262（模拟器 emulator-5554 实拍）
> 取证截图：`docs/design/audit-shots/`（light/dark × 字母/符号/数字/九宫格/按压/长按 共 14 张）
> 对标基准：微信键盘（Android）、Gboard、搜狗输入法。目标：逐项达到或超过三者最优值。
> 本文可直接交给实现 agent 照做；所有色值以主题 token 表达，改动集中在主题 JSON + 少量 Kotlin。

---

## 0. 现状架构速览（改哪里）

| 层 | 文件 | 管什么 |
|---|---|---|
| 主题 token（真源） | `app/src/main/assets/ime/theme/org.florisboard.themes/stylesheets/floris_day.json` / `floris_night.json`（另有 `.my` 变体 6 份同步改） | 键帽颜色/圆角/字号/阴影/popup 全部样式 |
| 兜底样式 | `app/src/main/kotlin/com/wordtaker/keyboard/ime/theme/FlorisImeThemeBaseStyle.kt` | 主题缺项时的默认值（与 JSON 同步） |
| 键帽渲染 | `app/src/main/kotlin/com/wordtaker/keyboard/ime/text/keyboard/TextKeyboardLayout.kt` `TextKeyButton()`（L364-477） | SnyggBox + SnyggText，无动画 |
| 按压/长按 popup | `app/src/main/kotlin/com/wordtaker/keyboard/ime/popup/PopupUi.kt`（PopupBaseBox/PopupExtBox）+ `TextKeyboardLayout.kt` boundsProvider（L268-291） | popup 几何与内容 |
| 键间距 | `app/src/main/kotlin/com/wordtaker/keyboard/ime/window/ImeWindowConstraints.kt`（L144-163：手机竖屏 H=2dp V=5dp） | 每侧 margin，键间隙=2×margin |
| 键高 | `TextKeyboardLayout.kt` L244-265（rowHeight cap ×1.12）+ `ImeWindowConstraints.kt` defKeyboardHeight（竖屏 0.26×屏高） | |
| T9 布局 | `app/src/main/assets/ime/keyboard/org.florisboard.layouts/layouts/characters/pinyin_t9.json` + `LayoutManager.kt`（merge characters_mod/default） | 九宫格排布 |
| 阴影实现 | `lib/snygg/src/main/kotlin/com/wordtaker/lib/snygg/ui/SnyggUi.kt` `snyggShadow()` L341-348（Modifier.shadow，纯 ambient，无 offset） | |
| 文本渲染 | `lib/snygg/.../SnyggText.kt`（无自动缩字，超宽即裁剪） | |

当前实测（420dpi）：字母键约 35×41dp，水平间隙 4dp、垂直间隙 10dp，圆角 8dp，字号 22sp Regular；日间键面 #FFFFFF/底 #ECECEC，夜间键面 #656565/底 #2C2C2C。

---

## P0 — 视觉硬伤（必须修）

### P0-1 九宫格键帽拼音字母组被裁剪，只剩数字
- **现状**：`pinyin_t9.json` 的 label 是 `"2 ABC"`…`"9 WXYZ"`，但键帽 22sp 单行 + `TextOverflow.Clip`（key 样式 `text-max-lines:1`，SnyggText 无缩字），文本超出 ~34dp 键宽被硬裁，只显示开头的数字（实拍 `dark-t9.png`、放大验证 4 键只见 "4"）。长按 popup 却显示 "5 jkl"，键面/弹窗信息不一致。
- **天花板**（微信/搜狗 T9）：拼音字母组为主视觉、数字为辅：字母 `ABC` 居中 20sp Medium，数字 `2` 左上角 11sp、前景 60% 透明度（token `--on-surface-variant`）。
- **改法**：在 `TextKeyButton()` 增加 T9 专用分支（`keyboard.mode == CHARACTERS && subtype 为 t9` 或按 label 模式 `^\d [A-Z]+$` 识别）：拆 label 为数字+字母两段，分别用两个 SnyggText 渲染（新元素名 `key-hint` 复用或新增 `key-t9-digit`）；同时 `pinyin_t9.json` label 保持数据不变。
- **优先级**：P0（信息缺失，功能级硬伤）。

### P0-2 九宫格键宽错误：3 列窄键悬浮在中央，两侧大片空白
- **现状**：T9 键沿用默认 `flayWidthFactor = 1.0`（`TextKey.kt` L152-170 无 T9 分支），每键=键盘宽/10≈34dp，3 键行居中后两侧各空 ~35% 宽度（实拍 `light-t9.png`）。
- **天花板**：微信/搜狗 T9 满宽 4 列栅格：左列功能键（符号/分词）、中间 3 列数字键、右列退格/重输，数字键宽≈(键盘宽-边距)/5×1.1，行高与全键盘一致。
- **改法**：
  1. `TextKey.kt` `flayWidthFactor` 增加 T9 case：`KeyType.NUMERIC/CHARACTER` 数字键 → `2.2f`（与 PHONE 模式 2.68f 同思路）；或
  2. 治本：重写 `pinyin_t9.json` 为完整 4 列布局（加 `widthFactor` 字段、左列 `，。？！` 符号键、`分词` 键、右列 delete），并在 `LayoutManager.kt` 为 T9 跳过 qwerty 的 `characters_mod/default` 合并（当前把 shift 塞进第三行，中文 T9 不需要 shift）。
- **优先级**：P0。

### P0-3 按压 popup 预览是"空白高板"，无放大字符
- **现状**：`boundsProvider` 生成 2.5×键高、1.1×键宽的整块面板，从键底伸到键上方（盖住按的键）；`PopupBaseBox` 把 label 以键面同字号 22sp 放在顶部 1/3 处，下方 2/3 全空白（实拍 `light-press-G.png`/`dark-press-G.png`：一根白/灰色长条，顶端一个小字）。且 popup 显示小写 `g` 而键帽是大写 `G`（大写化只做在 `TextKeyButton` L433-441，popup 未同步）。
- **天花板**（Gboard/微信）：脱离式气泡悬于键上方——宽=键宽×1.4，高=键高×1.35，气泡底边=键顶-4dp，圆角 10dp，阴影 elevation 6dp（色 rgba(0,0,0,0.20)），字符=键字号×1.5（≈32sp）居中，大小写与键帽一致；出现 90ms scale 0.85→1 + fade-in（FastOutSlowIn），消失 60ms fade-out。
- **改法**：
  1. `TextKeyboardLayout.kt` L271-291 boundsProvider：竖屏 `keyPopupWidth = visibleBounds.width * 1.4f`；`keyPopupHeight = visibleBounds.height * 1.35f`；`top = key.visibleBounds.top - keyPopupHeight - 4.dp.toPx()`（不再覆盖键身）。
  2. `PopupUi.kt` PopupBaseBox：label 居中整个气泡（去掉 `height(key.visibleBounds.height)` 顶部子盒），新增字号放大——主题 JSON `key-popup-box` `font-size: 32sp`；字母键 label 做与键帽相同的 uppercase 变换。
  3. 动画：PopupBaseBox 外层包 `AnimatedVisibility`/`graphicsLayer` scale+alpha（90ms 入、60ms 出，`FastOutSlowInEasing`）。
  4. 长按扩展 popup（`PopupExtBox`）沿用同一几何，行高=键高×1.1。
- **优先级**：P0（按压反馈是键盘质感第一触点）。

### P0-4 长按扩展 popup 焦点项：深色文字压在饱和绿上
- **现状**：`key-popup-element:focus` 只改背景 `#07C160`，前景继承 `--on-surface`（日间 #1A1A1A），深字压亮绿对比不足且脏（实拍 `light-longpress-E.png`）。
- **天花板**：焦点项前景纯白 + 背景 `--accent`，圆角同气泡内半径（10dp-2dp 内缩=8dp）。
- **改法**：`floris_day.json`/`floris_night.json`（及 .my 6 份）`"key-popup-element:focus"` 增加 `"foreground": "var(--on-accent)"`。
- **优先级**：P0（一行改动）。

---

## P1 — 质感差距（天花板必备）

### P1-1 键帽立体感：ambient 阴影几乎不可见，缺"键底暗边"
- **现状**：`key` `shadow-elevation: 1dp`（日间）/`0dp`（夜间），`snyggShadow` 用 `Modifier.shadow` 默认黑色 ambient+spot、无方向；1dp 在白底上近乎不可见——键帽是"贴纸"不是"键"。
- **天花板**（微信/搜狗共同做法）：键底 1px 暗边 + 极柔和投影：`offsetY +1dp`、blur 2-3dp、日间 rgba(0,0,0,0.18)、夜间 rgba(0,0,0,0.40)；键面与底色对比保持 ≥1.15:1。
- **改法**（二选一）：
  1. 轻量：主题 JSON `key` 增 `"shadow-elevation": "1.5dp"`、`"shadow-color": "#2E000000"`（snygg 已支持 shadow-color），夜间 `"#66000000"`；实测不够再走 2。
  2. 精确：`SnyggUi.kt` `snyggShadow()` 支持 offset（`graphicsLayer` + `drawBehind` 画 offsetY=1dp 的圆角矩形），token 新增 `--key-edge-color`。
- **优先级**：P1。

### P1-2 无任何按压动效（颜色瞬变、无缩放、无回弹）
- **现状**：`selector = PRESSED` 瞬时换背景色（`snyggBackground` 无 animate*），抬手瞬时还原；无 popup 的功能键（space/enter/shift/退格/123）按下只有一帧颜色跳变。
- **天花板**：
  - 按下：0ms 立即变 pressed 色（延迟感为零，保持现状的"立即"）；
  - 抬起：pressed 色 → normal 色 120ms `FastOutSlowInEasing` 渐变；
  - 功能键（无 popup 的键）：按下附加 scale 0.96，抬起 spring 回弹（`spring(dampingRatio=0.75f, stiffness=Spring.StiffnessMediumLow)`）；
  - 全程只动 transform/alpha/color，不触发重排。
- **改法**：`TextKeyButton()`：
  1. 背景色经 `animateColorAsState(targetValue, snap() when pressed else tween(120))` 后喂给 SnyggBox（需给 SnyggBox 提供 override background 入口，或在外层包一个绘制层）；
  2. `Modifier.graphicsLayer { scaleX = scaleY = pressScale }`，`pressScale by animateFloatAsState(if (key.isPressed && !hasPopup) 0.96f else 1f, spring(0.75f, MediumLow))`。
- **优先级**：P1。

### P1-3 夜间模式键面发灰、功能键与底色几乎融为一体
- **现状**（`floris_night.json`）：底 `#2C2C2C`、字母键 `#656565`（太亮太灰，"水泥感"）、功能键 `#3C3C3C`（与底只差 6% 亮度，实拍 `dark-letters.png` 中 shift/退格/123 几乎隐形）。
- **天花板**（微信深色/Gboard dark）：底 `#1C1C1E`；字母键 `#3A3A3C`（pressed `#48484A`）；功能键 `#2C2C2E`（pressed `#3A3A3C`）；文字 `#F2F2F7`；enter 保持 `#07C160`；层级：底 < 功能键 < 字母键，相邻层差 ≥8% 亮度。
- **改法**：`floris_night.json` defines 改 `--background: #1C1C1E`、`--surface: #3A3A3C`、`--surface-variant: #48484A`，`key[code=-7,...]` 背景 `#2C2C2E`/pressed `#3A3A3C`；`floris_pure_night*.json` 同步再降一档（底 #000）。
- **优先级**：P1。

### P1-4 字重：22sp Regular 缺挺拔感
- **现状**：key 无 `font-weight`（默认 400）；中文功能字（符号/换行/空格）同样 400。
- **天花板**：字母/数字键 22sp **Medium(500)**；功能键文字 16sp Medium；空格条 12sp Regular；键内字符垂直光学居中（数字/拉丁与 CJK 混排时 baseline 偏移 ≤1dp——Compose 用 `includeFontPadding=false` 已默认，无需额外处理，验收时截图对比即可）。
- **改法**：主题 JSON `key` 增 `"font-weight": "500"`；`key[code=-201,-202,-203]`（符号/ABC/123 视图键）与 `换行`/`空格` 相关规则同样补 500。BaseStyle.kt 同步。
- **优先级**：P1。

### P1-5 数字键盘出现两个绿色「换行」键堆叠
- **现状**：`light-numeric.png`：右列第 3、4 行各一个独立绿色 `换行`，视觉重复、割裂。
- **天花板**：微信数字盘 enter 是**单个跨两行高键**。
- **改法**：数字布局（`layouts/numeric/*.json` 对应 cjk/默认）中 enter 改为跨行（florisboard 布局不支持 rowspan 时：第 4 行 enter 去掉、第 3 行 enter 高度×2 需要渲染层支持——退而求其次：第 4 行右下角改为 `。` 或空格键，只保留一个 enter）。
- **优先级**：P1。

### P1-6 键间隙节奏：垂直 10dp / 水平 4dp 比例失衡
- **现状**：`ImeWindowConstraints.kt` 竖屏 H=2dp、V=5dp（间隙=4dp/10dp），行间过空、列间过挤，键盘显"松散"。
- **天花板**：微信比例 ≈ 6dp/9dp（间隙），即每侧 H=3dp、V=4.5dp；键帽更宽厚、行距收紧。
- **改法**：`ImeWindowConstraints.kt` L151 `PHONE_PORTRAIT -> 3.dp`（H），L161 `PHONE_PORTRAIT -> 4.5.dp`（V）。注意用户可在设置里缩放（keySpacingFactor），默认值即天花板值。
- **优先级**：P1。

### P1-7 符号面板顶行贴边裁切，无滚动余白
- **现状**：`light-symbols.png` 顶行（# % & + …… 《 》「）最后一键被屏幕右缘硬切一半，无渐隐提示。
- **天花板**：可滚动行两端 12dp 渐隐遮罩（`Brush.horizontalGradient` 到底色），或收纳为整数列。
- **改法**：符号顶行的容器 composable（`ime/text` 下符号行实现，或该行来自 symbols 布局第一行溢出——先确认是否本应换行）加 fade edge；若是布局键数超宽导致的裁切，则精简该行至 10 键内。
- **优先级**：P1。

---

## P2 — 锦上添花

### P2-1 候选词条分隔线颜色在夜间偏亮显脏
- 现状：`--spacer-color: #14FFFFFF`（夜间）实拍偏刺眼（`dark-t9-candidates.png` 中缝隙线明显）。天花板：分隔线 alpha ≤8%（`#0FFFFFFF`），且首尾不画。改 `floris_night.json` defines。

### P2-2 候选词首选高亮
- 现状：拼音候选全部同色（日间 #424242）。天花板（微信/搜狗）：首候选用 `--candidate`（#07C160）+ Medium。已有 `smartbar-candidate-word[auto-commit=1]` 规则走绿色，确认 auto-commit 属性在拼音候选下真正生效；如未生效，在 NlpManager/候选渲染处为 index==0 补属性。

### P2-3 空格条麦克风 icon 的按压涟漪
- 现状：空格按压仅整条变灰。天花板：中心 icon 12% 圆形涟漪扩散 180ms。`TextKeyButton` space 分支加 `rememberRipple` 或手绘 alpha 圈。

### P2-4 键帽字符按压微缩
- 有 popup 的字母键，键帽本体字符在 popup 出现时降 alpha 至 0.35（微信做法：视线聚焦气泡）。`TextKeyButton` 在 `key.isPressed && hasPopup` 时给 SnyggText 加 alpha。

### P2-5 收起键盘 chevron 与 123 键视觉打架
- 左下角 18dp chevron 叠在 123 键左缘（`TextKeyboardLayout.kt` L331-348），日间浅灰底上尚可、夜间贴键边显毛糙。天花板：chevron 独占一个功能键位或移入 toolbar。低优先级重排项。

### P2-6 T9 按压 popup 对超窄键的适配
- P0-2 修完键宽后复核：T9 数字键 popup 宽=键宽×1.4 不得超出屏幕左右缘（`PopupUiController` 已有 clamp 逻辑，验收时长按最左/最右列确认）。

---

## 验收清单（实现后逐项截图对比）

1. `light/dark × qwerty/T9/符号/数字` 8 张全景 vs 本次 audit-shots 基线；
2. 按压 G 键：气泡悬于键上方、32sp 大写 G、有投影、出现/消失有动画（录屏 30fps 逐帧看 ≤3 帧出现）；
3. 长按 E：焦点项白字绿底；
4. T9：键面 `ABC` 20sp 居中 + 数字 11sp 左上角，栅格满宽、无 shift 键；
5. 夜间：shift/退格/123 与底色肉眼可分（截图取色验证亮度差 ≥8%）；
6. 数字盘：仅一个换行键；
7. 所有 `.my` 主题变体（6 份 JSON）与主主题 diff 一致；
8. 改动后跑一遍设置内主题预览不崩溃（Snygg schema 校验通过）。

---

## 附：本次实拍取证索引（docs/design/audit-shots/）

| 文件 | 内容 |
|---|---|
| light-letters.png / light-letters-clean.png | 日间全拼字母 + 候选态 |
| light-press-G.png / light-press-E.png / light-longpress-E.png | 日间按压/长按（popup 硬伤证据） |
| light-symbols.png / light-numeric.png | 日间符号（顶行裁切）/数字（双换行） |
| light-t9.png | 日间九宫格（窄键+缺字母） |
| dark-letters.png / dark-press-G.png / dark-symbols.png | 夜间对应 |
| dark-t9.png / dark-t9-press.png / dark-t9-candidates.png | 夜间 T9 + 按压 + 候选 |
