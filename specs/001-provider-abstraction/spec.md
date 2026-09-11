# Feature Specification: Provider——对接大模型的统一入口（第16节）

## Clarifications

### Session 2026-09-10

- Q: Agent 的 AGENT.md frontmatter 修改后，新配置何时开始生效？ → A: 重启实例后生效——本节唯一注册路径是启动扫描；运行时注册与热重载归第 29 节。
- Q: `yokeos init` 创建的三个 Bootstrap 文件初始内容放什么？ → A: 最小占位模板——一级标题 + 一行用途说明 + 填写提示，不预填任何演示内容。
- Q: 引用清单外的 provider 名，加载期与调用期都报错吗？ → A: 双防线有意并存——加载期（AgentLoader）记错误日志并跳过该 Agent；调用期（ProviderService）抛含名字的异常作运行期兜底（analyze C1）。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 多 Provider 并存，按 Agent 配置精确路由 (Priority: P1)

企业在同一个 YokeOS 实例上跑多个 Agent：运维 Agent 用 deepseek、客服 Agent 用 qwen。每个 Agent 的一次模型调用，必须严格路由到它自己 `AGENT.md` frontmatter 里声明的那家 provider 和那个 model，拿回响应原样返回给上层；请求里可以附带「有哪些工具可用」的 schema 说明，模型若回复「想调某工具」，该请求原样交回上层，本能力绝不代为执行。

**Why this priority**: 这是 Provider 存在的意义本身——没有精确路由，多 Agent 并存就是空话；没有「只翻译不执行」，工具会被重复执行且绕过安全检查。这一条通了才有 MVP。

**Independent Test**: 配置两家 provider，各发一次调用，验证各自命中目标家、另一家零调用；附带工具说明发一次调用，验证模型的工具调用请求被原样透传、本能力零执行。

**Acceptance Scenarios**:

1. **Given** 实例配置了 deepseek 和 kimi 两家 provider，**When** 使用声明 kimi 的 Agent 发起一次调用，**Then** 请求发往 kimi，deepseek 全程零调用，响应原样返回。
2. **Given** Agent 声明的 provider 名在实例清单中不存在，**When** 发起调用，**Then** 立即得到明确报错（指出是哪个名字找不到），绝不静默改用其他家。
3. **Given** 调用附带了可用工具的 schema 说明，**When** 模型返回「想调用某工具」的请求，**Then** 该请求原样出现在返回结果中，本能力未执行任何工具。

### User Story 2 - 换模型只改配置，不碰代码 (Priority: P2)

管理员想给某个 Agent 换模型（换家或换 model），只改它 `AGENT.md` frontmatter 的 `provider` / `model` 字段，重载后生效，不需要改任何代码、不需要重新构建。

**Why this priority**: 「不锁厂商」是企业选型承诺；改配置即换模型是抽象层存在的直接收益。

**Independent Test**: 同一 Agent 改 frontmatter 的 provider 字段从一家换成另一家，各发一次调用，验证两次分别命中各自目标。

**Acceptance Scenarios**:

1. **Given** Agent 原先声明 deepseek，**When** 管理员把 frontmatter 改为 kimi 并重启实例，**Then** 后续调用全部发往 kimi。
2. **Given** 某个 Agent 目录的 AGENT.md 损坏或非法，**When** 实例启动，**Then** 该 Agent 记错误日志被跳过，其余 Agent 照常加载，启动不阻断。

### User Story 3 - 每次调用可审计，失败也留痕 (Priority: P3)

审计员事后能查到：某次会话调了哪家模型、输入输出各多少 token、耗时多久；某次调用失败了，失败原因是什么。成功和失败都必须在库里有记录。

**Why this priority**: 可审计是企业级底座的差异化卖点；只记成功不记失败，一次真实事故在系统里就完全没留下痕迹。

**Independent Test**: 一次成功调用后查审计记录字段齐全；一次失败调用（如超时）后查审计记录含失败标识与原因，且异常已抛给上层。

**Acceptance Scenarios**:

1. **Given** 一次调用成功，**When** 查审计表，**Then** 该次调用有记录：provider、model、token 三项用量、耗时、成功标识。
2. **Given** 一次调用失败（网络超时），**When** 调用方收到异常，**Then** 审计表已有该次调用的记录：失败标识 + 失败原因，先落账后抛错。

### Edge Cases

- `yokeos init` 在已有工作区上重复执行：已存在的目录与文件一律不覆盖（幂等）。
- 环境变量缺失（如 API key 未配置）：启动校验给出清晰报错，不静默失败、不带空值跑到运行期。
- Agent 引用清单外 provider 名：报错必须包含缺失的名字本身。
- 坏 AGENT.md 不阻断启动，但其余同名/合法 Agent 不受影响。

## Requirements *(mandatory)*

### Functional Requirements

- **FR1（工作区初始化）**: `yokeos init` 在当前目录幂等创建 `.yokeos/`——六子目录（agents / skills / output / memory / sessions / logs）+ 三个 Bootstrap 文件（AGENTS.md / SOUL.md / USER.md，内容为最小占位模板：一级标题 + 一行用途说明 + 填写提示），已存在一律不覆盖。
- **FR2（Agent 定义与派生）**: 每个 Agent = `.yokeos/agents/<name>/AGENT.md`；frontmatter 启动时派生为内部 Profile 并注册，按名查找。本节校验「provider 名能在全局清单找到」一条，其余字段校验随后续节各自补；坏文件记错误日志、不阻断启动。
- **FR3（实例级 Provider 清单）**: `application.yaml` 声明接入的 provider 及凭证来源（`${ENV_VAR}` 占位）；Profile 引用清单外的 provider 名必须显式报错（指出哪个名字找不到），绝不静默改用他家。
- **FR4（统一调用）**: 上层传入 sessionId、Profile、Prompt，按 Profile 经显式映射选中对应模型发起一次调用，结果原样返回；映射显式建立，不靠容器类型扫描。
- **FR5（只翻译不执行）**: 请求可携带工具 schema 说明；模型返回 tool call 请求原样交回上层，本模块零执行；LLM 框架自带的自动工具执行与 eager 自动装配必须禁用。
- **FR6（审计 day one）**: 每次调用成败都落 `llm_calls`（provider、model、token 三项、耗时、success、error_message，按 session 关联）；`tool_invocations` 同步建表、写入归第 17 节。
- **FR7（凭证只走环境变量）**: 代码、配置、日志无明文 key；启动校验，缺失或非法清晰报错不静默失败。

**明确不做（边界）**: ReAct 循环本身、工具的真正执行、fallback / 熔断 / hedge racing、成本聚合看板、流式响应、完整 CLI 命令组（`profile create/list/show/delete` 归第 18 节，本节 Agent 目录手写即可）。

### Key Entities

| 实体 | 说明 |
|------|------|
| `AGENT.md` frontmatter | Agent 自身的声明（本节消费 `provider` 段：name / model / temperature），正文是任务指令 |
| Profile | frontmatter 派生出的内部对象，全字段承载，按 name 注册与查找 |
| Provider 清单 | 实例级 `application.yaml` 声明：provider 名 + 凭证环境变量占位 |
| `llm_calls` | 审计表：provider / model / token 三项 / 耗时 / success / error_message / session 关联 |
| `tool_invocations` | 审计表（本节建表，写入归第 17 节） |
| `.yokeos/` 工作区 | 六子目录 + 三 Bootstrap 文件，`yokeos init` 幂等创建 |

## Success Criteria *(mandatory)*

### Measurable Outcomes

1. 双 Provider 并存时，任何一次调用 100% 命中 Agent 声明的目标家，另一家零调用；引用未声明名 100% 在发起前被拒且报错含该名字。
2. 每次调用（含失败）100% 在审计表留下记录；失败记录含原因，先落账后抛错。
3. 带工具说明的请求，自动执行 100% 处于关闭状态，工具调用请求原样透传、零执行。
4. `yokeos init` 重复执行零覆盖；凭证明文在代码、配置、日志中出现次数为 0。
5. 自动化验收全绿（验收 harness 承载，一次运行内完成）；人工项：BOM 依赖核实、真实 key 冒烟真调一次拿到非空回复。

## Assumptions

- 先跑通 DeepSeek 或 Kimi 一家（BOM 依赖核实结果定），第二家用于路由测试（mock 或真实）。
- 本节上层（ReAct 循环）尚不存在，「上层传入」以测试与冒烟入口承载；第 17 节接入后零改动复用。
- 工具 schema 的携带是可选参数；本节无真实工具，schema 翻译以测试桩验证。
- Profile 校验本节仅「provider 名存在」一条；temperature 等参数透传不解释。
- 本节演示口径：`yokeos init` 就绪 + 真实 key 冒烟真调拿到回复；完整 CLI 对话归第 18 节。
