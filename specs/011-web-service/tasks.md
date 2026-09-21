# Tasks: Web Service 与管理台第一版（第26节）

## Format: `[ID] [P?] [Story] Description`

> TDD 纪律（用户指令）：测试任务先于或伴随实现任务；实现与测试同任务闭环，红了当场修；集成冒烟单列。
> 测试方法名英文 camelCase 避连续大写，教学文档语义以 `@DisplayName` 保留。拆解依据：plan.md 模块落位、
> data-model.md（无新表 + SessionSummary + DTO）、contracts/rest-api.md（11 端点 + /admin 托管）、research.md D1~D10。

## Path Conventions

- 源码：`yokeos-<module>/src/main/java/com/yokeos/<module>/…`；测试：`yokeos-<module>/src/test/java/…`
- web 模块内包：`controller` / `controller/dto` / `error` / `config`（WebConfig）/ 既有根包（GlobalExceptionHandler）
- 前端工程：`yokeos-web/src/main/frontend/`，产物 `yokeos-web/src/main/resources/static/admin/`
- 集成冒烟落 `yokeos-boot/src/test`（25 节 E2E 同位；`@Primary` 覆盖 tools Bean 自备白名单——24 节坑）

## Phase 1: Setup (Shared Infrastructure)

- [x] T001 H3 依赖核实：`mvn -am dependency:resolve` 九模块全过（springdoc 2.6.0 随 yokeos-web 既有）；核实 frontend-maven-plugin 1.15.1 与 Node v20.18.0 下载源可达（research D8 待核实项）——任一失败即软门禁停下报告，不换版本不静默
- [x] T002 [P] `.gitignore` 补 `yokeos-web/src/main/frontend/node_modules/` 与前端构建产物 `yokeos-web/src/main/resources/static/admin/` 条目

## Phase 2: Foundational (Blocking Prerequisites)

- [x] T003 [P] `yokeos-core` …/core/session/`SessionManager.java` 接口补 `listRecent(int limit)` / `archive(String sessionId)`（javadoc 记归档语义：标记不终结，research D4）+ 新建 `SessionSummary.java` record（sessionId/agentName/channel/userId/status/lastActiveAt，data-model.md）——纯契约，由 T004/T005 覆盖
- [x] T004 先写 `InMemorySessionManagerTest` 扩用例（listRecent 按 lastActiveAt 倒序 + 上限；archive 置 status/archived_at、未命中 false；归档后同三元组 getOrCreate 幂等返回原会话）再实现 `InMemorySessionManager` 两方法转绿——同任务闭环
- [x] T005 先写 `SessionManagerTest`（18 节既有，storage 模块）扩用例（同 T004 三组 + 列表不触发 messages_json 反序列化）再实现 `JpaSessionManager.listRecent`（ORDER BY last_active_at DESC + LIMIT，经 repository 查询）/ `archive`（置 status + archived_at）转绿——同任务闭环
- [x] T006 [P] 先写 `GlobalExceptionHandlerTest` 扩用例（SessionNotFound/ResourceNotFound→404、ProviderUnavailable→503、AgentTimeout→504、响应体统一信封、500/503 不泄漏 `jdbc:sqlite` 串）再实现 `yokeos-web` …/web/error/ 四异常类 + `GlobalExceptionHandler` 三新映射（既有四映射与 sanitize 纪律不动）转绿——同任务闭环

**Checkpoint**：会话契约扩展与异常出口就绪，US1~US4 可开工。

## Phase 3: User Story 1 - 业务系统会话保持（5 端点）(Priority: P1) 🎯 MVP

目标：建会话→发消息（与 CLI 同一编排入口）→查历史→列表→归档全链路。独立判据：`mvn -pl yokeos-core,yokeos-storage,yokeos-web -am test` 全绿。

### Tests for User Story 1

- [x] T007 [P] [US1] 写 `yokeos-web/src/test/java/com/yokeos/web/controller/SessionApiControllerTest.java`（`@WebMvcTest`，mock `SessionManager`/`AgentService`）：`createMissingProfile_badRequest`（400）、`sendBlankOrOversizedMessage_badRequest`（空与 32KB+1 两例）、`sendToUnknownSession_notFound`、`getUnknownSession_notFound`、`archiveUnknownSession_notFound`（三路 404）、`sendMessage_delegatesToProcessExactlyOnce`（Controller 薄）、`history_truncatedToHundred`、`listFiltersByStatus`（?status= 过滤 + ≤100）——`@DisplayName` 保留教学文档语义

### Implementation for User Story 1

- [x] T008 [US1] 实现 `yokeos-web` …/controller/dto/ 五 record（`CreateSessionRequest`/`MessageRequest`/`MessageResponse`/`SessionView`/`SessionSummaryView`）+ …/controller/`SessionApiController.java` 5 端点（channel 固定 `web`、userId 缺省 `default`、32KB/100 条钳制、归档走 T005 契约；契约签名照 contracts/rest-api.md）→ T007 绿
- [x] T009 [US1] `mvn -pl yokeos-core,yokeos-storage,yokeos-web -am test` 收口，红了当场修

## Phase 4: User Story 2 - 一次性无状态调用 invoke (Priority: P2)

目标：`POST /agents/{name}/invoke` 跑完即返、不携带历史。独立判据：US2 切片测试全绿。

### Tests for User Story 2

- [x] T010 [P] [US2] 写 `yokeos-web/src/test/java/com/yokeos/web/controller/AgentApiControllerTest.java`（`@WebMvcTest`，mock `ProfileRegistry`/`SessionManager`/`AgentService`）：`invokeUnknownAgent_notFound`（先查注册表——verify `profileRegistry.get`，非 503）、`invokeBlankMessage_badRequest`、`invoke_delegatesToProcessExactlyOnce`、`twoInvokes_useDistinctSessions`（无状态，research D3）、invoke 会话 channel=`invoke`

### Implementation for User Story 2

- [x] T011 [US2] 实现 `yokeos-web` …/controller/`AgentApiController.java` 仅 `POST /{name}/invoke`（javadoc 注明 29/30 节扩展位；每次唯一 user 生成一次性会话）→ T010 绿

## Phase 5: User Story 3 - 信息查询与系统状态（5 GET）(Priority: P3)

目标：profiles/memory/tools/health/info 五只读端点。独立判据：US3 切片 + memory 模块测试全绿。

### Tests for User Story 3

- [x] T012 [P] [US3] 写三个切片测试（`@WebMvcTest`）：`ProfileApiControllerTest`（投影字段对齐、provider 段缺失 view 为 null 不炸）、`ToolApiControllerTest`（注册表全量 name/description）、`MemoryApiControllerTest`（`readAll()` 全文原样进 data）
- [x] T013 [P] [US3] 写 `SystemApiControllerTest`（`@WebMvcTest`）：`health_ok`；`info_providersDedupedAndSorted`（多 Profile 同 provider 去重排序）、空注册表时 providers 空数组不炸
- [x] T014 [US3] 先写 `MemoryServiceImplTest` 扩用例（markdown 档 `readAll` 回 MEMORY.md 原文含两分区标题；sqlite 档按注入同口径）再实现转绿——同任务闭环

### Implementation for User Story 3

- [x] T015 [US3] `yokeos-core` …/core/memory/`MemoryService.java` 补 `readAll()` + `yokeos-memory`：`LongTermMemoryStore` 契约补 `readAll()`、`MarkdownMemoryStore`（回原文）/`SqliteMemoryStore`（注入同口径）/`Mem0MemoryStore`（与 buildContext 同源）三实现 + `MemoryServiceImpl` 透传（22 节现有契约为准，H3）→ T014 绿
- [x] T016 [US3] `yokeos-web/pom.xml` 增补 `yokeos-tool` 模块依赖（research D6）+ 实现 `ProfileApiController` / `ToolApiController`（注入 `ToolRegistry`）/ `MemoryApiController` / `SystemApiController`（已配置口径，research D5）+ dto 三 record（`ProfileView`/`ToolView`/`InfoView`）→ T012/T013 绿

## Phase 6: User Story 4 - 管理台只读五页 + 托管 (Priority: P4)

目标：`/admin` 五页渲染真实数据、零写入口、SPA 回落。独立判据：前端构建进包 + WebSmoke 冒烟绿。

- [x] T017 [P] [US4] 写 `.claude/skills/yokeos-admin-ui/SKILL.md`（research D7）：token 直取 `website/.vitepress/theme/custom.css` 的 `--yoke-*` 变量（深蓝底/品牌蓝/琥珀/Inter+JetBrains Mono 全表）、工程约定（vite base `/admin/`、outDir static/admin、SPA 回落、只调 `/api/v1`、无认证假设）、组件规范（深色表格/状态圆点/三态/响应式收导航）、验收清单
- [x] T018 [US4] 建前端工程 `yokeos-web/src/main/frontend/`：`package.json`（vue 3 + vite）+ `vite.config.js`（base `/admin/'`、outDir `../resources/static/admin`）+ `index.html` + `src/main.js` + `src/styles/tokens.css` + `src/App.vue` 单文件五页（左导航五项/内容区/加载-空-错误三态占位/响应式，风格严格按 T017 skill；五页各调一个 GET 端点，错误展示信封 message）
- [x] T019 [US4] `yokeos-web/pom.xml` 加 frontend-maven-plugin 1.15.1 三 execution（install-node-and-npm v20.18.0 / npm install / npm run build）绑 `generate-resources` + `frontend.skip` 属性（默认 false，research D8）；跑 `mvn -pl yokeos-web -am package -DskipTests` 核实 `target/classes/static/admin/index.html` 产物落位（坑六）
- [x] T020 [US4] 先写 `yokeos-boot/src/test/java/com/yokeos/boot/WebSmokeIntegrationTest.java`（`@SpringBootTest` + `@Tag("integration")`，`@Primary` tools 白名单覆盖 24 节同款）：五 GET 端点真实可达、`/admin/` 200 且 HTML、`/admin/不存在路由` 回落 index.html、`/api/v1/不存在` JSON 404 不被劫持（坑一两半边）、`/admin/assets/**` immutable 与 `/admin/` no-cache 两档 Cache-Control 断言（坑二）、`/v3/api-docs` 可达且 paths 含 `/api/v1/sessions`（FR-008，analyze M1）、带 `Origin` 头请求响应含 `Access-Control-Allow-Origin: *`（FR-012，analyze L1）；再实现 `yokeos-web` …/config/`WebConfig.java`（/admin redirect→`/admin/`、forward→index.html、assets immutable 365d、`/admin/**` no-cache + `PathResourceResolver` 回落、CORS 全开）转绿——同任务闭环

**Checkpoint**：11 端点 + 管理台全链路可用，quickstart 手动链可跑。

## Phase 7: Polish & Cross-Cutting Concerns

- [x] T021 [P] 文档同步（拍板①②）：`docs/TechnicalSolution.md` §13 行 26 改分节口径 + §7.2 会话组补 `GET /api/v1/sessions`（18→19）；`docs/DemandAnalysis.md` §5.10 端点表补列表端点行；`CLAUDE.md` Web Service API 节端点数 18→19
- [x] T022 [P] `yokeos-cli` …/command/`ServeCommand.java` javadoc 更新（「REST 端点将在第 26 节接线」→已接线，含管理台）——零逻辑改动
- [x] T023 全量门禁 `mvn clean verify`（**不带** `-Dfrontend.skip=true`，坑六验收锚）九模块全绿 + 反作弊自查（无 `@Disabled`、无删断言、无放宽阈值）；boot 冒烟 `mvn -pl yokeos-boot -am test -Dgroups=integration -DexcludedGroups=`（真 key 链路归人工项）

## Dependencies & Execution Order

### Phase Dependencies

Phase 1 → Phase 2 → Phase 3（US1 MVP）→ Phase 4/5（可并行）→ Phase 6（依赖 US1/US3 端点作数据源）→ Phase 7

### User Story Dependencies

- US1 依赖 Phase 2（T003~T005 会话契约、T006 异常出口）
- US2、US3 相互独立，可并行（US3 的 T016 依赖自身 T015 与 Phase 2 的 T006）
- US4 依赖 US1（会话列表端点）与 US3（四个 GET 端点）——五页数据源
- Phase 7 依赖全部

### Within Each User Story

Tests 先行（红）→ Implementation 逐任务转绿 → 模块测试收口；同任务闭环任务（T004/T005/T006/T014/T020）内部先测后实现

### Parallel Opportunities

- T002 / T003 / T006（不同文件）；T007 / T010 / T012 / T013 / T017（五个测试/文档件互不依赖）
- T004 与 T005 并行（core 内存实现 vs storage JPA 实现，不同模块）

## Parallel Example: User Story 1

```text
先：T007（SessionApiControllerTest 写红）
后：T008（DTO + Controller 转绿）→ T009 模块收口
```

## Implementation Strategy

### MVP First (User Story 1 Only)

Phase 1~3 完成即满足「REST 会话链路可用」的最小演示（建会话→真模型对话→查历史→归档）。

### Incremental Delivery

会话链路（US1）→ 无状态 invoke（US2）→ 信息/状态端点（US3）→ 管理台（US4）→ 文档同步与全量门禁。每相结束都是可独立验证的增量。

### Parallel Team Strategy

单执行者按相序推进；如并行：US2 与 US3 各占一条线（均只依赖 Phase 2），US4 等两者收口。

## Notes

- H3 纪律贯穿：T001（npm/Node 源可达）与 T015（22 节契约实际形态）核实不到即停下报告
- 宪法 4 落点：全程同步，T020 的 504 仅映射占位（research D10），不造硬中断
- 手工验项（真 key 全链路、断 Provider 503、并发冒烟、五页浏览、`grep sk-`、两入口共享存储抽查）归验收报告「剩余人工项」，不进任务清单
- 坑↔回归对应：坑一→T020 两断言；坑二→T020 Cache-Control；坑三→T010；坑四→切片/冒烟分层本身；坑五→T006；坑六→T019/T023
