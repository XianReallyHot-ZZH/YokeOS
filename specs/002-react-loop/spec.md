# Feature Specification: ReAct 循环——Agent 的大脑（第17节）

**Feature Branch**: `specs/017-react-loop`

**Created**: 2026-09-13

**Status**: Draft

**Input**: User description: "第17节需求：ReAct 循环——Agent 的大脑"（六段式组装参数，取材 `docs/class/017-react-loop.md` 一、二部分，五项拍板已内嵌）

## Clarifications

### Session 2026-09-13

- 结构化歧义扫描（九类覆盖）：全部 Clear 或 Deferred-to-plan——五项拍板（教学文档头部）已钉死全部高影响决策点，无需向用户提问。
- Q: 可重试失败在退避内多次尝试时，`tool_invocations` 按什么粒度落账——每次尝试一条，还是一次工具调用请求一条？ → A: 一次工具调用请求落**一条最终态**记录（重试是执行内部策略，不逐尝试落多条）——出处：教学文档第四部分「重试耗尽落**最终**失败审计」与「成败都落审计、先落审计再还结果」（先完成执行含重试、后落账、再还结果）。
- Q: 引用 Skill 正文（FR5 第 [1] 段第 3 项）本节为什么不实现？ → A: 公共 Skill 库与按名引用归 29 节交付（技 §13 第 29 节行），本节 ContextLoader 留拼接位、不读 skills 目录——教学文档拍板口径「29 节留位」。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 一次对话内完成多步任务 (Priority: P1)

用户问「看看今天天气，帮我决定穿什么」。Agent 不是一问一答，而是自己决定先调 `http_get` 查天气、拿到数据看一眼、再基于结果给建议——一次对话内完成「思考 → 调 Tool → 观察 → 续推」，直到给出最终答复（需 §11 第 17 节行可演示成果）。多步任务里 Agent 只做它被点名可用的工具，一次响应要调多个工具时按顺序逐个执行。

**Why this priority**: 这是 ReAct 循环存在的意义本身——没有多步循环，Agent 就是个 chatbot；这一条通了才有本节 MVP，也是 Demo 一（每日天气）对话版的地基。

**Independent Test**: 给 Agent 一个需要外部数据的问题（天气类），mock 模型第一轮返回工具调用、第二轮基于工具结果收尾，验证工具被执行、结果回填、最终答复非空。

**Acceptance Scenarios**:

1. **Given** Agent 配置了 `http_get` 工具，**When** 用户提出需要外部数据的请求，**Then** 模型自主发起的工具调用被执行，其结果出现在下一轮上下文中，最终答复基于工具结果给出。
2. **Given** 一次模型响应里带多个工具调用请求，**When** 本轮执行，**Then** 逐个顺序执行（不并行），每个结果都按发生序回填。
3. **Given** 模型某一轮响应不带任何工具调用请求，**When** 循环判定，**Then** 立即收尾返回该轮文本（text 为 null 按空串收尾），零多余执行。

### User Story 2 - 工具失败不炸循环，模型看得到失败原因 (Priority: P2)

工具执行失败（URL 拿不到数据、参数不是合法 JSON、工具名没注册）：Agent 不崩溃、不中断——失败原因回填进对话历史，模型下一轮看到失败原因自行决定下一步（重试、换参数或直接告知用户）。可重试的失败按指数退避自动重试（默认最多 3 次尝试），不可重试的失败一次即止、失败结果直接交还循环。

**Why this priority**: 可靠性承诺（需 §8.2）——真实环境工具必然失败，一次失败就断整条对话的 Agent 没有可用性；重试策略是需求文档明确要求的非功能行为。

**Independent Test**: 用一个前两次失败、第三次成功的假工具验证退避内重试成功；用总失败的假工具验证重试耗尽后失败结果回填且审计只落最终结果；用非法 JSON 入参验证一次即止。

**Acceptance Scenarios**:

1. **Given** 工具内部抛 RuntimeException，**When** 执行，**Then** 转成带原因的失败结果回填循环，循环继续不中断，不上抛。
2. **Given** 模型给的参数不是合法 JSON 或工具名未注册，**When** 执行，**Then** 一次即止（不重试），失败结果 + 审计留痕，不抛异常。
3. **Given** 工具返回「可重试」的失败，**When** 执行，**Then** 按指数退避重试（退避间隔可注入，测试给零延迟），默认最多 3 次尝试；重试耗尽落最终失败审计。

### User Story 3 - 每一步留痕可审计，死循环有兜底 (Priority: P3)

审计员事后能回放一次多步对话的完整过程：每轮模型响应、每次工具调用（成败都记、失败含原因）、按 session 关联——`tool_invocations` 本节起写入（宪法 7 审计 day one）。模型陷入反复要调工具的死循环时，靠最大轮数兜底强制收尾（默认 10、`settings.max_iterations` 可覆盖），返回可辨认的收尾答复。

**Why this priority**: 可审计是企业级底座的差异化卖点，对话历史是「对外可查可审计」的载体；死循环兜底是 Agent 可控性的底线——两条都是本节验收硬条件。

**Independent Test**: mock 模型每轮都要求调工具，验证恰好 N 轮强制停（一轮不多）、收尾答复含「达到最大轮数」字样、每轮响应与工具结果全累积进会话；跑完后查审计表每次工具执行都有记录。

**Acceptance Scenarios**:

1. **Given** 模型每轮都返回工具调用请求（永不收敛），**When** 循环跑到最大轮数，**Then** 恰好 N 轮后强制停止，返回含「达到最大轮数」的收尾答复；转满的那几轮也全部留痕。
2. **Given** 一次多步对话完成，**When** 事后查会话历史，**Then** 消息按发生序完整可回放（user/assistant/tool 三角色），查审计表每次工具执行成败都有记录、按 session 关联。
3. **Given** Agent 在自己的 `AGENT.md` 里把 `settings.max_iterations` 配成 5，**When** 循环运行，**Then** 5 轮即停（配置覆盖默认值生效）。

### Edge Cases

- 模型响应既无文本也无工具调用请求 → 按空串收尾（不抛 NPE、不空转）。
- 历史恰好 N 轮 → 不截断；超过 N 轮 → 只留最近 `max_history_turns` 轮，以轮为界（一轮 = 一条用户消息及其后全部消息），工具结果跟住它的提问轮、不从中间撕裂。
- Bootstrap 文件缺失 → WARN 跳过不阻断组装；读文件 IO 失败 → 显式抛错（不静默跳过造成「人格悄悄丢了」）。
- 处理中途抛异常 → ThreadLocal 的 Agent 上下文必须在 finally 清掉（remove），会话不保存。
- 会话引用的 profileName 查不到 → 点名报错（含名字），不静默。
- `http_get` 响应超长 → 截断至约 8000 字符防撑爆上下文。
- 改 AGENT.md / Bootstrap 文件后下一次组装 → 立即读到新内容（每次重新读文件，零缓存）。

## Requirements *(mandatory)*

### Functional Requirements

- **FR1（契约上移，前置改造）**: core 立一套中性 Provider 协议——统一调用接口（签名：sessionId、Profile、请求对象 → 响应对象）+ 请求/响应/工具调用请求三类值对象，全为普通 Java 类型、不带任何 LLM 框架类型；第 16 节的 Provider 实现体改名并实现该接口、签名换型（进出 LLM 框架类型 → 中性值对象），对外行为零变化；token 审计仍在实现体内部闭环，响应值对象不携带用量。验收口径：16 节全部测试随改名平移后保持绿（路由不串台 / 点名报错 / 成败双路审计 / 自动执行关闭的断言语义逐条保留），core 源码无 LLM 框架类型引用。
- **FR2（统一 Tool 抽象补执行语义）**: Tool 统一抽象补执行方法——入参为 JSON 节点、返回带 content / success / errorMessage / retryable 四字段的结果对象（附 ok / error 工厂方法）；既有注释「执行语义归第 20 节」同步修订为「白名单校验归 20/24 节」。本节不做白名单校验。
- **FR3（一次对话内的多步循环）**: 循环引擎输入会话与用户消息，输出最终响应——追加用户消息 → 每轮组装 prompt → 经 Provider 调 LLM（sessionId 随调用传递作审计关联）→ 无工具调用请求即收尾返回文本；有则逐个顺序执行（不并行）、结果回填会话 → 进入下一轮；达到最大轮数（默认 10、`settings.max_iterations` 可覆盖）强制收尾，返回含「达到最大轮数」字样的收尾答复。循环必须自实现，不用 LLM 框架现成的 Agent 封装（宪法 1）。
- **FR4（消息累积）**: 每轮的模型响应与工具结果（成功与失败都进历史、失败存错误描述）按发生序累积进会话历史，先累积再判停（转满轮数的那几轮也全留痕）；会话消息为 (role, content, toolName) 三元记录，role 取 user / assistant / tool。
- **FR5（prompt 组装固定顺序）**: 组装器按固定顺序产出：[1] system prompt——identity 人格提示词 + Bootstrap 文件（按 Profile `bootstrap` 列表，相对序固定 AGENTS.md → SOUL.md → USER.md，列表只能裁剪不能乱序，每段前带角色 header 如 `## 项目约定（AGENTS.md）`，无覆盖语义）+ 引用 Skill 正文（29 节留位、本节不实现）+ AGENT.md 正文压轴；每次组装重新读文件零缓存，Bootstrap 缺失 WARN 跳过不阻断、读文件 IO 失败显式抛错；system prompt 末尾附当前日期时间行（时钟可注入、默认系统时钟）；[2] 长期记忆位——本节恒空跳过（22 节接入）；[3] 对话历史——只留最近 `max_history_turns` 轮（缺省 20），以轮为界截断不撕裂；[4] 可用 Tool 列表——不进文本，经请求对象的 availableTools 传递，只带 Profile `tools` 点名的那些（schema 翻译由 16 节既有适配单点负责）。
- **FR6（工具执行唯一路径）**: 工具执行统一收口一处执行器（宪法 2：执行权只有一条路）——先解析后执行（argumentsJson 不是合法 JSON 直接失败路径）；成败都落 `tool_invocations` 审计且先落审计再还结果（宪法 7）；工具抛 RuntimeException 转失败结果不炸循环（errorMessage 带原因、不上抛不中断）；未注册工具名走失败路径 + 审计留痕（不抛异常）；可重试失败（retryable=true）按指数退避重试、默认最多 3 次尝试，退避间隔可注入（测试给零延迟）；不可重试失败（未注册工具名、坏 JSON）一次即止；审计按一次工具调用请求落**一条最终态**记录（重试是执行内部策略，不逐尝试落多条）；执行前过白名单校验的挂点留注释位（24 节接线）。
- **FR7（编排入口）**: 三种触发源（CLI / Web / 定时）共用同一处理入口 process——按会话引用的 profileName 查 Profile（不存在点名报错含名字）；入口设 ThreadLocal 的 Agent 上下文、出口 finally 必清（用 remove 不用 set(null)）；正常路径结束后保存会话，异常路径不保存。
- **FR8（一个内置 HTTP 工具）**: `http_get`——入参 `{url}`，Java HttpClient 发 GET（连接/读取超时约 10 秒），返回响应正文文本，超长截断保护（约 8000 字符）；本节不做域名白名单（Sandbox 归 24 节）；参数 schema 按 16 节适配器的消费格式写。
- **FR9（会话内存版）**: 会话对象（sessionId、profileName、按序消息列表）+ 保存接口，内存实现兜底装配；`session_id` 三元组拼接公式（channel+user+agent）、getOrCreate 与持久化归 18 节。

**明确不做（边界）**: 工具并行调用（一次响应多个工具调用按顺序执行）、Agent 间任务委托、流式输出、上下文压缩（技 §4.3 第一阶段不做）；长期记忆注入归 22 节、Sandbox 白名单归 24 节（本节留注释位）、ToolRegistry 与 MCP 归 20 节（本节执行器直接持「工具名 → 工具」映射）、Session 持久化与 session_id 公式归 18 节、Spring 装配归 18 节（本节冒烟手工装配，16 节同款口径）、Skill 正文注入归 29 节、http_post 与全量工具组归 20 节。

### Key Entities

| 实体 | 说明 |
|------|------|
| 会话（Session） | 一次对话的全部状态：sessionId、profileName、按序累积的消息列表；本节内存版，持久化归 18 节 |
| 会话消息 | (role, content, toolName) 三元记录，role 取 user / assistant / tool；严格按发生序追加、可完整回放 |
| Provider 中性协议 | core 立的接口 + 三类值对象（请求：prompt 文本 + availableTools；响应：text + 工具调用请求列表 + hasToolCalls 便捷判断；工具调用请求：name + argumentsJson），不含 LLM 框架类型 |
| ToolResult | 工具执行结果：content / success / errorMessage / retryable 四字段，ok / error 工厂 |
| `tool_invocations` | 审计表（16 节已建）：session 关联 + 工具名 + 入参/结果 JSON + 成败 + 原因 + 耗时，本节起写入 |
| system prompt | identity 人格 + Bootstrap（AGENTS.md → SOUL.md → USER.md 固定相对序、带角色 header）+ Skill 位（29 节）+ AGENT.md 正文压轴 + 末尾日期时间行 |
| `settings.max_iterations` / `max_history_turns` | Agent 级配置：循环轮数上限（缺省 10）、历史保留轮数（缺省 20），本节开始消费 |

## Success Criteria *(mandatory)*

### Measurable Outcomes

1. 多步链路完整走通：一次对话内模型自主发起的工具调用 100% 经唯一执行路径执行并回填进下一轮上下文，最终答复非空——真 key 集成冒烟（真模型 + 真 `http_get`）可演示「思考 → 调 Tool → 观察 → 续推」（拍板⑤口径）。
2. 审计 100% 留痕：每轮 LLM 调用有 `llm_calls` 记录、每次工具执行（成功、失败、坏 JSON、未注册名）成败都落 `tool_invocations` 且按 session 关联；先落审计再还结果。
3. 死循环兜底精确：模型永不收敛时恰好 N 轮强制停（默认 10、Agent 配置覆盖生效）、一轮不多，收尾答复含「达到最大轮数」字样。
4. 契约上移零行为变化：16 节全部测试断言语义逐条保留且全绿；core 源码 LLM 框架类型引用为零。
5. 上下文与并发纪律：历史截断以轮为界不撕裂（恰好 N 轮不截断）；处理异常时 ThreadLocal 上下文 100% 被清理；`grep -rE "CompletableFuture|reactor|WebFlux"` 九模块无新增（宪法 4）。
6. 自动化验收全绿（验收 harness 承载，`mvn test` 一次运行内完成）；人工项：真 key 冒烟真跑一次、code review 确认循环自实现（宪法 1）与自动执行仍关闭（宪法 2）。

## Assumptions

- Spring AI 1.1.8 本地依赖为准：从 `ChatResponse` 提取工具调用请求的确切 API 写法以 `javap` 核实为准（H3：核实不到不写）；参照课件为 1.0.0-M6 期写法，存在代差。
- Jackson databind：core 若无显式依赖则补，记实施偏差。
- 上层触发入口（CLI / Web / 定时）本节尚不存在，以单测与集成冒烟承载；第 18 节接入后零改动复用本节入口。
- 长期记忆位恒空跳过（22 节接入时扩展）；引用 Skill 正文 29 节留位、本节不实现。
- 集成冒烟需真实 DeepSeek API key（经环境变量，缺失时 `assumeTrue` 跳过不失败）与 open-meteo 天气端点（无 key 可用）；冒烟手工装配（16 节同款口径），Spring 装配归 18 节。
- 本节演示口径 = 需 §11 第 17 节行「Agent 一次对话内完成多步任务」，由集成冒烟承载；完整 CLI 交互归 18 节（拍板⑤）。
