# Contract: Java 公开 API（第16节）

Phase 1 产物。本特性对仓内其他模块暴露的接口契约；签名以「已定字面量」纪律逐字保真（H1）。

## yokeos-core

```java
// profile 包
public record Profile(String name, String description, Identity identity,
                      ProviderConfig provider, List<String> tools, List<String> skills,
                      List<String> mcpServers, List<ChannelConfig> channels,
                      List<NotifyChannelConfig> notifyChannels, List<ScheduleConfig> schedules,
                      List<String> bootstrap, Settings settings) { … }

public final class AgentLoader {
    /** 扫描 .yokeos/agents/ 各子目录，把 AGENT.md frontmatter 派生成 Profile。
     *  本节校验仅「provider 名在全局清单」一条；坏文件记错误日志跳过，不阻断。 */
    public List<Profile> loadAll(Path workspace, Set<String> knownProviderNames) { … }
    public Profile deriveProfile(Path agentDir, Set<String> knownProviderNames) { … }
}

public final class ProfileRegistry {
    public void register(Profile profile) { … }   // 启动扫描唯一注册路径；运行时注册归第 29 节
    public Optional<Profile> get(String name) { … }
    public Collection<Profile> all() { … }
}

// tool 包
public interface YokeTool {                        // 最小接口，第 20 节扩展执行语义
    String getName();
    String getDescription();
    String getInputSchema();                       // JSON Schema 字符串
}

// audit 包（跨模块契约：core 定义，storage 实现，provider 消费——research D3）
public interface LlmCallAuditor {
    /** 成败都记一行；写入失败不吞。参数用显式整数而非框架 Usage——core 禁引 Spring AI（实现期修正）。 */
    void record(String sessionId, String provider, String model,
                Integer promptTokens, Integer completionTokens, Integer totalTokens,
                boolean success, String errorMessage, long durationMs);
}
```

## yokeos-provider

```java
public final class ProviderService {
    public ProviderService(Map<String, ChatModel> providerMap,   // 显式映射，宪法 3
                           ToolSchemaAdapter adapter, LlmCallAuditor auditor) { … }

    /** 一次 LLM 调用：按 profile.provider 取模型（查无抛异常）→ 组装（schema 只翻译、
     *  自动执行关闭）→ call → 成败双路审计 → 响应原样返回 / 异常上抛。 */
    public ChatResponse chat(String sessionId, Profile profile, Prompt prompt) { … }
}

public final class ToolSchemaAdapter {
    /** YokeTool 说明 → Spring AI 工具描述；只翻译，产物不含任何执行逻辑。 */
    public List<ToolDefinition> toSpringAiTools(List<YokeTool> tools) { … }
}

public class ProviderNotFoundException extends RuntimeException { … }

@ConfigurationProperties("yokeos")
public class ProvidersProperties {
    private List<ProviderConfig> providers = new ArrayList<>();  // name / apiKey / baseUrl
}
```

> `Prompt` / `ChatResponse` / `ChatModel` / `ToolDefinition` 直接使用 Spring AI 类型（research D5）；
> 组装与关闭自动执行的确切 API 写法以 1.1.8 本地依赖核实为准（research D2，H3）。

## yokeos-storage

```java
// JPA 实体 + Repository（llm_calls，含 success / error_message 两列）
public interface LlmCallRepository extends JpaRepository<LlmCall, Long> { … }

// LlmCallAuditor 的 JPA 实现（依赖倒置）
public final class JpaLlmCallAuditor implements LlmCallAuditor { … }
```

## 契约不变量（测试钉死点）

1. `providerMap` 显式建表；同名注册冲突启动即失败；不扫描容器。
2. `chat` 对未知名抛 `ProviderNotFoundException`（消息含缺失名字）。
3. 带 `YokeTool` 的请求：自动执行关闭 + schema 已翻译携带；tool call 原样透传、零执行。
4. 成功与失败调用都先落审计再返回/抛错（`success=false` + `error_message`）。
