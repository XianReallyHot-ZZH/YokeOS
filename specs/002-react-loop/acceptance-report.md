# 第 17 节验收报告：ReAct 循环——Agent 的大脑

**Feature**: `specs/002-react-loop` · **Branch**: `specs/017-react-loop` · **日期**: 2026-09-13
**完成判据**: 需求文档 §11 第 17 节行「Agent 一次对话内完成多步任务：思考 → 调 Tool → 观察 → 续推」（拍板⑤口径：由集成冒烟承载）

## 六项证据 DoD

### 1. `mvn clean verify` 全绿

九模块全部 SUCCESS（root reactor，2026-09-13 复跑稳定）：Spotless（google-java-format）+ 阿里 P3C + Checkstyle + SpotBugs/Find Security Bugs + PMD 全链路门禁通过。全仓 81 个测试 0 失败 0 错误 0 跳过，其中本节新增 47 个：

| 模块 | 测试类 | 数 |
|------|--------|---|
| yokeos-core | ReActLoopTest / PromptBuilderTest / ToolExecutorTest / AgentServiceTest / ContextLoaderTest | 8+6+8+5+5 |
| yokeos-provider | SpringAiProviderServiceTest（原 ProviderServiceTest 改名平移 5 + 补映射 3） | 8 |
| yokeos-storage | ToolInvocationRepositoryTest | 4 |
| yokeos-boot | ReActSmokeIntegrationTest（@Tag("integration")，默认排除；真跑见第 6 项） | 1 |

### 2. harness 映射表对号（关键回归逐个过）

教学文档第四部分八测试类全部存在且全绿（见第 3 项 ls 证据）。五坑↔测试逐个钉死：

| 关键回归 | 测试方法 | 断言要点 |
|---------|---------|---------|
| 坑一 死循环兜底 | `modelKeepsRequestingToolsForceStopAtMaxIterations` | `verify(chat, times(10))` 恰好 N 轮一轮不多 + 答复含「达到最大轮数」；另有 `maxIterationsOverrideFromProfileFiveRounds`（配置覆盖 5 轮即停） |
| 坑二 上下文撑爆 | `historyOverLimitTruncatedByTurn` + `truncationKeepsToolResultWithItsTurn` | 超 N 轮整轮被截、恰好 N 轮不截（`historyExactlyLimitNotTruncated`）、工具结果跟住提问轮不撕裂 |
| 坑三 累积留痕 | `everyRoundAccumulatesIntoSession` | 10 轮 assistant + 10 轮 tool 消息一条不少（先累积再判停） |
| 坑四 ThreadLocal 泄漏 | `processThrowsExceptionProfileContextClearedInFinally` | 抛异常后 `ProfileContext.current()` 为 null（finally + remove） |
| 坑五 双执行路径 | `callWithToolSchemaDisablesAutoExecution`（16 节平移）+ `translationCarriesNoExecutionLogic`（16 节既有） | `internalToolExecutionEnabled=FALSE` captor 断言 + 适配器产物 `call()` 抛异常——两道闸随改名平移保持 |
| 重试（拍板③） | `retryableFailsThenSucceedsWithinBackoffRetries` / `retryExhaustedFinalFailureAuditedOnce` / `nonRetryableSingleAttempt` | fail,fail,ok → `verify(tool, times(3))` + 审计恰一条最终态；耗尽一条最终失败；不可重试一次即止 |

### 3. 本节交付物存在性核对

逐项 ls 核对通过（2026-09-13，零缺失；输出存会话记录）：

- **core 六包**：`provider/` 四件（ProviderService 接口、ProviderRequest、ProviderResponse、ToolCallRequest）、`agent/` 五件（ReActLoop、PromptBuilder、ToolExecutor、AgentService、ProfileContext）、`context/ContextLoader`、`session/` 四件（Session、Message、SessionManager、InMemorySessionManager）、`audit/ToolInvocationAuditor`、`tool/`（YokeTool 扩 execute + ToolResult）——全部在场
- **provider**：`SpringAiProviderService.java` 在场；旧 `ProviderService.java` 已消失（git mv 改名，R 状态保历史）
- **tool**：`HttpGetTool.java` 在场
- **storage**：`ToolInvocation` / `ToolInvocationRepository` / `JpaToolInvocationAuditor` 三件在场
- **配置/表零新增**：`git diff master --stat` 对 `db/` 与 boot resources 无输出（与交付物清单「无新配置键 / 无新表」一致）
- **验证口径**：`grep "com.yokeos.provider" yokeos-core/src/main` 0 命中 + core pom 无 spring-ai 依赖（契约上移零泄漏）
- **H3 证据**：plan 期 `javap` 实证全套（`new Prompt(String)`、`getResult().getOutput().getToolCalls()`、`ToolCall(id,type,name,arguments)`、`Content.getText()`——research D2），实现照签名落笔，实现期零「核实不到」停点

### 4. 前序测试回归绿

第 16 节全部用例随 root `clean verify` 复跑通过：AgentLoaderTest(5)、ProvidersPropertiesTest(3)、ToolSchemaAdapterTest(2)、LlmCallRepositoryTest(3)、StorageAuditDdlTest(2)、InitCommandTest(3)、各 sanity、boot 上下文加载(2)——**契约上移与改名是「零行为变化」的搬移**（16 节断言语义逐条平移：路由不串台 / 点名报错 / 成败双路审计 / 自动执行关闭），外加 SpringAiProviderServiceTest 补 3 个映射用例全绿。地基测试（ConfigLoaderTest 等）同批全绿。

### 5. H4 七条全局不变量自查

| # | 不变量 | 结果 |
|---|--------|------|
| 1 | 涉外 IO 首行过 Sandbox | 留位说明：`HttpGetTool.execute` 与 `ToolExecutor` 各留白名单校验注释位，24 节接线（本节无 Sandbox，Sandbox 拒绝路径将复用 `tool_invocations` `success=false`） |
| 2 | LLM 调用成败都落 `llm_calls` | ✓ 16 节双路审计路径原样复用（平移断言钉死）+ 冒烟真库验证 |
| 3 | grep 无明文 key | ✓ 唯一命中为 16 节 `ProvidersPropertiesTest` 的反例夹具（`"sk-plaintext"` 是「明文必须被拒」测试的非法样本），零新增泄漏 |
| 4 | `session_id` 只在 SessionManager 拼接 | ✓ 本节 sessionId 全部由调用方给定（单测自造 / 冒烟固定串），三元组拼接公式未实现（归 18 节） |
| 5 | 无 Reactor/WebFlux/CompletableFuture/自建线程池 | ✓ grep 0 命中（退避为同步 `Thread.sleep`） |
| 6 | 无 Spring AI 自动工具执行路径 | ✓ 三层钉死：`internalToolExecutionEnabled(false)` + 适配器 `call()` 抛异常 + 平移回归断言；`ToolExecutor` 是唯一执行路径（core 侧零 Spring AI 引用，grep 0 命中） |
| 7 | 新触发入口汇入 AgentService.process | ✓ 统一入口本节已立（`process`：查 Profile → set 上下文 → run → save → finally clear）；本节无新触发入口（测试与冒烟承载），CLI 归 18 节、定时归 25 节、Web 归 26 节，届时只加调用方 |

### 6. 剩余人工项——**全部完成，无遗留**

- [x] **真 key 集成冒烟**（2026-09-13 当场真跑）：`mvn -pl yokeos-boot -am test -Dgroups=integration -DexcludedGroups=` → `ReActSmokeIntegrationTest` 通过（9.1s）：真 DeepSeek key + 真 `http_get` 打 open-meteo，多步链路「思考 → 调 Tool → 观察 → 续推」真实走通——答复非空、`tool_invocations` 新增 `success=true` 行、`llm_calls` 有记录，三断言全过
- [x] code review 确认（测不出的两条）：`ReActLoop` 自实现 for 循环、零 Spring AI import（宪法 1）；`ToolExecutor` 唯一执行路径、两道闸原样（宪法 2）——实现期自查 + 变更总结 review 清单载明
- [x] `grep -rE "CompletableFuture|reactor|WebFlux"` 九模块 0 命中（宪法 4）
- [x] 16 节测试全部保持绿（第 4 项）
- [x] 凭证卫生：全仓 grep 零真实命中

## 宪法合规小结

九条逐条过（plan.md Constitution Check 已载，implement 期零违规）。本节是宪法 1（自实现循环）的主角——`ReActLoop.run` 约 20 行调度代码，零框架 Agent 抽象；宪法 2 的执行权唯一性经契约上移加固（core 侧连 Spring AI 类型都不可见，双闸平移钉死）；宪法 7 的 `tool_invocations` 从本节起写入（16 节建表承诺兑现）。P3C 与拍板命名的一处冲突（`SpringAiProviderService` 不以 Impl 结尾）以类级 `@SuppressWarnings("PMD.…")` 显式抑制并在 javadoc 记理由——文档链字面量优先于风格规则，已回填 CLAUDE.md 陷阱表。

## 实施偏差记录（对照 plan/tasks 的裁量，均已在产物中注明）

1. **新增依赖 jackson-databind（core）**——research D7 / spec Assumptions 预告，版本走根 pom jackson-bom
2. **重试纳入本节**——拍板③批准（超出参照课件，技 §4.2/需 §8.2 出处）
3. **`PromptBuilder` 构造补第三参 `knownTools`**——analyze F1（HIGH）修正：contracts 两参签名与 tasks T012「工具候选集构造注入」冲突，修 contracts
4. **P3C `ServiceOrDaoClassShouldEndWithImplRule` 抑制**——计划外：类名拍板①定死，`@SuppressWarnings` 显式抑制 + javadoc 理由（回填陷阱表）
5. **SpotBugs RCN 判空简化**——计划外：1.1.8 的 `getResult()`/`getOutput()` 带 `@NonNull`，防御判空被判冗余，按 API 契约直取（回填陷阱表）
6. **`data-model.md` `result_json` 语义修正**——软门禁③文档链内部冲突：plan 期误写「失败存错误描述」，按技 §9.2 列定义改为「失败为 NULL，原因进 error_message」，实现与测试同步
7. **重试措辞张力采信**——技 §4.2「重试三次」↔ 教学文档「3 次尝试」，采信后者（research D5 记录）

## 方法论对照（对照层，一节一记）

对照 001 的沉淀再证三条：① **「能机器判的绝不留给人」前移有效**——H3 javap 核实从实现期提到 plan 期（research D2 全套实证），实现期零 API 停点、唯一一次签名相关失败（`call(any())` 歧义）是 mock 写法而非依赖未知；② **analyze 在 plan 期同样值得跑**——F1（contracts vs tasks 签名冲突）若漏到实现期就是一次返工，MEDIUM 以上先修的纪律回本；③ **静态语言的 TDD 重心在断言设计**——本节 47 个测试先写后实现，全部一次成型（三次转红均为装配/字面量笔误而非设计变更），「先见红」的价值再次被编译耦合稀释，与 001 观察一致。
