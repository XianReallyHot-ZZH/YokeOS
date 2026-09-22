# Tasks: 插件化 Agent——一个目录定义一个会自己跑的 Agent（第29节）

## Format: `[ID] [P?] [Story] Description`

> TDD 纪律（用户指令）：测试任务先于或伴随对应实现任务（验收 harness 先行），实现与测试在同一任务内闭环、
> 该模块测试红了当场修；集成冒烟单列。测试方法名用英文，教学文档语义以 `@DisplayName` 保留。
> 拆解依据：plan.md 模块落位（收口节只触 yokeos-core）、contracts/java-api.md 三契约、
> research.md D1~D8 决策、教学文档第四部分 harness 清单与 2.6 坑表。

## Path Conventions

- 源码：`yokeos-core/src/main/java/com/yokeos/core/{context,profile,agent}/…`
- 测试：`yokeos-core/src/test/java/com/yokeos/core/{context,profile,agent}/…`
- 测试资源（fixture）：`yokeos-core/src/test/resources/fixture/029/workspace-example/`
- 单模块构建必带 `-am`（reactor）；每文件落盘后 `mvn -q -pl yokeos-core spotless:apply`

## Phase 1: Setup (Shared Infrastructure)

- [x] T001 跑 `mvn -pl yokeos-core -am dependency:resolve` 冒烟——本节零新坐标，核实 reactor 可解析即可（H3 纪律照走；失败即软门禁停下报告，不降级不加依赖）

## Phase 2: Foundational (Blocking Prerequisites)

**测试基建先行：fixture 是三个 Story 测试类与人工演示的共享输入。**

- [x] T002 [P] 落示例 Agent 目录四件套于 `yokeos-core/src/test/resources/fixture/029/workspace-example/`：`agents/daily-reconcile/AGENT.md`（frontmatter：`skills: [report-format]` 按名引用、`schedules` 一条 `id: reconcile-morning` cron `0 0 9 * * *` zone Asia/Shanghai、`notify.channels` webhook `url: ${OPS_WEBHOOK_URL}` 占位；正文 = 四步任务编排——跑脚本拿 JSON → 判断 → 按已注入技能规范写报告、拿不准读 REFERENCE.md → 推送+记忆）、`agents/daily-reconcile/REFERENCE.md`（字段对照 + 已知可接受差异 + 升级联系人，语义照参照课件 §1.4）、`agents/daily-reconcile/scripts/reconcile.py`（纯标准库 CSV 比对、环境变量 `RECON_ORDERS_CSV`/`RECON_SETTLE_CSV`、输出差异 JSON）、`skills/report-format/SKILL.md`（agentskills.io 兼容 frontmatter `name`/`description` + P0/P1/P2 分级组稿规范正文，教学文档 §1.3 已给全文）——research D4；`grep -rn "sk-" fixture/` 零命中（凭证卫生）

## Phase 3: User Story 1 - 丢一个目录即上线 (Priority: P1) 🎯 MVP

**目标**：扫描→派生→注册→定时注册的既有机制（16/20/25 节）被 harness 钉死成断言——US1 无主代码任务（FR6 零改动），测试即固化。
**独立判据**：`mvn -pl yokeos-core -am test` 中 `AgentScanRegisterTest` 全绿。

- [x] T003 [US1] 写 `yokeos-core/src/test/java/com/yokeos/core/profile/AgentScanRegisterTest.java`（fixture 复制到 `@TempDir` + 内联目录互补；`@DisplayName` 保留教学语义）：`scanYieldsExactlyNAgents`（放 3 个合法目录恰得 3 个 Agent、名字一一对应不多不少）、`scheduledAgentCarriesScheduleAndReachesScheduler`（带 `schedules` 的 Profile 携带定时 + verify `AgentScheduler.registerProfile` 被调；不带的零调用）、`brokenDirSkippedAudibly`（坏目录——缺 AGENT.md / 非法 frontmatter——跳过且其余照常注册）——既有机制预期直接绿，红了当场修（修的是前序节回归，不是本节新码）

## Phase 4: User Story 2 - 点名 Skill 注入 (Priority: P1)

**目标**：ContextLoader 兑现 17 节留位——点名公共 Skill 正文整段注入 system prompt（本节核心增量）。
**独立判据**：`SkillInjectionTest` 全绿（坑①②③④ + 即时生效各守点）。

### Tests for User Story 2

- [x] T004 [P] [US2] 写 `yokeos-core/src/test/java/com/yokeos/core/context/SkillInjectionTest.java`（`@TempDir` 造 `.yokeos/skills/` 与 `agents/`；`@DisplayName` 保留教学语义）：`referencedSkillInjectedOthersSkipped`（库放两条点名一条：点名段带 `## 技能（名）` 段头注入、未点名内容零出现、`name:` frontmatter 字样零出现、Skill 段在 AGENT.md 正文之前——坑②③④一条用例钉死）、`missingSkillWarnsAndSkipsWithoutBlocking`（点名不存在 → 该条跳过、其余段照常注入、组装不抛——坑①）、`skillWithoutFrontmatterInjectedAsIs`（SKILL.md 缺围栏 → 按无 frontmatter 处理、正文原样注入——spec Edge Case，analyze F1）、`editSkillTakesEffectWithoutRestart`（改 SKILL.md 下次组装生效——零缓存）——预期红（appendSkills 未实现）

### Implementation for User Story 2

- [x] T005 [US2] 实现 `ContextLoader.appendSkills` 于 `yokeos-core/src/main/java/com/yokeos/core/context/ContextLoader.java`：`loadSystemPrompt` 在 Bootstrap 后、AGENT.md 正文前接入；逐名读 `workspace/skills/<名>/SKILL.md` → 剥 frontmatter（复用既有 `stripFrontmatter`，research D1）→ `## 技能（<名>）` 段头 + 正文整段；按声明序（重复声明原样，research D7）；路径非常规文件 → WARN 有痕跳过（编译期常量消息 + Skill 名进异常堆栈，research D2）；读失败 → 抛 `UncheckedIOException`（research D3）；Agent 目录内 `skills/` 子目录不读（research D8）；javadoc 留位注释兑现更新 → T004 绿
- [x] T006 [US2] `mvn -pl yokeos-core -am test` 全绿（含 17 节存量 `ContextLoaderTest` 回归——组装序新增 Skill 段不得破坏既有断言），红了当场修

## Phase 5: User Story 3 - 附属资源按需披露 (Priority: P2)

**目标**：常驻层边界钉死——正文 + 点名 Skill 之外零预载。
**独立判据**：`ProgressiveDisclosureTest` 全绿（在 T005 之后跑，边界断言才有效力）。

- [x] T007 [US3] 写 `yokeos-core/src/test/java/com/yokeos/core/context/ProgressiveDisclosureTest.java`（fixture 复制或 `@TempDir` 内联；标记串断言）：`bodyInjectedSubResourcesNotPreloaded`（AGENT.md 正文进 system prompt；`REFERENCE.md` 与 `scripts/reconcile.py` 内容各含标记串、零出现）、`agentDirSkillsSubdirNotInjected`（Agent 目录内 `skills/` 子目录内容不注入——公共库唯一来源，research D8）、`bodyEditTakesEffectWithoutRestart`（改正文下次组装生效）——在 T005 之后执行，红了当场修

## Phase 6: User Story 4 - 运行时注册原语 (Priority: P2)

**目标**：30 节地基——registry `exists`/`remove`（幂等）+ scheduler `unregisterProfile`（cancel(false) + 句柄移除）。
**独立判据**：`ProfileRegistryRuntimeTest` 与 `AgentSchedulerUnregisterTest` 全绿。

### Tests for User Story 4

- [x] T008 [P] [US4] 写 `yokeos-core/src/test/java/com/yokeos/core/profile/ProfileRegistryRuntimeTest.java`：`registerThenGetVisibleImmediately`（register 后 get/exists 立即可见）、`removeIdempotentAndInvisibleAfterwards`（remove 后 exists=false 且 get 空、重复 remove 返回 false）、`registerSameNameLastWins`（同名两次 register 后到者可见——29 节定夺）、`runtimeAndStartupShareValidation`（`AgentLoader.deriveProfile` 同源校验：同一非法 frontmatter 两次派生断言同一异常类型 + 同一消息）——exists/remove 未实现预期红
- [x] T009 [P] [US4] 写 `yokeos-core/src/test/java/com/yokeos/core/agent/AgentSchedulerUnregisterTest.java`（mock `TaskScheduler` 返回 mock `ScheduledFuture`，构造器签名照 25 节既有）：`unregisterCancelsHandleWithoutInterrupt`（注销后 verify `future.cancel(false)` 且句柄移除——坑⑤⑥）、`taskIdSharesDerivationWithRegister`（注册与注销按同一 `{profileName}:{id}` 命中同一条句柄——可用不同 id 互不误伤反证）、`unregisterWithoutSchedulesIsNoOp`（无 schedules 空跑不报错）、`doubleUnregisterStaysSilent`（重复注销静默无异常）——预期红

### Implementation for User Story 4

- [x] T010 [US4] 实现 `ProfileRegistry.exists(String)` / `remove(String)`（幂等返回 boolean）于 `yokeos-core/src/main/java/com/yokeos/core/profile/ProfileRegistry.java` + javadoc 落两笔：同名后到覆盖定夺（30 节 PUT 基础）、运行时方法与启动扫描同一段代码（contracts/java-api.md）→ T008 绿
- [x] T011 [US4] 实现 `AgentScheduler.unregisterProfile(Profile)` 于 `yokeos-core/src/main/java/com/yokeos/core/agent/AgentScheduler.java`：taskId 派生从 `registerProfile` 循环体抽私有共用方法（`{profileName}:{id}` 同一函数，research D6——严禁抄第二份）；逐条 `scheduledTasks.remove(taskId)` → 句柄存在则 `cancel(false)`（不打断执行中，research D5）；句柄不存在静默；不联动 registry、不写表（contracts 非契约）→ T009 绿

## Phase 7: Polish & Cross-Cutting Concerns

- [x] T012 收尾门禁：`mvn clean verify` 九模块全绿（贴关键输出与测试数）；H4 不变量 grep——`grep -rn "Skill" yokeos-tool/src/main/java/` 零命中（宪法 8：Skill 不进 tool 模块）、fixture 凭证零明文复核；quickstart.md 场景一/二记录输出、场景三~五人工项清单移交验收报告

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 → Phase 2**：依赖冒烟先行（失败即停）；fixture（T002）阻塞 T003/T004/T007
- **Phase 3（US1）**：只依赖 T002——与 Phase 4/6 并行可行
- **Phase 4（US2）**：T004 → T005 → T006 串行（红→绿→回归）
- **Phase 5（US3）**：依赖 T005（appendSkills 在位后「不注入」断言才有效力）
- **Phase 6（US4）**：T008→T010、T009→T011 两对独立，与 Phase 4/5 并行可行
- **Phase 7**：全部完成后收尾

### Parallel Opportunities

- T002 落盘后：T003 / T004 / T008+T009 三路可并行（不同包不同文件）
- T010 与 T011 可并行（registry 与 scheduler 两文件）

### MVP Scope

Phase 1~4（US1 + US2）：扫描注册机制固化 + 点名注入核心增量——两者合成「丢一个目录即上线」的可演示闭环（需求 §11 行 29 可演示成果）。US3/US4 为边界守点与 30 节地基，随后补齐。

## Notes

- 全任务只触 `yokeos-core`（主代码 3 类小改 + 测试 5 类 + fixture）；`AgentLoader` / `YokeosRuntime` / `PromptBuilder` / `ReAct` 零改动（FR6）
- 测试类名 ≤1 连续大写；方法名英文 camelCase 段内第二字符不得大写（28 节细化坑）；日志断言走行为断言（跳过 + 其余照常），WARN 形态由 review 把关
- 每任务完成即勾 `[x]`；红了的处置原则：US2/US4 红是 TDD 正常态（实现补上即绿），US1/US3 红是前序回归（当场修并记入验收报告）
