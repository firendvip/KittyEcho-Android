# 云词库 / 云输入 设计（Android 侧）

> 状态：**设计稿（待主干协调后端 endpoint 后进入实现）**。阶段4 项目之一。
> 边界：云词库需后端在线接口，属**跨模块**。本文档只定安卓侧架构 + 后端契约提案；
> 后端 endpoint 由主干/计费后端决策与实现，安卓侧不擅自建后端。

## 1. 目标与价值
本地拼音词库覆盖有限，长尾词、热词、新词、专名、网络流行语常打不出。云词库在**本地候选之外**异步补充在线候选，提升打字命中率；同时可作为**中文滑行输入**所缺拼音词库的数据源（见 §7）。

## 2. 非目标 / 红线
- **不上传正文/隐私内容**：仅发送当前编码串（拼音音节，如 `nihao`），绝不发送输入框已有文本、联系人、剪贴板等。
- **不阻塞本地候选**：本地候选**先出**，云候选到达后**增量补入**，网络慢/失败对用户无感。
- 隐私模式(incognito) / 密码框：**一律不发起云查询**。
- 默认策略见 §5（建议默认开启但带明确告知，或首启弹一次说明；最终由产品定）。

## 3. 架构
```
用户输入拼音
   │
   ├─► 本地 PinyinLanguageProvider.getListOfWords() ──► 立即渲染本地候选
   │
   └─► CloudDictionaryProvider (新增, 实现 NLP provider 接口)
          │  debounce 150ms + 取消在途请求
          ▼
       LRU 缓存命中? ──是──► 直接返回缓存候选
          │否
          ▼
       BackendClient.dictSuggest(pinyin, limit)  [复用现有鉴权头]
          │  timeout 500ms, 失败静默降级
          ▼
       合并去重 + 按 score 插入候选列表(本地高置信在前) ──► 增量刷新候选栏
```

- **CloudDictionaryProvider**：挂到 `nlpManager` 候选管线，与 `PinyinLanguageProvider` 并存。不改识别核心，只做「本地候选 + 云候选」合并层。
- **合并策略**：本地精确/高频候选保持在前；云候选按 score 插入中后段；`text` 去重；总数截断（如 ≤ 前 20）。
- **鉴权**：复用 `backend/BackendClient`（`x-device-id` sha256加盐 + `x-platform=android` + 可选 `Bearer`），与既有契约一致，不自造。

## 4. 性能与健壮性
- **debounce**：末次按键后 150ms 才发；新按键立即取消在途请求（避免抖动/浪费）。
- **timeout**：500ms；超时/网络错静默降级为本地候选，不弹错。
- **缓存**：LRU（如 200 条 pinyin→候选），会话内复用，减重复请求与流量。
- **local-first**：云结果永不推迟本地候选渲染；到达后 patch。
- **未配置/不可达**：endpoint 缺失或不通时整体 no-op，功能等价于纯本地，零副作用。

## 5. 隐私 / 设置 / 计费
- 设置项 `dict__cloud_enabled`（新增 pref）：开关 + 一句话说明「仅上传拼音编码用于联想，不含输入内容」。默认值待产品定（建议：默认开 + 首启一次性告知）。
- incognito / `EditorInfo` 密码或 `IME_FLAG_NO_PERSONALIZED_LEARNING`：强制不查询。
- **计费**：云词库查询是否计额度 = **计费契约问题，归主干/计费后端决策**。建议：免费、按设备限流（见契约提案 §限流）。

## 6. 待办拆分（endpoint 就绪后）
1. 新增 `backend/BackendClient.dictSuggest()`（按 §契约）。
2. 新增 `CloudDictionaryProvider` + 接入 `nlpManager` 候选合并。
3. 新增 pref `dict__cloud_enabled` + 设置页开关 + incognito 守卫。
4. LRU 缓存 + debounce + 取消 + timeout。
5. 埋点/日志（脱敏，不带正文）。
6. 真机验证：命中率、延迟、离线降级、隐私（抓包确认只发拼音）。

## 7. 与中文滑行输入的协同
中文拼音滑行当前被禁（`TextKeyboardLayout.kt` zh 守卫）且 `PinyinLanguageProvider.getListOfWords()` 返回空（无词库源）。云词库的 `dictSuggest` 接口若支持「拼音前缀→词」查询，可同时作为滑行分类器的词源候选，一并解锁中文滑行（需分类器对 `pinyin_qwerty` 布局精度校验）。**建议两者共用同一后端词库接口**，避免重复造。
