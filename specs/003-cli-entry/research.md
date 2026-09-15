# Research: CLI——YokeOS 的命令行入口（第18节）

Phase 0 产物。决策均标注出处（教学文档七项拍板 + 文档链条款 + 机器实证）；依赖核实已在本阶段用 `mvn dependency:tree` 现场完成（总纪律：能机器判的绝不留给人），不留实现期悬念。

## D1 范围口径：CLI 整节 12 命令（拍板①）

- **Decision**: 本节交付全部 12 个子命令：`init`（16 节已有，接入根命令）、`chat`（主角）、`serve`/`gateway`（启动骨架：起上下文常驻，REST 归 26 节）、`status` + `profile list/create/show/delete` + `provider list` + `tool list` + `session list`（轻命令，零 Spring）。
- **Rationale**: 技 §13 第 18 节行列的是能力主线（`CliChannel`、`yokeos chat`、记录查看）非全清单——同一行不可能再装下其余命令，但第一阶段 12 命令必须有节承接；specs/001 spec.md 明文「完整 CLI 命令组（`profile create/list/show/delete` 归第 18 节）」+ 参照第 18 节即 CLI 整节（课件 + specs/003 全 12 命令）。用户拍板①（2026-09-15）。
- **Alternatives considered**: ①只做 chat + 会话层（字面照技 §13 18 行）——被拒：其余 11 命令无处安放，29/30 节不接 CLI 命令、26 节只接 serve；②命令面分两节——被拒：参照一节完成，拆分徒增对账成本。
- **验收口径**: 12 命令注册与 `--help` 有测试断言（`YokeOsCliTest`）；验收报告记实施口径说明（技 §13 行 = 能力主线）。

## D2 sessions 表关联列名 `agent_name`（拍板②）

- **Decision**: `sessions` 表关联列按技 §9.2 字面量 **`agent_name`**（NOT NULL，加 `idx_sessions_agent` 索引）；core 领域字段仍是 `profileName`，storage 实体映射处注明对应关系。session_id 第三段同为 Agent 名（目录名 = Profile name = Agent name，三者同串）。
- **Rationale**: 表列名是技 §9.2 逐字定死的产品级字面量（宪法 7 手工建表脚本的权威源）；参照用 `profile_name` 是其「profiles/ YAML 文件」形态使然，本仓「一个目录 = 一个 Agent」（宪法 8）语境下技 §9.2 刻意选了 `agent_name`。照抄技术方案，不照抄参照。
- **Alternatives considered**: 列名改 `profile_name` 随参照——被拒：改表列字面量属软门禁②（修改已定字面量），且与技 §9.2 漂移。
- **验收口径**: `SessionRepositoryTest` 对列名显式断言（坑五回归：映射写错列立刻红）。

## D3 session_id 格式 `channel:user:agent` + 单点拼接（拍板③）

- **Decision**: `sessionId(channel, userId, agentName)` = `channel + ":" + userId + ":" + agentName`（如 `cli:wang:weather`），**全库唯一拼接点**在 `JpaSessionManager` 私有方法；所有入口只传三元组。格式不对外承诺（26 节 Web/25 节定时只依赖「给三元组得会话」语义）。
- **Rationale**: 技 §9.2 只定「channel 加 user 加 agent 联合生成」未定格式；参照 clarify 同款自答取可读可测试的最简形态。唯一承诺是拼接单点（H4④：两处各拼一遍、格式差一个分隔符，同一个人出现两条互不相认的历史）。
- **Alternatives considered**: ①哈希 id（如 sha256）——被拒：不可读不可测，session list 展示无意义；②`-` 分隔——被拒：用户名/Agent 名可含 `-` 有歧义风险，`:` 在三者取值域（目录名、环境用户名）中不出现。
- **验收口径**: `SessionManagerTest` 断言 id 格式；验收报告贴全库 grep 证据（拼接逻辑唯一命中实现一处）。

## D4 装配落位：YokeosRuntime 归 yokeos-cli + 双入口一套配置（拍板⑥）

- **Decision**: `YokeosRuntime`（重命令 Spring 装配面）落 `com.yokeos.cli`——`@SpringBootApplication(scanBasePackages = "com.yokeos")` + `@EnableJpaRepositories(basePackages = "com.yokeos.storage")` + `@EntityScan(basePackages = "com.yokeos.storage")` + `@Bean` 显式全链（providerMap → 双 auditor → SpringAiProviderService → ProfileRegistry → ContextLoader → PromptBuilder → ToolExecutor → ReActLoop → JpaSessionManager → AgentService → CliChannel，配方 = 17 节 `ReActSmokeIntegrationTest` 手工装配逐行转录）。fat JAR mainClass 切 `com.yokeos.cli.YokeOsCli`（boot pom spring-boot-maven-plugin，注释预告的切换点）。`YokeosBootApplication` 保留：扫 `com.yokeos` 时吸收 `YokeosRuntime` 的 `@Bean` 与 JPA 注解（同配置两入口）；其 `YokeosBootApplicationLoadTest` 补哑 key 测试属性（全链 Bean 进上下文后 provider 校验缺 key 即起不来——断言语义「上下文能起」不变）。运行配置唯一归 boot 的 `application.yaml`（追加 schema-002）。
- **Rationale**: 模块依赖方向硬约束（pom 已核）：boot 聚合依赖全部九模块含 cli，cli → boot 即循环依赖；参照 `OryxOsRuntime` 同款落位。参照 18 节踩过「cli 模块伴随一份 application.yml → fat JAR 双配置冲突」、末期纠正为统一归 boot（acceptance-report 在案）——直接吸收，不在 cli 放配置；开发期模块测试（YokeosRuntimeAssemblyTest）用 `@DynamicPropertySource`/测试属性自带 hermetic 配置，不依赖 boot yaml。
- **Alternatives considered**: ①ChatCommand 起 `YokeosBootApplication`——被拒：编译期 cli 不依赖 boot；②配置放 cli 一份方便模块独立跑——被拒：参照已实证的双配置冲突坑；③`YokeosRuntime` 放 boot、cli 只留命令——被拒：同①的反向依赖。
- **验收口径**: `YokeosRuntimeAssemblyTest`（哑 key + @TempDir SQLite）断言 JPA 仓库 Bean 数 > 0（坑二：Found 0 即红）+ 全链 Bean 就位 + getOrCreate→save→getOrCreate 往返；`YokeosBootApplicationLoadTest` 保持绿。

## D5 交互面三件 + ToolInvocationReader 只读口（拍板④）

- **Decision**: `CliChannel` 交互命令 `/context`（打印当前会话消息，每条截断至 200 字符防刷屏，带序号与角色）与 `/tools`（按 sessionId 经 core 新只读口 `ToolInvocationReader` 查 `tool_invocations`，逐条打印 工具名/成败/耗时毫秒/参数摘要，无记录输出「暂无 Tool 调用记录」）；`ChatCommand --message <text>` 单条模式（getOrCreate → process 一条 → 打印 → 退出，不进循环）。`com.yokeos.core.audit` 新增 `ToolInvocationRecord`（record：toolName/inputJson/success/errorMessage/durationMs/createdAt）+ `ToolInvocationReader`（`List<ToolInvocationRecord> findBySession(String sessionId)`），storage `JpaToolInvocationReader` 包既有 `ToolInvocationRepository.findBySessionId`（17 节已交付）。
- **Rationale**: 需 §5.9/§11 第 18 节行明文「可查上下文与 Tool 调用记录」、需 §5.12 明文 `--message`——参照无此三项（YokeOS 特有，差异化立在需求）。`/tools` 取审计表而非过滤会话历史：审计记录含入参/耗时/成败（会话历史只有工具回填文本），且语义即「调用记录」；只读口走依赖倒置（契约在 core、实现在 storage），26 节 Web 会话详情可复用。「查询接口放扩展阶段」（技 §9.2）指对外 REST 审计端点与报表，内部只读口不在此列。
- **Alternatives considered**: ①`/tools` 从会话历史过滤 role=tool 消息——被拒：丢失入参与耗时，语义是「工具看到什么」不是「调用过什么」；②channel-cli 直接依赖 storage 仓库——被拒：跨模块契约必须放 core（模块规则）；③`/context` 显示条数做成配置——被拒：交付物清单「无新配置键」，固定最近 50 条 + 截断保护够用。
- **验收口径**: `CliChannelTest` 断言 /context 输出含消息、/tools 输出含 mock 的调用记录（不走引擎）；`JpaToolInvocationReaderTest` 断言字段映射与按会话隔离。

## D6 profile delete 归档式（拍板⑤）

- **Decision**: `profile delete <name>` 把 `.yokeos/agents/<name>/` 整目录移入 `.yokeos/archive/<name>/`（归档目录不存在则创建；同名归档已存在则带时间戳后缀防覆盖），不物理删。`profile create` 写最小 AGENT.md 模板：frontmatter（name/description 占位/provider 取全局层第一个/`tools: [http_get]`）+ 正文一行指引，已存在报错不覆盖（init 同款幂等纪律）。
- **Rationale**: 技 §11.3 DELETE 端点语义「整个 Agent 目录归档 `.yokeos/archive/`（不物理删）」——CLI delete 与其同语义（同一动作两条入口），30 节 `AgentLifecycleService` 就位后改调同一方法。参照物理删 YAML 是其单文件 Profile 形态使然，不适用。
- **Alternatives considered**: ①物理删目录——被拒：与技 §11.3 语义冲突（丢定义不可恢复）；②本节不做 delete 推迟 30 节——被拒：12 命令面不完整，且归档式实现成本与物理删同级。
- **验收口径**: `ProfileCommandTest` 断言原目录消失、归档目录存在且内容完整。

## D7 依赖核实（H3，2026-09-15 plan 期完成）

- **Decision**: `yokeos-cli/pom.xml` 补显式 `org.springframework.boot:spring-boot-starter`（重命令 `SpringApplicationBuilder` 所需）；`yokeos-cli` 补 `org.yaml:snakeyaml`（provider list 直读 yaml）与 `org.xerial:sqlite-jdbc`（session list 纯 JDBC）显式依赖；`yokeos-storage` 补 `jackson-databind` 显式依赖（`JpaSessionManager` 序列化；版本不写——根 pom jackson-bom 接管，002 D7 同款）。全部记实施偏差。
- **Rationale**: `mvn -pl yokeos-cli dependency:tree` 实证（输出在案）：`spring-boot-starter` 当前仅 **test 域**经 `spring-boot-starter-test` 传入，main 代码引用 `SpringApplicationBuilder` 编不过或依赖侥幸传递（Maven 直接使用传递依赖属坏味道且不稳）；snakeyaml 2.4 经 core、sqlite-jdbc 3.53.2.1 与 starter-data-jpa 经 storage 传递可用，直接使用处按依赖纪律补显式。jackson-databind 2.21.5 已在 core 显式（17 节 D7）、storage 经传递可用，同款补显式。
- **Alternatives considered**: ①靠传递依赖不补——被拒：pom 单模块升级即断；②cli 引 starter-web——被拒：chat 无需 Web 栈（`.web(NONE)`），serve 的 Web 归 26 节（届时 boot/web 侧处理）。
- **附带核实**: provider 为裸 `spring-ai-openai`（pom 注释 + tree 双核）——classpath 无 spring-ai autoconfigure，cli 引 Boot 自家 starter 不触发宪法 2 eager 坑；`YokeosRuntime` 不注册任何 Spring AI 自动装配排除（无物可排）。

## D8 CliChannel 可测形态与命令测试边界

- **Decision**: `CliChannel` 构造注入 `AgentService`/`SessionManager`/`ToolInvocationReader`；`run(String profileName, String userId)` 委托 `run(profileName, userId, BufferedReader, PrintStream)` 重载（默认 System.in/out）——测试用 `StringReader` 驱动输入、`PrintStream(ByteArrayOutputStream)` 收输出，脚本化断言。`--message` 模式独立方法 `runOnce(profileName, userId, message)`（同 IO 注入形态）。命令测试边界：轻命令逐个直测（@TempDir 工作区）；`ChatCommand`/`ServeCommand`/`GatewayCommand` 只测注册与 `--help`（重命令真跑属人工项，进程级起上下文不进单测）；`YokeosRuntimeAssemblyTest` 用 `SpringApplicationBuilder(YokeosRuntime.class).properties(哑 key + @TempDir sqlite url).run()`——本地 hermetic 全上下文，**正常跑不标 integration**（无网络无真 key，与 `YokeosBootApplicationLoadTest` 同款定位）。
- **Rationale**: 参照 `CliChannel` 直用 System.in/out 导致不可单测（钉版树无 CliChannelTest——CLI 零测试瑕疵的一部分）；宪法 9 瑕疵不继承 + 技 §10 测试纪律「入口模块不例外」。装配测试若标 integration 会脱离日常 `mvn test` 门禁，坑二回归失去常态守护。
- **Alternatives considered**: ①System.setIn/setOut 替换——被拒：全局态污染并行测试；②装配测试标 integration——被拒：坑二回归必须日常跑；③ChatCommand 测试里起真上下文——被拒：单测秒级纪律 + 无 key 环境必挂。
- **验收口径**: 教学文档第四部分 11 测试类对号表全绿。
