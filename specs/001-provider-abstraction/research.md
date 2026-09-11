# Research: Provider——对接大模型的统一入口（第16节）

Phase 0 产物。决策均标注出处；实现期待核实项按 H3 纪律（核实不到不写，停下报告）。

## D1 Provider 接入路径：DeepSeek / Kimi 走 OpenAI 兼容协议

- **Decision**: 两家均经 `spring-ai-starter-model-openai` 接入——各自一个 `OpenAiChatModel`（不同 `base-url` + `api-key`），显式注册进 `Map<String, ChatModel>`。两家同为 OpenAI 协议、Bean 类型相同，恰好是「必须显式映射」的活教材（宪法 3 的现实成因）。qwen 如需第三家，走 Spring AI Alibaba dashscope starter。
- **Rationale**: 本地 `.m2` 实测（2026-09-10）：Spring AI 构件 24 个，model starter 仅 OpenAI 系（`spring-ai-openai` / `spring-ai-starter-model-openai` / autoconfigure），版本 1.0.0-M6 与 2.0.0；无 deepseek / moonshot 专有 starter。DeepSeek 与 Kimi（Moonshot）官方 API 均兼容 OpenAI 协议。
- **Alternatives considered**: ①每家专有 starter——被拒：BOM 内未必有、本地无；②只接一家——被拒：「双 provider 路由不串台」是硬门槛，必须两家并存；③qwen 先行——被拒：走 Alibaba starter 引入第二套协议栈，非本节必要。
- **待核实（H3，实现第一步）**: Spring AI **1.1.8 整体未入本地仓库**（.m2 仅 M6 与 2.0.0）——首次构建需联网拉取。开工先跑 `mvn dependency:tree`（或 `dependency:resolve`）确认 1.1.8 的 openai starter 可解析下载；失败即软门禁停下报告，不降级版本。

## D2 Spring AI 1.1.8 的调用与「关自动执行」写法

- **Decision**: `chat` 内部形态按 1.1.8 本地依赖核实后落笔（`ChatModel.call(Prompt)` + 工具执行开关的确切 API——1.x 系为 ToolCallingChatOptions 的 internalToolExecutionEnabled 类机制，但**以 jar 内实际签名为准**）；plan 与课件示例代码只作语义示意，不作为字面依据。
- **Rationale**: 参照课程期 1.0.0-M6 与本仓 1.1.8 存在 API 代差（宪法约束已载明）；教学文档骨架代码自带「示意，写法按 1.1.8 核实」标注。
- **Alternatives considered**: 照抄课件 M6 写法——被拒：代差即坑（坑四）。
- **验收锚点**: 无论写法如何，`ProviderServiceTest` 的 captor 断言必须证明「请求里自动执行关闭 + schema 已带上」——行为钉死，写法自由。
- **实现期实证（2026-09-10，T010）**: 1.1.8 已移除 options 上的静态 toolDefinitions 列表——工具经 `ToolCallback` 载体随行（`getToolDefinition()` 携带定义、`call()` 是执行入口）。适配器产物的 `call()` 刻意抛异常，成为宪法 2 的第二道闸；关闭执行用 `internalToolExecutionEnabled(false)`。另：`spring-ai-starter-model-openai` 的 autoconfigure 会 eager 装配 OpenAI 系 Bean 并强索 `spring.ai.openai.api-key`（boot 上下文启动即炸）——改用裸 `spring-ai-openai` 依赖 + 手工构造 ChatModel，已回填 CLAUDE.md 陷阱表。

## D3 LlmCallAuditor 接口落位：core（偏离参照，YokeOS 规则优先）

- **Decision**: 审计契约接口 `LlmCallAuditor` 放 `yokeos-core`（audit 包），`JpaLlmCallAuditor` 实现放 `yokeos-storage`，`yokeos-provider` 消费接口。
- **Rationale**: YokeOS CLAUDE.md / 宪法 9 的成文规则——「跨模块契约（接口 + 值对象）放 yokeos-core，下游模块实现（依赖倒置）」。该接口被 provider 消费、storage 实现，是典型跨模块契约。
- **Alternatives considered**: 参照第 16 节原样放 provider 模块（oryxos 即如此）——被拒：YokeOS 模块规则是宪法级约束，优先于逐点照抄；「照抄的是设计，不是疏漏」的反向应用——参照把契约放消费方模块是其结构选择，YokeOS 已有成文规则与之冲突时从 YokeOS。

## D4 YokeTool 最小接口本节落地

- **Decision**: 在 `yokeos-core`（tool 包）建 `YokeTool` 最小接口：`getName()` / `getDescription()` / `getInputSchema()` 三方法；完整 Tool 体系（`ToolResult`、执行语义）归第 20 节扩展。
- **Rationale**: `ToolSchemaAdapter` 本节就要翻译「工具说明」，接口是它的输入类型，不能悬空；参照第 16 节同样以最小形态触碰 `OryxTool`（3 行改动）。
- **Alternatives considered**: 本节直接用 Spring AI 的工具类型——被拒：会把 Spring AI 类型泄漏进 core 抽象，违反宪法 2 的边界精神。

## D5 出入参直接用 Spring AI 类型，不自研值对象层

- **Decision**: `chat(String sessionId, Profile profile, Prompt prompt)` 的 `Prompt` 与返回的 `ChatResponse` 直接使用 Spring AI 类型，不自建 `ProviderRequest` / `ProviderResponse` / `Usage` 包装层。
- **Rationale**: 宪法 2 的标准姿势即 `chatModel.call(new Prompt(messages, options))`；第 17 节 ReActLoop 组装的就是 Spring AI `Prompt`，去掉包装层可让两层无缝；技术方案未定义自研值对象（H1：无出处的对外概念不建）。
- **Alternatives considered**: 参照第 16 节的四个自研值对象（ProviderRequest / ProviderResponse / Usage / ToolCallRequest）——被拒：在 YokeOS 无语义增量，徒增一层翻译。
