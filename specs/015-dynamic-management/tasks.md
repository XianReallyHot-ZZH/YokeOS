---
description: "Task list for 动态管理（第30节）"
---

# Tasks: 动态管理——一句话生成、上传即上线（第30节）

**Input**: Design documents from `/specs/015-dynamic-management/`（spec.md / plan.md / research.md / data-model.md / contracts/java-api.md / quickstart.md）

**Prerequisites**: 29 节运行时原语在位（已核）；`docs/class/030-dynamic-management.md` 教学文档定稿（拍板①~⑧ + clarify 两问）

**Tests**: TDD 纪律（用户指定）：测试任务先于对应实现任务，实现与测试同任务闭环红→绿；集成冒烟单列。测试方法名英文、`@DisplayName` 保留教学文档中文语义。

**Organization**: 按 User Story 分相（spec.md 五 story）；AgentLifecycleService 骨架与 AgentStore 是多 story 共用件归 Foundational。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: 归属 user story（US1~US5）
- 全部路径仓库根相对；命令形态：单模块构建必带 `-am`

## Path Conventions

- core 主代码/测试：`yokeos-core/src/{main,test}/java/com/yokeos/core/agent/`
- web：`yokeos-web/src/{main,test}/java/com/yokeos/web/`（controller / controller/dto）
- 装配：`yokeos-cli/src/main/java/com/yokeos/cli/YokeosRuntime.java`；配置：`yokeos-boot/src/main/resources/application.yaml`
- 集成：`yokeos-boot/src/test/java/com/yokeos/boot/`；前端：`yokeos-web/src/main/frontend/src/`

---

## Phase 1: Setup

（空——分支 `specs/015-dynamic-management` 已建；零新 Maven 依赖（WatchService 为 JDK 内置，plan 已核）；无脚手架需求。直接进 Foundational。）

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 多 story 共用件——目录管家、编排者骨架（register 防重收口是 US1/US3 共用核心）、生成配置键。

- [x] T001 [P] `AgentStore` + 同任务 TDD 闭环：`yokeos-core/src/main/java/com/yokeos/core/agent/AgentStore.java`（write 建/覆写 `agents/<name>/AGENT.md`、archive 移 `archive/<name>/` 重名后缀 `-yyyyMMdd-HHmmss` 且按需 mkdirs、delete 物理删仅回滚用）与 `yokeos-core/src/test/java/com/yokeos/core/agent/AgentStoreTest.java`（@TempDir 真文件操作三组用例：write 建与覆写 / archive 按需建+重名不覆盖 / delete 物理删）。`mvn -q -pl yokeos-core -am spotless:apply` 后 `mvn -pl yokeos-core -am test` 该类绿
- [x] T002 `AgentLifecycleService` 骨架 + register 组 TDD：`yokeos-core/src/main/java/com/yokeos/core/agent/AgentLifecycleService.java`（构造注入 AgentLoader/ProfileRegistry/AgentScheduler/AgentStore/ProviderService + 生成配置两键；`register(Path)` 防重收口——已有同名先 `unregisterProfile(old)` 再 deriveProfile→register→有 schedules 则 registerProfile；get/list 直通）与 `AgentLifecycleServiceTest.java` register 组用例（防重先注销后注册 FR-016 / create 与 watcher 同段 register 的方法即此 / 带 schedules 注册定时）。core 模块测试绿
- [x] T003 [P] 生成配置键落 `yokeos-boot/src/main/resources/application.yaml`（`yokeos.agent-generation.provider: deepseek` + `model: deepseek-chat`，注释注明「缺失不阻断启动、调用时 503，技 §3.3」）；不进 ConfigLoader 启动校验（运行时功能非启动依赖）

**Checkpoint**: 目录管家与编排者 register 段就绪、配置键在位——US1/US2/US3 可开工。

---

## Phase 3: User Story 1 - API 建管 Agent，全程免重启 (Priority: P1) 🎯 MVP

**Goal**: POST/GET/GET{name}/PUT/DELETE 五端点——冲突零写入、注册失败回滚、删除时序、更新先注销后注册。

**Independent Test**: `mvn -pl yokeos-core,yokeos-web -am test` 全绿 + curl 免重启闭环（create → 不重启 GET 即见 → PUT → DELETE → archive/ 实证）。

- [x] T004 [US1] **测试先行**：`AgentLifecycleServiceTest` 扩 lifecycle 组用例——create 按序（写→派生→注册）/ name 冲突第一步拒零写入 / 注册失败回滚已写目录且 `verify(agentScheduler, never()).registerProfile(any())`（坑一）/ delete 按「注销定时→移索引→归档」InOrder（坑二）/ update schedules 变更先 unregister(old) 后 register(updated)（坑三）/ update 与 delete 目标不存在抛 IllegalArgumentException / unregisterByDir 只注销移索引不归档。先跑确认红
- [x] T005 [US1] `AgentLoader` 新增公共方法 `parse(String agentMarkdown, String expectedName)`（字符串→Profile，frontmatter 校验与 `deriveProfile` 同一套、非法抛 `IllegalArgumentException`；analyze H1——generate/update 落盘前校验所需，参照钉版树同款先例，新增非改接口）落 `yokeos-core/src/main/java/com/yokeos/core/profile/AgentLoader.java`；随后 `AgentLifecycleService` 实现 create（exists 拒→write→register→catch 回滚 store.delete）/ update（**先 parse 校验（非法 400 不落盘，旧定义不破坏）→ 过才 write → derive → unregister old → register new**，analyze H1 改序）/ delete（unregister→remove→archive）/ unregisterByDir；红转绿
- [x] T006 [US1] **测试先行**：`AgentApiControllerTest`（26 节存量）扩 5 端点用例——薄转发 lifecycle 恰调一次 / 400（已存在、name 白名单 `[a-zA-Z0-9][a-zA-Z0-9_-]*` ≤64 不匹配、markdown 非法）/ 404（get/put/delete 不存在）/ AgentView 投影含 agentMarkdown 全文 / **既有 invoke 用例零改动零回退**。先跑确认红（新用例）
- [x] T007 [US1] DTO 四件（`AgentView`/`CreateAgentRequest`/`UpdateAgentRequest`，GenerateRequest 占位随 US2）落 `yokeos-web/src/main/java/com/yokeos/web/controller/dto/` + `AgentApiController` 扩 5 端点（Controller 薄三件事纪律；类级 `@SuppressFBWarnings` 扩 justification）；红转绿
- [x] T008 [US1] `YokeosRuntime` 装配 `AgentStore` 与 `AgentLifecycleService` 两 Bean（workspace() 同源取 root；@Value 注入生成配置两键缺省空串）；`mvn -pl yokeos-cli -am test` 绿（既有装配测试零回退）

**Checkpoint**: US1 独立可测——API 建管闭环 + 26 节 invoke 零回退。

---

## Phase 4: User Story 2 - 一句话生成草稿，人在环里 (Priority: P1)

**Goal**: POST /api/v1/agents/generate——草稿原样返回不落盘不注册；围栏剥离；非法 400 可读；配置缺失 503；llm_calls 落账。

**Independent Test**: core 与 web 测试绿 + 真模型冒烟（assumeTrue DEEPSEEK_API_KEY）草稿可解析、`llm_calls` 最新一条 sessionId 前缀 `agent-generation`。

- [x] T009 [US2] **测试先行**：`AgentLifecycleServiceTest` 扩 generate 组用例——正常链（chat 被调一次、草稿返回）/ 不落盘不注册（store 与 registry 零交互）/ 围栏剥离（``` 包裹输出剥后可解析）/ LLM 产出非法抛 IllegalArgumentException 含可读原因 / 配置缺失抛 IllegalStateException（消息含配置键名）/ 空句 400；`AgentApiControllerTest` 扩 /generate 端点用例（200 草稿 / 400 / 503 透传）。先跑确认红
- [x] T010 [US2] `AgentLifecycleService.generate` 实现——配置校验 → 临时 Profile `new Profile("agent-generation", null, null, new Profile.ProviderConfig(provider, model, null), null×7)`（plan 写前核实形态）→ `providerService.chat("agent-generation-" + 序号, profile, new ProviderRequest(AUTHOR_PROMPT + sentence, null))` → `stripCodeFences` 剥围栏 → **`agentLoader.parse(agentMarkdown)` 字符串校验**（analyze H1：不落盘校验，非法 400）→ 返回全文；AUTHOR_PROMPT 常量（约束只输出一份 AGENT.md、frontmatter 必含项、provider.name 用指定名、不加围栏不加解释）；红转绿
- [x] T011 [US2] `GenerateRequest` DTO + `AgentApiController` /generate 端点接线（`{sentence}` → lifecycle.generate → `{agentMarkdown}` 信封）；红转绿

**Checkpoint**: generate→create 闭环可走（草稿直投 T007 的 create 端点）。

---

## Phase 5: User Story 3 - 丢目录即上线 (Priority: P1)

**Goal**: WorkspaceWatcher 实时监听 agents/ 目录级事件，与 API 同段 register；坏目录不拖垮；启动扫描不重复。

**Independent Test**: core 测试绿（handleChange 直调三用例）+ 集成层真丢目录轮询断言（归 T018）。

- [x] T012 [US3] **测试先行**：`yokeos-core/src/test/java/com/yokeos/core/agent/WorkspaceWatcherTest.java` 三用例——handleChange(CREATE 目录) → lifecycle.register 被调 / handleChange(DELETE) → unregisterByDir 被调 / handleChange(坏目录抛 RuntimeException) 不外溢不抛（监听器活着，@TempDir 造目录）。先跑确认红
- [x] T013 [US3] `yokeos-core/src/main/java/com/yokeos/core/agent/WorkspaceWatcher.java` 实现——start()（agents/ 按需建 + newWatchService + register CREATE/MODIFY/DELETE + 循环提交执行器）/ loop()（take 阻塞、InterruptedException 恢复中断位 return、key.reset() false 安静退出）/ handleChange 包级可见（DELETE→unregisterByDir；目录→register；异常 WARN 常量消息+目录名进异常，CRLF 门禁形态）；`YokeosRuntime` 装配 Watcher Bean（initMethod="start"、destroyMethod 关停 + Spring 管理单线程执行器 Bean——research D8，实施若复用更简记偏差）；红转绿

**Checkpoint**: 三录入同源齐——API create / Watcher 事件 / 启动扫描（既有）。

---

## Phase 6: User Story 4 - 工作区只读浏览，防目录穿越 (Priority: P2)

**Goal**: GET /workspace/tree + GET /workspace/file?path=——只读、穿越两形态 400。

**Independent Test**: web 切片测试绿（tree 结构 / file 正常 / 穿越 400 / 404）。

- [x] T014 [US4] **测试先行**：`yokeos-web/src/test/java/com/yokeos/web/controller/WorkspaceApiControllerTest.java`（WebSliceTestBoot + @Import + @MockBean 形态）——tree 返回 agents/archive 结构且 Agent 目录可展开 / file 正常路径 200 全文 / file `?path=../../etc/passwd` 400 / file `?path=/etc/passwd` 绝对路径 400（坑四两形态）/ file 不存在 404。先跑确认红
- [x] T015 [US4] `FileNode` DTO + `yokeos-web/src/main/java/com/yokeos/web/controller/WorkspaceApiController.java`——tree 递归列 agents/ 与 archive/ 两支（FileNode name/path/type/children）；file 解析 `workspaceRoot.resolve(path).normalize()` 断言 `startsWith(root)` 越界 IllegalArgumentException（→400）、Files.readString 不存在 ResourceNotFoundException（→404）、读失败 400 可读原因；零写端点。红转绿

**Checkpoint**: 管理台观察侧数据源就绪（可与 US1 并行开发，此处顺序执行）。

---

## Phase 7: User Story 5 - 管理台两页 (Priority: P2)

**Goal**: Agent 管理页（一句话新建→预览可改→创建→列表→查看/编辑/删除删前确认）+ 工作区页（左树右文只读），复用 yokeos-admin-ui skill 与官网 token。

**Independent Test**: 浏览器走完闭环（quickstart 场景 F）；无命令行介入。

- [x] T016 [P] [US5] Agent 管理页：经 `.claude/skills/yokeos-admin-ui/` 生成/扩页（`yokeos-web/src/main/frontend/src/`，App.vue pages 数组追加）——列表（GET /agents）+ 一句话新建（generate → textarea 可编辑预览含 cron/tools 敏感项提示 + name 输入 → POST create）+ 每行查看/编辑（GET /{name} 回填 agentMarkdown → PUT）/删除（二次确认 → DELETE）；错误区展示 ApiResponse.message
- [x] T017 [P] [US5] 工作区页：同 skill 生成——左侧 FileNode 树（GET /workspace/tree，agents 可展开）+ 点文件右侧只读内容（GET /workspace/file）；无任何写控件

**Checkpoint**: 五 story 全部独立可测；19 端点收口。

---

## Phase 8: Integration Smoke & Polish

- [x] T018 `yokeos-boot/src/test/java/com/yokeos/boot/AgentLifecycleIntegrationTest.java`（@Tag("integration")）——真上下文免重启闭环（POST create → 不重启 GET 列表可见 → PUT → invoke → DELETE → `.yokeos/archive/` 目录实证）/ 真丢目录（cp -r 进 agents/ → 轮询 ≤10s GET 列表出现；rm 后轮询消失）/ generate 真模型（assumeTrue `DEEPSEEK_API_KEY`，草稿可被解析 + **草稿直投 POST create 成功创建**（analyze M1：SC-003 闭环锚）+ llm_calls 前缀断言）；显式触发 `mvn -pl yokeos-boot -am test -Dgroups=integration -DexcludedGroups=` 全绿
- [x] T019 教学文档补图：经 arch-diagram skill 生成 `docs/images/class-030-1.svg`（两条录入路径一段注册代码）与 `class-030-2.svg`（create 编排流程含回滚），回填 `docs/class/030-dynamic-management.md` 图位；quickstart 场景 B/C/E curl 逐条跑通记证据
- [x] T020 收尾门禁：`mvn clean verify` 九模块全绿（**不带 frontend.skip**，坑八）+ 实施中新坑一条一行回填 `CLAUDE.md` 常见陷阱表 + CLAUDE.md「实施节奏」节标注 30 节完成态

---

## Dependencies & Execution Order

### Phase Dependencies

- **Foundational（T001~T003）**: 阻塞全部 story；T001 与 T003 可并行
- **US1（T004~T008）**: 依赖 T001/T002；**MVP 核心**
- **US2（T009~T011）**: 依赖 T002/T003 + US1 的 Controller（T007）
- **US3（T012~T013）**: 依赖 T002（register 段）
- **US4（T014~T015）**: 独立（仅依赖既有 web 基建）——理论可与 US1 并行，本仓单人顺序执行
- **US5（T016~T017）**: 依赖 US1/US2/US4 端点全在；T016/T017 可并行
- **Phase 8**: 依赖全部 story；T018~T020 大体可并行（T020 最后）

### Within Each Story（TDD 纪律）

- 测试任务（T004/T006/T009/T012/T014）先写先跑确认红 → 实现任务转绿 → 下一 story
- 每任务完成即勾 `[x]`；`mvn -pl <模块> -am test` 红了当场修
- 每文件落盘后 `mvn -q -pl <模块> -am spotless:apply`

### Parallel Opportunities

- T001 ∥ T003（不同模块不同文件）；T016 ∥ T017（前端两页独立）

## Implementation Strategy

MVP = Foundational + US1（API 建管免重启）——交付即可演示「调 API 建删改 Agent 不重启」；随后 US2（一句话）、US3（丢目录）补齐三条录入路径，US4/US5 补观察侧，Phase 8 集成与收尾。

## Notes

- 反作弊：不删断言、不 @Disabled、不放宽阈值；测试错则停下报告
- 软门禁命中（改已定字面量/前序公共接口/新依赖等）即停等用户
- 全程不自动 commit/push；合流由人决定
