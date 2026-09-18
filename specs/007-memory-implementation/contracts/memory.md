# Contract: Memory 门面、后端与内置 Tool（第22节，specs/007）

> Phase 1 产出。本节对外契约三层：`MemoryService` 门面（上层唯一入口）、`LongTermMemoryStore` 后端（可插拔实现点）、`save_memory`/`recall_memory` Tool（Agent 侧入口）。签名出处：技 §5.1、specs/006 D1/D2/D3/D7。

## 1. `MemoryService` 门面（落 yokeos-core，上层唯一入口）

```java
public interface MemoryService {
    String buildContext(Session session);               // 拼进 prompt 的长期记忆
    void remember(String content, MemoryScope scope);   // save_memory 转发
    List<String> recall(String keyword);                // recall_memory 转发
}
```

| 方法 | 前置条件 | 行为 | 后置条件 |
|------|---------|------|---------|
| `buildContext(session)` | 无（session 可为任意状态） | 委托后端 `load()`，返回「核心区全量 + 归档区截断后」的长-term 记忆文本（带分区 header） | **不包含任何会话消息**（坑七：会话历史归 PromptBuilder 既有段）；每次调用现读（契约一） |
| `remember(content, scope)` | content 非空非空白；scope 非 null | 委托后端 `append`（自动附日期） | 下一次 `buildContext`/`recall` 立即可见 |
| `recall(keyword)` | keyword 非空 | 委托后端 `recallByKeyword` | 只命中归档区；未命中返回空列表（调用方给提示语） |

**消费方**：`PromptBuilder`（[2] 注入位）、`MemoryTools`（两 Tool 的转发目标）。上层（ReActLoop/ToolExecutor）不感知后端形态。

## 2. `LongTermMemoryStore` 后端（落 yokeos-memory，可插拔实现点）

```java
public interface LongTermMemoryStore {
    void append(String content, MemoryScope scope);   // 写入，按 scope 分区，自动加日期 header
    String load();                                     // 核心区完整 + 归档区（截断后）
    List<String> recallByKeyword(String keyword);      // 只在归档区做关键词检索
}
```

### 四条行为契约（所有实现共同遵守，契约测试统一钉死）

| # | 契约 | 防的坑 | 契约测试锚点 |
|---|------|--------|-------------|
| 一 | **不缓存**——每次 `load`/`recall` 重新读（文件/查库/REST），不做进程内缓存 | 坑二（写入下一轮不可见） | 写入后立刻可读 |
| 二 | **核心区永不被截断**——截断只作用归档区，物理隔离（md 档裁剪函数只收归档段；sqlite 档 LIMIT 只在归档查询） | 坑一/坑五 | 超量归档后核心一字不少 |
| 三 | **scope 显式**——分区由调用方传入，实现不猜、不改写；缺省语义在 Tool 层（缺省 ARCHIVAL） | 坑四 | scope 路由到正确分区 |
| 四 | **关键词检索**——包含匹配（行匹配/LIKE/REST search），不上正则、分词、语义、向量 | 坑五（越界升级） | recall 只搜归档区 |

### 三档实现与一档测试基建

| 实现 | 存储体 | 截断 | 检索 | 备注 |
|------|--------|------|------|------|
| `MarkdownMemoryStore` | `.yokeos/memory/MEMORY.md` 两分区 | 归档段尾部字符裁剪（archive-max-chars=4000） | 归档区行匹配 | 默认档；单机定位（多副本不共享） |
| `SqliteMemoryStore` | `memory_entries` 表 | 归档查询 LIMIT（archive-max-rows=100） | LIKE（转义后） | 结构化档；零外部依赖 |
| `Mem0MemoryStore` | 自托管 Mem0 实例（REST） | Mem0 侧分页（以部署版本为准） | Mem0 search（语义检索——D4 允许的加强版） | 集成档；`${MEM0_*}` 占位、切档使用时校验 |
| `InMemoryMemoryStore` | 进程内 List | 归档保留最近 100 条 | 归档行匹配 | **测试基建**（契约测试 mem0 替身 + 轻量依赖），不进 backend 选项 |

**选档**：`yokeos.memory.backend` 三选一（markdown 缺省）；换档只改此行，门面与上层零改动。

## 3. 内置 Tool：`save_memory` / `recall_memory`（落 yokeos-memory/builtin）

### `save_memory`

| 项 | 值 |
|----|-----|
| 名称 | `save_memory` |
| 描述 | 记住一件值得长期记住的事 |
| 参数 | `content`（要记住的内容，必填）；`scope`（core 或 archival，不确定就填 archival） |
| scope 三态 | 缺省/空白 → `ARCHIVAL`；`core`/`CORE` → `CORE`；其他 → 返回「无法识别的记忆分区: <值>（应为 core 或 archival）」，**两分区都不写** |
| 成功返回 | `已记住` |
| 审计 | 经 ToolExecutor 既有路径落 `tool_invocations`（与其他内置 Tool 一视同仁，零新增审计逻辑） |

### `recall_memory`

| 项 | 值 |
|----|-----|
| 名称 | `recall_memory` |
| 描述 | 按关键词检索长期记忆 |
| 参数 | `keyword`（检索关键词，必填） |
| 命中返回 | 命中行以换行拼接 |
| 未命中返回 | `没有找到相关记忆`（提示语，**不抛异常**） |
| 检索范围 | 仅归档区（核心区全量在场无需检索） |

**注册**：`@Tool` 注解方法，经 20 节 `ToolRegistry.registerAnnotated(bean)` 管道（schema 由 Spring AI 生成、执行发起方永远是 ToolExecutor——宪法 2）。AGENT.md frontmatter `tools:` 点名 `save_memory`/`recall_memory` 后对 Agent 可见（PromptBuilder 只带点名工具，19 节坑先例）。

## 4. Prompt 注入位（`PromptBuilder` 接线）

组装顺序（技 §4.2）：`[1]` system prompt（ContextLoader）+ 当前时间行 → `**[2] 长期记忆**（本节接线：memoryService.buildContext(session)，每次组装现调）` → `[3]` 对话历史（`truncateByTurn`，17 节既有）→ `[4]` 工具清单（`ProviderRequest.availableTools`，既有）。[2] 与 [3] 各拼各的、互不包含（坑七回归点）。
