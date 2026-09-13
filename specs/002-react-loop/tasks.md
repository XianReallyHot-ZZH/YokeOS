# Tasks: ReAct 循环——Agent 的大脑（第17节）

## Format: `[ID] [P?] [Story] Description`

> TDD 纪律（用户指令）：测试任务先于或伴随实现任务（验收 harness 先行）；实现与测试同任务闭环，红了当场修；集成冒烟单列。测试方法名英文，教学文档语义以 `@DisplayName` 保留。拆解依据：plan.md 模块落位、contracts/java-api.md 逐字签名、data-model.md 值对象、教学文档第三部分交付物清单与第四部分 harness 表。

## Path Conventions

- 源码：`yokeos-<module>/src/main/java/com/yokeos/…`；测试：`yokeos-<module>/src/test/java/…`
- core 包：`core/provider` / `core/agent` / `core/context` / `core/session` / `core/audit` / `core/tool`；provider、storage、tool 为模块根包；冒烟落 boot 测试目录
- 签名一律照 contracts/java-api.md 逐字，不得现场发明；Spring AI 写法照 research.md D2 实证

## Phase 1: Setup (Shared Infrastructure)

- [x] T001 在 `yokeos-core/pom.xml` 声明 `com.fasterxml.jackson.core:jackson-databind`（**不写版本号**，根 pom `jackson-bom` 2.21.5 接管——research D7，记实施偏差），跑 `mvn -pl yokeos-core -am dependency:resolve` 核实可解析——失败即软门禁停下报告

## Phase 2: Foundational (第零步前置改造——契约上移 + 执行语义，阻塞全部 US)

- [x] T002 [P] 创建 core 契约四件于 `yokeos-core/src/main/java/com/yokeos/core/provider/`：`ProviderService` 接口、`ProviderRequest`、`ProviderResponse`（含 `hasToolCalls()`）、`ToolCallRequest`（签名逐字照 contracts）——全普通 Java 类型，由 T006 mock 与 T009 循环测试覆盖
- [x] T003 [P] 扩展 `yokeos-core/src/main/java/com/yokeos/core/tool/YokeTool.java`：补 `ToolResult execute(JsonNode input)`，16 节注释「执行语义归第 20 节」修订为「白名单校验归 20/24 节」（拍板②措辞）；同包新增 `ToolResult.java`（record 四字段 + `ok` / `error` 工厂）——由 T015 执行器测试覆盖
- [x] T004 [P] 创建 `yokeos-core/src/main/java/com/yokeos/core/session/` 四件：`Session`（final class，append 三兄弟：`appendUser` / `appendAssistant`〔text 为 null 按空串〕 / `appendToolResult`〔失败存错误描述〕）、`Message`（record 三元）、`SessionManager`（接口仅 `save`）、`InMemorySessionManager`——由 T009 累积回归覆盖
- [x] T005 [P] 创建 `yokeos-core/src/main/java/com/yokeos/core/audit/ToolInvocationAuditor.java`（八参 record 签名逐字，与 `LlmCallAuditor` 同包对称）——由 T015/T016 覆盖
- [x] T006 测试先行：`yokeos-provider/src/test/java/com/yokeos/provider/ProviderServiceTest.java` 改名平移为 `SpringAiProviderServiceTest.java`（git mv 语义；构造与 `chat` 调用换 `ProviderRequest`/断言换 `ProviderResponse`，**四组断言语义逐条保留**：路由不串台 / 点名报错 / 成败双路审计 / 自动执行关闭）+ 补映射用例：`toolCallsExtracted_notLost`（多工具调用逐项映射 name/argumentsJson）、`textNullSafe`、`noToolCalls_hasToolCallsFalse`——此刻红（实现未改名）
- [x] T007 `yokeos-provider/src/main/java/com/yokeos/provider/ProviderService.java` 改名重构为 `SpringAiProviderService.java`（git mv 语义）：`implements com.yokeos.core.provider.ProviderService`；显式映射 `Map<String, ChatModel>`、`ToolSchemaAdapter`、`LlmCallAuditor` 双路审计路径**原样复用**；新增双向映射——`ProviderRequest.promptText` → `new Prompt(String)`、`ChatResponse` → `ProviderResponse`（`getResult().getOutput()` 取 `getText()` / `getToolCalls()` 逐项映射，写法照 research D2 javap 实证）；`ProviderSmokeIntegrationTest` 引用同步改名 → T006 绿 + `mvn -pl yokeos-provider -am test` 全绿（16 节回归 = 零行为变化门禁）

**Checkpoint**: 契约上移完成、16 节零行为变化——US 实现可开始

## Phase 3: User Story 1 - 一次对话内完成多步任务 (Priority: P1) 🎯 MVP

**Goal**: ReAct 循环自实现跑通——组装 → 调用 → 判停 → 回填累积，轮数兜底。
**Independent Test**: `mvn -pl yokeos-core -am test` 全绿（`ReActLoopTest` / `PromptBuilderTest` / `ContextLoaderTest`）。

### Tests for User Story 1

- [x] T008 [P] [US1] 写 `yokeos-core/src/test/java/com/yokeos/core/context/ContextLoaderTest.java`（`@TempDir` 工作区）：`bootstrapAndIdentityAndBody_inOrder`（identity + 三 Bootstrap 带角色 header、固定相对序 + AGENT.md 正文压轴，header 断言字面量 `## 项目约定（AGENTS.md）` 等）、`editAgentBody_nextLoadSeesNewContent`（**无缓存回归**）、`editBootstrap_nextLoadSeesNewContent`（**无缓存回归**）、`missingBootstrap_warnsAndSkips`（不阻断）、`bootstrapListTrimmed_orderPreserved`（裁剪不乱序）
- [x] T009 [P] [US1] 写 `yokeos-core/src/test/java/com/yokeos/core/agent/ReActLoopTest.java`（mock `ProviderService` 接口）：`noToolCall_finishesInOneRound`（零工具执行）、`withToolCall_executesAndFeedsNextRound`（工具结果进下一轮 prompt）、`multipleToolCalls_executedSequentially`（顺序执行——in-order verify）、`everyRoundAccumulatesIntoSession`（**坑三回归**：含工具结果与 assistant 消息全在场）、`modelKeepsRequestingTools_forceStopAtMaxIterations`（**坑一回归**：恰好 10 轮一轮不多 + 答复含「达到最大轮数」）、`maxIterationsOverrideFromProfile_fiveRounds`（配置覆盖）、`neitherTextNorToolCalls_returnsEmptyString`、`toolFailure_resultFedBack_loopContinues`（失败回填不中断）
- [x] T010 [P] [US1] 写 `yokeos-core/src/test/java/com/yokeos/core/agent/PromptBuilderTest.java`（固定 `Clock` 注入）：`sectionsInOrder_systemWithDatetimeThenHistory`、`historyOverLimit_truncatedByTurn`（**坑二回归**）、`historyExactlyLimit_notTruncated`、`systemPromptEndsWithCurrentDatetime`（期望值由固定时钟计算，不赌真实时间）、`availableTools_onlyProfileNamed`（走 `ProviderRequest.availableTools`）、`truncationKeepsToolResultWithItsTurn`（轮界不撕裂——工具结果跟住提问轮）

### Implementation for User Story 1

- [x] T011 [US1] 实现 `yokeos-core/src/main/java/com/yokeos/core/context/ContextLoader.java`（`ContextLoader(Path workspace)`；identity → Bootstrap（零缓存现读、缺失 WARN、IO 失败显式抛错）→ Skill 位注释（29 节）→ AGENT.md 正文（frontmatter `---` 首对围栏剥离，按 `agents/<name>/AGENT.md` 定位）→ T008 绿
- [x] T012 [US1] 实现 `yokeos-core/src/main/java/com/yokeos/core/agent/PromptBuilder.java`（`PromptBuilder(ContextLoader, Clock)`；system prompt + 末尾日期时间行〔`当前时间：yyyy-MM-dd HH:mm:ss` 由注入 Clock 计算〕→ 记忆位注释（22 节）恒空 → 历史轮界截断（一轮 = 一条 user 及其后全部）→ availableTools 只取 Profile 点名；工具查找经构造注入的候选集，20 节换 ToolRegistry 来源本类不动）→ T010 绿
- [x] T013 [US1] 实现 `yokeos-core/src/main/java/com/yokeos/core/agent/ReActLoop.java`（`run(session, userMessage, profile)`：`appendUser` → for 轮次〔`build` → `chat(sessionId, profile, req)` → `appendAssistant` 先累积再判停 → 无工具调用返回 text（null 按空串）→ 逐个顺序 `toolExecutor.execute` + `appendToolResult`〕→ 转满 `maxIterations` 返回含「达到最大轮数」的收尾答复；不 import 任何 Spring AI 类型——宪法 1）→ T009 绿
- [x] T014 [US1] `mvn -pl yokeos-core -am test` 全绿，红了当场修

## Phase 4: User Story 2 - 工具失败不炸循环，模型看得到失败原因 (Priority: P2)

**Goal**: ToolExecutor 唯一执行路径 + 审计落账 + 一个真 HTTP Tool。
**Independent Test**: `mvn -pl yokeos-core,yokeos-storage -am test` 全绿（`ToolExecutorTest` / `ToolInvocationRepositoryTest`）。

### Tests for User Story 2

- [x] T015 [P] [US2] 写 `yokeos-core/src/test/java/com/yokeos/core/agent/ToolExecutorTest.java`（假工具内类 + mock `ToolInvocationAuditor`；退避基值注入 0）：`success_auditsTrue`、`failureToolResult_auditsFalseWithReason`、`toolThrows_convertedToFailingResult_noPropagate`（不炸循环）、`unknownToolName_failingResultPlusAudit`（不抛异常）、`invalidJson_failingResultPlusAudit`、`retryableFailsThenSucceeds_withinBackoffRetries`（**重试回归**：fail,fail,ok → 成功 + `verify(tool, times(3))` + 审计恰一条最终态）、`retryExhausted_finalFailureAuditedOnce`（三次耗尽 → `success=false` 一条）、`nonRetryable_singleAttempt`（**重试回归**：一次即止）
- [x] T016 [P] [US2] 写 `yokeos-storage/src/test/java/com/yokeos/storage/ToolInvocationRepositoryTest.java`（构造形态照 `LlmCallRepositoryTest`：静态临时 SQLite + 手工执行 `db/schema-001-audit.sql` 建表、不让 Hibernate 自动建）：`saveAndReloadRoundTrip`（全字段）、`successAndErrorColumnsExist`、`findBySessionId_roundTrip`（按 session 关联）

### Implementation for User Story 2

- [x] T017 [US2] 实现 storage 三件：`yokeos-storage/src/main/java/com/yokeos/storage/ToolInvocation.java`（JPA 实体，列定义逐字技 §9.2，映射既有表无新表）、`ToolInvocationRepository.java`（含 `findBySessionId`）、`JpaToolInvocationAuditor.java`（形态照 `JpaLlmCallAuditor`，写入失败不吞）→ T016 绿
- [x] T018 [US2] 实现 `yokeos-core/src/main/java/com/yokeos/core/agent/ToolExecutor.java`（`ToolExecutor(Map<String, YokeTool>, ToolInvocationAuditor, long retryBackoffBaseMs)`；序列：解析 argumentsJson（坏 JSON 失败路径）→〔Sandbox 检查位注释：24 节接线〕→ 执行 → 可重试按 `baseMs << attempt` 退避、总尝试上限 3（常量，research D5）→ 先落审计（最终态一条、duration 覆盖重试总耗时）再还结果；`RuntimeException` 转失败 `ToolResult` 不上抛；同步 `Thread.sleep` 退避——宪法 4）→ T015 绿
- [x] T019 [US2] 实现 `yokeos-tool/src/main/java/com/yokeos/tool/HttpGetTool.java`（`getName()="http_get"`；入参 `{url}` 从 `JsonNode.path("url")` 取；Java `HttpClient` GET、连接/读取超时 10 秒、非 2xx 转失败结果、正文超 8000 字符截断；域名白名单留 24 节注释位；schema 按 `ToolSchemaAdapter` 消费格式）——harness 无单测类（教学文档口径），由 T022 集成冒烟真调验证

## Phase 5: User Story 3 - 每一步留痕可审计，死循环有兜底 (Priority: P3)

**Goal**: 编排入口收口 ProfileContext 生命周期；真链路冒烟可演示。
**Independent Test**: `mvn -pl yokeos-core -am test` 全绿（`AgentServiceTest`）+ 冒烟真跑一次（需 key）。

### Tests for User Story 3

- [x] T020 [P] [US3] 写 `yokeos-core/src/test/java/com/yokeos/core/agent/AgentServiceTest.java`（mock `ReActLoop` / `SessionManager` / `ProfileRegistry`）：`profileContextAvailableDuringProcess`（处理期间可取当前 Profile）、`processThrows_profileContextClearedInFinally`（**坑四回归**：抛异常后 `ProfileContext.current()` 为 null）、`normalPath_sessionSaved_returnsLoopResult`、`exceptionPath_sessionNotSaved`、`profileMissing_throwsWithName`（点名报错含名字）

### Implementation for User Story 3

- [x] T021 [US3] 实现 `yokeos-core/src/main/java/com/yokeos/core/agent/ProfileContext.java`（ThreadLocal 封装 `set` / `current` / `clear`，clear 用 `remove()`）与 `AgentService.java`（`process`：查 Profile 缺失点名抛 `IllegalStateException` → `ProfileContext.set` → `reActLoop.run` → `sessionManager.save`（仅正常路径）→ `finally ProfileContext.clear`）→ T020 绿
- [x] T022 [US3] 写 `yokeos-boot/src/test/java/com/yokeos/boot/ReActSmokeIntegrationTest.java`（`@Tag("integration")`，手工装配：`AgentService` + `ReActLoop` + `PromptBuilder`（固定 Clock）+ `ContextLoader`（`@TempDir` 工作区 + 最小 AGENT.md）+ `SpringAiProviderService`（真 DeepSeek key、OpenAI 兼容手工构造）+ `ToolExecutor` + 真 `HttpGetTool` + storage JPA 审计（构造形态照 `LlmCallRepositoryTest`）；提示词强引导「必须先调用 http_get 获取天气再回答」打 open-meteo；断言三件：答复非空、`tool_invocations` 新增 `success=true` 行、`llm_calls` 有记录；缺 key `assumeTrue` 跳过；注明显式触发 `DEEPSEEK_API_KEY=xxx mvn -pl yokeos-boot -am test -Dgroups=integration -DexcludedGroups=`

## Phase 6: Polish & Cross-Cutting Concerns

- [x] T023 全量门禁 `mvn clean verify` 九模块全绿（Spotless / P3C / Checkstyle / SpotBugs / PMD + 全部单测含 16 节回归）+ 反作弊自查（无 `@Disabled`、无删断言、无放宽阈值）+ 宪法 grep：`grep -rE "CompletableFuture|reactor|WebFlux" --include="*.java" yokeos-*/src` 零新增、`grep -rE "springframework\.ai|com\.yokeos\.provider" yokeos-core/src/main` 零命中（契约上移验证口径）

## Dependencies & Execution Order

### Phase Dependencies

Phase 1 → Phase 2（改名重构依赖 jackson 与契约四件）→ Phase 3 / Phase 4（可并行）→ Phase 5 → Phase 6

### User Story Dependencies

- US1 与 US2 相互独立：循环 mock 执行器、执行器独立测——可并行推进
- US3 依赖 US1（`AgentService` 编排 `ReActLoop`）与 US2（冒烟需要 `ToolExecutor` / `HttpGetTool` / storage 审计）
- Phase 6 依赖全部

### Within Each User Story

Tests 先行（红）→ Implementation 逐任务转绿 → 模块测试收口

### Parallel Opportunities

- T002 / T003 / T004 / T005（四个不同包，无依赖）
- T008 / T009 / T010（三个测试类）；T015 / T016

## Parallel Example: User Story 1

```text
先并行：T008（ContextLoaderTest）+ T009（ReActLoopTest）+ T010（PromptBuilderTest）
后顺序：T011（转 T008 绿）→ T012（转 T010 绿）→ T013（转 T009 绿）→ T014 收口
```

## Implementation Strategy

### MVP First (User Story 1 Only)

Phase 1~3（T001~T014）即得「一次对话内多步任务」的最小演示（mock 执行器即可跑通循环语义）。

### Incremental Delivery

契约上移（Phase 2）→ 循环主体（US1）→ 执行器与真工具（US2）→ 编排与真链路冒烟（US3）→ 全量门禁收口。每相结束都是可独立验证的增量；Phase 2 完成即恢复 16 节全绿的稳定点。

### Parallel Team Strategy

单执行者按相序推进即可；如并行，US1 与 US2 各占一条线，US3 等待两者。

## Notes

- H3 已在 plan 期完成（research D2 javap 实证）——实现照签名落笔即可，不再有「核实不到」停点；若实现中发现签名与实证不符（本地依赖变动），停下报告
- 手工验项（真 key 冒烟真跑、宪法 grep 复核、`grep sk-`、16 节测试全绿人工确认）由 T022/T023 承载入口，完整清单归验收报告「剩余人工项」
- 五坑 ↔ 测试对号：坑一↔T009 `modelKeepsRequestingTools_forceStopAtMaxIterations`、坑二↔T010 `historyOverLimit_truncatedByTurn` + `truncationKeepsToolResultWithItsTurn`、坑三↔T009 `everyRoundAccumulatesIntoSession`、坑四↔T020 `processThrows_profileContextClearedInFinally`、坑五↔T006 平移的自动执行关闭断言
- 实施偏差预告：jackson-databind 新依赖（T001/D7）、重试纳入本节（拍板③）——验收报告「实施偏差」节记两条
