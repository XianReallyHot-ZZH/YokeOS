# Research: 动态管理（第30节）

> Phase 0 产物。教学文档头部 8 项拍板（用户 2026-09-22 批准）+ clarify 2 问已消掉主要歧义，本文记录设计决策的依据与被否备选；代码事实均经写前核实（H3，核实日期 2026-09-22）。

## D1 · 三新类落位 `yokeos-core` agent 包

- **Decision**: `AgentLifecycleService` / `WorkspaceWatcher` / `AgentStore` 落 `com.yokeos.core.agent`（与 `AgentScheduler`/`AgentService` 同包），参照钉版树同位（`io.oryxos.core.agent` 三类同构）。
- **Rationale**: `AgentLifecycleService` 是 `yokeos-web` Controller 调用的跨模块契约——宪法「契约上移、依赖倒置」；core 零 Spring 依赖纪律不破（纯 POJO + 构造注入，同 16/17 节类）。
- **Alternatives**: 落 yokeos-web（Controller 私有 service）——CLI 模式（`yokeos chat`）将来若需动态管理就得反向依赖 web，否；落独立新模块——宪法 9 九模块边界不动，否。

## D2 · register 防重收口（FR-016，clarify Q1 拍板）

- **Decision**: `AgentLifecycleService.register(agentDir)` 开头查注册表：已有同名 Agent → 先 `agentScheduler.unregisterProfile(old)` 再走注册。
- **Rationale**: 25 节 `AgentScheduler.registerProfile` 对同 taskId 是 `scheduledTasks.put` 覆盖句柄**不 cancel 旧排期**（写前核实 `AgentScheduler.java:112-134`）——Watcher MODIFY 路径裸 register 会让旧 cron 与新 cron 并跑且旧句柄失控。29 节场景（仅启动扫描）无暴露面，30 节接入第二录入路径即暴露；编排者一处收口，API/Watcher/未来扩展全部路径安全。
- **Alternatives**: ①改 25 节 `registerProfile` 幂等（cancel 旧的再排）——改前序节公共接口语义，超出本节声明改造点，否；②Watcher 调用方各自防重——逻辑分散易漏，否；③MODIFY 忽略——破坏「拷目录竞态二次收敛」（坑六），否。

## D3 · Watcher 边界：目录级事件 + PUT 显式重注册 + 启动扫描不重复

- **Decision**: Watcher 注册在 `.yokeos/agents/` 监听直接子项（CREATE/MODIFY/DELETE）；更新语义走 PUT 端点显式 `update`（先注销后注册），不指望 Watcher 捕获子目录内文件修改；启动全量扫描仍走 `AgentLoader.loadAll` 既有链路，Watcher 只管启动之后。
- **Rationale**: JDK WatchService 只报注册目录直接子项的事件；子目录内文件改动跨平台不可靠——参照实现回写阶段实证「macOS WatchService 不监听子目录内文件的改动，必须显式重注册」（其 5.2.3 原话）。启动扫描分离避免重复登记（尤其重复排定时）。竞态（cp -r 目录先建、AGENT.md 后落盘）：CREATE 首注册失败 WARN 跳过，子目录 mtime 变化再触发事件二次注册收敛——单测直调 `handleChange` 不依赖真实时序，收敛路径归集成测试轮询断言。
- **Alternatives**: Watcher 递归注册子目录（每 Agent 目录再 register watch）——复杂度与句柄管理成本高，且 PUT 已显式覆盖更新场景，第一阶段否；Watcher 兼做启动扫描——重复登记风险，参照也显式分开，否。

## D4 · generate 调用链：一次 `ProviderService.chat` + 临时 Profile + 独立配置键

- **Decision**: `generate(sentence)` = 配置校验（`@Value("${yokeos.agent-generation.provider:}")` 空串 → `IllegalStateException`，503）→ 构造一次性 Profile `new Profile("agent-generation", null, null, new Profile.ProviderConfig(provider, model, null), null×7)` → `providerService.chat("agent-generation-" + 序号, profile, new ProviderRequest(AUTHOR_PROMPT + sentence, null))` → 剥 ``` 围栏 → `agentLoader` 既有解析校验 → 返回草稿。
- **Rationale**: 复用既有 `ProviderService.chat`（协议转换 + llm_calls 审计天然落账，宪法 2/7）；`ProviderRequest` 无 `of` 工厂、`Profile` 12 参紧凑构造 null 容缺省（写前核实）；model 为空时不传 null 给端点（ProviderConfig.model null 由 `SpringAiProviderService.buildOptions` 按既有逻辑处理——与普通 Agent 缺 model 同路径）。sessionId 前缀 `agent-generation` 仅审计关联用，不经三元组、不进 Session（H4② 不涉）。
- **Alternatives**: 新建独立 ChatModel 直调——绕过审计与显式映射，违宪 2/3，否；session 化（getOrCreate 真会话）——generate 无对话语义，否。

## D5 · create/PUT 收 AGENT.md 全文 + name 白名单（拍板② + clarify Q2）

- **Decision**: `CreateAgentRequest{name, agentMarkdown}` / `UpdateAgentRequest{agentMarkdown}`；name 白名单 `[a-zA-Z0-9][a-zA-Z0-9_-]*` 且 ≤64，不匹配 400 零写入；带附属资源 Agent 走手工丢目录。
- **Rationale**: generate→预览→create 闭环里草稿就是 AGENT.md 全文，直收最顺（结构化字段形态与 generate 能力重复）；name 拼进目录路径且复用为 profileName/taskId 前缀，白名单一处挡住 `/`、`\`、`..`、空格、中文、点。
- **Alternatives**: 结构化字段表单（参照原始课件提过双形态）——两套入参两套校验，且 generate 已产全文，否；黑名单制——漏堵风险，否。

## D6 · workspace 防穿越与只读边界

- **Decision**: `file?path=` 解析为 `workspaceRoot.resolve(path).normalize()`，断言 `startsWith(workspaceRoot.toAbsolutePath().normalize())`，越界 400（`IllegalArgumentException` 既有映射）；`Files.readString` 读文本，不存在 404、读失败 400；tree/file 限定 agents/ 与 archive/ 两支；零写端点。
- **Rationale**: 技 §11.3 钉版形态（normalize + startsWith）；`../` 变形与绝对路径两形态都被归一拦截（绝对路径 resolve 后即自身，startsWith 不成立即 400）。
- **Alternatives**: 走 `FileTools` + `WhitelistSandbox`——Tool 白名单是 Agent 执行面语义（workspace root 粒度），管理台浏览是 web 层只读端点，技 §11.3 未要求挂 Sandbox，且引 yokeos-tool 依赖反而扩大 web 依赖面，否（26 节 ToolApiController 为列 Tool 才依赖 tool 模块，本节无此需）。

## D7 · 归档语义与重名处理

- **Decision**: `AgentStore.archive(name)` 把 `.yokeos/agents/<name>/` 移入 `.yokeos/archive/<name>/`；目标已存在则后缀 `-yyyyMMdd-HHmmss`；archive/ 按需 `mkdirs`。
- **Rationale**: 不物理删（定义与审计可追溯，宪法 7 延伸）；重名不覆盖历史；init 不预建（幂等不覆盖原则不动，CLAUDE.md 工作区结构表维持现状——拍板⑥）。
- **Alternatives**: 物理删——可追溯性丧失，否；UUID 后缀——人读不友好（时间戳可读且天然有序），否。

## D8 · Watcher 执行器形态（宪法 4 例外口径）

- **Decision**: `YokeosRuntime` 装配一个 Spring 管理的单线程执行器（`ThreadPoolTaskScheduler` 复用或单线程 factory executor Bean），`WorkspaceWatcher` Bean `initMethod="start"` 把监听循环提交执行器，`destroyMethod` 关停（shutdown → 监听线程 `take()` 中断 → 恢复中断位退出）。
- **Rationale**: 与 25 节调度池同类的基础设施守护线程（宪法 4 既有例外）；不手工 `new Thread`；生命周期随上下文。
- **Alternatives**: 复用 25 节 `ThreadPoolTaskScheduler` Bean 的 execute——池语义被调度线程与监听线程混用，故障域耦合，plan 阶段倾向独立单线程 executor（实施时若复用更简则记偏差）。

## D9 · 不采参照第五部分演化（拍板①对照记录）

- **Decision**: 参照课件 5.2 四块增补（脚手架 create、generate-files/saveFiles、per-agent 记忆、固定会话、文件可编辑端点）全部不做。
- **Rationale**: YokeOS 技 §7.2 十九端点表与 §11.3 按原始设计拍板；per-agent 记忆与本仓 Memory 架构（MemoryService 门面 + 三档后端）冲突、文件写端点与「工作区只读」钉版冲突。参照演化是其窗口内自由迭代，本仓以自有技术方案为锚（宪法 9「照抄的是设计不是演化路径」；文档链张力修文档优先——教学文档已记）。
- **Alternatives**: 跟随参照演化——端点数突破 19、与技 §7.2/需求 §5.10 全面冲突，需文档链大改且无本仓需求支撑，否。

## D10 · springdoc 与错误码零新增

- **Decision**: 8 个新端点由 springdoc 自动收录（26 节先例，零配置）；错误码全部走 `GlobalExceptionHandler` 既有映射（`IllegalArgumentException`→400 / `ResourceNotFoundException`→404 / `IllegalStateException`→503 / Provider 族→503），零扩展。
- **Rationale**: 26 节四问验收已确认该体系；本节不发明新状态码（FR-014）。
- **Alternatives**: 新增专用异常类（如 `ProfileValidationException`）——29 节已定 `IllegalArgumentException` 形态，再造类型徒增映射，否（拍板⑤）。
