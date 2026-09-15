# Contracts: CLI 命令面与会话管理

**Date**: 2026-09-15 | 消费方：终端用户（命令面）+ 26 节 Web / 25 节定时（SessionManager 三元组口径）+ 26 节 Web 会话详情（ToolInvocationReader 复用）

## 1. 命令面（用户契约）

```text
yokeos init                              # 初始化 .yokeos/ 工作区（幂等，16 节已有）
yokeos status                            # 工作区/配置/库文件状态摘要
yokeos chat [--profile <name>] [--message <text>]   # 交互对话（默认 default）；
                                         #   /quit 退出、EOF 等同退出、空行跳过、
                                         #   /context 查会话上下文、/tools 查本会话 Tool 调用记录；
                                         #   --message 单条模式：发一条即退出（空白值报参数错误）
yokeos serve [--port 8080]               # 启动骨架：起运行时常驻（REST 端点 26 节接线）
yokeos gateway                           # 启动骨架：守护进程模式
yokeos profile list|create <name>|show <name>|delete <name>
                                         #   操作 .yokeos/agents/；create 幂等不覆盖；
                                         #   delete 归档式移入 .yokeos/archive/（不物理删）
yokeos provider list                     # 实例声明的 provider 清单（name/base-url）
yokeos tool list                         # 当前就绪内置工具清单（20 节接 ToolRegistry 后改查注册表）
yokeos session list                      # sessions 概览（倒序前 20；库不存在→暂无会话）
```

统一行为：全部命令 `--help`；未知子命令/参数由 Picocli 统一报错（退出码非 0，无堆栈）。

## 2. SessionManager（com.yokeos.core.session，17 节前向接口本节补全）

```java
public interface SessionManager {
    /** 三元组唯一决定会话；同一三元组幂等返回同一条（含已恢复历史）。id 拼接唯一发生在 core SessionIds 一处（两实现共用）。 */
    Session getOrCreate(String channel, String userId, String profileName);
    Optional<Session> get(String sessionId);
    void save(Session session);   // 17 节已有：历史整体覆盖 messages_json + 刷 last_active_at
}
```

行为契约：`getOrCreate` 未命中即落一条 `status=active` 新记录；`archived` 状态本节不产生（26 节 DELETE 接线，列先建好恒空）。**id 格式 `channel:user:agent`（如 `cli:wang:weather`）为实现细节，不对外承诺**——唯一承诺：拼接只在 `SessionIds` 一处（H4④，两实现共用），所有入口（CLI 传 `"cli"`、Web 传 `"web"`、定时传 `"scheduler"`）只提供三元组。

## 3. CliChannel（com.yokeos.channel.cli）

```java
public CliChannel(AgentService agentService, SessionManager sessionManager,
                  ToolInvocationReader toolInvocationReader, ProfileRegistry profileRegistry);
public void run(String profileName, String userId);                    // 交互模式
public void run(String profileName, String userId,                     // IO 注入重载（可测形态，D8）
                BufferedReader in, PrintStream out);
public void runOnce(String profileName, String userId, String message, // --message 单条模式
                    BufferedReader in, PrintStream out);
```

行为契约：channel 字面量 `"cli"` 只作为三元组参数出现；空行跳过；EOF 等同 `/quit`（trim 判断）；`/context`/`/tools` 本地处理不转交引擎（/context 最近 50 条、每条截断 200 字符；/tools 逐条 工具名/成败/耗时/入参摘要，无记录→「暂无 Tool 调用记录」）；ProfileRegistry 为第四协作者——启动即验 Profile，不存在点名报错（含名字）、不进交互循环（实现期微调：行为契约「点名报错不进循环」的落法）。

## 4. ToolInvocationReader（com.yokeos.core.audit，/tools 数据源）

```java
public interface ToolInvocationReader {
    List<ToolInvocationRecord> findBySession(String sessionId);
}
public record ToolInvocationRecord(String toolName, String inputJson, boolean success,
                                   String errorMessage, long durationMs, Instant createdAt) {}
```

行为契约：只读、按会话隔离、成败行都在（与既有 `ToolInvocationAuditor` 写口同包对称）；storage 实现 `JpaToolInvocationReader` 包既有 `ToolInvocationRepository.findBySessionId`。

## 5. YokeosRuntime 装配面（com.yokeos.cli，重命令专用）

`@SpringBootApplication(scanBasePackages = "com.yokeos")` + `@EnableJpaRepositories(basePackages = "com.yokeos.storage")` + `@EntityScan(basePackages = "com.yokeos.storage")`（坑二正面解法）；`@Bean` 显式装配（配方 = 17 节冒烟手工装配转录）：providerMap（宪法 3）→ 双 auditor → SpringAiProviderService → ProfileRegistry（AgentLoader 启动扫描）→ ContextLoader → PromptBuilder → ToolExecutor（17 节工具 Map）→ ReActLoop → JpaSessionManager → AgentService → CliChannel。轻命令不进这里。boot `YokeosBootApplication` 组件扫描吸收本类（一套配置两入口，26 节 serve 直接受益）；运行配置唯一归 boot `application.yaml`（schema-locations 含 schema-002）。

## 6. Session 恢复构造器（com.yokeos.core.session，改造点）

```java
public Session(String sessionId, String profileName, List<Message> restored);
```
