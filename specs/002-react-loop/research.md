# Research: ReAct 循环——Agent 的大脑（第17节）

Phase 0 产物。决策均标注出处；Spring AI 写法实证已在本阶段用 `javap` 现场核实（总纪律：能机器判的绝不留给人），不留实现期悬念。

## D1 契约上移：core 立中性 Provider 协议（方案 A，修正 001 D5）

- **Decision**: core 新增 `com.yokeos.core.provider` 包——`ProviderService` 接口（`chat(String sessionId, Profile profile, ProviderRequest request)` → `ProviderResponse`）+ 三值对象 `ProviderRequest`（promptText + `List<YokeTool>` availableTools）/ `ProviderResponse`（text + `List<ToolCallRequest>` + `hasToolCalls()`）/ `ToolCallRequest`（name + argumentsJson）。16 节 `ProviderService` 具体类改名 `SpringAiProviderService` 并 implements 该接口：`ProviderRequest` → 内部 `Prompt`、`ChatResponse` → `ProviderResponse`，显式映射与 `LlmCall` 审计路径原样复用。**`ProviderResponse` 不带 Usage**——token 审计在实现体内部闭环（参照带 Usage 是因其 auditor 接口收 Usage 对象；本仓 `LlmCallAuditor` 收 `Integer×3`，无此需要）。
- **Rationale**: `ReActLoop` 落 core（技 §10 模块表）而 core 禁引 Spring AI 类型；16 节签名进出 `Prompt`/`ChatResponse`（001 research D5 的决策）使 provider→core 依赖之外再添 core→provider 即循环依赖。解法即契约上移——参照库 specs/002 `contracts/react-loop.md` D1 同款先例（「签名逐字保真、实现体零改动」）；本仓差异：16 节签名含 Spring AI 类型，上移 = 搬家 + 换型纠偏，实现体不能「一行不动」。用户拍板①（2026-09-13）。
- **Alternatives considered**: ①循环落 provider 模块——被拒：模块表钉死 core，且 CLI/Web/定时三入口都在 core 侧消费；②core 引 Spring AI 类型——被拒：core 禁引 Spring AI 是成文模块规则，契约上移正是为守住它；③16 节不动、core 里再包一层——被拒：两套 ProviderService 并存必乱。
- **验收口径**: 16 节全部测试随改名平移后保持绿（路由不串台 / 点名报错 / 成败双路审计 / 自动执行关闭断言语义逐条保留）；`grep "com.yokeos.provider" yokeos-core/src/main` 零命中；core pom 无 spring-ai 依赖。

## D2 Spring AI 1.1.8 提取与构造写法（javap 实证，2026-09-13 plan 期完成）

- **Decision**: `SpringAiProviderService` 的双向映射按以下已核实签名落笔：
  - **构造**: `new Prompt(String contents)` 存在——`ProviderRequest.promptText()` 单段文本直构（spring-ai-model jar `org.springframework.ai.chat.prompt.Prompt`）。
  - **text 提取**: `response.getResult().getOutput().getText()`——`Generation.getOutput()` 返回 `AssistantMessage`，`getText()` 来自 `Content` 接口（spring-ai-commons）。
  - **toolCalls 提取**: `response.getResult().getOutput().getToolCalls()` → `List<AssistantMessage.ToolCall>`；`ToolCall` 为 record `(String id, String type, String name, String arguments)`——`name()` / `arguments()`（String，即 JSON 文本）逐项映射 `ToolCallRequest`；`AssistantMessage.hasToolCalls()` 便捷判断存在，可实现 `hasToolCalls()` 不依赖列表判空。
- **Rationale**: 代差纪律（参照 1.0.0-M6 ↔ 本仓 1.1.8）在本仓的既定处理方式即 javap 核实（001 research D2 + CLAUDE.md 陷阱表）；本节把核实提前到 plan 期——签名进 `contracts/java-api.md` 时已是实证产物，实现期照抄即可。
- **实证环境**: 本地仓库 `D:\Developer\DeveloperInstall\maven-repo`（注意非默认 `~/.m2`），`spring-ai-model-1.1.8.jar` / `spring-ai-commons-1.1.8.jar`；`javap -p -classpath` 全量输出已在会话记录。
- **Alternatives considered**: 留到实现期核实（001 做法）——被拒：plan 已能机器判，留白只增风险；照抄课件 M6 写法——被拒：代差即坑（001 D2 已实证 1.1.8 无静态 toolDefinitions）。

## D3 Session / Message 消息模型：class 而非 record，role 用 String

- **Decision**: `Session` 为普通 final class（非 record）——`sessionId` / `profileName` 构造期定死，内部 `List<Message>` 可变累积；累积只走 append 三兄弟 `appendUser(String)` / `appendAssistant(ProviderResponse)`（text 为 null 按空串） / `appendToolResult(String toolName, ToolResult)`（成功存 content、失败存错误描述），`messages()` 访问器返回快照。`Message` 为 record `(String role, String content, String toolName)`，role 取 `user` / `assistant` / `tool` 三值（String 不 enum）。
- **Rationale**: record 不可变，与「按序累积」天然冲突（每 append 重建全列表是反模式）；role 用 String 与 OpenAI 协议消息形态一致，18 节 JSON 序列化零转换、`toolName` 仅 tool 角色非空（三元记录，教学文档逐字）。`SessionManager` 接口本节仅 `save(Session)`，`InMemorySessionManager` 按 sessionId 存 `ConcurrentHashMap`——`session_id` 三元组拼接公式与 `getOrCreate` 归 18 节（技 §13「Session 内存版」）。
- **Alternatives considered**: ①record + 每次新建——被拒：累积语义变拷贝语义，历史长了 O(n²)；②role 用 enum——被拒：多一层序列化映射，18 节落地时还得拆；③本节就把 session_id 公式做了——被拒：技 §13 明确归 18 节，提前做是范围蔓延。

## D4 ContextLoader 读源与字面量定死（零缓存）

- **Decision**: `ContextLoader(Path workspace)` 构造持工作区根；`String loadSystemPrompt(Profile profile)` 每次现读三处：①`identity.prompt`（内存，来自 Profile）；②Bootstrap——按 Profile `bootstrap` 列表（缺省三项全取）从 workspace 根读文件，**固定相对序** AGENTS.md → SOUL.md → USER.md（列表只能裁剪不能乱序），每段前带角色 header；③`AGENT.md` 正文——从 `workspace/agents/<profile.name>/AGENT.md` 现读去 frontmatter（Agent 名 = 目录名，宪法 8；Profile 不带路径字段，按名定位）。Skill 正文位留注释（29 节）。角色 header 字面量三条定死：`## 项目约定（AGENTS.md）` / `## 人格定义（SOUL.md）` / `## 用户偏好（USER.md）`。
- **Rationale**: 零缓存（技 §8.3「每次组装 prompt 时重新加载」）意味着不能依赖 16 节 `AgentLoader` 加载时的任何中间产物——Profile 只进内存的是 frontmatter，正文必须现读。AGENTS.md 的 header 字面量出自技 §8.3 逐字；SOUL/USER 两条按 CLAUDE.md 工作区结构行的定位语补全（「默认 agent 人格定义」/「用户偏好」）——教学文档只给了 AGENTS.md 一条例子，此处补齐为测试断言用的完整字面量组。错误纪律：Bootstrap 缺失 WARN 跳过不阻断、读 IO 失败显式抛错（「人格悄悄丢了」是最难查的软故障，教学文档坑二段）。
- **Alternatives considered**: ①Profile 加 agentDir 字段——被拒：改 16 节公共 record，超出拍板①授权的改造面（软门禁④）；②缓存 + 失效机制——被拒：技 §8.3 显式「不做任何缓存」；③frontmatter 剥离复用 AgentLoader——被拒：加载期解析结果不留存（零缓存），ContextLoader 自带最小剥离（`---` 首对围栏切分）。

## D5 ToolExecutor 重试形态：指数退避、3 次尝试、审计最终态一条

- **Decision**: `ToolExecutor(Map<String, YokeTool> tools, ToolInvocationAuditor auditor, long retryBackoffBaseMs)` 构造注入退避基值（**测试传 0，不赌真实时钟**）；可重试失败（`retryable=true`）按 `baseMs << attempt` 指数退避，**总尝试次数上限 3**（首次 + 2 重试，常量——「无新配置键」是交付物清单明确边界）；不可重试失败（未注册工具名、坏 JSON、`retryable=false`）一次即止。审计粒度：一次工具调用请求落**一条最终态**（先完成执行含重试、后落账、再还结果）；`tool_invocations` 的 `duration_ms` 覆盖全部尝试总耗时。执行序列：解析 argumentsJson →〔Sandbox 检查位：24 节接线，本节注释〕→ 执行 →（重试）→ 审计 → 还结果；工具抛 `RuntimeException` 转 `ToolResult.error(带原因, retryable=false)` 不上抛不炸循环。
- **Rationale**: 技 §4.2「默认指数退避最多重试三次」与教学文档「默认最多 3 次尝试」措辞存在张力（重试三次 = 4 次 vs 3 次尝试 = 3 次）；以教学文档（2026-09-13 用户定稿语料）为准取 **3 次尝试**，测试断言 `verify(tool, times(3))`。「重试纳入本节」超出参照课件范围（拍板③批准，验收报告记实施偏差）。退避用同步 `Thread.sleep`（宪法 4 例外清单里没有它，它就是同步阻塞形态）。
- **Alternatives considered**: ①4 次（首次+3 重试）——被拒：教学文档字面「3 次尝试」且测试清单写「退避内重试成功」以 3 为界；②maxAttempts 做成配置键——被拒：交付物清单「无新配置键」；③重试每次尝试各落一条审计——被拒：教学文档「重试耗尽落**最终**失败审计」+「先落审计再还结果」，最终态一条（spec Clarifications 已回填）。

## D6 集成冒烟落 yokeos-boot（跨四模块，手工装配）

- **Decision**: `ReActSmokeIntegrationTest` 落 `yokeos-boot/src/test`（拍板④）：手工装配 `AgentService` + `ReActLoop` + `PromptBuilder`（固定 `Clock`）+ `ContextLoader`（临时工作区）+ `SpringAiProviderService`（真 DeepSeek key，OpenAI 兼容手工构造）+ `ToolExecutor` + 真 `HttpGetTool` + storage JPA 审计（SQLite 临时库 + 手工建表脚本，构造形态照 `LlmCallRepositoryTest`）。提示词强引导「必须先调用 http_get 获取天气再回答」，打 open-meteo 无 key 端点；断言三件事：答复非空、`tool_invocations` 新增 `success=true` 行、`llm_calls` 有对应记录；`DEEPSEEK_API_KEY` 缺失时 `assumeTrue` 跳过。
- **Rationale**: 冒烟跨 core/provider/tool/storage 四模块，只有 boot 聚合全部依赖（16 节冒烟在 provider 模块是因它只跨两模块）；Spring 装配归 18 节（拍板口径「16 节同款手工装配」）。
- **Alternatives considered**: ①放 provider 模块——被拒：不依赖 tool/storage 就得 mock，冒烟就失去了「真链路」意义；②起完整 Spring 上下文——被拒：装配归 18 节，本节手工装配口径已拍板。

## D7 core 补 jackson-databind 显式依赖（实施偏差）

- **Decision**: `yokeos-core/pom.xml` 增 `com.fasterxml.jackson.core:jackson-databind`（**不写版本号**——根 pom `dependencyManagement` 已有 `jackson-bom` 2.21.5 接管）；用途：`YokeTool.execute(JsonNode)` 的参数形态 + `ToolExecutor` 的 argumentsJson 解析（`ObjectMapper.readTree`）。
- **Rationale**: core 现无 jackson 显式依赖（Boot 自带的在 boot/web 侧）；`JsonNode` 是教学文档拍板②定死的参数形态。记**实施偏差**：spec Assumptions 已预告。
- **Alternatives considered**: ①参数改 String 自己解析——被拒：拍板②逐字 `execute(JsonNode input)`；②core 引 spring-boot-starter-json——被拒：带 autoconfigure 的 starter 进 core，宪法 2 eager 装配坑的同族风险。
