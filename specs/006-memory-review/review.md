# 评审文档：Memory 设计评审（第 21 节，specs/006-memory-review）

> **课型**：评审课（拒绝产码）。**产出定位**：业界方案对照 + 本项目设计定稿，作第 22 节 `/speckit-specify` 的素材——「评审产文档、文档产 spec」。
> **评审输入**：[需] `docs/DemandAnalysis.md` §5.6/§11/§13 · [技] `docs/TechnicalSolution.md` §5/§9.1/§9.2 · [宪] CLAUDE.md 宪法 4/5/7/9 · [指] `docs/AiProgrammingGuide.md` §4.2 · [参] 参照库课件第 21 节（业界数据为其 2026 年口径，未另行联网核实——拍板记录⑤）。
> **教学文档**：`docs/class/021-memory-review.md`（定稿于 2026-09-18，拍板记录见其头部）。

---

## 1. 评审范围与方法

**范围**：核心能力三（Memory 两层记忆）的第一阶段设计，即 [技 §5] 全部条款 + 支撑它的 [技 §9.1]（向量库不进的论证）、[技 §9.2] `memory_entries` 表、[需 §5.6] 两层记忆与「第一阶段不做」清单。不含扩展阶段方案细节（向量三选一等，只评审「留口是否留对」）。

**方法（三读对照法）**：逐条读 [技 §5] → 每个设计点对照业界方案问一句「业界怎么做、我们为什么这样/不这样」→ 落「定稿」（§3）或提「差异」（§4）与「张力」（§5）。文档链内部冲突**修文档优先**；本次评审发现的两处张力经用户拍板裁决（教学文档拍板记录②③），不回改文档链原文，裁决留痕于本文档。

**结论形态**：定稿清单 D1~D11（§3）+ 差异记录三条（§4）+ 张力裁决两条与开放事项（§5）+ 第 22 节 specify 素材要点（§6）。

## 2. 业界方案对照

### 2.1 概念对照：业界术语 → YokeOS 对应物

| 业界概念 | 业界含义 | YokeOS 对应物 | 采纳/偏离 |
|---|---|---|---|
| 短期记忆 / 工作记忆 | 当前对话的上下文，容量有限、生命周期短 | Session（SQLite，`SessionManager`，按 Channel+用户+Agent 联合标识） | 采纳，既有体系即其落地 |
| 长期记忆 | 跨会话持久，存外部用时检索回 | `MEMORY.md` / `memory_entries` / Mem0（三档后端） | 采纳，形态分三档 |
| 情景记忆 | 具体发生过的事 | （无） | **第一阶段不做**（[需 §5.6]），归档区条目是弱形态替身 |
| 语义记忆 | 抽象事实与知识（用户偏好等） | 核心区 + 归档区条目 | 采纳，不进一步细分 |
| 程序记忆 | 怎么做事的流程 | **Skill 体系**（29 节，`skills/` 按名引用） | 采纳但**不归 Memory 管**——程序记忆沉淀为 Skill，两套机制各管各的 |
| 上下文压缩 | 把当前窗口内容变短 | 对话历史按 `max_history_turns` 简单截断（保留 system prompt + 最近 N 轮） | 采纳最简形态，智能总结放扩展 |
| 记忆压缩 | 长期库归并去重淘汰 | 归档区超阈值截断保留最近；更聪明的压缩放扩展 | 采纳最简形态（与 Mem0「add 即消解」的对照见 2.2） |
| 记忆提炼 | 从对话抽取该记的事实 | **Agent 主动调 `save_memory`**，时机与分区由模型显式判断 | **刻意偏离**主流自动抽取（依据见 D11） |
| core memory（MemGPT） | 永不换出、始终在场的小块 | `## 核心记忆` 分区（全量注入、永不截断、不参与检索） | 采纳概念 |
| archival / 外部上下文（MemGPT） | 磁盘侧，用时检索 | `## 归档记忆` 分区（截断 + 关键词检索） | 采纳概念 |
| 记忆操作接口（MemGPT 的启示） | Agent 只见稳定操作，底层换入换出无感 | `MemoryService` 门面 + `LongTermMemoryStore` 可插拔 | 采纳，接口墙思想同源 |
| 作用域分层（Mem0 的 user/session/agent） | 按归属谁切 | 会话层（Session）+ 全局长期层 | **偏离**：YokeOS 按「重要性与生命周期」切，不按归属切；两维度正交可对照 |
| 压力触发整理（MemGPT） | 窗口快满时 Agent 自行总结归档 | （无） | 不采纳，扩展阶段候选（信号驱动） |

### 2.2 项目对照：六个业界项目 → YokeOS 的取舍

| 项目 | 业界做法 | YokeOS 采纳 | YokeOS 偏离/不采纳 | 理由 |
|---|---|---|---|---|
| **MemGPT / Letta** | 记忆即 OS：主上下文=RAM、外部=磁盘；核心记忆永不换出；记忆管理工具交给 Agent；压力信号触发整理 | ①核心记忆分层概念；②「稳定记忆操作」的接口墙（`MemoryService` 门面思想同源）；③「记忆操作做成 Tool 交给 Agent」——`save_memory`/`recall_memory` 正是这个形态 | 不集成运行时；不做压力触发自动整理 | Letta 是同层竞品（有状态 Agent 运行时），集成等于在 Agent OS 里塞另一个 Agent OS，定位就乱了；自动整理解决的问题（漏记）第一阶段无真实信号支撑 |
| **Mem0** | add 即消解（矛盾更新/重复合并）；自动抽取；向量+图+键值混合；user/session/agent 三层作用域；47K+ star；**是库不是运行时** | 集成为第三档 `Mem0MemoryStore`：自托管、REST、`append/load/recall` 翻译 add/get/search，数据不出域；提炼、冲突消解、语义检索交给 Mem0 | 默认档（Markdown/SQLite）不做自动抽取、不做消解——纯 append，更新责任交给 Agent 判断 | 它是库、被设计为被集成，不与运行时定位打架；但自动抽取多一次模型调用、消解是黑盒不易审计，默认档把复杂度与数据攥在自己手里，Mem0 档留给「真要智能记忆且接受外部管线」的场景 |
| **Zep / Graphiti** | 时序知识图谱：边带 valid_from/valid_to，新事实不覆盖旧边而是失效之；LongMemEval 时序基准明显领先（Graphiti≈63.8% vs Mem0≈49%，GPT-4o 口径） | 无（认识其揭示的盲区：核心记忆装的多是**会变**的事实，更新责任要有人担——YokeOS 交给 Agent 主动改写） | 第一阶段不采纳、明确不自造 | 目标场景（运维助手、日报）以「偏好 + 事实陈述」为主，关系/时序密度低；图的上马门槛高于向量（图存储 + 图查询）；真需要时集成可自托管方案比自造理性 |
| **Cognee** | 图原生 ECL（Extract-Cognify-Load）自动提炼管线，14 种检索模式 | 无 | 不采纳 | 自动提炼与图原生都超出第一阶段信号；过重 |
| **LangMem** | LangGraph 生态官方记忆 | 无 | 不采纳 | 生态绑定，YokeOS 自有 ReAct 不在 LangGraph 上 |
| **ReMe** | 文件式透明记忆 | 其思路与默认档同路：`MEMORY.md` 文件式、人可读、git 可跟踪 | 不引入依赖 | 文件式自己就有，透明可控正是选 Markdown 为默认档的理由之一 |

**对照得出的三个总判断**（评审立场）：

1. **记忆是架构选型问题，不是有无问题**（LongMemEval 上架构差 15 分）——所以本节评审的存在本身是必要的，22 节实现严格按定稿走。
2. **YokeOS 的差异化落点不在「记忆更聪明」，而在「记忆可控」**：三档全部可自托管、默认档零外部依赖、审计与数据留在企业基础设施——与「严监管企业、数据不出域」的定位锚点一致。
3. **append 模式的软肋是已知且接受的**（Mem0 解剖点破：同一件事新旧说法会并存矛盾）——第一阶段的解法是「Agent 判断 + 主动改写」，第二解法是切 Mem0 档；不做的是「默默自动消解」。留痕在案，出现真实矛盾投诉再升级（信号驱动）。

## 3. YokeOS 设计定稿（评审冻结清单）

以下 D1~D11 逐条引 [技 §5]（及 §9.1/§9.2）条款，评审确认**冻结**为第 22 节实现依据。每条右侧为业界对照锚点（§2）。

| # | 定稿条款 | 出处 | 业界锚点 |
|---|---|---|---|
| D1 | **`MemoryService` 统一门面**：对 ReAct 循环只暴露一个记忆读写入口；会话记忆委托 `SessionManager`（SQLite Session），长期记忆委托 `LongTermMemoryStore`；组装 prompt 时上层只调它一个 | 技 §5.1 | 接口墙（MemGPT 稳定操作） |
| D2 | **`LongTermMemoryStore` 三方法**：`append(content, scope)`（scope 取 CORE/ARCHIVAL，默认 ARCHIVAL，自动加日期 header）；`load`（核心区全量 + 归档区截断）；`recallByKeyword`（只检索归档区） | 技 §5.1 | core/archival 分层（MemGPT） |
| D3 | **四条行为契约**（所有后端共同遵守）：①不缓存，每次重读；②核心区永不被截断，截断只作用归档区；③写核心还是归档由 Agent 经 `scope` 显式指定，系统不猜；④`recall` 是关键词检索，不做复杂化 | 技 §5.1 | 防坑一/二/四/五（见 §6.3） |
| D4 | **三档后端一次交付**，配置键 `memory.backend` 选择：`MarkdownMemoryStore`（默认，`.yokeos/memory/MEMORY.md`，定位单机档——多副本部署不共享，需分布式选另两档）；`SqliteMemoryStore`（`memory_entries` 表，截断=归档查询 `LIMIT N`、检索=`LIKE`、核心区=`WHERE scope='CORE'` 全量）；`Mem0MemoryStore`（自托管 REST，凭证与地址走环境变量占位）。**换档只改一行配置，`PromptBuilder`/`MemoryTools`/`ReActLoop` 不动** | 技 §5.1 | 接口墙的价值兑现 |
| D5 | **`MEMORY.md` 两分区格式**：`## 核心记忆` / `## 归档记忆` 两个 header，每条记忆带日期 header；格式不做更严格规定（Agent 写什么 LLM 自己理解）；两分区是组织方式区分，不引入独立文件；换后端时分区语义落到 `scope` 列或 Mem0 metadata，**分区约定不变** | 技 §5.2 | core memory（MemGPT） |
| D6 | **注入时机：每次组装 prompt 现读**，不做缓存（契约一）——`save_memory` 后下一轮立刻可见；性能口径：md 档每次读小文件 1~2ms、SQLite 档毫秒级查询、Mem0 档一次局域网 REST，百级并发可接受（技 §14）；cache+失效放扩展 | 技 §5.3 | 裁决一（§5.1） |
| D7 | **`MemoryTools`**：`save_memory` + `recall_memory` 两个内置 Tool，标注 `@Tool` 注册进 `ToolRegistry`，与其他内置 Tool 一视同仁；模块落位 `yokeos-memory`（裁决二，§5.2） | 技 §5.1 + 宪法 5 解读 | 记忆操作交给 Agent（MemGPT） |
| D8 | **`USER.md` 与 `MEMORY.md` 边界**：前者用户手写、YokeOS 只读、初始设定；后者 Agent 经 `save_memory` 写入、读写、成长记录；两者都进 system prompt 但来源与生命周期不同 | 技 §5.4 | 防坑三 |
| D9 | **`memory_entries` 表**（仅 SQLite 档）：`id`/`scope`（CORE/ARCHIVAL）/`content`/`created_at`；**手工建表脚本**，与 sessions/审计表同口径，不走 `ddl-auto=update` | 技 §9.2 + 宪法 7 | 防坑六 |
| D10 | **会话记忆归属既有 Session 体系**：本节不新增会话存储概念，`MemoryService` 委托而非重造；上下文超限沿用既有简单截断（`max_history_turns`，技 §4） | 技 §5.1/§4 | 短期记忆=Session |
| D11 | **第一阶段不做**（逐项照搬技 §5.5）：自动抽取（Markdown/SQLite 档不做，Mem0 档自带能力随档启用）；内置向量库（三档都不在 YokeOS 进程内自建向量层，语义检索切 Mem0 档由外部承担；进程内向量层 LanceDB/pgvector/JVector 三选一放扩展，技 §9.1）；情景记忆；Memory Wiki（结构化 claim/evidence、矛盾检测）；记忆压缩（超长简单截断）。**两条偏离依据**（评审补全，防 22 节被挑战）：①不做自动抽取——「漏记」在第一阶段只是推测无真实信号；ReAct 里 Agent 本可随时主动调 Tool，路径已有工程量近零；自动提炼要多一套「何时触发」判断逻辑 + 每次多一次模型调用。②不上向量——三个信号（关键词检索开始找不准 / 记忆量过千 / 出现跨会话精准召回的真实需求）一个都没出现之前不上；且 LanceDB Java 本地嵌入式未 GA、Qdrant/Chroma/Milvus 要外部进程、pgvector 要外部 PG、JVector 成熟度待验证（技 §9.1） | 技 §5.5/§9.1 | 刻意偏离主流（§2.2） |

**定稿总口径（对外叙述用）**：两层记忆（会话 + 长期），长期内部分核心/归档两分区；不照搬业界「三层」叫法（差异二，§4）。

## 4. 与参照实现的差异记录

与参照课件（oryxos 第 21 节）立场的显式分叉，全部有文档链背书，评审只做留痕：

| # | 差异 | 参照立场 | YokeOS 立场 | 理由 |
|---|---|---|---|---|
| 差异一 | 后端档位交付节奏 | 阶段一纯文件 + 关键词；SQLite/向量放阶段二信号驱动 | **三档第一阶段一次交付**（Markdown 默认 + SQLite + Mem0 集成），但三档都**不含进程内向量层**——「向量为扩展、信号驱动」的升级立场与参照一致 | 企业部署形态差异前置：多副本部署需要 SQLite 档 day one（md 单机档不共享）；数据不出域诉求需要 Mem0 档 day one。且三档共享同一接口墙，第二、三档是同一契约的换实现，增量成本可控——接口墙的红利正是为此 |
| 差异二 | 术语口径 | 「三层记忆」（核心/会话/归档并列） | 「两层（会话 + 长期）+ 长期内两分区（核心/归档）」 | 与 [需 §5.6]「两层记忆」及 CLAUDE.md「MEMORY.md 两分区」口径一致，对外叙述自洽；语义同构（会话=短期，长期=核心+归档） |
| 差异三 | Mem0 的引入时机 | 扩展阶段「可能集成」的候选 | 已拍板进第一阶段第三档 | YokeOS 锚点场景是严监管企业、数据不出域；自托管 Mem0 是「真要智能记忆」的现成答案，前置成一档让升级路径 day one 可验证 |

## 5. 张力与遗留裁决

### 5.1 裁决一：注入时机（需 ↔ 技）——以技为准

[需 §5.6]「Agent 启动时 MEMORY.md 整个文件作为长期上下文注入 system prompt」 vs [技 §5.3]「每次组装 prompt 现读、不缓存」。

**裁决（2026-09-18 拍板②）：以技为准——每次组装 prompt 现读。** 依据：「写入下一轮立即可见」是功能需求（Agent 调 `save_memory` 后当轮后续/下轮必须看得到），启动时注入 + 常驻缓存做不到；启动时只注入一次还意味着记忆跨进程不新鲜。需求文档的「启动时」是粗粒度表述，**不回改**，本裁决为 22 节唯一依据（防坑二的正身）。

### 5.2 裁决二：`MemoryTools` 模块落位（宪法 5 字面 ↔ 模块表）

宪法 5「内置 Tool……合并在 `yokeos-tool` 一个模块，不拆分」 vs CLAUDE.md 模块表把 `MemoryTools`（两个内置 Tool）放 `yokeos-memory`。

**裁决（2026-09-18 拍板③）：`MemoryTools` 随能力三落位 `yokeos-memory`，注册进 `ToolRegistry` 与其他内置 Tool 一视同仁。** 依据：宪法 5 的意图是防 **Tool 基础设施**（`ToolRegistry`、MCP、Sandbox、Notify 与文件/Shell/HTTP 等通用内置 Tool）拆模块导致依赖混乱；`MemoryTools` 是能力三的组成部分，依赖方向 `yokeos-memory → yokeos-tool`（用其注册与契约），单向无循环，不触宪法 5 要防的场景。宪法文本不改，本解读随本评审留痕，22 节照此落位。

### 5.3 开放事项（不阻塞第 22 节开工，plan 阶段定稿）

| # | 事项 | 现状 | 归属 |
|---|---|---|---|
| O1 | 归档截断阈值的配置键名 | [技 §5.1] 只说「阈值可在配置调整」（默认 4000 字），未定键名 | 22 节 plan 定稿 |
| O2 | Mem0 档配置键名（地址/凭证） | [技 §5.1] 只说「环境变量占位」，未定键名 | 22 节 plan 定稿 |
| O3 | `memory.backend` 缺省值 | [技 §5.1] 已定 Markdown 为默认档，配置缺省值照此（`markdown`） | 无需再议，plan 照抄 |
| O4 | 关键词匹配的精确语义 | 契约四只约束「不复杂化」；Markdown 档行匹配还是全文子串、SQLite 档 `LIKE` 形态，未细化 | 22 节 plan 细化（不得升级成语义/向量） |
| O5 | Mem0 档的集成测试策略 | 自托管 Mem0 需真实实例；冷缓存/无实例时 `assumeTrue` 跳过的口径 | 22 节 harness 设计（参照 20 节 MCP 冷缓存先例） |

## 6. 结论：第 22 节 specify 素材要点

### 6.1 六段式骨架预填（供 `/speckit-specify` 组装参数，只写 WHAT/WHY）

```text
第22节需求：Memory 两层记忆——让 Agent 跨对话记得住用户
背景与价值。Agent 底座区别于 chatbot 的核心体验：用一段时间后 Agent 自然记住
  用户偏好、项目信息、关键决策，下一次对话不需要重新解释（需 §5.6 用户核心体验）。
  大模型本身无状态，全靠调用方管理上下文；Memory 就是在有限 context window 之外
  管理一套让 Agent 记得住的机制。
用户场景。
  ① 用户首次告诉 Agent「我们项目用 Java 21、部署在 K8s」，Agent 判断该记、写入
     核心区；此后每次新对话 Agent 都自带这条背景，不再要求重复解释。
  ② 数周后用户问「上次定的技术选型是什么」，Agent 检索归档区找到旧结论并引用。
  ③ 团队部署偏好变了，Agent 改写核心记忆（旧说法被更新而非并存打架）。
功能需求（候选，specify 时按此裁剪）。
  FR1 长期记忆经统一门面读写，上层（循环、prompt 组装）不感知后端形态
  FR2 save_memory(content, scope?)：写入长期记忆；scope 取 core/archival、
     缺省 archival；写入自动附日期
  FR3 recall_memory(query)：关键词检索，只检索归档区
  FR4 每次组装 prompt 现读长期记忆（核心区全量 + 归档区截断后），不缓存；
     写入后下一轮立即可见
  FR5 核心区永不截断；归档区超阈值（默认 4000 字，可配置）保留最近内容
  FR6 三档后端经 memory.backend 选择：markdown（缺省）/ sqlite / mem0；
     换档只改这一行，上层不动
  FR7 默认档操作 .yokeos/memory/MEMORY.md：核心记忆 / 归档记忆两分区 header 组织
  FR8 sqlite 档落 memory_entries 表（scope/content/created_at，手工建表脚本）
  FR9 mem0 档经 REST 集成自托管实例（append/load/recall → add/get/search），
     地址与凭证走环境变量占位
  FR10 会话记忆复用既有 Session 体系，门面统一对外，不新增会话存储概念
  FR11 save_memory / recall_memory 作为内置 Tool 注册进 ToolRegistry，
     与其他内置 Tool 一视同仁
明确不做（边界）。自动抽取（mem0 档自带能力随档启用，markdown/sqlite 档不做）；
  进程内向量库；情景记忆；Memory Wiki（结构化 claim/evidence、矛盾检测）；
  记忆压缩（超长简单截断）。
验收标准。自动化由 22 节验收 harness 承载（mvn test 全绿），关键回归点 = 6.3
  坑↔测试表逐条；人工项：真模型跨对话演示（Agent 记住偏好并在后续对话用到，
  需 §11 第 22 节行「可演示成果」口径）。
依赖与假设。前序交付物：Profile.tools（16）、PromptBuilder 四段组装与
  ToolExecutor/ReActLoop（17）、SessionManager（18）、ToolRegistry 与
  AnnotatedToolAdapter（20）；无新增进程内依赖（mem0 档为可选 REST 集成，
  复用既有 HTTP 能力）；设计依据 = specs/006-memory-review/review.md 定稿 D1~D11。
```

### 6.2 设计依据索引（specify/plan 取材跳转表)

| 素材 | 位置 |
|---|---|
| 接口契约、四条行为契约、三档后端、注入时机 | 本文档 §3 D1~D7 |
| `memory_entries` 表字段 | [技 §9.2] |
| 向量不进的论证与扩展三方案 | [技 §9.1] |
| 落位与依赖方向（`yokeos-memory`，裁决二） | 本文档 §5.2 |
| 配置键待定项（O1/O2/O4/O5） | 本文档 §5.3 |
| 模块落位参照 | [技 §10] 模块表 `yokeos-memory` 行 |

### 6.3 坑 ↔ 22 节回归测试点（评审点名，harness 承接）

| 坑 | 症状 | 修复（定稿锚点） | 22 节回归测试点 |
|---|---|---|---|
| 坑一：`MEMORY.md` 无限膨胀 | 注入超 context window | 归档区超阈值截断保留最近，核心区永不截断（D3 契约二/D5） | 超长归档区被截、核心区一字不少 |
| 坑二：`MEMORY.md` 做缓存/预载 | `save_memory` 后下一轮看不见 | 每次组装现读不缓存（D3 契约一/D6/裁决一） | 写入后下一次组装立即可见 |
| 坑三：把 `USER.md` 当 `MEMORY.md` 写 | Agent 改掉用户初始设定 | USER.md 只读，成长记录只进 MEMORY.md（D8） | Memory 写路径碰不到 USER.md |
| 坑四：scope 系统猜 | 核心区塞满或该进核心的进归档被截 | Agent 经 scope 显式指定，缺省 archival（D3 契约三/D2） | 不传 scope 落归档、传 core 落核心 |
| 坑五：检索/截断越界作用核心区 | 核心记忆被重复注入或丢字 | 截断与检索只作用归档区（D3 契约二/四） | `recallByKeyword` 只匹配归档区 |
| 坑六：`memory_entries` 走 ddl-auto | SQLite ALTER TABLE 弱、迁移报错 | 手工建表脚本与审计表同口径（D9/宪法 7） | 建表走 schema.sql、列可存可读 |

### 6.4 评审结论

**通过。** [技 §5] 设计经业界对照后维持原样冻结（D1~D11），无需回改文档链；两处张力已裁决留痕；五项开放事项移交 22 节 plan 定稿；22 节可凭本文档直接起手 specify。
