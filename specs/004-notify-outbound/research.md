# Research: Notify——结果主动送出去的统一出口（第19节）

**输入**：spec.md 无 NEEDS CLARIFICATION（五项设计分叉已在教学文档起草期拍板，见 `docs/class/019-notify.md` 拍板记录）。本文件沉淀实现级决策与既有代码事实核实（D1~D7），供 plan/tasks/实现引用。

## D1：JDK HttpClient 的非 2xx 语义与 checked 异常包装（最关键的实现差异点）

**Decision**: `WebhookNotifyAdapter` 显式检查 `response.statusCode()`，非 `[200, 300)` 抛 `UncheckedIOException(new IOException("webhook 返回非 2xx: " + code))`；`HttpClient.send` 的 checked `IOException`（连接失败/超时）同样包 `UncheckedIOException` 上抛。

**Rationale**: 参照用 Spring `RestClient`——`retrieve()` 默认非 2xx 抛 `RestClientResponseException`，写法天然满足 FR2；本仓拍板③改用 JDK HttpClient 后语义不同：`send()` 对非 2xx **不抛异常**（返回 HttpResponse 由调用方检查），必须手动补状态码检查，否则「对端 5xx」会静默当成功——正是坑一。同时 `ToolExecutor.attemptOnce` 只 catch `RuntimeException`（17 节既有实现，异常转 `ToolResult.error("工具执行异常: ...", false)` 再落审计），checked `IOException` 直接上抛会炸循环——包 `UncheckedIOException` 才能落进既有「异常→失败结果→审计」路径。

**Alternatives considered**: ① 引入 spring-web 用 RestClient（参照形态）——被拍板③否决（新依赖）；② Adapter 内 catch IOException 转 `ToolResult.error` 返回——否决，`NotifyChannelAdapter.send` 签名是 `void`，且发送异常上抛、由 ToolExecutor 统一转失败是文档链定死口径（技 §6.8 审计路径）；③ 包 `RuntimeException` 通用类型——否决，`UncheckedIOException` 语义更准且消息可带状态码。

## D2：channel 参数匹配语义（拍板①，用户已批）

**Decision**: `channel` 缺省、空白或字面量 `default` → 取 `notifyChannels` 第一个；否则按 `NotifyChannelConfig.name()` 精确匹配；未命中报错点名、不回退默认。

**Rationale**: 本仓渠道模型三字段（`name`/`type`/`config`，16 节已定），同一 Agent 可配两条 webhook 渠道（如 `ops-group`/`dev-group`）推不同的群——按 type 匹配无歧义性可解（两条都是 `webhook`）。参照 specs/004 按 type 匹配的推理建立在「渠道模型只有 type+config、无 name 可匹配」之上，对本仓不成立。

**Alternatives considered**: 按 type 匹配（参照同构）——同 type 多渠道场景失效；name 前缀/模糊匹配——过度设计。

## D3：NotifyTools 的 Tool 形态

**Decision**: `NotifyTools implements YokeTool`（`getName()`/`getDescription()`/`getInputSchema()`/`execute(JsonNode)`），不使用 `@Tool` 注解。

**Rationale**: 17 节先例 `HttpGetTool` 同形态（`@Tool` 注册机制与 ToolRegistry 归 20 节，参照「实现顺序说明」同款）；`YokeTool.execute` 签名正是既有 `ToolExecutor` 的执行口径，零接线成本即可被审计。`getInputSchema()` 手写 JSON Schema 字符串（`content` 必填、`channel` 可选），与 16 节 `ToolSchemaAdapter` 消费格式同构。

**Alternatives considered**: `@Tool` 注解 Bean——注册体系未就位（20 节），宪法 2 的 `@Tool` 仅用于 schema 生成场景，本节不需要。

## D4：AgentLoader 的 type 三态处理（拍板②，用户已批）

**Decision**: `notify.channels` 条目的 `type` 读取 frontmatter；缺省（null/空白）视为 `"webhook"`；声明值不在支持集（当前 `{"webhook"}`）→ 记 SLF4J error 日志（编译期常量消息，渠道名与类型进参数/异常）并剔除该条目，Agent 其余字段照常派生、启动不阻断。

**Rationale**: 技 §8.2「校验失败的 Agent 不阻断启动但记录错误日志」总原则；16 节教学文档「后面各节的字段各自补自己的校验规则」在此兑现。剔除而非整 Agent 跳过：notify 渠道是可选能力，剔一条渠道不该废掉整个 Agent——运行时 `notify` 调用会因无可用渠道明确报错（FR3 闭环）。

**Alternatives considered**: ① 维持硬编码 `"webhook"`——`type: email` 被静默当 webhook，违背「不静默失败」；② 非 webhook 跳过整个 Agent——过度惩罚，notify 是可选能力。

**测试断言方式**: 仓内无 ListAppender 先例——type 剔除用例以**行为断言为主**（派生 Profile 的 `notifyChannels` 不含被剔除条目、其余字段照常），辅以 logback `ListAppender` 挂 `AgentLoader` logger 断言错误日志点名（首次引入，注明确认门禁通过）。

## D5：假 webhook 测试载体

**Decision**: `WebhookNotifyAdapterTest` 用 JDK 内置 `com.sun.net.httpserver.HttpServer` 起本地假 webhook（`new InetSocketAddress(0)` 自动分配端口，handler 记录方法/路径/body 并按用例回 200/500）。

**Rationale**: 参照钉版树同款（research D5 同源）；本地回环、无外网依赖、不引入 MockWebServer 新测试依赖（软门禁⑥）；真 HTTP 协议栈覆盖 `HttpClient` 真实行为（比 mock HttpClient 更硬）。

**Alternatives considered**: WireMock/MockWebServer——新测试依赖；mock `HttpClient`——测不到真实序列化与状态码路径。

## D6：失败口径与重试语义

**Decision**: 对端非 2xx、连接失败、缺 url 三种失败形态同口径显式失败；`NotifyTools` 返回的确定性 `ToolResult.error` 全部 `retryable=false`；发送异常经 ToolExecutor 转 `ToolResult.error(..., false)` 亦不可重试——**notify 任何失败都不做工具级重试**。

**Rationale**: 参照 specs/004 clarify「对端 3xx/4xx 与 5xx 同口径——凡非成功响应都异常上抛」；重试对推送是危险的：第一次实际送达但响应慢被判失败 → 重试 → 群里重复消息。`ToolExecutor` 只在 `retryable=true` 时退避重试，notify 失败一次即止，交还模型决定下一步（技 §4.2 循环语义）。

**Alternatives considered**: 发送失败标 `retryable=true` 让 ToolExecutor 退避重试——重复推群风险，否决。

## D7：超时设置

**Decision**: `HttpClient` 复用 `HttpGetTool` 同款口径：`connectTimeout` 10s；请求级 `HttpRequest.Builder.timeout()` 10s（notify 专设——对端 webhook 挂起不响应时不能拖死 ReAct 循环同步链路）。构造注入 `HttpClient` 便于测试定制。

**Rationale**: `HttpGetTool` 只设了 connectTimeout；notify 的对端是第三方群机器人服务，请求级超时是必要补充；同步模型下无异步兜底，超时是唯一防线（宪法 4）。

**Alternatives considered**: 不设请求级超时——对端挂起 = 循环线程挂起，否决；30s+ 长超时——同步链路里不可接受。

## 附：本节不涉及的核实项

- Spring AI API 核实（javap）：不涉及——本节零 Spring AI 交互。
- pom 变更：无——yokeos-tool 既有依赖（yokeos-core + spring-boot-starter-test）足够；Jackson 经 yokeos-core 传递已有（`JsonNode` 在 `YokeTool` 签名中使用，17 节已验证）。
- schema.sql：无变更（零新表）。
