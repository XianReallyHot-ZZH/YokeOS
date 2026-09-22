# Contracts: 动态管理（第30节）

> Phase 1 产物。两类契约：REST 端点（统一前缀 `/api/v1`、信封 `{code, message, data, timestamp}`，错误码 400/404/503 沿用既有映射）与 core 公共方法（跨模块契约，`yokeos-web` 与 `yokeos-cli` 消费）。

## REST 端点（本节 8 个；invoke 26 节既有不动；合计 19 收口）

### POST /api/v1/agents/generate

| 项 | 契约 |
|----|------|
| 请求体 | `{"sentence": "一句话需求"}`（空/缺失 → 400） |
| 200 | `data: {"agentMarkdown": "<AGENT.md 草稿全文>"}`——不落盘、不注册 |
| 400 | 句子为空；LLM 产出剥围栏后非合法定义（消息含校验失败细节） |
| 503 | 生成配置缺失（消息含 `yokeos.agent-generation.provider` 配置方法）；Provider 故障 |
| 审计 | 每次调用落 `llm_calls`（sessionId 前缀 `agent-generation`），成败都落 |

### POST /api/v1/agents

| 项 | 契约 |
|----|------|
| 请求体 | `{"name": "...", "agentMarkdown": "..."}`（name 白名单 `[a-zA-Z0-9][a-zA-Z0-9_-]*` 且 ≤64） |
| 200 | `data: AgentView`——目录已落盘、已注册（有 schedules 已挂定时），不重启即出现在列表 |
| 400 | name 已存在（零写入）；name 不匹配白名单（零写入）；agentMarkdown 非法（注册失败回滚已写目录） |

### GET /api/v1/agents

| 项 | 契约 |
|----|------|
| 200 | `data: [AgentView]`——全部已注册 Agent |

### GET /api/v1/agents/{name}

| 项 | 契约 |
|----|------|
| 200 | `data: AgentView`（含 `agentMarkdown` 全文——编辑回填） |
| 404 | 不存在 |

### PUT /api/v1/agents/{name}

| 项 | 契约 |
|----|------|
| 请求体 | `{"agentMarkdown": "..."}` |
| 200 | `data: AgentView`——覆写即时生效；schedules 变更已先注销旧定时再注册新（不并跑）；不依赖文件监听 |
| 400 | agentMarkdown 非法（不落盘，旧定义不破坏） |
| 404 | 不存在 |

### DELETE /api/v1/agents/{name}

| 项 | 契约 |
|----|------|
| 200 | 按「注销定时 → 移出索引 → 目录归档 `.yokeos/archive/`」顺序完成（InOrder 可证）；归档重名加 `-yyyyMMdd-HHmmss` 后缀 |
| 404 | 不存在 |

### GET /api/v1/workspace/tree

| 项 | 契约 |
|----|------|
| 200 | `data: [FileNode]`——`.yokeos/agents/` 与 `.yokeos/archive/` 两支（agents 每个 Agent 目录可展开列其内文件） |
| 只读 | 本端点无写形态 |

### GET /api/v1/workspace/file?path=\<相对路径\>

| 项 | 契约 |
|----|------|
| 200 | `data` 为文件文本内容（限定 `.yokeos/` 内） |
| 400 | 路径穿越（`../` 变形、绝对路径——resolve 后 normalize 不落在 root 内）；非文本读失败 |
| 404 | 文件不存在 |
| 只读 | 本端点无写形态（在线编辑明确不做，拍板①） |

## core 公共方法（`com.yokeos.core.agent`，跨模块契约）

### AgentLifecycleService

```java
/** 唯一注册段：防重收口（已有同名先 unregisterProfile 旧）→ deriveProfile → registry.register
 *  → 有 schedules 则 scheduler.registerProfile。API create 写完目录后、Watcher 事件均调它（FR-011/016）。 */
Profile register(Path agentDir)   // 校验失败抛 IllegalArgumentException（→400）

/** 创建：exists 第一步拒（IllegalArgumentException）→ store.write → register；
 * 注册失败 store.delete 回滚再抛（不留半个 Agent）。 */
Profile create(String name, String agentMarkdown)

/** 更新：未命中 IllegalArgumentException；**先 parse 字符串校验（非法 400 不落盘，旧定义不破坏）→
 * 过才 store.write 覆写 → deriveProfile → unregisterProfile(old) → register(updated)。
 * schedules 变更天然先注销后注册。**（analyze H1 改序） */
Profile update(String name, String agentMarkdown)

/** 删除：未命中 IllegalArgumentException（web 层前置 404 判定后调用）；
 * unregisterProfile → registry.remove → store.archive（时序不可反）。 */
void delete(String name)

/** Watcher DELETE 事件用：目录已被手工删，只注销 + 移索引、不归档。 */
void unregisterByDir(Path agentDir)

/** 一句话草稿：句子空白 IllegalArgumentException；配置缺失 IllegalStateException；
 * ProviderService.chat → 剥 ``` 围栏 → AgentLoader.parse 字符串校验（不落盘校验，analyze H1）
 * → 返回全文。不落盘、不注册。 */
String generate(String sentence)

Optional<Profile> get(String name) / Collection<Profile> list()   // 直通 registry
```

### AgentStore

```java
Path write(String name, String agentMarkdown)  // agents/<name>/AGENT.md，目录按需建、已存在覆写
void archive(String name)                      // 移 archive/<name>/（重名 -yyyyMMdd-HHmmss），archive/ 按需建
void delete(Path agentDir)                     // 物理删目录（仅 create 回滚用）
```

### WorkspaceWatcher

```java
void start()                                                   // 装配 initMethod：agents/ 注册 WatchService，循环提交执行器
void handleChange(Path agentDir, WatchEvent.Kind<?> kind)      // 包级可见供单测直调：
                                                               // DELETE → unregisterByDir；目录 → register；异常 WARN 不抛
```

### AgentLoader（29 节存量类，本节新增一个公共方法——analyze H1）

```java
/** 字符串→Profile：frontmatter 校验与 deriveProfile 同一套，非法抛 IllegalArgumentException。
 * generate（剥围栏后）与 update（落盘前）的"不落盘校验"共用。新增是扩展非改接口。 */
Profile parse(String agentMarkdown, String expectedName)
```

## 前端契约（管理台两页，走且仅走上组端点）

- **Agent 管理页**：列表 GET /agents；一句话新建 → POST /agents/generate → 预览可编辑（提示改 cron/tools）+ name → POST /agents；编辑 → GET /{name} 回填 → PUT /{name}；删除 → 二次确认 → DELETE /{name}。
- **工作区页**：左树 GET /workspace/tree；点文件 GET /workspace/file；只读无编辑。
- 错误展示 ApiResponse.message；风格复用官网设计 token（`.claude/skills/yokeos-admin-ui/`）。
