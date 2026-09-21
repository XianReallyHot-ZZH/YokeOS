# Research: Web Service 与管理台第一版（第26节）

Phase 0 产物。决策均标注出处（教学文档拍板①~⑥ + clarify 2026-09-21 + 参照钉版树 commit f40a467 实证）；实现期待核实项按 H3 纪律（核实不到不写，停下报告）。

## D1 端点范围分节口径：本节 11 端点，写侧与工作区归 29/30

- **Decision**: 本节交付 11 端点（会话 5 含列表、invoke 1、信息查询 3、系统状态 2）+ 只读五页管理台；Agent 写侧 6 端点（generate/CRUD）与工作区 2 端点及对应页面归 29/30 节正题。技 §13 行 26「18 个端点 + 管理台第一版（含 Agent 管理页与工作区页）」随本节修订为分节口径。
- **Rationale**: 文档链内部冲突（技 §13 行 26 与行 29/30、需求 §11 行 30 直接矛盾），用户拍板①取参照切法——参照 26 节 commit f40a467 实际交付即 11 端点 + 只读五页，其 `AgentApiController` javadoc 明写「29/30 节在本 Controller 上加 CRUD——本节只此一个端点」；运行时注册（29）与一句话生成（30）是写侧端点的必要前置，提前即掏空后两节。
- **Alternatives considered**: 字面执行技 §13 行 26 全 18 端点——被拒：需把 AgentLifecycleService/运行时注册/一句话生成全部提前，与课节序「顺序不乱」相悖（拍板记录）。

## D2 会话列表端点补位：GET /api/v1/sessions 为第 19 端点

- **Decision**: 补 `GET /api/v1/sessions`（最近 ≤100 条摘要 + `?status=` 过滤）为第一阶段第 19 个端点；需求 §5.10、技 §7.2、CLAUDE.md 同步 18→19。
- **Rationale**: 管理台「会话」观察页需要列表数据，18 端点清单无此端点；参照钉版树有同款（上游赢，拍板②）。参照控制器实证：列表带 `?status` 过滤、上限 100。
- **Alternatives considered**: 不补、会话页降级为按 sessionId 手输查询——被拒：观察页体验残废，且上游已验证该端点的必要性。

## D3 invoke 无状态落法：每次唯一 user 的一次性会话

- **Decision**: `POST /agents/{name}/invoke` 每次调用以 channel=`invoke` + 每次唯一的 user 生成一次性会话，跑完保留落库（审计可查）但下次不复用；「无状态」= 不携带历史，回归测试锚「连续两次 invoke 第二次不含第一次消息」。
- **Rationale**: 参照钉版树用固定三元组 `getOrCreate("invoke","default",name)`——同一 Agent 多次 invoke 共享历史，与其自家「无状态调用」文档口径相悖（代码实证）；需求 §5.10 明文「无状态调用」。宪法 9 瑕疵不继承。
- **Alternatives considered**: 照抄参照固定三元组——被拒：违背需求语义；会话 id 公式加代数因子支持「归档后新开」——被拒：动 H4④ 单点拼接公式，影响面远超本节。

## D4 归档语义：标记不终结（clarify 2026-09-21，Option A）

- **Decision**: DELETE 归档仅置 `status=archived` + `archived_at`；同三元组 `getOrCreate` 幂等返回原会话（历史保留、状态不变），发消息不查状态；列表按 `?status=` 过滤。「终结后重开」若未来有真实需求走显式 unarchive 端点（扩展阶段）。
- **Rationale**: 与 18 节「同一三元组幂等返回同一条（含已恢复历史）」契约字面一致，实现零改造（`getOrCreate` 现状即 `findById` 不过滤 status，代码实证）；PK=三元组拼接决定了「同三元组新开一条」必须改拼接公式，不做。
- **Alternatives considered**: getOrCreate 命中 archived 置回 active（隐式复活）——被拒：用户无感知、语义隐晦；archived 拒收新消息——被拒：无解归档端点即死路。

## D5 /info 的 Provider 状态：已配置口径

- **Decision**: `/info` 返回运行信息 + 已加载 Profile 引用到的 provider 名（去重排序）；不做 live 探活。
- **Rationale**: 拍板④——探活会真调 API 花钱、把外部可用性变成自家状态页的可用性；参照同款（其 SystemApiController javadoc 明文「核心阶段不做 live 探活——连通性以已配置为准」）。
- **Alternatives considered**: live 探活每 provider 一次轻量调用——被拒（上述）；列全局清单全部 provider——被弃：口径混入未使用项，「已加载 Profile 引用」更真实。

## D6 Tool 列表依赖方向：yokeos-web 直依 yokeos-tool（不采 Map 快照）

- **Decision**: yokeos-web 增补对 yokeos-tool 的模块依赖，`ToolApiController` 注入 `ToolRegistry` 列 `all()`。
- **Rationale**: `ToolRegistry` 是含 MCP 动态注册的运行时真相源；参照为让 web 不依赖 tool 模块改注入 `@Qualifier("tools") Map<String, OryxTool>` 快照 Bean——快照在装配期固化，与注册表可能偏离。web 是顶层消费者（与 boot 同位），依赖方向合法无循环。
- **Alternatives considered**: 参照的 `@Qualifier Map` 方案——被拒（真相源唯一）；在 core 造 ToolInfoProvider 间接层——被拒：无语义增量，徒增一层。

## D7 管理台风格：钉 website token + 项目内 skill（拍板③）

- **Decision**: 管理台视觉钉本仓 `website/.vitepress/theme/custom.css` 的 `--yoke-*` 变量（深蓝底 `#0b1220`/`#111b31`/`#16223d`、边框 `#1e2c4a`、文字三档 `#e6eaf2`/`#97a3bc`/`#5c6a85`、品牌蓝 `#4f7cff` hover `#6b90ff`、琥珀 `#f5a623`、Inter + JetBrains Mono），固化 `.claude/skills/yokeos-admin-ui/SKILL.md`（token + 工程约定 + 三态/响应式规范 + 验收清单）；本节生成管理台、30 节加页都调它。
- **Rationale**: 拍板③，参照同款（oryxos-admin-ui skill，其 26 节 commit 交付、30 节复用）；token 从官网 CSS 直取保证同源可复现。
- **Alternatives considered**: 风格散写进提示词——被拒：30 节复用时靠人肉对齐；引外部 UI skill——被拒：带不来「与官网一致」，真正决定一致性的是项目自己的 token。

## D8 前端构建串联：frontend-maven-plugin 绑 generate-resources

- **Decision**: frontend-maven-plugin 1.15.1 三 execution（install-node-and-npm → npm install → npm run build）绑 `generate-resources` 阶段，`workingDirectory=src/main/frontend`、`installDirectory=target`、`nodeVersion=v20.18.0`；`frontend.skip` 属性默认 false（逃生门 `-Dfrontend.skip=true`）；`.gitignore` 补 `node_modules/` 与产物。
- **Rationale**: CLAUDE.md 技术栈表钉版（Vue 3 + Vite 经 frontend-maven-plugin Node v20.18.0，第 26 节落地）；参照同款配置逐字段对齐（f40a467 pom 实证）；绑 generate-resources 早于 process-resources，一条 `mvn package` 出全量 fat JAR。
- **Alternatives considered**: 前端单独 npm build 后再 mvn package——被拒：两步构建破坏「一条命令」部署单元承诺（31 节 Demo 根）。
- **待核实（H3，实现第一步）**: 本机/CI 可达 npm registry 与 Node 下载源；首跑下载 Node 耗时属预期。

## D9 eager 装配坑：16 节已化解，本节无需 exclude

- **Decision**: 不加任何 `spring.autoconfigure.exclude`。参照 26 节的最大启动坑（`OpenAiAutoConfiguration` eager 索要 `spring.ai.openai.api-key` 致 serve 起不来）在本仓不存在——16 节起即用裸 `spring-ai-openai` 依赖 + 手工构造 ChatModel（CLAUDE.md 陷阱表第 2 条，yokeos-provider pom 注释实证）。
- **Rationale**: 坑预埋期已拆；25 节真上下文 E2E（SchedulerEndToEndIntegrationTest 等）持续验证 boot 上下文无 eager 装配问题。
- **Alternatives considered**: 照参照课件加 exclude——被拒：无病吃药，且会在配置里留下误导性注释。

## D10 60 秒超时口径：504 映射占位，真实超时由 provider 层承载

- **Decision**: 交付 `AgentTimeoutException` + 504 映射（含测试）；不在 Web 层造硬中断——同步 + 虚拟线程模型（宪法 4）下硬中断需异步包装或线程 interrupt，不做。真实超时来源 = provider 调用超时（17 节已收紧 retry/超时），失败以 503/500 语义传导。
- **Rationale**: 参照同款——其 `AgentTimeoutException` 亦无生产抛出点（钉版树 grep 实证：仅异常类 + handler + 测试抛出），纯口径占位；「结构照抄」包含这个诚实口径。
- **Alternatives considered**: CompletableFuture+orTimeout 造 60s 硬超时——被拒：宪法 4 明文禁区。
