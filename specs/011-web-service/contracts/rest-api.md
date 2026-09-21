# Contract: REST API 与 /admin 托管（第26节）

Phase 1 产物。本特性对外暴露的接口契约；路径与信封字段为已定字面量（H1 逐字保真，出处：需求 §5.10 / 技 §7.2 + 拍板①②）。

## 统一信封与错误码

所有响应（成功与错误）共用信封：

```json
{ "code": 0, "message": "success", "data": { … }, "timestamp": 1790000000000 }
```

（`code` 成功为 0、错误为 HTTP 状态值——地基 `ApiResponse` 既定字面量，镜像参照实现。）

| HTTP | 语义 | 触发 |
|------|------|------|
| 400 | 参数错误 | 缺 profile / 消息空 / 超 32KB |
| 404 | 资源不存在 | 会话不存在 / Agent 不存在 / 无匹配处理器与静态资源 |
| 500 | 内部错误 | 兜底；**响应体固定话术，绝不泄漏内部细节**（细节只进日志） |
| 503 | Provider 故障 | 引擎/Provider 异常（含既有 IllegalStateException 映射） |
| 504 | 调用超时 | 口径占位（真实超时由 provider 层承载，research D10） |

## 端点（本节 11 个，统一前缀 /api/v1）

### 会话管理（SessionApiController，channel=web）

| # | Method Path | 请求 | data 形态 | 错误 |
|---|-------------|------|-----------|------|
| 1 | `POST /api/v1/sessions` | `{ "profile": "weather", "userId": "u1"(可选) }` | `{ "sessionId": "web:u1:weather" }` | 缺 profile → 400 |
| 2 | `POST /api/v1/sessions/{id}/messages` | `{ "content": "今天北京天气" }` | `{ "reply": "…" }` | 空/超 32KB → 400；会话不存在 → 404 |
| 3 | `GET /api/v1/sessions?status=active`(可选) | — | `SessionSummaryView[]`（最近 ≤100，last_active 倒序） | — |
| 4 | `GET /api/v1/sessions/{id}` | — | `{ "sessionId", "profileName", "messages": [ … ≤100 条 ] }` | 不存在 → 404 |
| 5 | `DELETE /api/v1/sessions/{id}` | — | `{ "archived": true }` | 不存在 → 404；归档是标记不终结 |

### Agent 调用（AgentApiController，本节仅此端点；29/30 在此 Controller 扩展写侧）

| # | Method Path | 请求 | data 形态 | 错误 |
|---|-------------|------|-----------|------|
| 6 | `POST /api/v1/agents/{name}/invoke` | `{ "content": "…" }` | `{ "reply": "…" }` | Agent 不存在 → 404（先查注册表）；空/超 32KB → 400 |

无状态：每次调用一次性会话（channel=`invoke`、user 每次唯一），连续两次 invoke 互不携带历史。

### 信息查询（Profile / Memory / Tool ApiController，只读）

| # | Method Path | data 形态 |
|---|-------------|-----------|
| 7 | `GET /api/v1/profiles` | `[{ "name", "description", "providerName", "model", "tools": [ … ] }]` |
| 8 | `GET /api/v1/memory` | 长期记忆全文（String，两分区原貌） |
| 9 | `GET /api/v1/tools` | `[{ "name", "description" }]`（注册表全量，含 MCP） |

### 系统状态（SystemApiController）

| # | Method Path | data 形态 |
|---|-------------|-----------|
| 10 | `GET /api/v1/health` | `{ "status": "ok" }` |
| 11 | `GET /api/v1/info` | `{ "product": "yokeos", "version": …, "providers": ["deepseek", …] }`（已加载 Profile 引用到的 provider 名去重排序，不探活） |

## 行为契约（非端点）

- **同一引擎**：端点 2/6 走与 `yokeos chat` 完全相同的编排入口，Controller 层零业务逻辑（测试锚「恰调一次」）。
- **消息上限**：单条 32KB；历史返回 ≤100 条；会话列表 ≤100 条。
- **OpenAPI**：springdoc 自动生成，Swagger UI 可访问（`/swagger-ui.html`），覆盖全部 11 端点。
- **CORS**：第一阶段全开（`/**` 所有源与常用方法），扩展阶段收敛白名单。
- **虚拟线程**：全部请求同步阻塞跑在虚拟线程上，无异步栈。

## /admin 托管契约（WebConfig）

| 请求 | 行为 |
|------|------|
| `GET /admin` | 301/302 → `/admin/` |
| `GET /admin/` | forward → `index.html` |
| `GET /admin/assets/<hash 文件>` | 原样返回 + `Cache-Control: immutable, max-age=31536000` |
| `GET /admin/<其他任意路径>` | 命中真实文件则返回（`no-cache`）；否则回落 `index.html`（SPA 路由兜底，刷新不 404） |
| `GET /api/v1/<不存在路径>` | **JSON 404**，绝不落入 SPA 回落（API 与静态托管互不劫持） |

管理台五页只调本契约只读端点，零写入口；三态占位以信封 `code` 为判据。

## 契约不变量（测试钉死点）

1. 任一响应（含错误）都是统一信封 JSON——无裸字符串、无 HTML（API 路径）。
2. 端点 2 正常路径编排入口恰调一次（Controller 薄）。
3. 端点 6 两次调用的 sessionId 不同（无状态）；Agent 未注册名 404 非 503。
4. 500/503 响应体不含内部细节（连接串/路径/堆栈零出现）。
5. SPA 回落只作用于 `/admin/**`；`/api/v1/**` 404 永远 JSON。
