# Feature Specification: Notify——结果主动送出去的统一出口（第19节）

**Feature Branch**: `specs/004-notify-outbound`

**Created**: 2026-09-15

**Status**: Draft

**Input**: User description: "第19节需求：Notify——结果主动送出去的统一出口。背景与价值、用户场景、功能需求 FR1~FR6、明确不做、验收标准、依赖与假设（完整需求见 `docs/class/019-notify.md` 一、二部分）"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 定时结果主动送达，换渠道零代码 (Priority: P1)

用户对 Agent 说「每天早上帮我看看天气，穿搭建议直接发到我们群里」。之后每天到点自动查天气、生成建议，并把结果推送到该 Agent 配置好的群——没有人在另一端等响应，也不需要任何人工干预。运营方想把通知从 A 群换到 B 群时，只改该 Agent 配置里的 webhook 地址（或对应环境变量），不碰任何代码、不改对话内容。

**Why this priority**: 这是 Notify 存在的全部理由：定时触发的结果若送不出去，跑完一整套循环也只能烂在会话里没人看到；25 节定时模块和 31 节两个 Demo 都指着这个出口。「配置即 Agent」总原则在出站方向的体现。

**Independent Test**: 用本地假 webhook（单测层 HTTP 假服务）承接推送，断言收到一次 POST、body 含推送内容、目标地址来自该 Agent 的通知配置而非硬编码——两个不同地址的渠道各发各的。

**Acceptance Scenarios**:

1. **Given** Agent 配置了一个 webhook 通知渠道，**When** 通知能力被调用（content="今天 28°C，建议短袖"），**Then** 目标地址收到恰好一次 POST，body 中携带该内容，Content-Type 为 JSON。
2. **Given** 同一 Agent 配置了两个不同地址的渠道，**When** 分别指定渠道推送，**Then** 两次内容各自发往对应地址；改配置即改目标，实现中无硬编码地址。

---

### User Story 2 - 发送失败不许装成功 (Priority: P1)

对端 webhook 返回错误（如 5xx）或连接失败时，通知能力必须把失败显式抛出——Agent 不能以为发出去了，事后审计也必须能看到这次失败。

**Why this priority**: 静默吞掉发送失败是最危险的软故障：日报「发了」但群里没人收到，发现时已经断了一周。「发出去没送到」与「没发出去」对 Agent 是同一件事。

**Independent Test**: 假 webhook 固定返回 500，断言发送调用异常上抛（不被吞掉）；渠道配置缺少 url 时，断言明确报错点名且零请求发出。

**Acceptance Scenarios**:

1. **Given** 假 webhook 固定返回 500，**When** 发送一条通知，**Then** 调用以异常结束、错误信息可见；该失败经工具执行统一路径留痕（审计记录 success=false）。
2. **Given** 渠道配置缺少 url，**When** 发送一条通知，**Then** 发起前即报错点名缺什么，目标地址零请求。

---

### User Story 3 - notify 工具的渠道解析 (Priority: P2)

Agent 在对话里调用 notify 工具：大多数时候只传 content——系统取该 Agent 配置的第一个通知渠道；显式传 channel 时按渠道名（name）选中对应渠道（同一 Agent 可配两个同类型的 webhook 渠道推不同的群，按 name 匹配才无歧义）；该 Agent 压根没配通知渠道时明确报错，绝不静默失败。

**Why this priority**: 渠道解析是 notify 工具的业务核心；「未配置却装作发送成功」与 US2 是同一类必须钉死的软故障。channel 参数匹配语义是本仓独立拍板（拍板①）：参照按 type 匹配因其渠道模型无 name 字段，本仓渠道模型 name/type/config 三字段（16 节已定），按 name 匹配。

**Independent Test**: 构造带/不带通知渠道的 Agent 配置置入当前 Agent 上下文，分别断言：缺省取第一个渠道、显式选中指定渠道、未配置时报错点名且适配器零调用。

**Acceptance Scenarios**:

1. **Given** 当前 Agent 配置了两个通知渠道（ops-group、dev-group），**When** 只传 content 调用 notify，**Then** 内容送往第一个渠道。
2. **Given** 同上，**When** 传 channel 指定 dev-group，**Then** 内容送往 dev-group。
3. **Given** 当前 Agent 未配置通知渠道，**When** 调用 notify，**Then** 返回明确错误（点名未配置），不发出任何请求。
4. **Given** channel 指定了不存在的渠道名，**When** 调用 notify，**Then** 返回明确错误点名该名字，不回退默认渠道（避免消息发错地方）。

### Edge Cases

- `content` 参数缺失或空白 → 明确报错点名（必填项），不发起请求。
- 当前无 Agent 上下文（入站链路未设置上下文）→ 明确报错，不猜测默认渠道。
- 渠道条目的 type 无对应实现（如后续扩展期配置了专用渠道但未装配实现）→ 报错点名该 type 与已装配的类型集。
- frontmatter 声明第一阶段不支持的 type（如 email）→ 该渠道被剔除并记错误日志，Agent 其余配置照常加载、启动不阻断。
- 各家群机器人 payload 格式差异：第一阶段统一发 `{"content": ...}`，仅部分渠道（如 Discord）天然兼容该格式——发到不认格式的渠道通常 HTTP 200 但内容丢弃，对接知识记入教学文档，格式适配留扩展阶段。

## Requirements *(mandatory)*

### Functional Requirements

- **FR1（出站通知抽象·接口先行）**: 定义「把一条内容送到某个通知目标」的渠道适配接口，接口签名 MUST NOT 携带任何具体渠道特有概念（不出现企业微信/飞书/钉钉等词）；通知目标 = 渠道类型 + 配置键值对，具体含义由实现解释。第一阶段唯一实现为通用 HTTP webhook：企业 IM 群机器人（企业微信、飞书、钉钉、Slack）皆经 webhook 地址接入，一档覆盖，MUST NOT 逐家接签名算法与 AccessToken 刷新。
- **FR2（webhook 发送语义）**: 发送 = 对渠道配置的 url 发一次 POST，body 统一 `{"content": ...}`；url MUST 来自渠道配置非硬编码——两个不同 url 的渠道各发各的（换渠道零代码）；config 缺 url MUST 报错点名、零请求；对端非 2xx 或连接失败 MUST 异常上抛不吞。
- **FR3（notify 内置 Tool 渠道解析）**: notify 是 LLM 可调用的内置 Tool：content 必填（缺失报错点名）；channel 可选——缺省、空白或字面量 `default` 取第一个渠道，显式传值 MUST 按渠道条目的 name 匹配（拍板①）；匹配不到 MUST 报错点名、不回退默认；渠道条目的 type 无对应实现 MUST 报错点名已装配类型集；当前无 Agent 上下文或未配置通知渠道 MUST 报错点名、适配器零调用。发送异常（HTTP 层）上抛，经既有工具执行统一路径落审计。
- **FR4（渠道配置与校验）**: `AGENT.md` frontmatter 声明 `notify.channels`（每项 `name` / `type` / `config`，url 走 `${ENV_VAR}` 占位不落明文——webhook URL 本身即凭证）；type 缺省视为 `webhook`；声明第一阶段不支持的 type MUST 记错误日志并剔除该渠道、不阻断启动，其余字段照常加载。
- **FR5（复用既有机制·零新增审计）**: notify 与其他 Tool 走同一条执行与审计路径（`tool_invocations` 成败都记，含发送失败），零新增审计逻辑；域名白名单校验为 24 节 Sandbox 接线留位（本节仅检查位注释：与 `http_post` 共享同一份 `http.allowed_domains`，校验先于发送），24 节接线后补「校验先于发送」顺序回归。
- **FR6（注册进对话）**: notify 注册进 CLI 对话的可用工具列表，`yokeos chat` 对话里说「把测试消息推一下」即可触发推送。

**明确不做（边界）**: 企业微信/飞书/钉钉专用 Adapter（各家 payload 格式适配、加签、AccessToken 刷新）；邮件、短信、IM SDK 直连渠道；失败重试策略复杂化；富文本卡片消息（需要富文本的业务方走 MCP 方式二自接专用 server，两条路并存）；定时触发本身（25 节）；24 节前的域名白名单实现。

### Key Entities

| 实体 | 说明 |
|------|------|
| `notify.channels`（frontmatter 段） | Agent 声明的通知渠道列表，每项 `name` / `type` / `config`（url 走 `${ENV_VAR}`），供 notify 工具运行时解析 |
| 渠道适配接口（`NotifyChannelAdapter`） | 出站通知的抽象：把一条内容送到一个通知目标；接口语汇零渠道特有词（接口先行的第一次亮相） |
| 通知目标（`NotifyTarget`） | 渠道类型 + 配置键值对；具体含义由实现解释 |
| `notify`（内置 Tool） | LLM 对话内可调用的推送入口：content 必填、channel 可选按 name 匹配 |
| `tool_invocations`（既有审计表） | notify 的每次调用成败都经既有路径写入，零新增审计逻辑 |

## Success Criteria *(mandatory)*

### Measurable Outcomes

1. 配置了通知渠道的 Agent，一次成功的 notify 调用 100% 在目标地址收到恰好一次 POST、body 携带内容；目标地址 100% 来自该 Agent 配置（换渠道只改配置零代码）。
2. 对端 5xx / 连接失败 / 缺 url 三种失败形态 100% 显式失败（异常上抛或报错点名），零静默成功；未配置通知渠道时 100% 报错点名且零请求。
3. 渠道解析：缺省 100% 取第一个渠道；显式 name 100% 命中对应渠道；指定名不存在 100% 报错点名不回退。
4. 接口中立性：主代码 grep 渠道特有词（wecom/feishu/dingtalk）零命中；凭证明文（webhook URL/token）在代码、配置、日志中出现次数为 0。
5. 自动化验收全绿（验收 harness 承载：`mvn clean verify` 九模块全绿，18 节基线 125 测试 + 本节新增、前序零回归）；「校验先于发送」顺序回归明文留 24 节。人工项：真实群机器人真推一条进群、凭证 grep、接口中立性思维自查。

## Assumptions

- HTTP 客户端用 JDK 内置 HttpClient（同步阻塞，宪法 4），不引入 spring-web 新依赖（拍板③）；本节不碰 Spring AI，无 API 代差问题。
- Sandbox 接口 23/24 节才就位：本节域名白名单按检查位注释留位，与 17 节 `http_get` 同款处理；「校验先于发送」InOrder 回归 24 节接线后补入本节测试类。
- 定时触发（25 节）尚不存在，US1 的「到点自动」由「对话内调用 notify」承载演示（拍板④：`yokeos chat` 说「把测试消息推一下」）。
- `notify` 的 channel 参数按 name 匹配（拍板①）；NotifyTools 构造注入 type→实现 的映射（拍板⑤），第一阶段只装 webhook 一档。
- 第一阶段统一 payload `{"content": ...}`，各家格式适配是扩展阶段专用 Adapter 的事；人工冒烟选天然兼容该格式的渠道。
