# Feature Specification: Memory 两层记忆——让 Agent 跨对话记得住用户（第22节）

## Clarifications

### Session 2026-09-18

- Q: 三档后端之间切换时，已有记忆数据要迁移吗？ → A: 不迁移——换档即换存储体（MEMORY.md / memory_entries / Mem0 实例各自独立）。技 §5 无迁移概念，评审差异一「第二、三档是同一契约的换实现」指行为契约不变、非数据互通；跨档数据迁移属扩展阶段候选。
- Q: markdown 档归档区截断的裁剪粒度？ → A: 字符粒度、从尾部保留最近内容（技 §5.1「保留最近内容」；与契约四「不做复杂化」同源——不按行边界、不按条目对齐）。sqlite 档对应行数（归档查询 LIMIT N）。
- Q: save_memory / recall_memory 的调用留痕走哪条路？ → A: 走 ToolExecutor 既有审计路径写 tool_invocations（FR11「与其他内置 Tool 一视同仁」的直接推论），Memory 模块不新增审计逻辑。


## User Scenarios & Testing *(mandatory)*

### User Story 1 - Agent 跨对话记住用户偏好并在后续对话用到 (Priority: P1)

用户首次告诉 Agent「我们项目用 Java 21、部署在 K8s」，Agent 判断这条背景值得长期记住、主动调 `save_memory` 写入核心区；此后每次新对话（新 Session），Agent 的 system prompt 都自带这条背景，用户不需要重复解释。数周后用户问「上次定的技术选型是什么」，Agent 经 `recall_memory` 检索归档区找到旧结论并引用；团队偏好变了，Agent 主动改写核心记忆（旧说法被更新而非并存打架）。

**Why this priority**: 「用一段时间后 Agent 自然记住用户偏好、下次对话不需要重新解释」是 Agent 底座区别于 chatbot 的核心体验（需 §5.6），也是本节可演示成果的直接口径（需 §11 第 22 节行）。没有这条，Memory 就没有用户可感知的价值。

**Independent Test**: 真模型跨对话演示——会话一告诉 Agent 一条偏好（观察模型主动调 `save_memory`、审计留痕），新开会话问相关问题，答复体现该偏好；单测侧以「写入后下一次 prompt 组装立即可见」锚同一条链路。

**Acceptance Scenarios**:

1. **Given** Agent 的长期记忆为空，**When** 用户在会话一中说出一条偏好且模型调 `save_memory` 写入核心区，**Then** 写入自动附日期、落在核心分区。
2. **Given** 核心区已有一条偏好，**When** 用户开启新会话（新 Session）提问，**Then** 组装 prompt 时长期记忆被现读注入（核心区全量在场），答复体现该偏好。
3. **Given** 归档区存有数周前的选型结论，**When** 用户问「上次定的技术选型是什么」，**Then** Agent 调 `recall_memory` 检索归档区命中并引用；未命中时得到「没有找到相关记忆」提示而非报错。

### User Story 2 - Agent 主动记事：分区显式、缺省安全 (Priority: P2)

Agent 经两个内置 Tool 自己读写长期记忆：`save_memory(content, scope?)` 把要长期记住的事追加到指定分区（scope 取 core / archival，缺省 archival，非法值明确报错点名、不静默落错区）；`recall_memory(query)` 按关键词检索、只检索归档区（核心区本来就被全量注入，不需要检索）。系统不做自动抽取——写不写、写哪个区，判断权在 ReAct 循环里的模型。

**Why this priority**: 两个 Tool 是长期记忆的唯一读写入口（评审 D11 偏离依据①：自动抽取无真实信号、Agent 主动调用工程量近零）；scope 显式指定防「该进核心的进了归档被截掉」。

**Independent Test**: 对门面与工具的单测——不传 scope 落归档、传 core 落核心、非法值返回明确提示、关键词未命中返回提示语不抛异常。

**Acceptance Scenarios**:

1. **Given** Agent 调 `save_memory` 未传 scope，**When** 写入完成，**Then** 内容落在归档分区、带日期 header。
2. **Given** Agent 调 `save_memory` 传 scope=core，**When** 写入完成，**Then** 内容落在核心分区。
3. **Given** Agent 调 `save_memory` 传 scope=urgent（非法值），**When** 调用返回，**Then** 得到点名非法值的明确提示，两个分区都没有新内容。
4. **Given** 核心区有一条含关键词 X 的记忆，**When** Agent 调 `recall_memory(X)`，**Then** 返回为空/未命中——检索只作用归档区。

### User Story 3 - 换记忆后端只改一行配置，上层零感知 (Priority: P3)

运维把 `yokeos.memory.backend` 从 markdown 改成 sqlite（或 mem0），重启后同一个 Agent 的记忆读写行为不变——`PromptBuilder`、两个 Tool、全部上层代码一个字不动。三档定位：markdown 是默认单机档（人可读、git 可跟踪）；sqlite 是记忆量上千、多副本部署要共享的结构化档（复用既有 SQLite，零外部依赖）；mem0 是真需要自动抽取/语义检索时的自托管外部集成档（数据不出域）。

**Why this priority**: 「接口不变、实现随便换」是第 21 节评审那道接口墙的价值承诺，本节以三档一次交付当场兑现；多副本部署形态（企业场景）需要非文件档 day one 可选。

**Independent Test**: 契约测试对三档实现参数化统一跑同一套断言（写后立读、截断只裁归档、scope 路由、检索只搜归档）；markdown/sqlite 两档各跑一次真对话体感一致。

**Acceptance Scenarios**:

1. **Given** 同一套上层代码与同一套契约测试，**When** 后端在三档间切换，**Then** 契约测试全部通过（换档不改上层）。
2. **Given** 实例配置 `yokeos.memory.backend: sqlite`，**When** Agent 写入一条记忆，**Then** 落在 `memory_entries` 表（scope 列区分分区），重启后可读。
3. **Given** 切到 mem0 档但 `MEM0_BASE_URL` 等环境变量缺失，**When** 发生记忆读写，**Then** 得到清晰报错（指出缺什么），不静默失败、不阻断进程启动。

### User Story 4 - 记忆无限膨胀不撑爆上下文 (Priority: P4)

长期记忆越攒越多，但每次注入 prompt 的内容有界：核心区（用户是谁、项目背景、关键偏好）永远完整在场、一字不截；归档区超过阈值只保留最近内容（markdown 档默认 4000 字、sqlite 档默认 100 行，均可配置）。且每次组装 prompt 现读——Agent 刚写入的记忆下一轮立刻可见，没有缓存 stale 问题。

**Why this priority**: 注入超 context window 是 Memory 最直接的运行事故（评审坑一）；「写完下一轮立刻可见」是记忆可用性的底线（评审坑二、裁决一）。

**Independent Test**: 契约测试对三档统一灌超量归档条目后断言：最早的被裁、最近仍在、核心区一字不少；写入后立即 load/recall 均可见。

**Acceptance Scenarios**:

1. **Given** 核心区 1 条 + 归档区 500 条，**When** 加载长期记忆，**Then** 核心区内容完整在内、归档区最早的被裁掉、最近的内容保留。
2. **Given** Agent 刚调 `save_memory` 写入一条，**When** 下一次组装 prompt，**Then** 新记忆已在注入内容中。

### Edge Cases

- `MEMORY.md` 文件不存在（首次运行/空工作区）：视作空记忆，不报错；首次写入时按两分区格式创建。
- 非法 scope 值：返回点名非法值的明确提示，不静默落错区、不抛裸异常。
- 关键词未命中：返回「没有找到相关记忆」提示语，不抛异常。
- 归档区恰好在阈值边界（等于/差一个字符或一行）：截断行为确定（超阈值才裁、保留最近）。
- sqlite 档检索关键词含 `%` / `_`：作为字面量匹配，不放大成通配符。
- mem0 档环境变量缺失：运行时清晰报错；缺省配置不阻断进程启动（报错发生在使用时）。
- 对话历史与长期记忆的拼接：长期记忆注入一次、会话历史由 prompt 组装器既有段承载一次，两者不重复注入。
- `USER.md`（用户手写的初始设定）与 `MEMORY.md` 边界：Memory 写路径物理上碰不到 `USER.md`（它是只读 Bootstrap 文件）。

## Requirements *(mandatory)*

### Functional Requirements

- **FR1（统一门面）**: 长期记忆经统一门面（`MemoryService`）读写，上层（ReAct 循环、prompt 组装）只认门面、不感知后端形态；门面接口物理落位 core 模块（依赖方向：memory 模块已依赖 core，接口留在 memory 会成环——与 16 节 Provider 接口上移同款理由）。
- **FR2（save_memory）**: `save_memory(content, scope?)` 写入长期记忆：scope 取 core / archival、缺省 archival、非法值明确报错点名不静默落错区；每条写入自动附日期 header。
- **FR3（recall_memory）**: `recall_memory(query)` 按关键词检索，只检索归档区；未命中返回提示语、不抛异常。
- **FR4（现读不缓存）**: 每次组装 prompt 现读长期记忆（核心区全量 + 归档区截断后），不做进程内缓存；写入后下一轮立即可见。
- **FR5（截断契约）**: 核心区永不被截断；归档区超阈值保留最近内容——markdown 档按字符（默认 4000，配置键 `yokeos.memory.archive-max-chars`）、sqlite 档按行数（默认 100，配置键 `yokeos.memory.archive-max-rows`）。
- **FR6（后端选档）**: 三档后端经 `yokeos.memory.backend` 选择：markdown（缺省）/ sqlite / mem0；换档只改这一行配置，上层与契约测试不动。
- **FR7（markdown 档）**: 默认档操作 `.yokeos/memory/MEMORY.md`：`## 核心记忆` / `## 归档记忆` 两分区 header 组织；文件不存在视作空记忆不报错；截断是归档段字符串裁剪、检索是行匹配。
- **FR8（sqlite 档）**: sqlite 档落 `memory_entries` 表（id / scope / content / created_at + scope 索引），手工建表脚本 `schema-003-memory.sql`、与 sessions/审计表同口径，不走 ddl-auto=update；截断 = 归档查询 LIMIT、检索 = SQL LIKE（通配符转义）、核心区 WHERE scope='CORE' 全量。
- **FR9（mem0 档）**: mem0 档经 REST 集成自托管实例（append / load / recall 翻译 add / get / search，scope 落 metadata）；地址与凭证走环境变量占位（`yokeos.memory.mem0.base-url=${MEM0_BASE_URL}`、`api-key=${MEM0_API_KEY}`），缺省空、不阻断启动、仅切到该档使用时运行时清晰报错。
- **FR10（会话记忆复用）**: 会话记忆复用既有 Session 体系（SessionManager），门面统一对外、不新增会话存储概念；`buildContext` 只出长期记忆，会话历史仍由 prompt 组装器既有对话历史段承载（防止历史被注入两遍）。
- **FR11（内置 Tool）**: `save_memory` / `recall_memory` 作为内置 Tool 注册进 `ToolRegistry`（经 `@Tool` schema 生成路径），与其他内置 Tool 一视同仁；落位 memory 模块（specs/006 裁决二），依赖方向 memory → tool 单向无环。
- **FR12（prompt 接线）**: prompt 组装器接线：system prompt 段之后、对话历史段之前拼入门面返回的长期记忆，每次组装现调。

**明确不做（边界）**: 自动抽取（mem0 档自带能力随档启用，markdown/sqlite 档不做）；进程内向量库（三档都不在进程内自建向量层）；情景记忆；Memory Wiki（结构化 claim/evidence、矛盾检测）；记忆压缩（超长简单截断即第一阶段的压缩）；`readAll` 查询方法与 per-Agent 记忆作用域（26 节 `GET /api/v1/memory` 需要时再议——钉版树末态不采纳）；REST 查询端点（归 26 节）；三档后端间的数据迁移（Clarifications 裁决：换档即换存储体，迁移属扩展阶段）。

### Key Entities

| 实体 | 说明 |
|------|------|
| `MemoryService` 门面 | 上层唯一入口：拼进 prompt 的长期记忆 / 记一条 / 查一下；接口落 core，实现落 memory 模块 |
| `LongTermMemoryStore` 后端 | 长期记忆的可插拔实现点（append(content, scope) / load / recallByKeyword），三档实现各写各的 |
| `MemoryScope` | CORE / ARCHIVAL 两分区语义，Agent 显式指定、缺省 ARCHIVAL |
| `MEMORY.md` | markdown 档存储体：`## 核心记忆` / `## 归档记忆` 两分区，每条带日期 header |
| `memory_entries` | sqlite 档存储体：id / scope / content / created_at + scope 索引（手工建表脚本 schema-003） |
| `save_memory` / `recall_memory` | 两个内置 Tool：长期记忆暴露给 Agent 的唯一读写入口 |
| `yokeos.memory.*` | backend（markdown/sqlite/mem0）、archive-max-chars（4000）、archive-max-rows（100）、mem0.base-url/api-key |
| `USER.md` | 只读 Bootstrap（用户初始设定），与 MEMORY.md（Agent 成长记录）来源与生命周期不同，Memory 写路径不可触碰 |

## Success Criteria *(mandatory)*

### Measurable Outcomes

1. 四条行为契约（不缓存 / 核心永不截断 / scope 显式 / 关键词检索）对三档后端统一验证 100% 通过——任何一档破契约，契约测试对应参数立刻红。
2. 七个坑的回归点全部可自动化验证：超长归档被截核心一字不少；写入后下一次组装立即可见；Memory 写路径产物不含 USER.md；scope 缺省归档/显式核心/非法报错；检索只命中归档区；建表走 schema-003 手工脚本真库可存可读；buildContext 不含会话消息、组装后历史不重复。
3. 换后端只改 `yokeos.memory.backend` 一行：上层代码与契约测试改动行数为 0。
4. 凭证明文在代码、配置、日志中出现次数为 0（mem0 档走 `${MEM0_*}` 占位）。
5. `mvn clean verify` 九模块全绿（自动化验收由 harness 承载）；人工项：真模型跨对话演示（会话一写入偏好、新会话答复体现偏好）、markdown/sqlite 两档切换体感各跑一次、mem0 档真实例可选。

## Assumptions

- 设计依据已冻结：specs/006-memory-review 定稿 D1~D11 与开放事项落定（O1 阈值两键、O2 mem0 键、O3 缺省 markdown、O4 关键词匹配不上语义/向量、O5 替身 + mock + 人工测试策略）。
- 契约测试对 mem0 档用进程内替身（InMemoryMemoryStore 形态，测试基建、不进 backend 选项）；真实 REST 交互由 mock 单测锚；自托管 mem0 真实例为可选人工项（依赖外部服务，缺位不阻塞验收）。
- `PromptBuilder` 是本节唯一改造的前序公共类（17 节预留注释点名「22 节 MemoryService 接入、构造器届时扩展」）；既有单测同步更新构造器调用。
- yokeos-memory 模块 pom 补 yokeos-tool 依赖（MemoryTools 注册用）；无新增进程内依赖（mem0 档走既有 HTTP 客户端能力）。
- 真模型跨对话演示使用本机既有真 key（DEEPSEEK_API_KEY），走 `yokeos chat`；「跨对话」以新 Session（换 user 标识或重开会话）承载。
- 会话记忆（短期）与上下文截断（max_history_turns）为既有行为，本节不改动、只复用。
