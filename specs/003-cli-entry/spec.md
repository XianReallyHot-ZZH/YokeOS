# Feature Specification: CLI——YokeOS 的命令行入口（第18节）

**Feature Branch**: `specs/003-cli-entry`

**Created**: 2026-09-15

**Status**: Draft

**Input**: User description: "第18节需求：CLI——YokeOS 的命令行入口"（六段式组装参数，取材 `docs/class/018-cli-entry.md` 一、二部分，七项拍板已内嵌）

## Clarifications

### Session 2026-09-15

- 结构化歧义扫描（九类覆盖）：全部 Clear 或 Deferred-to-plan——七项拍板（教学文档头部）已钉死全部高影响决策点（范围口径 / `agent_name` 列 / session_id 格式 / 交互面字面量 / delete 归档语义 / 装配落位与配置归属 / 演示口径），无需向用户提问。
- Q: 装配完整性测试需要真实 API key 吗？ → A: 不需要——用哑 key 占位（配置校验只查存在性与格式、不起真调用）；真 key 只用于人工项的多轮对话验收。出处：教学文档第四部分「哑 key + @TempDir SQLite」。
- Q: `--message` 给了空白值怎么处理？ → A: 报参数错误、退出码非 0——不静默进交互模式（空消息没有处理意义，与交互内空行跳过同理）。默认自答（无文档链明文），停点供确认。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 终端里跟 Agent 说上话 (Priority: P1)

开发者敲 `yokeos chat` 进入交互式对话：问「今天天气怎么样，穿什么合适」，Agent 调工具、查天气、给建议；继续追问、继续答。对话里随时 `/context` 查看当前会话上下文、`/tools` 查看本会话 Tool 调用记录；`/quit` 正常退出；`--profile weather` 换 Agent；`--message "xxx"` 发单条消息后即退出（需 §5.12）。隔天再进来，上次聊过什么还在——多轮对话靠同一条会话串起来（需 §11 第 18 节可演示成果）。

**Why this priority**: 这是 Provider、ReAct 之后第一个「看得见摸得着」的入口——到这一步用户才能真正跟自己搭的 Agent 说上话，Demo 一（每日天气）的对话版靠它撑起；`/context`、`/tools` 是本节可演示成果的直接承载。

**Independent Test**: 用测试替身代替引擎，脚本化驱动「多行输入 + 各交互命令 + /quit」的交互，验证每行输入都转交引擎、回复都打印、交互命令不走引擎、退出干净；会话幂等与恢复由 US2/US3 独立钉死。

**Acceptance Scenarios**:

1. **Given** 默认 Agent 就绪，**When** 用户敲 `yokeos chat` 并连续输入两句话，**Then** 每句都交给引擎处理并打印回复，两句共享同一条会话。
2. **Given** 交互进行中，**When** 用户输入 `/context`，**Then** 打印当前会话消息（单条截断保护）不转交引擎；输入 `/tools`，**Then** 打印本会话 Tool 调用记录（工具名/成败/耗时，来自审计表）不转交引擎。
3. **Given** 交互进行中，**When** 用户输入 `/quit`（前后带空白也认）或输入流关闭（EOF），**Then** 命令正常退出、不抛异常堆栈；空行跳过不转交。
4. **Given** 用户敲 `yokeos chat --profile weather`，**When** 对话发起，**Then** 使用 weather 这个 Agent（不传则用 default）；Profile 不存在时点名报错、不进交互循环。
5. **Given** 用户敲 `yokeos chat --message "今天天气如何"`，**When** 执行，**Then** 发一条、打印回复、即退出（不进交互循环）；`--message` 空白值报参数错误退出非 0。

### User Story 2 - 会话口径一次钉死：幂等与隔离 (Priority: P1)

会话标识由三元组（渠道+用户+Agent）唯一决定，生成只发生在会话管理者内部一处。同一三元组反复获取永远是同一条会话；三元组任何一个元素不同就是不同会话。CLI、Web、定时——所有入口都只报三元组，谁都不许自己拼字符串。

**Why this priority**: 会话层是后面所有入口共用的地基（17 节预告改造点）。两处各拼一遍、格式差一个分隔符，同一个人就会出现两条互不相认的历史——这类口径问题最难查，必须在第一个用起来会话的入口这一节钉死。

**Independent Test**: 对会话管理者连续两次以同一三元组获取，断言拿到同一会话；改变任一元素，断言是不同会话；全库检索会话标识拼接逻辑，只存在于会话管理者实现一处。

**Acceptance Scenarios**:

1. **Given** 会话管理者就绪，**When** 以 ("cli","wang","weather") 两次获取，**Then** 两次返回的会话标识相同（幂等）。
2. **Given** 同上，**When** 以 ("web","wang","weather") / ("cli","li","weather") / ("cli","wang","daily") 获取，**Then** 与基准各是不同会话（三元组任一元素不同即隔离）。
3. **Given** 代码库全量检索，**When** 查会话标识的拼接逻辑，**Then** 只存在于会话管理者实现内部一处（H4④ 不变量）。

### User Story 3 - 会话落库与跨重启恢复 (Priority: P1)

会话持久化到 sessions 表：标识、所属 Agent（`agent_name` 列）、渠道、用户、整体序列化的对话历史（`messages_json`）、状态、三个时间戳。进程重启后，按同一三元组进来还能拿回完整历史，继续聊是在恢复的历史上追加。

**Why this priority**: 「隔天再聊还记得」是会话存在的意义（需 §5.11「重启 YokeOS 后正在进行的 Session 可以恢复」）；CLI 是第一个真正用起来 Session 的入口，持久化随本节交付。

**Independent Test**: 存一条含 user/assistant/tool 三类消息的会话，模拟重启（同一库文件、新建数据访问实例重查），断言消息一条不丢、顺序不变。

**Acceptance Scenarios**:

1. **Given** 手工建表脚本建出的 sessions 表，**When** 保存一条含三类消息的会话并按三元组重新获取，**Then** 历史序列化回读后消息完整、顺序不变。
2. **Given** 已保存的会话，**When** 模拟进程重启后按同一三元组获取，**Then** 拿回同一条会话及其全部历史；继续对话时新消息追加在恢复的历史之后。
3. **Given** 会话有活动，**When** 保存，**Then** `last_active_at` 刷新；新建时 `created_at`/`status=active` 正确；零消息的新会话正常保存与恢复。

### User Story 4 - 12 个子命令与轻重分流 (Priority: P2)

一个命令行入口挂 12 个子命令：跑 Agent 的（chat/serve/gateway）、看情况的（status、profile list/create/show/delete、provider list、tool list、session list）、起项目的（init，16 节已交付本节接入根命令）。「看一眼就退」的轻命令直接读写文件/数据库秒回、不启动重运行时；只有要跑引擎的命令才付启动代价。fat JAR 的主入口是命令行入口，CLI 参数直达子命令。

**Why this priority**: 命令面数量多但逻辑浅；分流标准（要不要调模型/跑引擎）一开始定死，否则要么全都慢、要么后面改起来伤筋动骨（参照课件坑二）。fat JAR 主入口不切，CLI 参数进不了 jar。

**Independent Test**: 逐个执行 12 个命令的帮助与基本路径（轻命令在无重运行时依赖下独立完成）；profile 命令组对 Agent 目录的增删查幂等与归档语义各有断言。

**Acceptance Scenarios**:

1. **Given** 已构建的命令行入口，**When** 依次运行 12 个子命令的 `--help`，**Then** 每个都有帮助信息；未知子命令/参数统一报错、退出码非 0、无堆栈。
2. **Given** `.yokeos/agents/` 下有若干 Agent 目录，**When** 运行 `yokeos profile list` / `show <name>`，**Then** 列出清单 / 打印 AGENT.md 原文，全程不启动重运行时、秒级返回。
3. **Given** 运行 `yokeos profile create demo`，**Then** 写出最小 AGENT.md 模板（provider 缺省取全局层 `yokeos.providers` 第一个）；已存在时报错不覆盖（幂等纪律）。**Given** 运行 `yokeos profile delete demo`，**Then** 目录归档式移入 `.yokeos/archive/`（不物理删），原目录不复存在。
4. **Given** `yokeos provider list` / `tool list` / `session list`，**Then** 分别列出配置的 provider（name/base-url，不解析 key）、当前真实就绪的内置工具（注明 20 节接注册表后改查）、会话概览；库文件不存在时输出「暂无会话」不抛异常。
5. **Given** fat JAR，**When** `java -jar ... chat --help`，**Then** CLI 参数直达子命令（主入口已切换）；serve/gateway 启动上下文常驻并打印「REST 端点 26 节接线」说明。

### User Story 5 - 重命令启动即校验装配完整 (Priority: P2)

chat/serve/gateway 启动完整运行时后，数据访问层必须装配完整——存储在独立模块，扫描范围必须显式声明。「仓库接口数量为 0」这类装配残缺不允许带病运行：审计和会话会静默写不进去，等发现时已经丢了数据（参照课件坑四，本仓 boot 主类当前同样缺显式声明）。

**Why this priority**: 真实踩过的坑：照「轻重分流、重命令才启动运行时」的思路走几乎绕不开，必须提前想到并用测试钉住；升格为机器断言（宪法 9：参照留人工目检的瑕疵不继承）。

**Independent Test**: 以重命令同款装配面启动一次测试上下文（哑 key + 临时 SQLite 库），断言会话与两张审计表的仓库 Bean 都在（数量 > 0）、能完成一次会话写读往返。

**Acceptance Scenarios**:

1. **Given** 重命令使用的运行时装配面，**When** 启动上下文，**Then** JPA 仓库 Bean 数 > 0（装配残缺立刻红），会话管理者/双审计员/统一处理入口/交互通道 Bean 全部就位。
2. **Given** 三种运行模式，**When** 分别启动，**Then** 共享同一份 Agent 配置与同一套会话存储（数据不因模式切换而丢）。

### Edge Cases

- chat 输入空行 → 不转交引擎，继续读下一行。
- chat 引用的 Profile 不存在 → 启动即点名报错（含名字），不进入交互循环。
- `/quit` 前后有空白（" /quit "）→ trim 后判断，照常退出。
- 输入流关闭（EOF/Ctrl-D/管道结束）→ 等同退出，不抛异常堆栈。
- 同一三元组并发获取会话（虚拟线程）→ 不产生两条会话（同库唯一主键兜底）。
- `messages_json` 为空的新会话 → 正常保存与恢复（零消息不是错误）。
- 重启恢复后的会话继续对话 → 新消息追加在恢复历史之后，不覆盖不重排。
- `/tools` 时本会话尚无任何工具调用 → 输出「暂无 Tool 调用记录」，不抛异常。
- `--message` 提供但为空白 → 报参数错误退出非 0，不静默进交互模式。
- `profile create` 目标已存在 → 报错不覆盖（幂等纪律）；`profile show/delete` 目标不存在 → 点名报错。
- `provider list` 时 application.yaml 缺失或无 `yokeos.providers` → 友好提示先配置，不抛异常。
- `session list` 在库文件不存在时 → 「暂无会话」，不抛异常。
- `profile create` 时全局层无任何 provider → 报错提示先配置 provider，不写残缺模板。

## Requirements *(mandatory)*

### Functional Requirements

- **FR1（命令面）**: 系统提供唯一命令行入口，注册 12 个子命令（init、status、chat、serve、gateway、profile list/create/show/delete、provider list、tool list、session list）；每个命令有帮助信息（`--help`）；未知子命令/参数统一报错、退出码非 0、无堆栈。
- **FR2（薄壳纪律）**: 命令行层只做「读输入 → 转交统一处理入口 → 打印结果」，不含任何 Agent 逻辑（不想、不调模型、不执行工具）。
- **FR3（轻重分流）**: 不调模型/不跑引擎的命令（init、status、profile 四件、provider list、tool list、session list）不启动 Spring 上下文、秒级返回（纯文件/只读 JDBC 直达）；chat/serve/gateway 才启动完整运行时。
- **FR4（chat 交互面）**: chat 维护当前会话、逐行读入交给第 17 节统一处理入口；`/quit`（trim 后判断）退出、EOF 等同退出、空行跳过不转交；`/context` 打印当前会话消息（最近优先、单条截断保护）不转交引擎；`/tools` 打印本会话 Tool 调用记录（工具名/成败/耗时，来自 `tool_invocations` 审计表只读查询）不转交引擎；`--profile` 指定 Agent（默认 `default`）；`--message <text>` 单条消息模式：发一条、打印回复、即退出（需 §5.12），空白值报参数错误退出非 0。交互通道的 IO 可注入（测试脚本化驱动）。
- **FR5（会话标识口径）**: 会话标识由三元组 channel+user+agent 联合唯一生成（格式 `channel:user:agent`，如 `cli:wang:weather`；格式不对外承诺），拼接只发生在会话管理者实现内部一处，所有入口只提供三元组；同一三元组多次获取幂等返回同一会话（含已恢复历史），任一元素不同必须是不同会话；未命中获取即落一条 `status=active` 新记录。
- **FR6（会话持久化）**: 会话持久化到 sessions 表（列：`session_id` 主键、`agent_name`、`channel`、`user_id`、`messages_json`、`status`、`created_at`、`last_active_at`、`archived_at`；手工建表脚本 schema-002，SQLite，禁自动迁移）；对话历史整体 JSON 序列化存一列（与 17 节会话消息三元组一一对应）；保存时整体覆盖 `messages_json` 并刷 `last_active_at`；跨进程重启按同一三元组恢复完整历史（消息不丢、顺序不变）；`status` 本节只产生 `active`，`archived` 归档动作归 26 节（列先建好恒空）。
- **FR7（三模式共享）**: 三种运行模式（chat/serve/gateway）共享同一份 Agent 配置与同一套会话存储；serve/gateway 本节为启动骨架（起完整运行时常驻并打印说明，REST 端点归 26 节、多通道挂载扩展阶段；serve `--port` 透传监听端口，默认 8080）。
- **FR8（装配完整性）**: 重命令运行时装配面显式声明存储模块的实体与仓库扫描范围；数据访问层装配完整（仓库 Bean 数 > 0）以测试钉死（哑 key + 临时 SQLite 库启动同款装配面），装配残缺立刻红、不允许带病运行；17 节前向定义的会话管理最小接口（仅 save）由本节补全为 getOrCreate/get/save（预告改造点，内存实现随动、17 节既有测试零改动保持绿）。
- **FR9（profile 命令组）**: 操作 `.yokeos/agents/` 下的 Agent 目录——list 列出目录清单、show 打印 AGENT.md 原文、create `<name>` 写最小 AGENT.md 模板（已存在报错不覆盖；provider 缺省取全局层 `yokeos.providers` 第一个，无 provider 配置时报错提示先配置）、delete `<name>` 归档式移入 `.yokeos/archive/`（不物理删，对齐 30 节 DELETE 端点语义；归档目录按需创建）。
- **FR10（session list）**: 纯只读 JDBC 查询会话概览（标识/Agent/状态/最后活跃），库文件不存在时输出「暂无会话」不抛异常；`status` 检查工作区/配置/库文件存在性输出摘要（零 Spring）。
- **FR11（fat JAR 入口与配置归属）**: fat JAR 主入口切换为命令行入口（构建配置 mainClass 指 CLI 根命令，CLI 参数直达子命令）；启动模块原有的 Spring 入口类保留（26 节 serve 接线）；运行配置唯一归启动模块的 application.yaml（`schema-locations` 登记 schema-002），不在 CLI 模块另放配置；伴随修正：启动模块上下文加载测试补哑 key 测试属性（全链 Bean 进上下文后无 key 起不了上下文，断言语义不变）。

**明确不做（边界）**: IM Channel（企业微信/飞书/钉钉/Slack）扩展阶段；REST 端点归 26 节（serve 本节只起上下文常驻骨架）；定时触发归 25 节；ToolRegistry/MCP 归 20 节（tool list 本节列当前真实就绪的内置工具并注明，不查注册表）；对话历史按条拆表不做（整体 JSON 一列）；认证、流式输出不做（需 §5.10 第一阶段边界）；归档会话动作归 26 节（本节 status 只产生 active）；channel「web」的实际接入归 26 节、「scheduler」归 25 节（本节只钉死三元组口径与拼接单点）。

### Key Entities

| 实体 | 说明 |
|------|------|
| 会话记录（sessions 表一行） | 一条会话的持久化形态——标识（三元组唯一决定）、所属 Agent（`agent_name`）、渠道、用户、整体序列化的历史（`messages_json`）、状态（active/archived）、三个时间戳 |
| 会话管理者（SessionManager，17 节前向接口本节补全） | 三元组→会话的唯一权威：获取或新建（幂等）、按标识查、保存；标识拼接只在其实现内部一处（H4④） |
| 命令（12 个） | 用户与系统交互的动作单元，分轻（文件/只读 JDBC 直达，零 Spring）重（启动完整运行时）两类 |
| Tool 调用记录只读口 | `/tools` 的数据源：按会话标识查 `tool_invocations`（工具名/成败/原因/耗时），与既有审计写口同包对称；26 节 Web 会话详情可复用 |
| 重命令运行时装配面 | chat/serve/gateway 共用的 Spring 装配：显式声明 JPA 扫描范围 + 全链显式 Bean（配方 = 17 节冒烟手工装配转录）；轻命令不进这里 |
| 会话消息 | 17 节既有 (role, content, toolName) 三元记录——`messages_json` 序列化形态与其一一对应，回读零转换 |

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 用户在终端完成一次多轮对话（≥2 轮）并正常退出，`/context` 能看到刚才的对话、`/tools` 能看到本会话的工具调用记录；再次进入历史仍在（需 §11 第 18 节可演示成果，真 key 人工项）。
- **SC-002**: 同一三元组 100 次获取产生且仅产生 1 条会话；三元组任一元素不同即不同会话（全排列验证）——自动化回归钉死。
- **SC-003**: 会话含 N 条消息（三类角色混合）时跨重启恢复后仍是 N 条且顺序不变（零丢失）；继续对话追加在恢复历史之后。
- **SC-004**: 轻命令（如 profile list）冷执行秒级返回（无重运行时启动等待，脚本计时留证据）；重命令启动后数据访问层仓库 Bean 数 > 0（机器断言，Found 0 即红）。
- **SC-005**: 12 个子命令 100% 可执行且有帮助信息；未知子命令/参数有清晰报错而非堆栈（退出码非 0）。
- **SC-006**: 自动化验收全绿（验收 harness 承载，`mvn test` 一次运行内完成）；人工项单列：真 key 多轮对话、轻命令秒回体感、fat JAR 12 命令冒烟、chat 启动日志 "Found N JPA repository interfaces" N > 0 目检。

## Assumptions

- 16 节（Provider 显式映射、审计两表、`yokeos init`、frontmatter 解析）与 17 节（ReAct 循环、统一处理入口、Session 内存版、`http_get`）为直接上游；17 节前向定义的会话管理最小接口（仅 save）由本节按预告改造点补全。
- Picocli 4.7.6 已锁定根 pom，子命令/参数/帮助由它承担，不自己解析 args；snakeyaml / sqlite-jdbc / spring-boot-starter 经传递可用，直接使用处补直接依赖（记实施偏差）。
- sessions 表口径与前两节审计表一致：手工建表脚本（schema-002）、SQLite、不依赖自动迁移（宪法 7）；`agent_name` 列名为技 §9.2 字面量（拍板②），core 领域字段仍 `profileName`，实现处映射注明。
- Tool 调用记录只读查询复用 17 节既有 `tool_invocations` 仓库（`findBySessionId` 已交付），只读口为内部查询、不违反「查询接口放扩展阶段」（那条指对外 REST 审计端点与报表）。
- 模块依赖方向硬约束：CLI 模块不依赖启动聚合模块（反向即循环依赖），重命令 Spring 装配面落 CLI 模块自身；启动模块组件扫描会自然吸收该装配面（两个入口不会两套配置）。
- 「当前用户」取运行环境系统用户名（核心阶段无认证，参照 clarify 同款默认）；装配完整性测试用哑 key 占位（配置校验只查存在性与格式）。
- 本节演示口径 = 需 §11 第 18 节行「`yokeos chat` 多轮会话，可查上下文与 Tool 调用记录」（拍板⑦）；真 key 多轮对话为人工项，`--message` 单条模式供 Demo 脚本化使用。
