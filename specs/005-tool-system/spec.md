# Feature Specification: Tool 体系与 MCP——Agent 能干事的手（第20节）

**Feature Branch**: `specs/005-tool-system`

**Created**: 2026-09-17

**Status**: Draft

**Input**: User description: "第20节需求：Tool 体系与 MCP——Agent 能干事的手。背景与价值、用户场景、功能需求 FR1~FR10、明确不做、验收标准、依赖与假设（完整需求见 `docs/class/020-tool-system.md` 一、二部分；拍板记录①~⑦已用户批准）"

## Clarifications

### Session 2026-09-17

- Q: 一个 MCP server 的某个工具与已注册工具重名时，该 server 的其余工具还要继续注册吗？ → A: 逐工具容错——注册表层面重名仍拒绝并点名；MCP 连接流程按单工具粒度容错：重名工具记 WARN 点名跳过，该 server 其余工具照常注册（不连坐；参照实现的整 server 中断属「瑕疵不继承」，宪法 9）。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 三种来源的工具，Agent 无差别使用 (Priority: P1)

企业运维 Agent 接到「查一下应用为什么重启」：它用 `read_file` 读配置、`list_dir` 翻目录、`shell` 跑日志命令、`http_get` 调内部状态接口，多步组合完成任务——每次调用都落审计表。这些工具可能来自底座内置、业务方的 `@Tool` Java Bean、或某个外部 MCP server，但对 Agent 的对话体验完全一样：模型点名、底座执行、结果回填。业务方给底座加能力有三档路：零代码（AGENT.md + 复用社区 MCP server，主推）、轻代码（自写 MCP server 配进 `.yokeos/mcp_servers.yaml`）、重代码（`@Tool` Java Bean 进程内直调）——能用低档不用高档。

**Why this priority**: 「Agent 能干事」是本节存在理由本身——此前链路只有最小 `http_get`（17 节）与 `notify`（19 节）；统一注册面立不起来，三种来源就三种写法，ReAct 循环对来源的感知会迅速腐化架构。本条通了才有 MVP。

**Independent Test**: 三种来源的工具（注解管道、MCP 适配、直接实现）各注册一件，断言全部以同一抽象身份进入注册面、可按名查找；Agent 按其 frontmatter `tools` 清单点名的工具恰好可用（不多不少）。

**Acceptance Scenarios**:

1. **Given** 注册面里有内置文件工具、业务方 `@Tool` Bean 工具、外部 MCP server 工具各至少一件，**When** 逐一按名调用，**Then** 三者行为同构（调用→结果或失败原因→审计留痕），调用方无法感知来源差异。
2. **Given** Agent 的 frontmatter `tools` 清单点名了注册面中的三件工具，**When** 组装该 Agent 的可用工具，**Then** 结果恰好等于声明清单（未注册名跳过、注册面其余工具不混入）。
3. **Given** 两个来源试图以同名注册，**When** 第二次注册发生，**Then** 注册被拒绝且报错点名该名字，先注册者不受影响。

---

### User Story 2 - 外部 MCP server 失联，不拖垮底座 (Priority: P1)

业务方在 `.yokeos/mcp_servers.yaml` 里配了三个 MCP server。某天其中一个是坏配置（命令不存在）、一个进程起不来——底座启动时必须照常起来：好的 server 工具照常注册可用，坏的只记一条 WARN 日志点名跳过，绝不因为一个外部进程的生死决定自己的启动。配置文件本身缺失或解析失败同理：按零 server 处理，启动照常。

**Why this priority**: 「外部依赖的可用性不是自己的可用性」是企业底座的底线；MCP server 是任意语言写的外部进程，失联是常态而非异常。反过来，一个 server 配错就把整个底座打趴，业务方不敢配第三个 server。

**Independent Test**: 配置指向一个好 server 与一个连接必失败的 server，触发启动连接流程，断言整个过程不抛异常、好 server 的工具已注册、坏 server 的工具零注册。

**Acceptance Scenarios**:

1. **Given** 配置含 good-server 与 bad-server（连接必失败）两条，**When** 启动连接流程执行，**Then** 流程正常完成不抛异常，good-server 的工具在注册面，bad-server 的工具零注册且日志有 WARN 点名。
2. **Given** `.yokeos/mcp_servers.yaml` 不存在，**When** 启动，**Then** 按零 server 处理，底座与内置工具照常工作。
3. **Given** 配置里某 server 的 `transport` 不是 stdio（如 sse），**When** 启动连接流程执行，**Then** 该 server 被跳过并记 WARN，其余 server 不受影响。
4. **Given** 某 server 的 `env` 值引用了未设置的环境变量占位符，**When** 配置加载，**Then** 占位符保留原样并记 WARN，不抛异常不阻断。

---

### User Story 3 - 调用透传与失败语义可信 (Priority: P1)

Agent 对话中模型点名调用一个 MCP 工具 `github_search` 带参数 `{"query":"yokeos"}`：参数必须**原样**转发到 MCP server（不增不删不改），server 返回的文本内容拼接为结果返回；server 自己报错（如限流）时，该调用返回**可重试**的失败——网络类瞬态失败值得循环再试一次。同理，内置工具的失败语义必须诚实：`shell` 命令非零退出码报失败并带 stderr、命令挂死按超时（默认 30 秒）强杀终止；`read_file` 读超大文件截断保护；HTTP 4xx/5xx 报失败。

**Why this priority**: 工具是模型的手，手的感觉不真实（参数被改、失败装成功、挂死无终），模型基于错误观察做的所有推理都是错的；可重试标记直接决定 ReAct 循环要不要再试。

**Independent Test**: mock MCP server 返回工具清单与调用结果，断言转发参数逐字一致、成功结果包装正确、`isError` 结果包装为可重试失败；`shell` 用挂死命令验证超时终止、用非零退出命令验证失败带 stderr。

**Acceptance Scenarios**:

1. **Given** MCP server 声明了工具且模型带参调用，**When** 调用执行，**Then** 转发到 server 的参数与模型给出的逐字一致，返回的文本内容如实回填。
2. **Given** MCP server 对一次调用返回错误标记，**When** 结果回填，**Then** 该次调用为失败、错误信息含 server 给出的内容、标记为可重试。
3. **Given** `shell` 收到一条永不退出的命令，**When** 超时（默认 30 秒，测试可注入更短值）到达，**Then** 进程被强制终止、该次调用报失败且信息含超时秒数。
4. **Given** `shell` 收到的命令以非零码退出，**When** 调用结束，**Then** 该次调用报失败且信息含退出码与 stderr 内容。

---

### User Story 4 - 工具面可查，配置错误有痕 (Priority: P2)

管理员用 `yokeos tool list` 查看当前注册了哪些工具，看到的是**真实就绪的静态注册面**（内置六件 + notify），而不是规划清单；MCP 动态工具注明随重命令启动注册、此处不列。某个 Agent 的 frontmatter `tools` 清单写错了名字（注册面没有），启动日志出现 WARN 点名——「模型零工具可用」这类配置错误必须在启动时看得见，而不是等到对话里模型两手空空。

**Why this priority**: 可观测性是运维底线；19 节实证过「点名不在候选集被静默略过」的最危险形态（漏写 `tools:` → 零工具、模型只会口头答复），本节把静默变有痕。

**Independent Test**: 运行 `yokeos tool list` 断言输出恰好覆盖静态注册面七件、无规划中的幽灵条目；构造点名未注册名的 Agent 目录，断言启动日志出现含该名字的 WARN。

**Acceptance Scenarios**:

1. **Given** 底座已实现六件内置工具与 notify，**When** 运行 `yokeos tool list`，**Then** 输出恰好七件（无多无少），并注明 MCP 动态工具不在此列。
2. **Given** 某 Agent 的 `tools` 清单含注册面不存在的名字 `foo_tool`，**When** 启动加载，**Then** 该 Agent 正常注册（不阻断），日志出现点名 `foo_tool` 的 WARN。

### Edge Cases

- `mcp_servers.yaml` 存在但内容非法（非 YAML / 结构不对）→ 按零 server 处理 + WARN，不阻断启动。
- MCP server 的 `listTools` 返回空列表 → 零工具注册，不算失败。
- `read_file` 目标是目录或不存在 → 报错点名路径，不吞。
- `write_file` 目标父目录不存在 → 自动创建后写入。
- 一次响应里模型同时点名多个工具 → 顺序逐个执行（并行调用明确不做）。
- 两个 MCP server 暴露同名工具 → 后注册者被重名拒绝（与内置重名同一条规则），报错点名；该 server 其余工具照常注册（逐工具容错，不连坐）。
- `tool list` 在无 Spring 上下文的轻命令环境运行 → 零 Spring 启动开销，静态注册面即时构造。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001（统一注册表）**: 三种来源的工具（`@Tool` 注解 Bean、外部 MCP server、直接实现）MUST 统一成既有工具抽象注册进同一注册面；工具执行器与 prompt 组装器只认注册面、不感知来源；工具契约三件套（名称 / 描述 / 参数 schema）任一缺失视为该工具不合格（契约测试遍历注册面兜底）。
- **FR-002（重名拒绝）**: 同名注册 MUST 拒绝并点名，绝不静默覆盖；先注册者不受影响。MCP 注册流程按单工具粒度容错：重名工具记 WARN 跳过，该 server 其余工具照常注册（clarify B）。
- **FR-003（文件工具三件）**: `read_file` 读取文本文件（超约 8000 字符截断并注明总长）；`write_file` 覆盖写且父目录不存在自动创建；`list_dir` 列出目录条目（排序稳定）；目标不存在或非法 MUST 报错点名路径。
- **FR-004（shell 工具）**: 命令以 argv 数组直传（`{"command": ["git","status"]}` 形态），MUST NOT 经 shell 解释拼接；超时默认 30 秒到点强杀并报失败；非零退出码报失败带退出码与 stderr；成功返回 stdout。
- **FR-005（HTTP 工具两件）**: `http_get` / `http_post`（JSON body），同步阻塞、连接与读取超时约 10 秒、响应超长截断（沿用 17 节口径）；4xx/5xx MUST 报失败；`http_get` 从 17 节最小单类形态演进为成对工具，原形态退役。
- **FR-006（MCP 配置加载）**: `.yokeos/mcp_servers.yaml` 声明 server（`name` / `transport` / `command` / `env`）；`env` 值支持 `${ENV}` 占位、缺失保留原样并 WARN；文件缺失或解析失败 MUST 按零 server 处理不阻断启动；`transport` 非 stdio 跳过并 WARN（第一阶段 stdio 唯一）。
- **FR-007（MCP 连接与注册）**: 启动时逐 server 连接、调 tools/list、逐个包装注册；任一 server 连接/初始化/列工具失败 MUST 只 WARN 跳过其工具，不拖垮启动、其余 server 照常注册。
- **FR-008（MCP 调用透传）**: 调用参数 MUST 原样转发；文本内容拼接为结果；server 报错 MUST 包装为可重试失败（错误信息含 server 给出的内容）。
- **FR-009（Profile 点名有痕）**: prompt 组装按 `Profile.tools` 点名过滤的结果 MUST 恰好等于声明清单（未知名跳过）；启动加载时对点名了但注册面没有的工具名 MUST 记 WARN 不阻断。
- **FR-010（tool list 改查注册表）**: `yokeos tool list` MUST 输出真实就绪的静态注册面（内置六件 + notify），保持轻命令零 Spring；MCP 动态工具注明随重命令启动注册、此处不列。
- **FR-011（init 补配置模板）**: `yokeos init` MUST 在工作区幂等补建 `.yokeos/mcp_servers.yaml` 注释模板（已存在不覆盖）。
- **FR-012（审计零新增）**: 全部工具调用 MUST 沿用既有 `tool_invocations` 审计路径，零新增审计逻辑。
- **FR-013（安全检查位）**: 六件工具每个动作第一行 MUST 留白名单校验检查位注释（钉死校验调用形态与共享配置口径）；校验本体归第 24 节，本节不实现。

### Key Entities

- **统一注册面（ToolRegistry）**: 所有来源工具的汇合点，按名注册/查找/过滤，重名拒绝；对执行器与 prompt 组装器暴露既有 Map 形态。
- **工具抽象（YokeTool，17 节既有）**: 名称 / 描述 / 参数 schema / 执行四方法契约；本节不新增抽象。
- **`.yokeos/mcp_servers.yaml`**: MCP server 连接配置（name / transport / command / env），`${ENV}` 占位从环境变量解析。
- **Profile.tools 点名清单**: Agent frontmatter 声明的可用工具子集，过滤与 WARN 校验的依据。
- **`tool_invocations`（既有审计表）**: 每次工具调用的最终态记录，本节零新增逻辑。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 注册面中每个工具 100% 通过契约三件套检查（名称/描述/schema 非空非空白、schema 含参数定义）——参数化测试遍历，新工具自动纳入。
- **SC-002**: 三种来源工具 100% 以统一抽象身份注册；同名注册 100% 被拒绝且报错含名字。
- **SC-003**: 任一 MCP server 失联时启动成功率 100%（流程不抛异常），其余 server 工具 100% 照常注册；配置文件缺失/解析失败同口径。
- **SC-004**: MCP 调用参数转发 100% 逐字一致；server 错误 100% 包装为可重试失败。
- **SC-005**: `shell` 挂死命令 100% 在超时内被终止并报失败；`read_file` 超长内容 100% 截断；HTTP 4xx/5xx 100% 报失败。
- **SC-006**: 按 `Profile.tools` 过滤结果 100% 恰好等于声明清单；点名未注册名 100% 在启动日志留 WARN。
- **SC-007**: `yokeos tool list` 输出与静态注册面 100% 一致（七件，无幽灵条目）；`yokeos init` 重复执行零覆盖（含 mcp_servers.yaml）。
- **SC-008**: 自动化验收 `mvn clean verify` 九模块全绿（含本节新增测试，前序零回归）；人工项：真实 stdio MCP server 接入并在 `yokeos chat` 中被模型真实调用、检查位注释抽查、凭证卫生 grep 零命中。

## Assumptions

- 统一工具抽象与结果对象（17 节）、`Profile.tools`/`mcpServers` 字段（16 节建全）、notify 工具与渠道适配器（19 节）、prompt 点名过滤与执行器 Map 消费（17 节）均已就位；本节只换 Map 的来源，前序类不动（唯一例外：17 节最小 http_get 单类按预告演进退役）。
- 新依赖两件：spring-ai-model（`@Tool` schema 生成，宪法 2 允许用途）与 MCP Java SDK（stdio 同步客户端）；版本经 spring-ai-bom 1.1.8 管理或显式钉版，动手前 `mvn dependency:resolve` 核实；SDK API 写法以本地依赖 `javap` 核实为准（参照课件为旧版形态存在代差）。
- Sandbox 接口第 23 节评审、24 节落地：本节全部安全校验以检查位注释留位，白名单拦截用例与顺序回归归 24 节。
- 五件扩展工具（edit_file/grep/glob/ask_user/web_search）为参照超集交付，YokeOS 需求清单九件不含，扩展阶段按信号补齐（教学文档拍板④）。
- MCP server 的 Profile 级声明（frontmatter `mcp_servers` 字段）本节不消费——MCP 工具为实例级全局注册面；目录语义与按 Profile 过滤归 29 节。
- 集成冒烟（真 stdio MCP server + 真模型）标 `@Tag("integration")` 默认排除，环境缺失（npx 不可用/无 key）assumeTrue 跳过不失败。
- 演示口径 = 需 §11 第 20 节行：内置工具全量可用、接入外部 MCP server；「白名单校验生效」条的完整兑现按教学文档拍板①归 24 节。
