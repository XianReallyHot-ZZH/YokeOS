# YokeOS

学习型项目：以复刻 OryxOS 为路径，掌握 Agent 开发与企业级 AI 编程方法论，长期演化为企业级开源 Agent OS。

## Language — 项目与过程

**第一阶段**:
以学习为最高使命的阶段——复刻 OryxOS 的功能与实现、复刻作者用 AI 编程的过程，终点是第 31 节等价交付。
_Avoid_: MVP、初期版本

**复刻（两层）**:
既指产品层（OryxOS 的功能与实现），也指过程层（作者使用 AI 编程的方法与流程）。单说"复刻"时两层都算。
_Avoid_: 仿写、重写、抄代码

**参照库**:
`vendors/oryxos` submodule，钉在 `origin/main`。教学文章（第 1~32 节课件）不在 main 上，经 `class-30` 分支访问。
_Avoid_: 上游、原仓库、oryxos 项目（指代不清时）

**逐节跟拍**:
按 OryxOS 课程第 16→31 节的顺序与课型复刻；节奏自定，顺序不乱。
_Avoid_: 跟做、按课表

**课型分流**:
四类课程的处理方式：代码课（走完整 spec 流程并产码）、评审课（拒绝产码）、串联课（只固化 E2E）、Demo 课（真实运行与发布）。
_Avoid_: 课程分类、课种

**立项文档链**:
项目初期的核心文档序列：业界调研 → 产品定位 → 需求分析 → 技术方案 → AI 编程指南，五篇与参照库立项期 docs 一一对应（见 ADR 0007）。
_Avoid_: 前置文档、文档准备阶段

**验收报告（acceptance-report）**:
每节课程交付时产出的六项证据文档，含"门禁拦下了什么、怎么修的"。
_Avoid_: 总结、复盘报告（指代验收文档时）

**对照学习层**:
每节复刻后用实践反检自有 SDD 方法论、并做作者工具链与 mattpocock 技能映射对照的学习闭环；沉淀在 `docs/methodology/`。
_Avoid_: 学习笔记（指代该闭环时）

## Language — 产品与定位

**复刻型起步**:
YokeOS 进入 Agent 底座品类的方式：以 OryxOS 为参照实现逐节复刻其运行时内核，第一阶段不做产品功能差异化，差异化立在过程——可追溯的规格、验收证据与方法论沉淀。
_Avoid_: 仿制、贴牌、二次开发（指 YokeOS 与 OryxOS 的关系时）

**Agent 底座（Agent OS）**:
装在企业自己基础设施上、私有可审计、不锁云的运行底座，让一群 Agent 被管起来；YokeOS 自称用"Agent 底座"或"Agent Harness OS"。完整行业定义见 `docs/IndustryResearch.md` 附录 A。
_Avoid_: Agent 平台、智能体中台

**Harness**:
套在模型外面、让"会生成文本的模型"变成"能可靠做事的 Agent"的运行骨架；介于 agent runtime（让一个 Agent 跑起来）与 Agent 底座（让一群 Agent 被管起来）之间。
_Avoid_: 挽具（直译）、Agent 框架（指 Harness 层时）

**人推 / 钟推**:
Agent 的两类触发源：人推 = 人工发起（CLI、REST API），钟推 = `AgentScheduler` 按 cron 到点自动发起，即第三触发源。两个入口汇入同一个 `AgentService`，走同一条执行链路，行为一致、审计同构。
_Avoid_: 自动触发、机器推（指代钟推时）

**锚需求不锚概念**:
定位原则：产品锚在不会变的企业刚需（私有、可控、可审计、跟 Java 体系对齐）上，不锚在可能被稀释或改名的技术概念（如"Agent OS"）上。
_Avoid_: 蹭概念、追风口（表述定位依据时）
