# Tasks: CLI——YokeOS 的命令行入口（第18节）

## Format: `[ID] [P?] [Story] Description`

> TDD 纪律（用户指令）：测试任务先于或伴随实现任务（验收 harness 先行）；实现与测试同任务闭环，红了当场修；集成冒烟单列。测试方法名英文，教学文档语义以 `@DisplayName` 保留。拆解依据：plan.md 模块落位、contracts/cli.md 逐字签名、data-model.md 表与命令面、教学文档第三部分交付物清单与第四部分 harness 表（11 测试类）。

## Path Conventions

- 源码：`yokeos-<module>/src/main/java/com/yokeos/…`；测试：`yokeos-<module>/src/test/java/…`
- core 包：`core/session`（SessionManager/Session/InMemorySessionManager）、`core/audit`（新增只读口）；storage 模块根包；命令落 `com.yokeos.cli` + `com.yokeos.cli.command`（InitCommand 16 节既有位置不动）
- 签名一律照 contracts/cli.md 逐字，不得现场发明；依赖补齐照 research D7 实证清单
- 硬约束：yokeos-cli 不依赖 yokeos-boot（模块依赖方向，research D4）

## Phase 1: Setup (Shared Infrastructure)

- [x] T001 记录改造前基线：仓库根 `mvn clean test -q` 确认 16/17 节全部测试绿（后续「前序零改动保持绿」的对照基准）
- [x] T002 [P] 依赖补齐四处（research D7，记实施偏差）：`yokeos-cli/pom.xml` + `org.springframework.boot:spring-boot-starter` + `org.yaml:snakeyaml` + `org.xerial:sqlite-jdbc`、`yokeos-storage/pom.xml` + `com.fasterxml.jackson.core:jackson-databind`（均不写版本号，根 pom BOM 接管）；`mvn -pl yokeos-cli,yokeos-storage -am dependency:resolve` 核实可解析——失败即软门禁停下报告

## Phase 2: Foundational (core 契约补全——17 节预告改造点 + 新表，阻塞全部 US)

- [x] T003 [P] `yokeos-core/src/main/java/com/yokeos/core/session/`：`SessionManager` 接口补全 `getOrCreate(String channel, String userId, String profileName)` 与 `Optional<Session> get(String sessionId)`（save 原有）；`Session` 加恢复构造器 `Session(String sessionId, String profileName, List<Message> restored)`（append 三兄弟不动）；`InMemorySessionManager` 随动实现两新方法——跑 `mvn -pl yokeos-core -am test` 确认 17 节既有测试零改动全绿（预告改造点门禁）
- [x] T004 [P] `yokeos-core/src/main/java/com/yokeos/core/audit/` 新增 `ToolInvocationRecord`（record：toolName/inputJson/success/errorMessage/durationMs/createdAt）+ `ToolInvocationReader`（`List<ToolInvocationRecord> findBySession(String sessionId)`）——签名逐字照 contracts/cli.md §4，与既有 `ToolInvocationAuditor` 写口同包对称
- [x] T005 [P] `yokeos-storage/src/main/resources/db/schema-002-sessions.sql`（9 列逐字 data-model.md：session_id 主键、agent_name/channel/user_id NOT NULL、messages_json TEXT、status NOT NULL、created_at/last_active_at/archived_at、idx_sessions_agent 索引；CREATE TABLE IF NOT EXISTS 幂等）+ `yokeos-boot/src/main/resources/application.yaml` `schema-locations` 追加 `classpath:db/schema-002-sessions.sql`

**Checkpoint**: 契约与表就位、17 节零改动全绿——US 实现可开始（US2/US3 地基先行，US1 依赖其契约）

## Phase 3: User Story 2+3 - 会话口径与落库恢复 (Priority: P1，US1 的地基)

**Goal**: 三元组幂等/隔离、sessions 表持久化、跨重启恢复、/tools 只读口。
**Independent Test**: `mvn -pl yokeos-storage -am test` 全绿（`SessionManagerTest` / `SessionRepositoryTest` / `JpaToolInvocationReaderTest`）。

### Tests for User Story 2+3

- [x] T006 [P] [US2] 写 `yokeos-storage/src/test/java/com/yokeos/storage/SessionManagerTest.java`（@DataJpaTest + @AutoConfigureTestDatabase(NONE) + @TempDir SQLite 文件库 + 测试内执行 schema-002，16 节 `LlmCallRepositoryTest` 模式）：`sameTriple_everyGetOrCreateReturnsSameSession`（**坑一回归**：幂等 + channel 隔离断言）、`anyDifferentTripleElement_createsDifferentSession`（user/agent 各异全排列）、`sessionIdFollowsTripleFormat`（`cli:wang:weather`）、`getOrCreateMissPersistsActiveRecord`（status=active + channel/user_id/**agent_name 列断言——坑五回归**）、`getOrCreateHit_restoresHistory`、`saveRefreshesLastActiveAt`、`concurrentGetOrCreate_sameTriple_singleRow`（**并发回归**〔analyze M1〕：虚拟线程 N 线程并发同三元组 getOrCreate，断言库中仅一条——主键唯一兜底，撞键按已存在处理）
- [x] T007 [P] [US3] 写 `yokeos-storage/src/test/java/com/yokeos/storage/SessionRepositoryTest.java`：`saveAndReloadRoundTrip`（手工脚本建表、全字段）、`threeRoleMessages_roundTripCompleteAndOrdered`（user/assistant/tool 三类消息序列化回读零丢失、序不变——**坑四回归**）、`simulateRestart_historySurvives`（同库文件新建 manager 重查；扩展〔analyze M2〕：恢复 → `appendUser` → save → 再重查，条数 +1 且原序不变——恢复是追加不是覆盖）、`zeroMessageSession_savesAndRestores`、`columnsMatchTechSpec`（九列逐一对齐技 §9.2，agent_name 在列）
- [x] T008 [P] [US3] 写 `yokeos-storage/src/test/java/com/yokeos/storage/JpaToolInvocationReaderTest.java`：`mapsEntityToRecordFields`（六字段逐一对齐）、`findBySession_onlyOwnSession`（会话隔离）、`successAndFailureBothListed`

### Implementation for User Story 2+3

- [x] T009 [US2] [US3] 实现 storage 三件：`Session` JPA 实体（@Table("sessions")，与 core 同名不同包、JpaSessionManager 内全限定引用；agent_name ↔ profileName 映射处注明）、`SessionRepository`（JpaRepository<Session, String>）、`JpaSessionManager`（id 走 core `SessionIds.compose` 单点〔全库唯一拼接点，实施收敛〕格式 `channel:user:agent`；getOrCreate 命中→Jackson 反序列化恢复领域 Session、未命中→落 active 新记录；save 覆盖 messages_json + 刷 last_active_at；archived 不写入）→ T006/T007 绿
- [x] T010 [US3] 实现 `yokeos-storage/src/main/java/com/yokeos/storage/JpaToolInvocationReader.java`（implements core `ToolInvocationReader`，包既有 `ToolInvocationRepository.findBySessionId`，投影 `ToolInvocationRecord`）→ T008 绿
- [x] T011 门禁：`mvn -pl yokeos-storage -am test` 全绿（含 16/17 节该模块回归），红了当场修

**Checkpoint**: 会话层钉死——US1 交互壳可开工

## Phase 4: User Story 1 - 终端里跟 Agent 说上话 (Priority: P1) 🎯 MVP

**Goal**: CliChannel 交互壳全行为（/quit、EOF、空行、/context、/tools、--message）。
**Independent Test**: `mvn -pl yokeos-channel-cli -am test` 全绿（`CliChannelTest`，mock 引擎脚本化驱动）。

### Tests for User Story 1

- [x] T012 [US1] 写 `yokeos-channel-cli/src/test/java/com/yokeos/channel/cli/CliChannelTest.java`（mock `AgentService`/`SessionManager`/`ToolInvocationReader` + `StringReader` 输入 / `ByteArrayOutputStream` 收输出）：`multiLine_eachForwardedAndReplyPrinted`、`quitWithWhitespace_exitsCleanly`、`eof_exitsWithoutStack`（**坑三回归**：null 行不抛）、`blankLine_skippedNotForwarded`、`messageMode_singleShotThenExit`（--message 单条即退、getOrCreate 只调一次）、`contextCommand_printsMessages_notForwarded`（含最近消息、不走引擎）、`toolsCommand_printsInvocations_notForwarded`（含 mock 的 success=true 行；空记录→「暂无 Tool 调用记录」）、`profileMissing_namedErrorBeforeLoop`（点名异常透出不进循环）

### Implementation for User Story 1

- [x] T013 [US1] 实现 `yokeos-channel-cli/src/main/java/com/yokeos/channel/cli/CliChannel.java`（构造注入三协作者；`run(profileName, userId)` 委托 `run(profileName, userId, BufferedReader, PrintStream)` IO 注入重载；`getOrCreate("cli", userId, profileName)` 三元组不拼 id；`/quit` trim 判断、EOF 等同、空行跳过；`/context` 每条截断 200 字符带序号角色；`/tools` 逐条 工具名/成败/耗时 ms/入参摘要；`runOnce(...)` 单条模式同 IO 注入；零 Agent 智能——宪法 2/FR2）→ T012 绿 + `mvn -pl yokeos-channel-cli -am test`

**Checkpoint**: US1~US3 独立可验（mock 引擎）——真跑待 US5 装配面

## Phase 5: User Story 4+5 - 命令面、轻重分流与装配完整 (Priority: P2)

**Goal**: 12 命令全量（轻零 Spring / 重经 YokeosRuntime）+ 装配完整性机器断言 + fat JAR 入口切换。
**Independent Test**: `mvn -pl yokeos-cli,yokeos-boot -am test` 全绿（命令六测试类 + `YokeosRuntimeAssemblyTest` + boot LoadTest）。

### Tests for User Story 4+5

- [x] T014 [US4] 写命令测试六件于 `yokeos-cli/src/test/java/com/yokeos/cli/`（先写红）：`YokeOsCliTest`（`twelveSubcommandsRegistered` 逐名断言、`helpWorks_perCommand`、`unknownCommand_nonZeroExitNoStack`）、`StatusCommandTest`、`ProfileCommandTest`（create 幂等不覆盖 / delete 归档到 `.yokeos/archive/` 原目录消失 / list、show 主路径 / create 无 provider 配置报错）、`ProviderListCommandTest`（列 name/base-url；yaml 缺失友好提示）、`ToolListCommandTest`（含 `http_get` 与 20 节注明）、`SessionListCommandTest`（库不存在→「暂无会话」；有库→列概览行）
- [x] T020 [US5] 写 `yokeos-cli/src/test/java/com/yokeos/cli/YokeosRuntimeAssemblyTest.java`（哑 key `DEEPSEEK_API_KEY=sk-test-dummy` + @TempDir SQLite 经 builder properties 注入——research D8）：`jpaRepositoriesWired_countGreaterThanZero`（**坑二回归**：Found 0 即红）、`runtimeBeansAllPresent`（SessionManager〔JpaSessionManager〕/双 auditor/AgentService/CliChannel）、`sessionRoundTrip_throughRealBeans`（getOrCreate→save→getOrCreate 历史在）

### Implementation for User Story 4+5

- [x] T015 [US4] 实现 `yokeos-cli/src/main/java/com/yokeos/cli/YokeOsCli.java` 根命令（@Command name="yokeos" + mixinStandardHelpOptions + 12 子命令注册；`main` = `System.exit(new CommandLine(new YokeOsCli()).execute(args))`；InitCommand 既有类接入）→ YokeOsCliTest 注册/help 断言绿
- [x] T016 [P] [US4] 实现 `command/StatusCommand.java`（零 Spring：.yokeos 工作区/application.yaml/.yokeos/yokeos.db 存在性摘要输出）→ 测试绿
- [x] T017 [P] [US4] 实现 `command/ProfileCommand.java`（嵌套 list/create/show/delete 四子命令，零 Spring 纯 Files）：list 列 `.yokeos/agents/` 目录、show 打印 AGENT.md 原文、create 写最小模板（frontmatter：name/description 占位/provider 取全局层 `yokeos.providers` 第一个/`tools: [http_get]`；已存在报错不覆盖）、delete 归档式 `Files.move` 至 `.yokeos/archive/<name>/`（目录按需建、同名带时间戳后缀防覆盖——D6）→ 测试绿
- [x] T018 [P] [US4] 实现 `command/ProviderListCommand.java`（SnakeYAML 直读 classpath application.yaml 的 `yokeos.providers` 列 name/base-url，不解析 key；缺文件/空清单友好提示）+ `command/ToolListCommand.java`（列当前真实就绪 `http_get` + 「20 节接 ToolRegistry 后改查注册表」注明）→ 测试绿
- [x] T019 [P] [US4] 实现 `command/SessionListCommand.java`（纯只读 JDBC DriverManager 查 `.yokeos/yokeos.db` 的 sessions：session_id/agent_name/status/last_active_at 倒序前 20；库文件不存在→「暂无会话」退出码 0）→ 测试绿
- [x] T021 [US5] 实现 `yokeos-cli/src/main/java/com/yokeos/cli/YokeosRuntime.java` 装配面（@SpringBootApplication(scanBasePackages="com.yokeos") + @EnableJpaRepositories/@EntityScan 显式指 com.yokeos.storage——坑二正面解法 + @Bean 全链：providerMap（宪法 3）→ 双 auditor → SpringAiProviderService → ProfileRegistry〔AgentLoader 启动扫描〕→ ContextLoader → PromptBuilder → ToolExecutor〔17 节工具 Map〕→ ReActLoop → JpaSessionManager → AgentService → CliChannel，配方 = 17 节 ReActSmokeIntegrationTest 手工装配转录）+ `command/ChatCommand.java`（@Option --profile 默认 default、--message；`SpringApplicationBuilder(YokeosRuntime.class).web(NONE).bannerMode(OFF)` 起上下文→取 CliChannel→`run(profileName, System.getProperty("user.name"))`；--message 空白值报参数错误退出非 0；有值走 runOnce）→ T020 绿
- [x] T022 [US4] 实现 `command/ServeCommand.java` + `command/GatewayCommand.java` 启动骨架（`SpringApplication.run(YokeosRuntime.class)` 常驻 + keepAlive〔`Thread.currentThread().join()`〕+ 打印「REST 端点 26 节接线」说明；serve `--port` 默认 8080 透传 `server.port`）——注册与 --help 断言由 T014 承载
- [x] T023 [US4] boot 两处：`yokeos-boot/pom.xml` spring-boot-maven-plugin `mainClass` → `com.yokeos.cli.YokeOsCli`（注释预告的切换点）+ `YokeosBootApplicationLoadTest` 补哑 key 测试属性（全链 Bean 进上下文后无 key 起不来；断言语义「上下文能起」不变）→ `mvn -pl yokeos-boot -am test` 绿
- [x] T024 门禁：`mvn -pl yokeos-cli,yokeos-channel-cli,yokeos-boot -am test` 全绿 + 16/17 节全模块回归绿，红了当场修

**Checkpoint**: 12 命令 + 装配完整——Polish 收口

## Phase 6: Polish & Cross-Cutting Concerns

- [x] T025 全仓硬门禁：`mvn clean verify` 九模块全绿（Spotless/P3C/Checkstyle/SpotBugs/FindSecurityBugs）——静态检查红修实现不改规则
- [x] T026 fat JAR 冒烟（可自动化部分）：`mvn -pl yokeos-boot -am package -DskipTests` 后 `java -jar` 逐项——根/12 子命令 `--help`、`init` 幂等（二次不覆盖）、`profile list` 与 `session list` 计时秒回留证、`chat` 无 key 启动→provider 点名报错（16 节 validate 路径）、启动日志 "Found N JPA repository interfaces"（N>0）截取留证
- [x] T027 H4 七条全局不变量逐条自查（grep/测试证据）+ 教学文档「本节交付物」四组逐项 ls/grep 存在性核对 + `session_id` 单点拼接全库 grep 证据（唯一命中 `SessionIds.compose`）+ `grep -r "sk-"` 凭证卫生 + 轻命令零 Spring import review
- [x] T028 验收报告 `specs/003-cli-entry/acceptance-report.md`（六项证据 DoD，结构照 specs/002）+ 剩余人工项清单（真 key 多轮对话 + /context /tools + 重进历史还在、轻命令秒回体感、三模式共享存储体感）+ 实施偏差记录（D7 四处依赖补显式、范围口径 D1）+ 实施中新坑一条一行回填 CLAUDE.md 常见陷阱表（如有）

---

## Dependencies & Execution Order

### Phase Dependencies

- Phase 1（T001~T002）→ Phase 2（T003~T005，阻塞全部 US）→ Phase 3（US2/3 地基）→ Phase 4（US1，依赖 T003 契约与 T010 只读口）→ Phase 5（US4/5，依赖 T009/T013）→ Phase 6 收口
- **US 内部**: T006/T007/T008（harness，可并行）→ T009/T010（实现，转绿）→ T011 收口；T012 → T013；T014 → T015~T019（轻命令互不同文件可并行）→ T020 → T021 → T022/T023 → T024

### Parallel Opportunities

```text
Phase 2：T003 ∥ T004 ∥ T005（不同模块/文件）
Phase 3：T006 ∥ T007 ∥ T008（harness 三件不同文件）；T009 完成后 T010 独立
Phase 5：T016 ∥ T017 ∥ T018 ∥ T019（轻命令四组互不同文件，T015 根命令就位后）
```

## Implementation Strategy

### MVP First (Phase 1~4)

T001~T013 即得「会话口径钉死 + 交互壳全行为」的最小可验增量（mock 引擎）；真跑链路由 Phase 5 的装配面补齐。

### Incremental Delivery

契约与表（Phase 2）→ 会话层（Phase 3，稳定点：storage 全绿含前序回归）→ 交互壳（Phase 4）→ 命令面与装配（Phase 5）→ 门禁与验收（Phase 6）。每相结束都是可独立验证的增量。

## Notes

- H3 已在 plan 期完成（research D7 dependency:tree 实证）——依赖照清单落笔；若实现中发现与实证不符（本地依赖变动），停下报告
- 五坑 ↔ 测试对号：坑一↔T006 `sameTriple_everyGetOrCreateReturnsSameSession` + T027 grep、坑二↔T020 `jpaRepositoriesWired_countGreaterThanZero`、坑三↔T012 `eof_exitsWithoutStack`/`blankLine_skippedNotForwarded`、坑四↔T007 `simulateRestart_historySurvives`、坑五↔T006 `getOrCreateMissPersistsActiveRecord` + T007 `columnsMatchTechSpec`
- 手工验项（真 key 多轮对话、fat JAR 全家冒烟、秒回体感）入口在 T026/T027，完整清单归验收报告「剩余人工项」
- 实施偏差预告：依赖补显式四处（T002/D7）、范围口径 CLI 整节（D1）——验收报告「实施偏差」节记两条
- 全程不自动 commit/push；每任务完成勾 tasks.md `[x]`
