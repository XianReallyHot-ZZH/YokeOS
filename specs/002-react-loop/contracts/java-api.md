# Contract: Java 公开 API（第17节）

Phase 1 产物。本特性对仓内其他模块暴露的接口契约；签名以「已定字面量」纪律逐字保真（H1）——实现与测试以此为准，改名即软门禁。Spring AI 侧写法已 javap 实证（[research.md](../research.md) D2）。

## yokeos-core（新增公开 API）

```java
// provider 包——契约上移（拍板①；全普通 Java 类型，core 禁引 Spring AI）
public interface ProviderService {
    /** 一次 LLM 调用：按 Profile 路由、工具只翻译不执行；sessionId 随调用传递作审计关联。 */
    ProviderResponse chat(String sessionId, Profile profile, ProviderRequest request);
}

public record ProviderRequest(String promptText, List<YokeTool> availableTools) {}

public record ProviderResponse(String text, List<ToolCallRequest> toolCalls) {
    /** 模型是否提出了工具调用请求（循环判停依据）。 */
    public boolean hasToolCalls() { … }
}

public record ToolCallRequest(String name, String argumentsJson) {}

// agent 包
public final class ReActLoop {
    /** 主循环（宪法 1：自实现）：追加用户消息 → 每轮〔组装→调用→先累积→判停→顺序执行回填〕
     *  → 无工具调用返回 text（null 按空串）；转满 maxIterations 返回含「达到最大轮数」的收尾答复。 */
    public ReActLoop(PromptBuilder promptBuilder, ProviderService providerService,
                     ToolExecutor toolExecutor) { … }
    public String run(Session session, String userMessage, Profile profile) { … }
}

public final class PromptBuilder {
    /** 固定顺序：system prompt（含末尾日期时间行）→〔记忆位：22 节接入前恒空〕→ 历史（轮界截断）。
     *  工具不进文本，经 ProviderRequest.availableTools 传递，只带 Profile.tools 点名的那些；
     *  工具来源 = 构造注入的候选集（与 ToolExecutor 共享同一映射，20 节换 ToolRegistry 时两者同换）。 */
    public PromptBuilder(ContextLoader contextLoader, Map<String, YokeTool> knownTools, Clock clock) { … }
    public ProviderRequest build(Session session, Profile profile) { … }
}

public final class ToolExecutor {
    /** 唯一执行路径（宪法 2）：解析 argumentsJson →〔Sandbox 检查位：24 节接线，本节留注释〕
     *  → 执行 →（可重试失败指数退避，总尝试上限 3；退避基值注入，测试传 0）
     *  → 先落审计（最终态一条）再还结果；异常转失败 ToolResult 不上抛。 */
    public ToolExecutor(Map<String, YokeTool> tools, ToolInvocationAuditor auditor,
                        long retryBackoffBaseMs) { … }
    public ToolResult execute(String sessionId, ToolCallRequest call) { … }
}

public final class AgentService {
    /** 编排入口（三触发源共用）：查 Profile（点名报错）→ ProfileContext.set → run →
     *  save（仅正常路径）→ finally ProfileContext.clear（remove）。 */
    public AgentService(ProfileRegistry profileRegistry, ReActLoop reActLoop,
                        SessionManager sessionManager) { … }
    public String process(Session session, String userMessage) { … }
}

/** ThreadLocal 封装：工具执行时知道「当前是哪个 Agent」（坑四：clear 必须 finally，remove 不 set(null)）。 */
public final class ProfileContext {
    public static void set(Profile profile) { … }
    public static Profile current() { … }        // 未设时 null
    public static void clear() { … }
}

// context 包
public final class ContextLoader {
    /** system prompt 供给（零缓存现读，改完立即生效）：identity.prompt → Bootstrap（Profile.bootstrap
     *  列表，固定相对序 AGENTS.md→SOUL.md→USER.md，只能裁剪不能乱序，每段带角色 header，缺失 WARN
     *  跳过、IO 失败显式抛错）→〔Skill 正文位：29 节留注释〕→ AGENT.md 正文（workspace/agents/<name>/）压轴。 */
    public ContextLoader(Path workspace) { … }
    public String loadSystemPrompt(Profile profile) { … }
}

// session 包
public final class Session {
    public Session(String sessionId, String profileName) { … }
    public String sessionId() { … }
    public String profileName() { … }
    public List<Message> messages() { … }         // 按发生序快照
    public void appendUser(String content) { … }
    public void appendAssistant(ProviderResponse response) { … }   // text 为 null 按空串
    public void appendToolResult(String toolName, ToolResult result) { … }  // 失败存错误描述
}

public record Message(String role, String content, String toolName) {}   // role: user/assistant/tool

public interface SessionManager {                 // 本节仅 save；getOrCreate/持久化归 18 节
    void save(Session session);
}

public final class InMemorySessionManager implements SessionManager { … }

// audit 包（跨模块契约：core 定义，storage 实现——与 LlmCallAuditor 同包对称）
public interface ToolInvocationAuditor {
    /** 成败都记一行最终态；写入失败不吞。列定义逐字技 §9.2。 */
    void record(String sessionId, String toolName, String inputJson, String resultJson,
                boolean success, String errorMessage, long durationMs);
}

// tool 包（扩展）
public interface YokeTool {
    String getName();
    String getDescription();
    String getInputSchema();
    /** 执行语义（拍板②）：白名单校验归 20/24 节，此处只执行。 */
    ToolResult execute(JsonNode input);           // com.fasterxml.jackson.databind.JsonNode
}

public record ToolResult(String content, boolean success, String errorMessage, boolean retryable) {
    public static ToolResult ok(String content) { … }
    public static ToolResult error(String errorMessage, boolean retryable) { … }
}
```

## yokeos-provider（16 节改造，行为零变化）

```java
/** 16 节 ProviderService 改名（git mv 语义）并实现 core 契约接口（拍板①）。
 *  显式映射 Map<String, ChatModel>、internalToolExecutionEnabled(false)、ToolSchemaAdapter、
 *  LlmCall 双路审计路径全部原样复用；新增双向协议映射：
 *  ProviderRequest.promptText → new Prompt(String)（D2 实证构造器）；
 *  ChatResponse → ProviderResponse：getResult().getOutput() 取 AssistantMessage，
 *  getText() 作 text、getToolCalls() 逐项 name()/arguments() 映射 ToolCallRequest（D2 实证）。 */
public final class SpringAiProviderService implements ProviderService {
    public SpringAiProviderService(Map<String, ChatModel> providerMap,
                                   ToolSchemaAdapter adapter, LlmCallAuditor auditor) { … }
    @Override
    public ProviderResponse chat(String sessionId, Profile profile, ProviderRequest request) { … }
}
```

## yokeos-tool

```java
/** 内置 HTTP Tool 最小形态：入参 {url}，GET，连接/读取超时 10 秒，响应正文超长截断 8000 字符。
 *  域名白名单归 24 节（Sandbox 接线位留注释）；20 节扩展为 HttpTools 全量。 */
public final class HttpGetTool implements YokeTool { … }   // getName() = "http_get"
```

## yokeos-storage

```java
// JPA 实体 + Repository（tool_invocations 列定义逐字技 §9.2；映射既有表，无新表）
public interface ToolInvocationRepository extends JpaRepository<ToolInvocation, Long> {
    List<ToolInvocation> findBySessionId(String sessionId);   // 按 session 关联查询
}

// ToolInvocationAuditor 的 JPA 实现（依赖倒置，形态照 JpaLlmCallAuditor）
public final class JpaToolInvocationAuditor implements ToolInvocationAuditor { … }
```

## 定死字面量（测试断言锚点）

| 字面量 | 值 | 出处 |
|---|---|---|
| 收尾答复 | 含「达到最大轮数」 | 教学文档（测试断言字面量） |
| Bootstrap header | `## 项目约定（AGENTS.md）` | 技 §8.3 逐字 |
| Bootstrap header | `## 人格定义（SOUL.md）` / `## 用户偏好（USER.md）` | research D4（按 CLAUDE.md 定位语补全） |
| 工具名 | `http_get` | 技 §13 / 需 §5.7 |
| 重试总尝试上限 | 3 | 教学文档（与技 §4.2 张力的采信侧，research D5） |
| 日期时间行 | `当前时间：<yyyy-MM-dd HH:mm:ss>`（注入 Clock 计算） | tasks T012 / research D4 |
| 轮数 / 历史缺省 | 10 / 20 | 技 §4.3（Profile.Settings.DEFAULT 既有） |
| HTTP 截断 / 超时 | 8000 字符 / 10 秒 | 教学文档第三部分第七步 |

## 契约不变量（测试钉死点）

1. **执行权唯一**：工具调用只经 `ToolExecutor`（宪法 2）；`ToolSchemaAdapter` 产物 `call()` 依旧抛异常；`internalToolExecutionEnabled(false)` 不回退。
2. **循环自实现**：`ReActLoop` 不 import Spring AI 任何 Agent 抽象（宪法 1，人工 review + import 扫描）。
3. **先累积再判停**：任何收尾路径（含转满轮数）下 Session 含全部轮次的 assistant 与 tool 消息。
4. **审计最终态一条**：一次 `execute` 调用恰一条 `tool_invocations` 落账；成败都落、先落账再还结果；`duration_ms` 覆盖重试总耗时。
5. **ProfileContext 生命周期**：`process` 正常与异常路径出口后 `ProfileContext.current()` 均为 null。
6. **契约上移零泄漏**：core 主源码 `grep "com.yokeos.provider\|springframework.ai"` 零命中；16 节测试断言语义逐条平移全绿。
