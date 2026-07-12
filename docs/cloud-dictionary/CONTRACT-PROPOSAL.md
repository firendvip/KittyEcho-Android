# 云词库后端接口 契约提案（安卓侧提出，待主干/计费后端确认）

> 面向：主干·总调度 → 计费后端（ai-input-method-server）。
> 目的：为「云词库/云输入」联想 + 中文滑行词源提供一个在线查询 endpoint。
> 本文档为**提案**，字段/路径/计费口径最终以后端 + `BILLING_SPEC.md` 为准；安卓侧照最终契约实现，不自造。

## Endpoint
`POST {base}/dict/suggest` （base = 现有 `https://look3.cn/aiapi`，与 `/polish`、`/quota` 同源）

## 请求头（复用现有客户端契约，同 /polish）
- `x-device-id`: 设备标识（ANDROID_ID sha256 加盐，同现有口径）
- `x-platform: android`
- `Authorization: Bearer <token>`（可选；匿名亦可用，走匿名限流）
- `Content-Type: application/json`

## 请求体
```json
{
  "pinyin": "nihao",          // 必填：当前拼音编码串（全拼/简拼），无空格
  "limit": 10,                 // 可选：候选上限，默认 10，上限 20
  "prefix": false,             // 可选：true=前缀联想(滑行/整句用)，false=精确音
  "context": ""                // 可选：上一个已上屏词，用于排序；默认空。仅在用户开启且非隐私时才带
}
```
**隐私红线**：请求只含拼音编码（+可选的前一个词），**不含输入框正文/个人信息**。密码框/隐私模式客户端根本不发。

## 响应体（沿用项目统一 envelope：success/data/error）
```json
{
  "success": true,
  "data": {
    "candidates": [
      { "text": "你好", "score": 0.98, "source": "cloud" },
      { "text": "拟好", "score": 0.41, "source": "cloud" }
    ]
  },
  "error": null
}
```
- `text`：候选词；`score`：0~1 排序权重；`source`：来源标记（cloud/hot/user 等，便于客户端合并策略）。
- 失败：`success=false` + `error`，客户端**静默降级**为纯本地候选。

## 性能 / 限流 / 计费（请后端定）
- 延迟目标：p95 < 300ms（客户端 timeout 500ms 后放弃）。
- 限流：建议按 `x-device-id` 限流（如匿名 N 次/分钟，登录更高），防刷。
- **计费口径（关键决策，归计费后端）**：云词库联想建议**不计润色额度**（高频、单次极轻），或单列一个宽松免费额度。请在 `BILLING_SPEC.md` 明确。

## 词库来源（后端实现自由）
后端可用开源拼音词库（如 rime/结巴/搜狗细胞词库导入）+ 热词榜；本接口对客户端只暴露「拼音→候选词」。若同一接口支持 `prefix=true` 的前缀查询，可同时作为**中文滑行输入**的词源（见 DESIGN.md §7），避免重复造词库服务。

## 客户端侧准备（endpoint 就绪即可接）
安卓侧架构与接入点见同目录 `DESIGN.md`；BackendClient 加 `dictSuggest()` 一个方法即可对接，鉴权头复用现有。
