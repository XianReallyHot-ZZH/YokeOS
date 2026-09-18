---
description: "Task list for feature implementation"
---

# Tasks: Memory 两层记忆——让 Agent 跨对话记得住用户（第22节）

**Input**: Design documents from `/specs/007-memory-implementation/`（plan.md / research.md D1~D8 / data-model.md / contracts/memory.md / quickstart.md）

**Tests**: TDD 纪律显式启用——测试任务先于或伴随对应实现任务（验收 harness 先行），实现与测试在同一任务内闭环、该模块测试红了当场修；集成冒烟不适用（本节真模型跨对话为人工项，无 `@Tag("integration")` 新增）。测试方法名英文，教学文档语义以 `@DisplayName` 保留。

**Organization**: 门面契约 + 默认档 + 注入接线（US1，MVP）→ MemoryTools 两 Tool（US2）→ 三档齐备与选档装配（US3）→ 收尾（截断契约 US4 并入各档实现与契约测试承载，不单列相）。分批明文：① 契约测试 mem0 参数用 `InMemoryMemoryStore` 替身（教学文档拍板④），`Mem0MemoryStore` 真实 REST 交互由 mock 单测锚、真实例人工可选；② `SqliteMemoryStore` 不设专属测试类（参照钉版树实物为准——其契约测试注释提到的 SqliteMemoryStoreTest 实物不存在；真库 LIMIT/LIKE 锚 `MemoryEntryRepositoryTest`，分区路由逻辑由契约测试 fakeRepo 参数锚）；③ 坑三（USER.md 不可触碰）锚在 `MarkdownMemoryStoreTest`（写入后 `@TempDir` 内 USER.md 不存在也不被创建）+ 收尾 grep；④ `memoryService()` @Bean 在 US1 先以 markdown 直构（注释注明演进位），US3 改造为按 `yokeos.memory.backend` 三选一；⑤ `PromptBuilder` 既有用例的构造器更新与新增断言在同一任务闭环。

## Phase 1: Setup

- [x] T001 记录改造前基线：`mvn test` BUILD SUCCESS 全绿——记录测试数（21 节合流后基线），确认零 pom 变更状态
- [x] T002 依赖落地（H3，research D1/D2）：`yokeos-memory/pom.xml` 增 `com.yokeos:yokeos-tool` 依赖（模块间依赖，MemoryTools 注册用）；验证 `mvn -pl yokeos-memory -am dependency:resolve` 绿（零新增外部坐标——RestClient 为 spring-web 既有传递）；`javap -p -classpath <spring-web jar> org.springframework.web.client.RestClient` 核实 `post()/uri()/body()/retrieve()` 签名（写前核实，D2）

## Phase 2: US1 门面、默认档与注入接线（P1）🎯 MVP

**Goal**: MemoryService/MemoryScope 契约落 core + LongTermMemoryStore 接口 + InMemoryMemoryStore 测试基建 + MarkdownMemoryStore 默认档 + MemoryServiceImpl 薄门面 + PromptBuilder [2] 位接线（17 节预留兑现）。
**Independent Test**: `mvn test -pl yokeos-core -am` + `mvn test -pl yokeos-memory -am` + `mvn test -pl yokeos-cli -am` 全绿（坑二/三/七回归点在位）。

- [x] T003 [P] [US1] 门面契约三件（纯接口）：`yokeos-core/src/main/java/com/yokeos/core/memory/MemoryService.java`（`buildContext(Session)`/`remember(content, scope)`/`recall(keyword)` 三方法，javadoc 钉死「buildContext 只出长期记忆不含会话消息——坑七」与「接口落 core 的依赖方向论证，16 节同款」）+ `MemoryScope.java`（CORE/ARCHIVAL 枚举）+ `yokeos-memory/src/main/java/com/yokeos/memory/LongTermMemoryStore.java`（`append(content, scope)`/`load()`/`recallByKeyword(keyword)` 三方法，javadoc 载四条行为契约，contracts/memory.md 为准）
- [x] T004 [P] [US1] InMemoryMemoryStore 测试基建：`yokeos-memory/src/main/java/com/yokeos/memory/InMemoryMemoryStore.java`——进程内 List 双分区、归档保留最近 100 条、满足四条契约；javadoc 钉死「测试基建（契约测试 mem0 替身 + 轻量依赖），不进 memory.backend 选项」（教学文档拍板⑥）
- [x] T005 [P] [US1] MarkdownMemoryStoreTest：`yokeos-memory/src/test/java/com/yokeos/memory/MarkdownMemoryStoreTest.java`——`@TempDir` 真文件：①CORE/ARCHIVAL 写入各归各分区（`@DisplayName("scope写入路由到正确分区")`，坑四）；②load 返回核心全量+归档、文件不存在视作空不报错（`@DisplayName("文件不存在视作空记忆")`）；③归档段超 `archive-max-chars` 从尾部保留最近字符、核心区段一字不少（`@DisplayName("截断只裁归档区_核心区一字不能少")`，坑一/坑五）、恰好等于阈值不裁；④行检索只命中归档区（坑五）；⑤每条带 `[yyyy-MM-dd]` 日期 header；⑥写入后 `@TempDir` 内 USER.md 不存在也不被创建（`@DisplayName("记忆写入路径碰不到用户初始设定文件")`，坑三）
- [x] T006 [P] [US1] MarkdownMemoryStore 默认档：`yokeos-memory/src/main/java/com/yokeos/memory/MarkdownMemoryStore.java`——两分区 header 字面量 `## 核心记忆`/`## 归档记忆`（data-model §2）；`append` 追加 `- [日期] 内容` 并写回（首写建分区结构）；`load` 每次 `Files.readString` 现读（契约一）、核心段完整返回、**裁剪函数只接收归档段文本**（契约二的物理隔离）、归档段尾部字符裁剪（archive-max-chars 默认 4000，构造可注入阈值）；`recallByKeyword` 只读归档段行匹配（契约四）；每方法首行 Sandbox 检查位注释（FILE_WRITE，24 节接线——20 节六件工具同款）；checked IOException 包 `UncheckedIOException`（19 节 research D1 同款）
- [x] T007 [P] [US1] MemoryServiceImplTest：`yokeos-memory/src/test/java/com/yokeos/memory/MemoryServiceImplTest.java`——mock `LongTermMemoryStore`：①`buildContext` 委托 `load()` 返回、**传入带消息的 Session 后输出不含任何会话消息内容**（`@DisplayName("门面上下文只含长期记忆_不含会话消息")`，坑七）；②`remember`/`recall` 参数原样转发（ArgumentCaptor）；③空记忆（load 返回空串）不炸
- [x] T008 [P] [US1] MemoryServiceImpl 薄门面：`yokeos-memory/src/main/java/com/yokeos/memory/MemoryServiceImpl.java`——`buildContext` 委托 `store.load()`（只出长期记忆）；`remember`/`recall` 直接转发；javadoc 钉「会话历史归 PromptBuilder 既有 [3] 段承载——技 §4.2 [2]/[3] 表述重叠的裁决」
- [x] T009 [US1] PromptBuilderTest 更新（测试先行）：`yokeos-core/src/test/java/com/yokeos/core/agent/PromptBuilderTest.java`——mock `MemoryService`：①组装产物在 system prompt 段之后、首条历史之前出现 `buildContext` 返回内容（`@DisplayName("长期记忆注入在系统提示与对话历史之间")`）；②每条会话消息在产物中恰好出现一次（`@DisplayName("会话历史只由历史段承载_不重复注入")`，坑七）；③`verify(memoryService, times(1)).buildContext(...)` 每次 build 现调（`@DisplayName("每次组装都现读长期记忆")`，坑二链路收口）；既有用例构造器调用全部更新（增参 mock 门面）
- [x] T010 [US1] PromptBuilder 接线 + 装配：`yokeos-core/src/main/java/com/yokeos/core/agent/PromptBuilder.java`——构造器增参 `MemoryService`、`build()` 在 system prompt/时间行之后、`truncateByTurn` 历史之前拼入 `memoryService.buildContext(session)`（17 节「[2] 恒空跳过」预留注释兑现，注释更新为已接线）；`yokeos-cli/src/main/java/com/yokeos/cli/YokeosRuntime.java`——新增 `memoryService()` @Bean（本阶段 `new MemoryServiceImpl(new MarkdownMemoryStore(workspace().resolve("memory"), 4000))` 直构 markdown 档，注释注明 US3 演进为 properties 选档）、`promptBuilder()` @Bean 增参注入
- [x] T011 [US1] 阶段门禁：`mvn test -pl yokeos-core -am` + `mvn test -pl yokeos-memory -am` + `mvn test -pl yokeos-cli -am` 全绿（21 节基线零回归），红了当场修

## Phase 3: US2 MemoryTools 两 Tool（P2）

**Goal**: save_memory/recall_memory 落地并注册进 ToolRegistry（与其他内置 Tool 一视同仁）。
**Independent Test**: `mvn test -pl yokeos-memory -am -Dtest='builtin.MemoryToolsTest'` 全绿 + cli AssemblyTest 注册面 ≥9。

- [x] T012 [P] [US2] MemoryToolsTest：`yokeos-memory/src/test/java/com/yokeos/memory/builtin/MemoryToolsTest.java`——mock `MemoryService`：①不传 scope → `remember` 收到 `ARCHIVAL`（`@DisplayName("不传分区缺省写归档")`，坑四）；②传 `core` → `CORE`；③传非法值 `urgent` → 返回含该值的明确提示、`remember` 零调用（`@DisplayName("非法分区明确报错_不静默落错区")`）；④recall 未命中 → 「没有找到相关记忆」不抛异常（`@DisplayName("检索未命中返回提示语不抛异常")`）；⑤命中多条 → 换行拼接
- [x] T013 [P] [US2] MemoryTools：`yokeos-memory/src/main/java/com/yokeos/memory/builtin/MemoryTools.java`——`@Tool(name="save_memory")`（content 必填 + scope 可选：空白→ARCHIVAL、`toUpperCase(ROOT)` 解析、`IllegalArgumentException` 捕获转点名提示语——不抛出污染对话历史；成功返回「已记住」）+ `@Tool(name="recall_memory")`（未命中提示语、命中拼接）；只认 `MemoryService` 门面（javadoc：对底层哪档后端完全无感）；注册路径 javadoc 钉「经 ToolRegistry.registerAnnotated，执行发起方永远是 ToolExecutor（宪法 2）」
- [x] T014 [US2] YokeosRuntime 注册面：`YokeosRuntime.java` `tools()` 内 `registry.registerAnnotated(new MemoryTools(memoryService()))`（九件全量：7 既有 + save_memory/recall_memory）；`YokeosRuntimeAssemblyTest` 断言注册面 ≥9
- [x] T015 [US2] 阶段门禁：`mvn test -pl yokeos-memory -am` + `mvn test -pl yokeos-cli -am` 全绿

## Phase 4: US3 三档齐备与选档装配（P3）

**Goal**: memory_entries 真库 + 契约测试三档统一 + sqlite/mem0 两档 + `yokeos.memory.backend` 选档装配——「换后端只改一行」兑现。
**Independent Test**: `mvn test -pl yokeos-storage -am` + `mvn test -pl yokeos-memory -am` 全绿（契约测试三档参数全过）。

- [x] T016 [P] [US3] MemoryEntryRepositoryTest（测试先行）：`yokeos-storage/src/test/java/com/yokeos/storage/MemoryEntryRepositoryTest.java`——真 SQLite 文件库（18 节 `yokeos.db.dir` 指向 target/ 口径 + busyTimeout）：①手工建表脚本能存能读（`@DisplayName("手工建表脚本建出的memory_entries能存能读")`，坑六）；②`findRecentArchival(Pageable)` 按createdAt DESC 取最近 N、更早的不在（`@DisplayName("归档区LIMIT取最近N")`）；③`searchArchival` 只命中归档行（`@DisplayName("LIKE检索只命中归档区")`）；④关键词含 `%`/`_` 按字面命中（`@DisplayName("LIKE通配符按字面转义")`）
- [x] T017 [P] [US3] storage 三件：`yokeos-storage/src/main/resources/db/schema-003-memory.sql`（DDL 逐字 data-model.md §1：memory_entries + idx_memory_scope，头注释注明出处技 §9.2 + 宪法 7，CREATE IF NOT EXISTS 幂等）+ `yokeos-storage/src/main/java/com/yokeos/storage/MemoryEntry.java`（JPA 实体，照 Session 实体风格，research D8）+ `MemoryEntryRepository.java`（`findByScope` / `findRecentArchival(Pageable)` / `searchArchival`——LIKE 拼接前转义 `\`/`%`/`_` + `ESCAPE '\'`，research D5）
- [x] T018 [US3] MemoryStoreContractTest（契约测试，依赖 T004/T006/T017 的接口与基建）：`yokeos-memory/src/test/java/com/yokeos/memory/MemoryStoreContractTest.java`——`@TestInstance(PER_CLASS)` + 非静态 `@MethodSource("allStores")` 三档工厂（markdown=TempDir 直构 / sqlite=`new SqliteMemoryStore(fakeRepo())` 背靠内存 List 有状态假 Repository / mem0=`InMemoryMemoryStore::new` 替身，research D7）：①写后立读 + 检索立即命中（`@DisplayName("写入后立刻可读_不允许有缓存")`，契约一/坑二）；②灌 500 条归档后核心一字不少、最早被裁、最近保留（`@DisplayName("截断只裁归档区_核心记忆一字不能少")`，契约二/坑一）；③CORE 不参与检索、ARCHIVAL 可检索（`@DisplayName("检索只作用归档区")`，契约四/坑五）；④scope 路由（契约三/坑四）
- [x] T019 [US3] SqliteMemoryStore 结构化档：`yokeos-memory/src/main/java/com/yokeos/memory/SqliteMemoryStore.java`——`append` 直插（契约一天然满足）；`load` 核心区 `findByScope("CORE")` 全量 + 归档区 `findRecentArchival(PageRequest.of(0, maxRows, DESC createdAt))`（**LIMIT 只加在归档查询**——契约二的 SQL 结构保证，research D4）；`recallByKeyword` 转发 `searchArchival`（契约四）；构造注入 maxRows（默认 100）
- [x] T020 [P] [US3] Mem0MemoryStoreTest：`yokeos-memory/src/test/java/com/yokeos/memory/Mem0MemoryStoreTest.java`——mock `RestClient` 链（或 `RestClient.Builder` 工厂注入）：①`append` 发出的请求体含 scope metadata（ArgumentCaptor 断言 JSON 体，`@DisplayName("写入请求携带分区标记")`）；②`recallByKeyword` 转发 search 查询（`@DisplayName("检索转发给Mem0的search")`）；③非 2xx → 明确异常含状态码（`@DisplayName("Mem0侧错误翻译为明确异常")`）；④baseUrl 占位缺失（`${MEM0_BASE_URL}` 未解析）→ 构造时清晰报错点名变量名（`@DisplayName("mem0配置缺失在使用时清晰报错")`，FR9/research D6）
- [x] T021 [P] [US3] Mem0MemoryStore 集成档：`yokeos-memory/src/main/java/com/yokeos/memory/Mem0MemoryStore.java`——RestClient 翻译 add/get/search（端点与字段常量按 research D3 mock 定契约口径，javadoc 注明「真实字段以部署的 Mem0 版本为准」）；`${MEM0_*}` 占位运行时解析、缺失抛含变量名 `IllegalStateException`（不阻断启动——校验发生在构造/使用时，D6）；scope 落 metadata；每方法首行 Sandbox 检查位注释（HTTP_REQUEST，24 节）
- [x] T022 [US3] MemoryProperties + 选档装配：`yokeos-memory/src/main/java/com/yokeos/memory/MemoryProperties.java`（`@ConfigurationProperties("yokeos.memory")` 绑定 backend/archive-max-chars/archive-max-rows 三普通键；**mem0 段不走绑定**——占位提前解析会阻断启动，javadoc 记 D6 论证；16 节 ProvidersProperties 先例）+ `YokeosRuntime.memoryService()` 改造：按 `yokeos.memory.backend` 三选一构造 store 注入同一 `MemoryServiceImpl`（markdown 缺省；未知名清晰报错不静默回退）+ `yokeos-boot/src/main/resources/application.yaml` 增 `yokeos.memory` 四组键与注释 + `sql.init.schema-locations` 追加 `classpath:db/schema-003-memory.sql`
- [x] T023 [US3] 阶段门禁：`mvn test -pl yokeos-storage -am` + `mvn test -pl yokeos-memory -am` + `mvn test -pl yokeos-cli -am` + `mvn test -pl yokeos-boot -am` 全绿（契约测试三档参数全过，18 节既有真库用例零回归）

## Phase 5: 收尾（六项证据 DoD）

- [x] T024 全仓硬门禁：`mvn clean verify` 九模块全绿（测试数 = 21 节基线 + 本节新增；Spotless/P3C/Checkstyle/SpotBugs/FindSecurityBugs 全过），红了修实现不改规则
- [x] T025 H4 全局不变量逐条自查 + 教学文档「本节交付物」逐项 ls/grep 存在性核对 + 宪法专项 grep：`grep -rn "internalToolExecutionEnabled(true)\|\.tools(" yokeos-*/src/main` 零命中（宪法 2）、`grep -rn "import reactor" yokeos-*/src/main` 零命中（宪法 4）、`grep -rn "USER.md" yokeos-memory/src/main` 仅注释命中零写路径（坑三）、`grep -rn "sk-" --include='*.java' --include='*.yaml' yokeos-*/src` 零明文（宪法 7）、Sandbox 检查位注释形态抽查（MarkdownMemoryStore/Mem0MemoryStore 两处）
- [x] T026 验收报告：`specs/007-memory-implementation/acceptance-report.md`（结构照 specs/005：六项证据 DoD + harness 映射表 + 坑一~七回归点逐个过 + 实施偏差节 + 剩余人工项——真模型跨对话演示与两档切换体感）；CLAUDE.md 常见陷阱表回填（如有新坑）；对话内输出三段式变更总结

## Dependencies

- T001/T002 先行（T002 阻塞全部——pom 依赖是编译前提）。
- Phase 2 内：T003 无依赖（纯接口）；T004 依赖 T003；T005→T006、T007→T008 测试先行配对；T009→T010（PromptBuilder 测试更新先行）；T010 依赖 T003/T006/T008（门面链齐）；T011 收口。
- Phase 3 内：T012→T013；T014 依赖 T013 与 T010（memoryService Bean 在）；T015 收口。
- Phase 4 内：T016→T017（repo 测试先行配对）；T018 依赖 T004/T006/T017（契约工厂三件齐）；T019 依赖 T017；T020→T021；T022 依赖 T006/T019/T021（三档齐）+ T016（schema-locations 追加）；T023 收口。
- 并行机会：T003/T005/T007 同模块不同文件、T016（storage）与 T020（memory mock）跨模块、T019 与 T021 不同文件。

## Implementation Strategy

- MVP = Phase 2（US1：契约 + 默认档 + 注入接线——单档即可支撑「跨对话记住偏好」演示）；Phase 3 补 Tool 面（US2）；Phase 4 三档齐备与选档（US3，「墙」的完整兑现）；Phase 5 收尾。
- 每阶段门禁当场修红；本节结束时 yokeos-core 新增 memory 包两件 + PromptBuilder 接线、yokeos-memory 从占位到九件（接口/四实现/门面/配置/工具）、yokeos-storage 新增三件，全仓测试数 = 21 节基线 + 本节新增（估算 +30 左右：契约 4×3 参数 + 各档/门面/工具/repo/prompt 用例）。
- 人工项（quickstart.md 场景二/三）在 T025/T026 记录：真模型跨对话演示、markdown↔sqlite 切换体感、mem0 真实例可选。
