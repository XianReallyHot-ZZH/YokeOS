# 第18节验收报告：CLI——YokeOS 的命令行入口

**日期**: 2026-09-15 | **分支**: `specs/003-cli-entry` | **任务**: 28/28 完成（tasks.md 全勾）

## 六项证据 DoD

### 1. `mvn clean verify` 九模块全绿 ✅

含 Spotless / P3C(PMD) / Checkstyle / SpotBugs / FindSecurityBugs 全部门禁。`BUILD SUCCESS`，测试 **125** 个全过：

```text
yokeos-core     38（AgentLoader 5 / ReActLoop 8 / AgentService 5 / ToolExecutor 8 / PromptBuilder 6 / ContextLoader 5 / sanity 1）
yokeos-provider 14（SpringAiProviderService 8 / ProvidersProperties 3 / ToolSchemaAdapter 2 / sanity 1）
yokeos-storage  24（SessionManager 7 / SessionRepository 5 / JpaToolInvocationReader 3 / LlmCall 3 / ToolInvocation 4 / sanity 2）
yokeos-memory    1 · yokeos-tool 1（sanity，本节未触）
yokeos-channel-cli 10（CliChannel 9 + sanity 1）
yokeos-web       6（本节未触）
yokeos-cli      29（YokeOsCli 3 / YokeosRuntimeAssembly 3 / Status 2 / Profile 6 / ProviderTool 3 / SessionList 3 / ConfigLoader 6 / Init 3）
yokeos-boot      2（YokeosBootApplicationLoadTest——双入口吸收 YokeosRuntime 后仍起）
```

过程中被门禁拦下并修复（规则零排除）：Checkstyle MissingJavadoc×2（构造器 javadoc）、PMD 魔法值×6（yaml 键名/路径/占位符前后缀常量化）、SpotBugs×4（`Path.getFileName` 判空链、classpath 流 try-with-resources、catch 收窄 IOException）。

### 2. 教学文档 harness 11 测试类逐一对号 ✅

| 测试类 | 关键回归（坑↔测试）落地 |
|---|---|
| `SessionManagerTest` | **坑一** `sameTriple_everyGetOrCreateReturnsSameSession`（幂等+channel 隔离）+ 全排列隔离 + id 格式 `cli:wang:weather` + **坑五** `getOrCreateMissPersistsActiveRecord`（agent_name 列断言）+ 恢复历史 + save 刷活跃 + **并发** `concurrentGetOrCreate_sameTriple_singleRow`（M1：4 虚拟线程同发零新增） |
| `SessionRepositoryTest` | 手工脚本建表（schema-002）、三类消息回读零丢失序不变、**坑四** `simulateRestart_historySurvives`（含 M2 扩展：恢复→追加→+1 原序不变）、零消息会话、九列对齐技 §9.2（JDBC metadata） |
| `JpaToolInvocationReaderTest` | 六字段对齐、按会话隔离、成败都在 |
| `CliChannelTest` | **坑三** `eof_exitsWithoutStack` / `blankLine_skippedNotForwarded`、/quit trim、多行转交打印、--message 单条即退、/context 与 /tools 不走引擎（never process）、Profile 不存在点名报错（新增第四协作者启动即验） |
| `YokeOsCliTest` | 12 命令注册（九顶层 + profile 四件 + 三组 list 子命令）+ --help + 未知命令报错非 0 无堆栈 |
| `YokeosRuntimeAssemblyTest` | **坑二** `jpaRepositoriesWired_countGreaterThanZero`（Found 0 即红）+ 全链 Bean + 真实 Bean 会话往返 |
| 五个命令测试类 | 各自主路径含 Edge（create 幂等/delete 归档/库不存在提示/yaml 缺失提示） |

### 3. 「本节交付物」逐项存在性核对 ✅

- **代码**：core `SessionIds`/`SessionManager` 补全/`Session` 恢复构造器/`InMemorySessionManager` 随动/`ToolInvocationRecord`+`ToolInvocationReader`；storage `Session` 实体/`SessionRepository`/`JpaSessionManager`/`JpaToolInvocationReader`/`schema-002-sessions.sql`；channel-cli `CliChannel`；cli `YokeOsCli`+`YokeosRuntime`+8 命令类（InitCommand 接入）；boot yaml schema-locations + pom mainClass 切换——ls 证据 9/9 + 命令目录 8 文件
- **测试**：11 类 10 文件存在（Provider/Tool 合一个测试类，用例分列）
- **表**：`sessions` 九列 + idx_sessions_agent，列名对齐测试钉死
- **配置**：无新配置键（`yokeos.root` 为装配属性缺省 `.yokeos`，非新配置面）

### 4. 前序节回归 ✅

16/17 节全部测试断言零改动全绿（上表 38+14+24 中的 16/17 节部分即证据）。两处声明过的触碰（均「语义零变化」）：

- `AgentServiceTest` 两行断言类型随动（`InMemory.get` 随接口返回 `Optional`：`assertSame(...get().orElseThrow())` / `assertTrue(...get().isEmpty())`——断言语义逐条保留）
- `SpringAiProviderService.buildOptions` **私有方法**补 `model` 逐请求传递（兑现技 §3.3「Profile 层管调用参数」——16/17 节靠冒烟手工装配的 ChatModel 默认 model 兜底，装配面通用化后必须补上；`builder.model(String)` 经 1.1.8 javap 实证；17 节 8 测试全绿）

### 5. H4 七条全局不变量自查 ✅

| # | 不变量 | 结论 |
|---|---|---|
| ① | 涉外 IO 过 Sandbox | 本节无新涉外 IO（CLI 文件/本地库；http_get 17 节交付原样装配，Sandbox 位仍留 24 节） |
| ② | 审计成败都落库 | 未触碰审计链；YokeosRuntime 把双 auditor 装进运行链；真跑证据：`/tools` 显示 http_get 成功行（审计表真实数据） |
| ③ | 无明文 key | grep 零命中（测试哑值 `sk-test-dummy` 已排除）；`/provider list` 不展示 key |
| ④ | session_id 只在一处拼 | `grep '+ ":" +'` 全库唯一命中 `SessionIds.compose`（比 plan 预想的 JpaSessionManager 私有方法更收敛——见实施偏差②） |
| ⑤ | 无异步编程模型 | `grep CompletableFuture/reactor/WebFlux` 九模块零新增；并发用例用 `Thread.ofVirtual` |
| ⑥ | 无 Spring AI 自动执行 | 本节零新增 Spring AI 写法；classpath 无 spring-ai autoconfigure（裸依赖）；`internalToolExecutionEnabled(false)` 路径原样 |
| ⑦ | 新触发入口汇入统一处理入口 | CLI 入口 → `CliChannel` → `AgentService.process`（17 节入口零改动复用）；Web 归 26 节、定时归 25 节 |

### 6. 人工项当场跑完 ✅（fat JAR `yokeos-boot-0.1.0-SNAPSHOT.jar`）

- **真 key 多轮对话**：单条模式「1+1」→「1+1 等于 2」；交互模式「我叫小王」→「你刚才说你叫小王」（跨轮记忆）；单条与交互共享同一条会话（`/context` 显示全部 6 条）
- **/context**：`── 会话上下文（6 条）──` 带序号角色完整可读
- **/tools**：天气问题真调 open-meteo（22.1°C）后显示 `#1 http_get [成功] 1484ms {url…}`——审计只读口全链路
- **会话落库与跨进程恢复**：`session list` 显示 `cli:xianreallyhotzzh:default | default | active | 2026-09-15 09:41`（单条模式与交互模式两进程同一条会话 = 重启恢复的体感证据；机器级恢复另由 `simulateRestart` 钉死）
- **Found 3 JPA repository interfaces**（坑二正面证据，对照改造前基线 "Found 0"——T001 日志在案）
- **轻命令秒回**：`profile list` 冷执行 real 0.74s（零 Spring）；init 幂等两跑零变化
- **12 命令 --help 全 0 退出**；未知命令 Picocli 建议（"Did you mean: yokeos session or yokeos serve?"）；`chat --message "  "` 参数错误 exit=2；无 key 启动点名报错（16 节 validate 路径）
- **剩余项（体感类，无功能缺口）**：serve 的 REST 侧三模式共享存储验证归 26 节端点就位后；隔天口径的真「隔天」由 simulateRestart 机器证据承载

## 实施偏差

1. **依赖补显式四处**（research D7 预告）：cli +`spring-boot-starter`/`snakeyaml`/`sqlite-jdbc`、storage +`jackson-databind`（均无版本号走 BOM）。
2. **id 拼接单点升级为 core `SessionIds`**（contracts 原写 JpaSessionManager 私有方法）：`InMemorySessionManager` 同为实现者也要拼 id——收敛到公共单点两实现共用，H4④ 比原设计更严密；contracts/tasks 口径已同步。
3. **CliChannel 第四协作者 ProfileRegistry**（contracts 原三协作者）：「点名报错不进循环」的落法是启动即验；contracts 已同步。
4. **`SpringAiProviderService.buildOptions` 补 `model` 逐请求传递**：技 §3.3 兑现，见上表第 4 项。
5. **`AgentServiceTest` 两行断言类型随动**（Optional 化，语义保留），见上表第 4 项。
6. **provider/tool/session 三命令为「组 + list 子命令」嵌套形态**：初版平命令 + aliases 在单测全绿、fat JAR 冒烟才暴露 `Unmatched argument: 'list'`（命令语法≠注册名）；已修并回填陷阱表。
7. **`SessionListCommand` 时间格式化**（epoch 毫秒→`yyyy-MM-dd HH:mm`）：展示层修正，真跑发现。
8. **装配测试哑 key 方案未用上**（research D8 预案）：cli 测试 classpath 无 application.yaml → provider 清单空 → validate 天然通过，比预想更干净。
9. **并发回归用例形态调整**（analyze M1 落地）：SQLite BUSY 确定性考虑取「预置后并发命中」；撞键兜底分支留防御实现。已回填陷阱表。
10. **范围口径 = CLI 整节 12 命令**（拍板①）：技 §13 第 18 节行列能力主线，全命令面依据 specs/001 预告 + 参照第 18 节口径。

## 收尾动作

CLAUDE.md 常见陷阱表回填三条（Picocli 组子命令形态 / SQLite 并发 BUSY / 非交互 shell 密钥与多 provider validate 连坐）——第 18 节实证。
