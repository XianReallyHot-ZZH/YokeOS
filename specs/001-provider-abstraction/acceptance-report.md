# 第 16 节验收报告：Provider——对接大模型的统一入口

**Feature**: `specs/001-provider-abstraction` · **Branch**: `specs/001-provider-abstraction` · **日期**: 2026-09-11
**完成判据**: 需求文档 §11 第 16 节行「配置 Provider 与 API key，CLI 发消息拿到 LLM 回复」（演示口径见拍板②：init 就绪 + 真调拿到回复）

## 六项证据 DoD

### 1. `mvn clean verify` 全绿

九模块全部 SUCCESS（root reactor，2026-09-10/11 多轮复跑稳定）：Spotless（google-java-format）+ 阿里 P3C + Checkstyle + SpotBugs/Find Security Bugs + PMD 全链路门禁通过，总时长约 1:07~1:19。新增 21 个单测全绿：

| 模块 | 测试类 | 数 |
|------|--------|---|
| yokeos-core | AgentLoaderTest | 5 |
| yokeos-provider | ProviderServiceTest / ProvidersPropertiesTest / ToolSchemaAdapterTest | 5+3+2 |
| yokeos-storage | LlmCallRepositoryTest | 3 |
| yokeos-cli | InitCommandTest | 3 |

### 2. harness 映射表对号（关键回归逐个过）

教学文档第四部分五测试类全部存在且非空。三个「最值钱」回归（坑↔测试钉死）：

| 关键回归 | 测试方法 | 断言要点 |
|---------|---------|---------|
| 坑一 显式映射（宪法 3） | `routeByNameTwoProvidersNoCrosstalk` | verify 目标 `times(1)`、另一家 `never()` |
| 坑二 自动执行关闭（宪法 2） | `callWithToolSchemaDisablesAutoExecution` | captor 断言 `internalToolExecutionEnabled=FALSE` + schema 携带；另有 `translationCarriesNoExecutionLogic` 断言适配器产物 `call()` 抛异常（第二道闸） |
| 失败审计（宪法 7） | `callFailureAuditsSuccessFalseRecord` | 抛异常**且**审计先落 `success=false`+原因 |

### 3. 本节交付物存在性核对

18 项逐项 ls 核对通过（2026-09-11 脚本执行，零缺失）：core 五类（Profile/AgentLoader/ProfileRegistry/YokeTool/LlmCallAuditor）、provider 四类、storage 三类 + DDL、cli InitCommand、五测试类、`yokeos.providers` 配置段（嵌套 YAML 结构核对）。**H3 证据**：Spring AI 1.1.8 联网解析成功（.m2 从无到有）；关键 API 全部 `javap` 核实后落笔（research D2 实证）。

### 4. 前序测试回归绿

第 16 节为首个代码节，前序 = 工程地基：`ConfigLoaderTest`、`ApiResponseTest`、`GlobalExceptionHandlerTest`、六模块 sanity、boot `YokeosBootApplicationLoadTest`——全部随 root `clean verify` 复跑通过（boot 上下文含新接线的 SQLite datasource + 手工脚本幂等建表）。

### 5. H4 七条全局不变量自查

| # | 不变量 | 结果 |
|---|--------|------|
| 1 | 涉外 IO 首行过 Sandbox | 留位说明：本节无涉外 IO 工具执行（适配器 `call()` 抛异常禁执行），Sandbox 第 24 节接线 |
| 2 | LLM 调用成败都落 `llm_calls` | ✓ 双路审计（成功 token 三项/失败 error_message），单测 + 冒烟真库双重验证 |
| 3 | grep 无明文 key | ✓ 全仓扫描 0 命中（key 只经环境变量） |
| 4 | `session_id` 只在 SessionManager 拼接 | 本节 sessionId 由调用方传参透传，未自行拼接（SessionManager 第 18 节） |
| 5 | 无 Reactor/WebFlux/CompletableFuture/自建线程池 | ✓ grep 0 命中 |
| 6 | 无 Spring AI 自动工具执行路径 | ✓ `internalToolExecutionEnabled(false)` + 适配器 `call()` 抛异常 + 回归测试三层钉死；`ChatClient` 形态 grep 0 命中 |
| 7 | 新触发入口汇入 AgentService.process | 本节无新对话触发入口（`init` 为工作区命令）；ReAct 接线归第 17 节 |

### 6. 剩余人工项——**全部完成，无遗留**

- [x] **真 key 冒烟**（2026-09-11）：`DEEPSEEK_API_KEY=*** mvn -pl yokeos-provider -am test -Dgroups=integration -DexcludedGroups=` → `ProviderSmokeIntegrationTest` 通过：真调 `deepseek-flash` 拿到非空回复，`llm_calls` 新增 `success=true` 行（error_message 为空）
- [x] 凭证卫生：全仓 `grep` 明文 key 0 命中
- [x] `yokeos init` 幂等人工抽查：jshell 真机运行 `EXIT=0`，六目录 + 三 Bootstrap 占位模板齐全，二次运行 diff 零变化
- [x] 可演示成果核对（拍板②口径）：init 就绪 + 真调拿到 LLM 回复 ✓

## 宪法合规小结

九条逐条过（plan.md Constitution Check 已载，implement 期零违规）。宪法 2 的「最容易被写错的一条」当场两次应验并被钉死：starter eager 装配（换裸依赖）与 toolDefinitions 代差（ToolCallback 载体 + 双闸）——均已回填 CLAUDE.md 陷阱表与 research D2。

## 实施偏差记录（对照 plan/tasks 的裁量，均已在产物中注明）

1. `ProviderSmokeIT` → `ProviderSmokeIntegrationTest`（P3C 命名规则 ≤1 连续大写）
2. starter → 裸 `spring-ai-openai`（宪法 2 eager 装配实证）
3. `LlmCallAuditor` 参数 `Usage` → 三显式整数（core 禁引 Spring AI）
4. `chat` 增加 4 参重载（tools 显式传入；3 参字面量签名保留为委托）
5. schema 落点沿用 `db/schema-001-audit.sql`（地基既有惯例，替代任务措辞的 `schema.sql`）
6. 新增依赖：`hibernate-community-dialects`（JPA+SQLite 落地件）、provider 测试域 `spring-jdbc`/`sqlite-jdbc`、`spotbugs-annotations`（误报标注）——均附理由注释
7. boot 接线（datasource + `sql.init` 幂等执行手工脚本）——DDL 头部预告的本节职责

## 方法论对照（对照层，一节一记）

对照 mattpocock 形态（grill → spec → tickets → implement）：本节 clarify 的两问即 grill 角色（先收紧再动工）；tasks 停点比对 ≈ tickets 审；TDD 红→绿在 Java 下以「测试 + 抛异常桩」落地，红的价值被编译耦合稀释——**印证**：静态语言里 harness 先行的重心在「断言设计」而非「先见红」；analyze 抓出 2 处 MEDIUM（与试跑经验一致，再证值得固化进流程）。
