# 记忆系统（能力三：让 Agent 记得住）

***一句话定位：两层记忆——会话记忆跨重启恢复，长期记忆跨对话保留——让 Agent 记住用户的偏好、项目背景和关键决策，下一次对话不需要重新解释。***

## 它解决什么

普通 chatbot 每次对话都从零开始；Agent 底座区别于 chatbot 的核心体验，就是 Agent 用得越久越懂你。**基于这个能力**：Agent 跨多次对话记住用户偏好（「我一般用 Spring Boot 不用 Spring MVC」）；长任务中断后恢复继续做；历史决策可追溯（「上次为什么选 DeepSeek 不选 Kimi」在记忆里能查到）；团队内多个 Agent 共享同一个用户的偏好记忆。

## 怎么工作

### 统一门面 + 两层实现

对 ReAct 循环只暴露一个 `MemoryService` 接口，内部再分：

- **会话记忆**：当前对话的完整历史，按 Channel + 用户 + Agent 联合标识，持久化到 SQLite，重启后可恢复；超过 context window 时截断早期对话
- **长期记忆**：`MEMORY.md` 文件（默认档），Agent 通过两个内置工具主动读写——`save_memory(content)` 追加、`recall_memory(query)` 按关键词检索；启动时整份注入 system prompt

![Memory 架构：MemoryService 门面统一收口 SessionManager 和 LongTermMemoryStore](/images/docs-memory-service.svg)

### 核心/归档两个分区

`MEMORY.md` 内部用两个一级分区组织：

![MEMORY.md 内部结构：核心记忆区永远保留，截断和检索只作用在归档记忆区](/images/docs-memory-structure.svg)

- **核心记忆区**：必须始终在场的少量关键事实，**永远完整注入、永不截断**
- **归档记忆区**：一般事实与历史，超过 4000 字时保留最近内容；`recall_memory` 只在归档区检索（核心区本来就会被全量注入）

写核心还是写归档由 Agent 经 `scope` 参数显式指定，系统不猜。第一阶段不做自动抽取——Agent 通过 `save_memory` 的调用时机自己决定记什么。

### 三档后端，一次交付

长期记忆抽成 `LongTermMemoryStore` 后端接口（`append` / `load` / `recallByKeyword`），三档实现靠一行配置 `memory.backend` 切换，`MemoryService` 以上一个字不动：

| 后端 | 底层 | 适合 |
|------|------|------|
| `MarkdownMemoryStore`（默认） | `.yokeos/memory/MEMORY.md` 一个 Markdown 文件 | 零依赖、人可读、git 可跟踪；单机档 |
| `SqliteMemoryStore` | `memory_entries` 表，截断变 `LIMIT`、检索变 `LIKE` | 记忆量上千、要结构化查询 |
| `Mem0MemoryStore` | 自托管 Mem0（数据不出域），提炼与语义检索交给它 | 真需要智能记忆时的外部集成档 |

三档共同遵守四条契约：不缓存（每次现读，`save_memory` 下一轮立即可见）；核心区永不被截断；分区由 Agent 显式指定；recall 是关键词检索不复杂化。

### MEMORY.md 与 USER.md 的区别

| 文件 | 来源 | 读写方 | 用途 |
|------|------|--------|------|
| `USER.md` | 用户手写 | YokeOS 只读不写 | 用户的「初始设定」（Bootstrap 文件） |
| `MEMORY.md` | Agent 经 `save_memory` 写入 | YokeOS 读写 | Agent 的「成长记录」（长期记忆） |

## 目标用法示例

```
你：以后日报只关注 AI 和芯片方向
    → Agent 调 save_memory(content="用户更关注 AI 和芯片方向")

（第二天，每日科技日报到点自跑）
    → system prompt 注入 MEMORY.md
    → 组稿自然侧重 AI 和芯片方向的条目
```

记忆在跨天场景里真正生效——这是[每日科技日报 Demo](./quick-start#两个日跑-demo-第一阶段的验收)的验收要点之一。

## 第一阶段边界

- 不做自动从对话中抽取事实（分区完全由 Agent 的调用时机决定）
- 不在进程内自建向量库；语义检索由 Mem0 档外部承担，知识库与语义记忆整体方案放扩展阶段
- 不做情景记忆（第三层）、Memory Wiki（claim/evidence、矛盾检测）、记忆压缩（超长简单截断）
