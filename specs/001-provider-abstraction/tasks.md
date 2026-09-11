# Tasks: Provider——对接大模型的统一入口（第16节）

## Format: `[ID] [P?] [Story] Description`

> TDD 纪律（用户指令）：测试任务先于或伴随实现任务；实现与测试同任务闭环，红了当场修。
> 测试方法名英文，参照语义以 `@DisplayName` 保留。拆解依据：plan.md 模块落位与契约签名、
> data-model.md 两表全列、contracts/ 两份契约。

## Path Conventions

- 源码：`yokeos-<module>/src/main/java/com/yokeos/<module>/…`；测试：`yokeos-<module>/src/test/java/…`
- 模块内包名：core → `profile` / `tool` / `audit`；provider → 根包；storage → 根包；cli → 根包

## Phase 1: Setup (Shared Infrastructure)

- [x] T001 在 `yokeos-provider/pom.xml` 声明 `spring-ai-starter-model-openai`（版本随根 BOM `spring-ai.version=1.1.8`），跑 `mvn -pl yokeos-provider dependency:resolve` 联网核实 1.1.8 可下载解析（本地 .m2 尚无 1.1.8，research D1 待核实项）——失败即软门禁停下报告，不降级版本
- [x] T002 核对 `yokeos-storage/pom.xml`（sqlite-jdbc 3.53.2.1、spring-boot-starter-data-jpa）与各模块测试依赖（JUnit 5 / Mockito，地基已备则只核对）——不加 plan 外依赖

## Phase 2: Foundational (Blocking Prerequisites)

- [x] T003 [P] 创建 `Profile` 全字段值对象（含嵌套 `Identity` / `ProviderConfig` / `Settings` 等记录类）于 `yokeos-core/src/main/java/com/yokeos/core/profile/Profile.java`——纯数据类，由 T012 `AgentLoaderTest` 覆盖
- [x] T004 [P] 创建 `YokeTool` 最小接口（`getName` / `getDescription` / `getInputSchema`）于 `yokeos-core/src/main/java/com/yokeos/core/tool/YokeTool.java`——由 T007 `ToolSchemaAdapterTest` 覆盖（research D4）
- [x] T005 [P] 创建 `LlmCallAuditor` 跨模块契约接口于 `yokeos-core/src/main/java/com/yokeos/core/audit/LlmCallAuditor.java`（research D3：core 定义，storage 实现，provider 消费）——由 T006 mock 与 T019 auditor 落库用例覆盖（analyze T1）

## Phase 3: User Story 1 - 多 Provider 并存，按名精确路由 (Priority: P1) 🎯 MVP

目标：双 provider 按名路由不串台、未知名清晰报错、带工具请求自动执行关闭且 schema 只翻译。独立判据：`mvn -pl yokeos-provider test` 全绿。

### Tests for User Story 1

- [x] T006 [P] [US1] 写 `yokeos-provider/src/test/java/com/yokeos/provider/ProviderServiceTest.java`（mock `ChatModel`）：`routeByName_twoProvidersNoCrosstalk`（verify 目标 `times(1)`、另一家 `never()`）、`callWithUnknownProvider_throwsWithNameInMessage`、`callWithToolSchema_disablesAutoExecution`（captor 断言关闭 + schema 携带）、`callFailure_auditsSuccessFalseRecord`（抛异常且审计先落 `success=false`+原因）——`@DisplayName` 保留参照课件语义
- [x] T007 [P] [US1] 写 `yokeos-provider/src/test/java/com/yokeos/provider/ToolSchemaAdapterTest.java`：schema 翻译后字段一一对齐、产物不含任何执行逻辑

### Implementation for User Story 1

- [x] T008 [US1] 实现 `yokeos-provider/src/main/java/com/yokeos/provider/ProvidersProperties.java`（`yokeos.providers` 绑定：name / api-key / base-url）+ 启动期校验（`api-key` 必须为 `${ENV_VAR}` 占位形态、环境变量缺失清晰报错不静默——FR7，analyze E1）+ `ProvidersPropertiesTest`（占位形态校验 / 缺失报错两用例）+ `yokeos-boot/src/main/resources/application.yaml` 补 `yokeos.providers` 示例段（deepseek + kimi，`${ENV_VAR}` 占位）
- [x] T009 [US1] 实现 `yokeos-provider/src/main/java/com/yokeos/provider/ToolSchemaAdapter.java`（`YokeTool` → Spring AI 工具描述，只翻译）→ T007 绿
- [x] T010 [US1] 实现 `ProviderNotFoundException.java` 与 `ProviderService.java`（构造器收 `Map<String, ChatModel>` + adapter + auditor；`chat(sessionId, Profile, Prompt)`：按名取模型→查无抛异常→schema 翻译→调用（自动执行关闭，1.1.8 写法按 H3 核实，research D2）→响应原样返回；审计双路留 US3 接线位）→ T006 绿
- [x] T011 [US1] `mvn -pl yokeos-core,yokeos-provider test` 全绿，红了当场修

## Phase 4: User Story 2 - 换模型只改配置 (Priority: P2)

目标：frontmatter 派生 Profile 并注册、坏文件不阻断、`yokeos init` 幂等。独立判据：core 与 cli 模块测试全绿。

### Tests for User Story 2

- [x] T012 [P] [US2] 写 `yokeos-core/src/test/java/com/yokeos/core/profile/AgentLoaderTest.java`：合法 `AGENT.md` frontmatter 全字段派生（含 T003 `Profile` 断言）、引用不存在 provider 名报错清晰（消息含名字）、坏 YAML 不阻断其余加载、`${ENV}` 占位从环境变量解析
- [x] T013 [P] [US2] 写 `yokeos-cli/src/test/java/com/yokeos/cli/InitCommandTest.java`（`@TempDir`）：产物齐全（六目录 + 三 Bootstrap 占位模板文件）、二次运行零变化（幂等）、文件内容为占位模板形态

### Implementation for User Story 2

- [x] T014 [US2] 实现 `yokeos-core/src/main/java/com/yokeos/core/profile/AgentLoader.java`（SnakeYAML 解析 frontmatter → `Profile`；校验 provider 名在全局清单；坏文件记错误日志跳过）→ T012 绿
- [x] T015 [US2] 实现 `yokeos-core/src/main/java/com/yokeos/core/profile/ProfileRegistry.java`（`Map<String, Profile>` 索引：register / get / all；启动扫描为唯一注册路径，29 节再补运行时注册）
- [x] T016 [US2] 实现 `yokeos-cli/src/main/java/com/yokeos/cli/InitCommand.java`（不启 Spring、纯文件操作、结构化日志、退出码契约见 contracts/cli-init.md）→ T013 绿

## Phase 5: User Story 3 - 每次调用可审计 (Priority: P3)

目标：成败双路先落账再返回/抛错，`llm_calls` 含补列两表落库。独立判据：storage 测试全绿 + 冒烟可跑。

### Tests for User Story 3

- [x] T017 [US3] 写 `yokeos-storage/src/test/java/com/yokeos/storage/LlmCallRepositoryTest.java`：测试内执行 `schema.sql` 手工建表（不让 Hibernate 自动建）；存读往返；`success` / `error_message` 两列真实存在

### Implementation for User Story 3

- [x] T018 [US3] 实现 `yokeos-storage/src/main/resources/schema.sql`（`llm_calls` + `tool_invocations` 两表全列，按 data-model.md）+ `LlmCall.java` 实体 + `LlmCallRepository.java` → T017 绿
- [x] T019 [US3] 实现 `yokeos-storage/src/main/java/com/yokeos/storage/JpaLlmCallAuditor.java`（实现 core 的 `LlmCallAuditor`，依赖倒置）+ 接进 `ProviderService` 审计双路（成功 `success=true`+token 三项+耗时；失败 `success=false`+`error_message`，先落账再抛）+ 在 `LlmCallRepositoryTest` 补 auditor 端到端落库一用例
- [x] T020 [US3] 写 `yokeos-provider/src/test/java/com/yokeos/provider/ProviderSmokeIntegrationTest.java`（`@Tag("integration")`）：读真 key、真调一次、断言非空响应且 `llm_calls` 新增 `success=true` 行；运行形态（手工构造或最小 Spring 装配）以 1.1.8 核实结果定；注明显式触发命令 `mvn -pl yokeos-provider test -Dgroups=integration`

## Phase 6: Polish & Cross-Cutting Concerns

- [x] T021 全量门禁 `mvn clean verify` 全绿（Spotless / P3C / Checkstyle / SpotBugs / PMD + 全部单测）+ 反作弊自查：无 `@Disabled`、无删断言、无放宽阈值

## Dependencies & Execution Order

### Phase Dependencies

Phase 1 → Phase 2 → Phase 3/4（可并行）→ Phase 5 → Phase 6

### User Story Dependencies

- US1、US2 相互独立（均只依赖 Phase 2 的契约/值对象），可并行推进
- US3 依赖 US1（`ProviderService.chat` 存在才有审计接线点）
- Phase 6 依赖全部

### Within Each User Story

Tests 先行（红）→ Implementation 逐任务转绿 → 模块测试收口

### Parallel Opportunities

- T003 / T004 / T005（三个不同文件，无依赖）
- T006 / T007；T012 / T013

## Parallel Example: User Story 1

```text
先并行：T006（ProviderServiceTest）+ T007（ToolSchemaAdapterTest）
后顺序：T008 → T009（转 T007 绿）→ T010（转 T006 绿）→ T011 收口
```

## Implementation Strategy

### MVP First (User Story 1 Only)

仅 US1（T001~T011）即满足「双 provider 路由 + 只翻译不执行」的硬门槛演示。

### Incremental Delivery

US1 路由核心 → US2 配置形态（frontmatter / init）→ US3 审计落库 → 全量门禁收口。每相结束都是可独立验证的增量。

### Parallel Team Strategy

单执行者按相序推进即可；如并行，US1 与 US2 各占一条线，US3 等待 US1。

## Notes

- H3 纪律贯穿：T001（BOM 可解析）与 T010（1.1.8 调用写法）核实不到即停下报告，不猜 API
- 手工验项（dependency:tree 人工复核、真 key 冒烟、`grep sk-`、init 幂等抽查）归验收报告「剩余人工项」，不进任务清单
- 三个关键回归测试 = 坑一/坑二/失败审计，均已落 T006；「每个坑一个回归测试钉死」
