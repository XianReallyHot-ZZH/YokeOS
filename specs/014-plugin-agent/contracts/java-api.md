# Contract: Java API（第29节）

**Branch**: `specs/014-plugin-agent` | **Date**: 2026-09-22

本节对外契约 = 三个既有类的行为契约扩展（无新类、无新 REST 面、无新 CLI 命令）。签名以下游消费方（30 节 `AgentLifecycleService`、既有 `PromptBuilder`）可依赖为准：

## ContextLoader（yokeos-core / context）

```java
/** 组装 system prompt：identity → Bootstrap → 点名 Skill 正文（本节接线）→ AGENT.md 正文压轴。 */
public String loadSystemPrompt(Profile profile)
```

**行为契约**（消费方 `PromptBuilder` 零改动依赖）：

1. `Profile.skills()` 点名的每个名 → 读 `workspace/skills/<名>/SKILL.md`，剥 frontmatter 整段注入，段头 `## 技能（<名>）`，按声明序（重复声明原样重复，research D7）。
2. 点名不存在 → WARN 跳过该条，其余段照常（组装不失败）。
3. 存在但读失败（IO）→ 抛 `UncheckedIOException`。
4. Agent 目录内 `skills/` 子目录不读（公共库是唯一注入来源，research D8）。
5. 零缓存：每次调用现取全部文件（AGENT.md / Bootstrap / SKILL.md 同语义）。

## ProfileRegistry（yokeos-core / profile）

```java
public boolean exists(String name)   // 按名查存在
public boolean remove(String name)   // 存在移除返回 true；不存在（重复 remove）返回 false——幂等
public void register(Profile profile) // 既有：覆盖同名（29 节定夺：后到者胜，30 节 PUT 的机制基础）
```

**契约要点**：线程安全（ConcurrentHashMap 既有）；`register`/`remove` 是运行时方法——启动扫描与运行期新增走同一段代码（`AgentLoader.deriveProfile` 同源校验：同一异常类型 + 同一消息）。

## AgentScheduler（yokeos-core / agent）

```java
/** 注销该 Agent 的全部定时：cancel(false) 不打断执行中任务 + 移除句柄；编排（先定时后索引）归 30 节。 */
public void unregisterProfile(Profile profile)
```

**行为契约**：

1. 逐条以**与注册侧同一 taskId 派生**（`{profileName}:{id}`，私有共用方法）找句柄。
2. 找到 → `ScheduledFuture.cancel(false)`（不打断执行中——正在跑的跑完落账）并从句柄表移除。
3. 句柄不存在（重复注销/从未注册）→ 静默无操作，不抛错。
4. Profile 无 schedules → 空跑不报错。
5. 不联动 `ProfileRegistry`、不写 `scheduled_tasks` 表（30 节 DELETE 编排消费）。

## 非契约（显式不做，防 30 节误依赖）

- 不提供 `hasScheduledTask` 类公共探针（research D6——参照偏差，测试用 mock verify）。
- 不提供 Skill 库 CRUD 方法（库的填充是文件系统手工动作）。
- 不提供「remove Agent 自动注销定时」的联动（30 节编排职责）。
