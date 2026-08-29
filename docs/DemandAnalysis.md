# YokeOS 需求文档

> 本文档定义 YokeOS 项目的功能需求和非功能需求，作为后续技术方案设计、研发实施、测试验收的依据。本文档回答 **What**，不回答 **How**，How 在后续的技术方案中展开。前置阅读《YokeOS 行业调研》（`docs/IndustryResearch.md`）与《YokeOS 产品定位》（`docs/yokeos.md`），本文档基于调研得出的领域判断与定位拍板，不重复论证企业 Agent 底座领域的现状。

---

## 1. 项目概述

### 1.1 YokeOS 是什么

YokeOS 是基于 Java 实现的面向企业场景的 **Agent 底座（Agent Harness OS）**。它装在企业自己的 K8s 或服务器上，作为统一底座，在底座上跑各种业务 Agent（运维助手、客服助手、HR 助手、销售助手、知识管理助手等），共享一套渠道接入、模型路由、工具调用、记忆系统、沙箱执行能力。数据完全留在企业自己的基础设施，不锁任何云生态。

这个位置上已有先行者：业界开源项目已经把「让 Agent 可靠跑起来」的这套设计在个人与小团队场景验证过（OpenClaw 用 Node.js，Hermes Agent 用 Python）；Java 生态里，OryxOS 已以 Agent OS 为定位起步、发布了可用版本——这一层刚起步、仍稀薄，还没有规模化实现。Java 是大量企业现有后端的事实标准技术栈，Spring AI Alibaba 已经把底层 LLM 调用解决了，缺的就是上面那一层「运行和管理一群 Agent 的底座」。YokeOS 补这个位置，并以 OryxOS 为参照实现「复刻型起步」：同样的技术栈与模块边界，第一阶段能力清单上它能对到的，OryxOS 都有，不追求产品功能差异化；差异化立在另一条轴上——全程规格驱动构建，每一步都有可追溯的规格与验收证据。YokeOS 与 OryxOS 的关系是明确的：**同类、同栈、同锚点的后来者与参照实现**，不掩饰这个起点。

#### Agent runtime、Harness 与 Agent 底座的分层

**Agent 底座**跟 **agent runtime**（Agent 运行时）、**Harness** 不是一回事：

- **agent runtime**：让单个 Agent 跑起来的执行内核，负责 LLM 调用、工具执行、上下文管理、循环控制
- **Harness**：套在模型外面、让「会生成文本的模型」变成「能可靠做事的 Agent」的运行骨架：驱动 reason → act → observe 循环、组装上下文、约束工具边界、记录审计
- **Agent 底座**：内核包含一个 agent runtime 和它的 harness，但在其之上还要管多个 Agent 的生命周期、统一的对外对内接入、统一记忆、多租户、审计这些 OS 级治理能力

借操作系统类比，runtime 像单个进程的执行环境，Agent 底座像管理一群进程、调度资源、提供共享服务和治理的那层。一句话：runtime 和 harness 让一个 Agent 跑起来、跑得对，**Agent 底座让一群 Agent 在企业里被管起来**。

#### 交付分三段

理解这个分层，才能看懂 YokeOS 的交付节奏：

1. **第一阶段**：先把 Agent 底座的单机运行时内核用 Java 做扎实，这一层在能力上对齐业界开源 Agent OS 的基础层
2. **扩展阶段**：能力补齐（知识库与语义记忆）与底座分布式（状态外置、多副本、高可用），加上真正的差异化治理层（多租户、SSO、完整审计、Tool 治理）
3. **长期**：跨节点 Agent 协作（对接 A2A），由扩展阶段和社区共建陆续补齐

第一阶段交付的是 Agent 底座的**内核底座**，而不是一个治理能力完备的企业级底座，后者是终局，第一阶段是地基。

---

### 1.2 YokeOS 能干什么

YokeOS 优先做六个核心能力，基于这六个能力可以扩展出企业里大量真实需求。这六个能力都属于「让单个 Agent 跑得好、一群 Agent 被管起来」的运行时内核层；让 YokeOS 成为真正「OS」的多 Agent 治理能力（多租户、Tool Policy、完整审计、SSO），在扩展和社区阶段补齐。

#### 能力一：对接 LLM

YokeOS 通过 Provider 抽象层对接主流大模型（DeepSeek、通义、Kimi、智谱、混元、豆包、Anthropic、OpenAI 等），Agent 不感知具体调的是哪家模型，运行时切换无 lock-in，支持本地推理。

**基于这个能力可以做的事：**
- 任意业务场景的自然语言对话助手，Agent 通过 LLM 理解用户意图、给出回复
- 同一个 Agent 在不同任务用不同模型，简单任务走便宜模型、复杂任务走强模型
- 接入企业自有的本地推理服务（Ollama、vLLM），数据完全不出企业
- 多 Provider 并存通过显式映射区分，做一份报告可以让规划用便宜模型、综合用强模型

#### 能力二：ReAct 循环

ReAct（Reason + Act）是 Agent 的核心工作机制：Agent 接到一个任务后，LLM 思考要不要调工具、调哪个工具，调用之后看结果，再决定下一步，直到给出最终响应。

**基于这个能力可以做的事：**
- Agent 能自主决定何时调用哪个工具，不需要业务方写死流程
- 多步骤任务可以一次对话内连续完成（先读文件、再分析、再调 API、再生成报告）
- Agent 出错时能自己重试、换工具、走别的路径
- 复杂业务流程不需要预先编排，Agent 在运行时动态决定执行路径

#### 能力三：Memory 两层记忆

Agent 记得住用户的偏好、项目、决策、对话历史。两层记忆设计，第一阶段先实现会话和长期两层，情景记忆放扩展阶段补齐：

| 层次 | 说明 | 第一阶段 |
|------|------|---------|
| 会话记忆 | 当前对话的完整历史，过长时截断保留近期 | ✅ 实现 |
| 长期记忆 | 用户偏好、项目背景、关键事实，存在 MEMORY.md 文件里，跨对话保留 | ✅ 实现（极简版） |
| 情景记忆 | 每个任务过程中学到的东西，修改了什么文件、做了什么决策 | ⏳ 扩展阶段 |

**基于这个能力可以做的事：**
- Agent 跨多次对话记住用户偏好（「我一般用 Spring Boot 不用 Spring MVC」）
- 长任务过程中状态保持，对话中断后能恢复继续做
- 团队内多个 Agent 共享同一个用户的偏好记忆
- 历史决策可追溯（「上次为什么选 DeepSeek 不选 Kimi」在记忆里能查到）

#### 能力四：工具体系

Agent 能调用工具实际操作系统。YokeOS 提供两类 Tool：

- **内置 Tool**：YokeOS 自带的基础工具（读写文件、执行 Shell、发起 HTTP 请求、记忆读写、通知推送）
- **扩展 Tool**：业务方自己扩展的工具，按门槛从低到高有三种方式

| 方式 | 门槛 | 做法 | 适用场景 |
|------|------|------|---------|
| 零代码 | 最低 | 写 Agent 目录（AGENT.md）+ 复用社区现成 MCP server | 业务方只描述意图，LLM 自己组合调用 |
| 轻代码 | 中等 | 用任何语言写 MCP server | 接入企业自有系统（ERP、CRM） |
| 重代码 | 最高 | 用 `@Tool` 注解写 Java Spring Bean | 深度集成，性能最好 |

**基于这个能力可以做的事：**
- 给 Agent 接入企业自己的 ERP、CRM、CMDB，让 Agent 真正能干企业的活
- 接 GitHub、Jira、Confluence，做研发助手
- 接 Prometheus、Grafana、SSH，做运维自愈
- 业务方零代码扩展，写 Agent 目录 + 复用 MCP，纯 markdown 就能上线新场景

#### 能力五：通知与定时

Agent 干完活要能触达人，没人触发也要能自己跑。Notify 把执行结果推到配置的通知渠道（第一阶段内置 Webhook 适配，企业 IM 群机器人皆经 Webhook 接入）；定时任务让每个 Agent 按 cron 到点自动运行，作为继 CLI、REST API 之后的**第三触发源**，与另外两个入口复用同一条执行链路。

**基于这个能力可以做的事：**
- 日报、巡检、摘要类任务到点自动运行，不需要人守着触发
- 执行结果主动触达（群机器人 Webhook 推送），人不用盯着屏幕等
- 同一个 Agent 不管从 CLI、REST API 还是定时触发，走的是同一条执行链路，行为一致、可对照
- 定时执行历史落库可查，到点没跑、跑了什么，都有据可查

#### 能力六：对外服务（Web Service）

YokeOS 通过完整的 REST API 把所有能力对外暴露，业务系统用 HTTP 调一下就能用上 Agent，不用关心内部怎么实现。Web Service 是 YokeOS 的对外门面，是企业把 AI 能力嵌入已有业务系统的唯一通道。同时附 Web 管理台第一版：给运营方一个不碰命令行的观察与管理窗口。

API 覆盖六类操作：

| 类别 | 端点功能 |
|------|---------|
| 会话管理 | 创建会话、发消息、查历史、归档会话 |
| Agent 调用与动态管理 | 无状态调用一次 Agent；一句话生成定义草稿、增删改查 Agent 目录 |
| Profile 信息 | 列运行配置、看详情 |
| Memory 操作 | 查长期记忆 |
| Tool 信息 | 列可用 Tool、看元信息 |
| 系统状态 | 健康检查、运行信息、Provider 状态 |

#### 关于 Channel

第一阶段还有一个基础模块是 Channel（消息接入渠道）。Channel 主要解决「消息进来、响应出去」，第一阶段只内置 CLI 一种，企业微信、飞书、钉钉等 IM Channel 放扩展阶段。Channel 是核心功能模块，但它不算「六个核心能力」之一。

#### 六个能力组合可以解决的场景

| 场景 | LLM | ReAct | Memory | Tool | Web Service |
|------|-----|-------|--------|------|-------------|
| 全渠道客服 | 理解用户问题 | 循环调知识库 | 记住客户历史 | 接 CRM | HTTP 接入客服系统 |
| 运维助手 | 分析告警 | 调日志查询+重启 | 记住历史故障 | 接 Prometheus/SSH | Webhook 触发 |
| 研发助手 | 理解需求 | 读代码改代码 | 记住项目惯例 | 接 GitHub/CI | IDE 插件接入 |
| 知识管理 | 理解问题 | 检索文档 | 记住团队约定 | 接 Confluence | 内网门户嵌入 |
| 销售助手 | 拼装客户画像 | 调 CRM+企查查 | 记住客户偏好 | 接销售系统 | 销售 App 调用 |
| 数据分析 | 生成 SQL | 执行查询+出图 | 记住业务表结构 | 接 BI 系统 | BI 工具集成 |

---

### 1.3 文档定位

本文档按三档分级定义 YokeOS 的功能需求：

1. **核心功能**：最短链路，跑通「放一个 Agent 目录、跟它对话、它能调用工具、能到点自跑并触达人」这件事，对应 Agent 底座的运行时内核
2. **扩展功能**：生产级使用必需但不在核心链路上的能力，包含知识库与语义记忆、底座分布式，以及企业级治理层（多租户、SSO、完整审计、Tool Policy）
3. **社区共建功能**：长期方向，开放给社区贡献

第一阶段的范围对齐参照实现公开构建过程的课程节序（第 16→31 节），逐节等价交付（见第 11 章）。这是极强的范围约束，核心功能范围必须收得很紧，只覆盖运行时内核的最短跑通链路。

---

## 2. 术语和概念

为避免歧义，统一核心术语定义（对齐业界开源 Agent OS 事实标准）：

| 术语 | 定义 |
|------|------|
| **Agent（智能体）** | 一个具象的业务智能体，定义本体是一个目录 `.yokeos/agents/<name>/`：`AGENT.md`（frontmatter 是运行配置、正文是任务指令）加可选附属资源（脚本、参考材料）。一个目录就是一个完整可用的业务 Agent，不是写代码写出来的 |
| **Profile（配置）** | 底座内部的运行时宿主配置对象，决定一个 Agent「怎么跑」：绑定的 LLM Provider、可用 Tool 列表、Channel、通知渠道、定时规则和 Tool Policy。它不是一份单独手写的 YAML——`AgentLoader.deriveProfile()` 把 `AGENT.md` 的 frontmatter 派生成 `Profile` |
| **Provider（供应商）** | LLM API 服务的抽象，实现统一接口让 Agent 不感知具体调的是哪家模型 |
| **ReAct 循环** | Agent 的核心工作机制，Reason + Act。LLM 思考是否调用工具，调用后看结果，再决定下一步，直到给出最终响应 |
| **Tool（工具）** | Agent 可以调用的外部能力。内置 Tool 是 YokeOS 自带的（文件、Shell、HTTP、通知推送）；扩展 Tool 是业务方自己写的 |
| **Memory（记忆）** | Agent 跨对话保留的状态，分两层：会话记忆、长期记忆（MEMORY.md）；情景记忆放扩展阶段 |
| **Channel（渠道）** | Agent 对外接入的消息入口，包括 CLI、企业微信、飞书、钉钉、Slack 等 |
| **Web Service** | YokeOS 对外暴露的完整 REST API，是业务系统集成 YokeOS 的唯一通道 |
| **Session（会话）** | 用户和 Agent 一次对话的上下文容器，包含对话历史、当前上下文、临时变量 |
| **Sandbox（沙箱）** | 工具执行的隔离环境。第一阶段是应用层白名单校验，扩展阶段补容器级隔离 |
| **Tool Policy（工具策略）** | 控制 Agent 可用工具的允许或拒绝规则，在 Profile 级别配置 |
| **Skill（技能）** | 公共能力实体，存 `.yokeos/skills/<name>/`，每个子目录一个 SKILL.md（兼容 agentskills.io 开放标准）；Agent 在 `AGENT.md` frontmatter 按名引用，底座把引用到的 Skill 正文注入 system prompt。Skill 不是 Tool，不进 `ToolRegistry` |
| **Bootstrap（引导文件）** | 加载到系统提示词中的上下文文件：AGENTS.md（项目级 agent 行为说明）、SOUL.md（agent 人格定义）、USER.md（用户偏好） |
| **Workspace（工作区）** | YokeOS 实例的工作目录，默认是 `.yokeos/`，包含 Agent 目录、全局 Skill 库、Bootstrap 文件、记忆、会话、产出物、日志的子目录 |

---

## 3. 设计目标

YokeOS 的核心目标可以用四个词概括：**统一、私有、易接入、可观测**。

| 目标 | 说明 |
|------|------|
| **统一** | 企业内多个业务 Agent 共享同一套底座。Channel、Provider、Tool、Memory、Sandbox 这些公共能力下沉到 YokeOS，企业上一个新 Agent 放一个 Agent 目录（一份 `AGENT.md`）就能跑起来 |
| **私有** | 数据完全留在企业自己的基础设施上，部署在企业自己的 K8s、虚拟机或物理机上。YokeOS 本身不收集任何企业数据，不锁任何云 |
| **易接入** | 基于 Spring Boot 的标准 Java 工程结构，跟企业现有的 ERP、CRM、CMDB、SSO、监控系统直接对接，运维工具链复用现有 Java 生态 |
| **可观测** | 标准的 Prometheus 指标、结构化 JSON 日志、健康检查接口、Web 管理台，适配企业现有监控告警体系；LLM 调用与工具调用两路审计从第一天落库 |

---

## 4. 典型场景

以下三个典型场景描述 YokeOS 完整形态（含扩展阶段能力）下的目标用法，第一阶段先具备其运行时内核。

### 场景一：运维助手

某中型 SaaS 公司的运维团队基于 YokeOS 搭一个运维助手，接入企业微信。Agent 配了几个 Tool（告警分诊、日志查询、服务重启、变更审批）。凌晨告警通过 webhook 进 YokeOS，Agent 收到告警后调用日志查询 Tool 拉错误堆栈，跟历史故障库交叉引用发现是已知 bug，自动应用 mitigation Skill 重启服务，在企业微信运维群里汇报「已自愈，详情见附件」，值班工程师早晨起来看下记录就行。

**YokeOS 在此场景的角色**：Channel 接入（企业微信）、Provider 路由（主备 LLM）、Tool 调用（SSH、Prometheus、Slack 通知）、Memory（历史故障库）、Skill（自愈 runbook）。

### 场景二：知识管理助手

某金融企业的法务团队基于 YokeOS 搭一个知识管理 Agent，接入飞书。Agent 索引了内部的合同模板、法规文档、历史案例、咨询记录。员工在飞书里问「上次签 SaaS 服务协议是怎么处理数据出境条款的」，Agent 检索 Memory 拉出历史案例，综合相关法规给出建议草稿，标注引用来源。

**关键点**：Memory 检索准确度和引用追溯（合规要求所有 Agent 回复必须可追溯到引用源）。

### 场景三：销售助手

某制造业企业的销售部门基于 YokeOS 搭一个客户洞察 Agent，接入企业微信和 CRM。销售跑客户前问 Agent「明天去拜访 A 公司，有什么我需要知道的」，Agent 调用 CRM connector 拉客户历史交易记录，调用企查查 MCP 工具查最新工商信息，调用知识库 Tool 提取关键决策人和采购习惯，综合输出客户简报。

**YokeOS 在此场景的核心能力**：MCP 集成（外部数据）、企业 IT 系统 connector（自家 CRM）、Tool 编排。

---

## 5. 核心功能

> 核心功能是第一阶段内必须完成的最短链路，对应 Agent 底座的运行时内核。目标是跑通一个完整链路：放一个 Agent 目录配置一个 Agent，通过 CLI 或 REST API 跟它对话，它能调用 LLM 和工具完成任务，能到点自跑并推送通知，并能通过 REST API 对外暴露。

### 5.1 工作区初始化

YokeOS 的工作目录是 `.yokeos/`，通过 `yokeos init` 命令初始化。

```bash
yokeos init   # 在当前目录下创建 .yokeos/ 工作区
```

初始化后的目录结构：

```
.yokeos/
├── agents/            # 每个子目录 = 一个 Agent（AGENT.md + 可选附属资源）
├── skills/            # 公共 Skill 库（每个子目录一个 SKILL.md + 可选附属资源）
├── output/            # Agent 产出物
├── memory/
│   └── MEMORY.md      # 长期记忆文件
├── sessions/          # 会话历史
├── logs/              # 结构化日志
├── AGENTS.md          # Bootstrap：项目级 agent 行为说明
├── SOUL.md            # Bootstrap：默认 agent 人格定义
└── USER.md            # Bootstrap：用户偏好
```

- 三个 Bootstrap 文件在 Agent 启动时被自动加载到系统提示词，让 Agent 知道项目背景、自己的身份、用户偏好
- `yokeos init` 幂等：已存在的目录和文件一律不覆盖。六个子目录建好后，用 `yokeos profile create <name>` 生成第一个 Agent 目录

---

### 5.2 定义一个 Agent：AGENT.md

一个目录 = 一个 Agent。`.yokeos/agents/<name>/AGENT.md` 由两部分组成：**frontmatter** 是这个 Agent 自己的 profile（用哪个 Provider/模型、能用哪些 Tool、绑定哪个 Channel、要不要定时、往哪推送），**正文**是任务指令。`AgentLoader.deriveProfile()` 把 frontmatter 派生成底座认识的 `Profile`。

**AGENT.md frontmatter 结构：**

```yaml
name: string                    # Agent 名（= 目录名）
description: string             # 描述

identity:
  agent_name: string            # Agent 名称
  prompt: string                # 人格/系统提示词（或引用 SOUL.md）

provider:
  name: string                  # Provider 名称（deepseek/qwen/kimi 等）
  model: string                 # 模型名
  temperature: float            # 温度参数（可选）

tools:
  - string                      # 可用 Tool 名称列表

skills:
  - string                      # 按名引用全局 Skill 库（.yokeos/skills/<名>/），正文注入 system prompt

mcp_servers:
  - string                      # 引用的 MCP Server 列表

channels:
  - name: string                # Channel 名称
    config: {}                  # Channel 配置

notify:
  channels:                     # 通知渠道（第一阶段内置 Webhook 适配）
    - name: string
      type: webhook
      config: {}                # Webhook 地址等

schedules:
  - cron: string                # 到点自动触发的 cron 规则（第三触发源）

bootstrap:
  - string                      # Bootstrap 文件列表

settings:
  max_iterations: 10            # 最大 ReAct 迭代次数
  max_history_turns: 20         # 最大对话历史轮数
```

**Agent 管理命令**（命令组名沿用 `profile`，操作的是 `.yokeos/agents/` 下的 Agent 目录）：

```bash
yokeos profile create <name>    # 创建 Agent（生成最小 AGENT.md 模板）
yokeos profile list             # 列出全部 Agent
yokeos profile show <name>      # 查看某个 Agent 的 AGENT.md
yokeos profile delete <name>    # 删除 Agent（整个目录）
```

第一阶段支持创建并管理多个 Agent，多个 Agent 可以在同一个 YokeOS 实例上并存，这是「OS」在第一阶段的最小体现。

---

### 5.3 动态管理：一句话生成、上传即上线

Agent 目录的增删改查全程**免重启**，三条入口等价：

1. **REST API**：一句话生成定义草稿（一次 LLM 调用产出规范的 `AGENT.md`，**原样返回预览、不落盘、不注册**），人过一眼、可改（尤其定时时刻、工具权限），确认后再走创建接口落盘并注册；列表、查看、更新、删除同组端点（见 5.10）
2. **Web 管理台**：Agent 管理页走同一组 API，覆盖「一句话新建 → 预览可改 → 创建 → 编辑 → 删除」闭环；另有工作区页浏览目录树与文件
3. **直接丢目录**：把写好的 Agent 目录放进 `.yokeos/agents/`，底座监测到目录变化后校验并加载，即插即用

生成动作落审计；LLM 产出非法定义时返回明确的校验错误，不静默失败。一句话生成所用的系统默认 Provider/模型走独立配置键，与具体 Agent 的 Provider 配置区分。

**第一阶段不做**：草稿审批流、Agent 版本管理、跨实例同步。

---

### 5.4 Provider 抽象（核心能力一：对接 LLM）

Provider 是 LLM 调用的统一抽象。所有 LLM 调用通过 Provider 接口走，Agent 不感知具体调的是哪家。

第一阶段直接基于 Spring AI Alibaba 的 `ChatClient` 实现。Spring AI Alibaba 已经做好了主流 LLM（DeepSeek、通义、文心、Kimi、智谱、混元、豆包、Anthropic、OpenAI 等）的 connector，YokeOS 把它们包装成 Provider，不重复造轮子。多 Provider 并存通过显式映射区分，不靠类型扫描。

每个 Provider 实例配置：
- `provider 名`（deepseek、qwen、kimi 等）
- `模型名`
- `API key`
- `可选的 base URL`

**第一阶段不做**：fallback 和 hedge racing。Provider 故障时直接报错给 Agent；成本透明只做基础版（每次 LLM 调用记录 token 使用量、Provider、模型落到审计表）。

---

### 5.5 ReAct 循环（核心能力二：Agent 大脑）

ReAct 循环是 Agent 的核心工作机制，也是 YokeOS 最关键的一段代码。

**核心算法（Reason + Act）：**

```
接到用户消息
  └─ 追加到 Session 对话历史
     └─ 组装 Prompt（system prompt + Bootstrap + 长期记忆 + 对话历史 + 可用 Tool 列表）
        └─ 调用 LLM Provider 获取响应
           ├─ [无 Tool 调用] → 返回最终响应
           └─ [有 Tool 调用] → 执行 Tool，把结果追加到对话历史 → 继续循环
```

达到最大迭代次数（默认 10 次）强制结束。

**实现要点：**
- 核心循环约数十行 Java 代码，自己实现而不依赖 Spring AI 的 Agent 抽象
- 最大迭代次数可在 Agent 定义里覆盖
- 每次 LLM 调用和 Tool 调用都记录结构化日志并写入审计表

**第一阶段不做**：Tool 调用并行、上下文动态压缩、Agent 间任务委托。

---

### 5.6 Memory 两层记忆（核心能力三：让 Agent 记得住）

**第一阶段实现会话 + 长期两层（情景记忆放扩展阶段）：**

#### 会话记忆

- 当前对话的完整历史，按 Channel + 用户 + Agent 联合标识
- Session 数据持久化到本地 SQLite，重启后可以恢复
- 上下文超过 LLM context window 上限时简单截断早期对话

#### 长期记忆（极简版）

- 存在 `.yokeos/memory/MEMORY.md` 一个 Markdown 文件，跨所有对话保留
- Agent 通过两个内置 Tool 主动读写：
  - `save_memory(content)`：把要长期记住的事追加到 MEMORY.md
  - `recall_memory(query)`：按关键词检索 MEMORY.md 里的相关内容
- Agent 启动时 MEMORY.md 整个文件作为长期上下文注入到 system prompt
- 文件超过 4000 字时简单截断（扩展阶段做压缩）

**第一阶段不做**：自动从对话中抽取事实、语义检索（用关键词匹配）、情景记忆、Memory Wiki、矛盾检测。

**用户核心体验**：用 YokeOS 一段时间后，Agent 自然会记住用户的偏好、项目信息、关键决策，下一次对话不需要重新解释。这是 Agent 底座区别于 chatbot 的核心体验。

---

### 5.7 Tool 体系（核心能力四：让 Agent 能干事）

Tool 是 Agent 可以调用的外部能力。Agent 通过 LLM Function Calling 决定何时调哪个 Tool，YokeOS 负责 Tool 的注册、查找、调用、结果回传。

#### 内置 Tool（第一阶段 9 个）

| Tool | 类型 | 说明 |
|------|------|------|
| `read_file` | 文件 | 读取文件内容，受路径白名单限制 |
| `write_file` | 文件 | 写入文件内容，受路径白名单限制 |
| `list_dir` | 文件 | 列出目录，受路径白名单限制 |
| `shell` | Shell | 执行命令，有超时和命令白名单限制 |
| `http_get` / `http_post` | HTTP | 发起 HTTP 请求，有域名白名单限制 |
| `save_memory` | Memory | 把内容追加到 MEMORY.md |
| `recall_memory` | Memory | 按关键词检索 MEMORY.md |
| `notify` | 通知 | 把执行结果推送到 Agent 配置的通知渠道 |

#### 扩展 Tool（业务方扩展）

| 方式 | 门槛 | 推荐度 | 场景 |
|------|------|--------|------|
| **方式一**：写 Agent 目录（AGENT.md）+ 复用 MCP server | 零代码 | ⭐⭐⭐ 主推 | 描述意图，LLM 自己组合现成能力 |
| **方式二**：自己写 MCP server | 轻代码 | ⭐⭐ | 接入企业自有系统，任何语言皆可 |
| **方式三**：写 Java @Tool Bean | 重代码 | ⭐ | 深度集成，性能最好 |

> **选择原则**：能用方式一就不用方式二，能用方式二就不用方式三。

**零代码示例**：想做「每天早上推送昨日 GitHub PR 评审进度到 Slack」，只需：
1. 建 `.yokeos/agents/daily-pr-digest/`，写一份 `AGENT.md`：frontmatter 声明 provider、`mcp_servers`、`schedules`，正文写任务指令
2. 复用社区现成的 `github-mcp` 和 `slack-mcp`，配置在 `mcp_servers.yaml`
3. 需要固定产出格式就在 frontmatter 里按名引用对应公共 Skill，底座把 Skill 正文注入 system prompt

整个过程不写一行代码。

#### Sandbox 安全隔离

第一阶段用应用层白名单校验实现：
- 文件操作：路径白名单，校验真实路径仍位于白名单根内
- Shell：命令白名单
- HTTP：域名白名单
- 执行超时和资源占用限制

> 注：不使用 Java SecurityManager，它在 JDK 17 起已废弃、JDK 21 已不可用。完整的 Docker/K8s 容器级沙箱放在扩展功能。

---

### 5.8 通知与定时（核心能力五：让 Agent 触达人、自己跑）

#### Notify 通知

- Agent 通过内置 `notify` Tool 把执行结果推到自己在 frontmatter 里配置的通知渠道
- 第一阶段内置 **Webhook 适配器**：企业 IM 群机器人（飞书、企业微信、钉钉、Slack）皆经 Webhook 接入，不需要各家 SDK
- 推送动作过 Sandbox 域名白名单，写入审计表

#### 定时任务（第三触发源）

- 每个 Agent 可在 frontmatter 里声明 cron 定时规则，到点自动触发
- 定时触发与 CLI、REST API 复用**同一条执行链路**——同一个 Agent 不管从哪个入口触发，行为一致、审计同构
- 定时执行历史落库，可查每次到点有没有跑、跑了什么、结果如何

**第一阶段不做**：更多通知渠道适配器（邮件、IM SDK 直连）、失败重试策略的复杂化、分布式调度。

---

### 5.9 Channel 接入

Channel 是 Agent 对外的消息接入入口，主要解决「消息进来、响应出去」这件事。HTTP 接入归 Web Service，不在 Channel 范畴内。

第一阶段只内置一种 Channel：**CLI Channel**，通过 `yokeos chat` 命令启动，支持多轮对话、查看上下文、查看 Tool 调用记录。

企业微信、飞书、钉钉、Slack 等 IM Channel 放在扩展功能（实现复杂度高，需要 OAuth 和企业资质，不在第一阶段能完成的范围）。

---

### 5.10 Web Service（核心能力六：对外接口暴露与 Web 管理台）

Web Service 是 YokeOS 的对外完整门面，业务系统通过 REST API 接入 YokeOS 的所有能力。这是 YokeOS 区别于偏个人定位的 OpenClaw、Hermes 的关键能力。

#### 第一阶段核心端点

统一前缀 `/api/v1`，按五组组织：

| 类别 | 端点 | 说明 |
|------|------|------|
| 会话管理 | `POST /api/v1/sessions` | 创建会话 |
| 会话管理 | `POST /api/v1/sessions/{id}/messages` | 发消息（触发 ReAct 循环） |
| 会话管理 | `GET /api/v1/sessions/{id}` | 查历史 |
| 会话管理 | `DELETE /api/v1/sessions/{id}` | 归档会话 |
| Agent 调用与动态管理 | `POST /api/v1/agents/generate` | 一句话生成定义草稿（不落盘、不注册，人在环预览） |
| Agent 调用与动态管理 | `POST /api/v1/agents` | 创建 Agent（落盘 + 注册，免重启） |
| Agent 调用与动态管理 | `GET /api/v1/agents` | 列出全部 Agent |
| Agent 调用与动态管理 | `GET /api/v1/agents/{name}` | 查看 Agent 定义 |
| Agent 调用与动态管理 | `PUT /api/v1/agents/{name}` | 更新 Agent 定义 |
| Agent 调用与动态管理 | `DELETE /api/v1/agents/{name}` | 删除 Agent |
| Agent 调用与动态管理 | `POST /api/v1/agents/{name}/invoke` | 无状态调用 |
| 工作区 | `GET /api/v1/workspace/tree` | 工作区目录树 |
| 工作区 | `GET /api/v1/workspace/file` | 只读查看工作区文件 |
| 信息查询 | `GET /api/v1/profiles` | 列运行配置 |
| 信息查询 | `GET /api/v1/memory` | 查长期记忆 |
| 信息查询 | `GET /api/v1/tools` | 列可用 Tool |
| 系统状态 | `GET /api/v1/health` | 健康检查 |
| 系统状态 | `GET /api/v1/info` | 运行信息 + Provider 状态 |

#### 扩展阶段补齐的端点

Agent 的 show/reload；Memory 的 append/clear/search；Tool describe 和调用历史查询；LLM call 历史、token 统计；Webhook 触发、流式 SSE 响应；Prometheus metrics、OpenAPI spec。

**第一阶段不做**：认证机制（无认证假设内网）、流式响应 SSE、WebSocket、RBAC 权限、审计仪表板。

#### Web 管理台第一版

与 REST 同端口、同进程托管的 Web 管理台，给运营方一个不碰命令行的窗口：

- **只读观察五页**：会话、Agent（Profile）、Tool、长期记忆、系统状态（含各 Provider 连通情况），数据全部来自只读端点，界面不设写入口
- **Agent 管理页**：列表 + 查看/编辑/删除 + 「一句话新建 → 预览可改 → 创建」流程（走 5.3 同一组 API）
- **工作区页**：目录树 + 只读浏览文件
- 统一响应信封与错误提示；单页应用内部路径刷新可正常打开，不影响 REST 路由

第一阶段的管理台以「看得见、管得了 Agent」为界：只读五页覆盖观察，写操作收敛在 Agent 管理一组。Memory 写入、调用历史查询、审计仪表板等管理能力放扩展阶段。

#### 业务系统集成场景

| 模式 | 方式 | 适用 |
|------|------|------|
| 同步调用 | `POST /agents/{name}/invoke` 等返回 | Stateless 短任务 |
| 会话保持 | 先创建 Session，后续多次发消息 | 连续对话 |
| Webhook 触发 | 告警系统、CI/CD 通过 Webhook 调 Agent | 事件驱动 |
| 跨语言集成 | 任何能发 HTTP 请求的语言都能接入 | 通用集成 |

---

### 5.11 Session 管理

Session 是用户和 Agent 一次对话的上下文容器，包含起止时间、用户身份、Agent 标识、对话历史、当前上下文、临时变量。Session 标识由 Channel、用户、Agent 联合生成。

第一阶段 Session 数据持久化到本地 SQLite（`.yokeos/` 工作区内）。重启 YokeOS 后，正在进行的 Session 可以恢复。Session 上下文超过 LLM 的 context window 时，简单截断早期对话保留近期对话。

---

### 5.12 三种运行模式

| 模式 | 命令 | 说明 |
|------|------|------|
| 交互对话 | `yokeos chat` | 交互式多轮对话，开发调试和日常使用的主要方式。`--message "xxx"` 可发单条消息后退出 |
| HTTP API | `yokeos serve` | 启动后在指定端口（默认 8080）开放 RESTful 接口与 Web 管理台，业务系统通过 HTTP 调用 |
| 守护进程 | `yokeos gateway` | 常驻守护进程，同时服务多个 Channel |

三种模式共享同一份 Agent 配置和 Session 存储。

---

### 5.13 命令行工具

第一阶段实现 **12 个命令**：

| 类别 | 命令 | 说明 |
|------|------|------|
| 启动和状态 | `yokeos init` | 初始化工作区 |
| 启动和状态 | `yokeos status` | 查看配置和运行状态 |
| 启动和状态 | `yokeos chat [--profile <name>]` | 交互对话 |
| 启动和状态 | `yokeos serve` | 启动 HTTP API 服务 |
| 启动和状态 | `yokeos gateway` | 启动多渠道守护进程 |
| Agent 管理 | `yokeos profile list` | 列出所有 Agent |
| Agent 管理 | `yokeos profile create <name>` | 创建新 Agent |
| Agent 管理 | `yokeos profile show <name>` | 查看 Agent 定义 |
| Agent 管理 | `yokeos profile delete <name>` | 删除 Agent |
| 查询 | `yokeos provider list` | 列出已配置的 Provider |
| 查询 | `yokeos tool list` | 列出已注册的 Tool |
| 查询 | `yokeos session list` | 列出会话历史 |

---

### 5.14 配置与密钥加载

第一阶段做基础版：

- 敏感配置通过**环境变量**注入或独立的本地配置文件加载，不明文写死在 `AGENT.md` frontmatter 里
- 配置里用 `${ENV_VAR}` 占位，加载时从环境变量解析
- 配置加载时做基础校验（必填项、格式），缺失或非法时给出清晰报错，不静默失败

完整的加密存储、密钥轮转、对接企业密钥管理系统（KMS、Vault）放在扩展阶段。

---

### 5.15 项目主页

YokeOS 作为开源项目，需要一个独立的主页作为对外门面，讲清楚 YokeOS 是什么、能干嘛、怎么用，引导开发者快速上手。

主页在第一阶段做出来，与核心代码同期发布，作为 YokeOS 1.0 对外亮相的一部分。技术栈推荐使用 VitePress、Astro 或 Docusaurus 等静态站点生成器。

---

## 6. 扩展功能

扩展功能在核心功能完成后推进，补齐生产级使用必需但不在最短链路上的能力，以开源社区方式陆续补齐。

### 6.1 渠道和模型层

- **多 Channel 接入**：企业微信、飞书、钉钉、Slack、邮件，通过 Channel Adapter 插件机制扩展
- **Provider Fallback 和可靠性**：三层 failover（hedge racing、circuit breaker、自动切换），Provider 故障时自动切换备用
- **Adaptive Routing**：LLM 路由从静态配置升级为动态决策，根据任务类型、历史调用质量、当前 Provider 负载自动选择

### 6.2 记忆和能力层

- **Memory 自动抽取**：LLM 在对话结束时自动提取值得长期保留的事实写入 MEMORY.md
- **Memory 语义检索**：集成向量数据库（Milvus、Qdrant、Weaviate、PostgreSQL pgvector），按语义相似度匹配——长期记忆预留的向量检索升级空间在这一步兑现
- **情景记忆**：补齐 Memory 第三层，记录任务过程中修改的文件、决策、成果
- **Memory Wiki**：结构化 claim/evidence、矛盾检测、新鲜度管理
- **知识库与语义记忆**：文档导入、切分、向量化检索，补齐底座能力版图
- **Skill 库增强**：Skill 绑定从 frontmatter 按名引用升级为软连接选择可见集合与渐进式披露（元数据先注入、正文按需加载）；补语义推荐、版本管理和企业审查流程

### 6.3 工具和安全层

- **MCP Server 暴露**：YokeOS 自己作为 MCP server，把内部 Agent 能力暴露给其他系统
- **Tool Policy**：Profile 级别的 Tool 允许或拒绝规则，这是 Agent OS 治理能力里最轻、最能体现 OS 管控的一项，扩展阶段优先做
- **Tool LRU 加载**：工具数量多时，动态加载，避免把所有工具塞进 LLM context
- **Web 认证**：管理台与 REST API 的认证鉴权
- **完整 Sandbox 隔离**：Docker 容器和 K8s pod 两种 sandbox 实现，WebAssembly Sandbox 作为高性能选项

### 6.4 治理和运维层

> 这一层是 YokeOS 区别于个人级 Agent OS 的核心差异化所在

- **Web 仪表板增强**：管理台补审计日志查询、监控看板
- **SSO 和多租户**：SAML/OIDC 接入，对接企业 AD/Okta/Entra ID/阿里云 IDaaS。三级租户模型（组织、部门、项目），RBAC 权限粒度到 Agent、Tool、Skill 级别
- **审计与可追溯**：完整审计事件记录、JSON 结构化输出、trace ID 串联、敏感信息脱敏、SIEM 导出——审计两表第一阶段已在写入，这一层补查询、治理与导出
- **可观测性**：Prometheus 指标、结构化日志、健康检查接口、Grafana Dashboard 模板
- **底座分布式**：实例无状态、状态外置（会话与短期上下文、长期记忆、审计与大文件、配置分路外置），多副本部署支撑更大规模与高可用
- **平台基线升级**：Spring Boot 4 + Spring AI 2.0，作为一次真实的架构升级练习

### 6.5 企业集成层

- **企业 IT 系统 connector**：ERP（用友、金蝶、SAP）、CRM（销售易、纷享销客、Salesforce）、CMDB、监控系统、内网知识库的现成 connector

---

## 7. 社区共建功能

社区共建功能不在 YokeOS 主线开发计划内，作为长期方向开放给社区贡献，不规定时间表。

- **剩余项目文档**：API 参考文档、部署运维手册、贡献者指南 CONTRIBUTING.md、典型场景使用手册
- **Skills Marketplace**：社区贡献的 Skill 共享平台，兼容 agentskills.io 开放标准，跟 OpenClaw 和 Hermes Agent 技能互通；企业导入前经自有审查流程
- **跨节点 Agent 协作**：引入 Agent 通信底座，对接 A2A，让多节点上的 Agent 跨节点发现、委托、可靠异步协同（第三阶段的形态）
- **SDK 多语言支持**：优先级 Java → Python → TypeScript → Go
- **可视化 Agent 编辑器**：让非工程师也能配置 Agent；编排平台（Dify 等）可作为客户端跑在 YokeOS 之上
- **Native 文件生成**：不依赖 LibreOffice 直接生成 pptx、docx、xlsx（Apache POI）
- **多区域部署**：跨地域 YokeOS 集群，Agent、Memory、Session 可以跨区域协同
- **Kubernetes Operator**：一键部署、声明式配置、GitOps 工作流
- **移动端管理台**：手机随时查集群状态、处理告警
- **Voice Channel**：语音唤醒和连续语音对话
- **RISC-V 和边缘部署**：跑在 Raspberry Pi、边缘网关（GraalVM Native Image）

---

## 8. 非功能需求

### 8.1 性能

| 指标 | 目标 |
|------|------|
| 单节点 Agent 数 | ≥ 10 个 |
| 单节点并发 Session 数 | ≥ 100 个 |
| Session 创建 P99 延迟 | ≤ 200ms |
| YokeOS 内部转发开销 | ≤ 50ms |

（LLM 调用本身的延迟取决于 Provider，不在 YokeOS 控制范围内）

### 8.2 可靠性

- 已注册的 Agent 定义和已写入的 Session 数据保证不丢
- LLM Provider 故障时第一阶段直接报错，完整 failover 在扩展阶段实现
- Tool 调用失败时按重试策略再调，默认指数退避最多三次

### 8.3 可运维性

- Agent 定义的增删改查全程免重启（见 5.3 动态管理）
- 支持物理机、虚拟机、Docker、Kubernetes 部署

### 8.4 兼容性

- **JDK**：21 及以上（Spring Boot 3.x 要求）
- **操作系统**：Linux 主流发行版（Ubuntu 22.04+、CentOS 8+、Debian 11+、Alibaba Cloud Linux 3、Rocky Linux）
- **LLM 协议**：OpenAI 兼容协议是事实标准，只要 Provider 实现这套协议，YokeOS 就能直接接

### 8.5 安全

- API 调用支持 HTTPS
- 敏感配置（LLM API key、数据库密码、Tool 凭证）支持加密存储，不能明文写在配置文件里
- Tool 调用通过应用层白名单校验做基础隔离，校验真实路径
- 完整的鉴权机制、Docker Sandbox 隔离、SSO 集成放在扩展阶段

### 8.6 合规

- **数据驻留**：YokeOS 不主动外发任何数据，所有数据留在企业自己的基础设施上
- 完整的审计日志覆盖、SIEM 导出、SOC 2、GDPR、HIPAA、等保三级的对接放在扩展阶段

---

## 9. 关键流程

### 流程一：工作区初始化

```
用户执行 yokeos init
  → YokeOS 创建 .yokeos/ 目录及六个子目录
    （agents / skills / output / memory / sessions / logs）
  → 创建三个 Bootstrap 文件（AGENTS.md、SOUL.md、USER.md）
  → 幂等：已存在的目录和文件一律不覆盖
用户编辑 Bootstrap 文件填入项目背景、Agent 人格、用户偏好
```

### 流程二：Agent 创建和启动

```
用户执行 yokeos profile create <name>
  → YokeOS 在 .yokeos/agents/<name>/ 下创建 AGENT.md（最小模板）
用户编辑 AGENT.md：frontmatter 配 Provider、Tool 列表、Channel、通知渠道、
  定时规则、按名引用的 Skill，正文写任务指令
用户执行 yokeos chat --profile <name>
  → AgentLoader.deriveProfile() 把 frontmatter 派生成 Profile
  → 初始化 Provider 连接
  → 注册 Tool 到 Agent 工具池
  → ContextLoader 把 AGENT.md 正文、Bootstrap 文件、引用到的 Skill 正文加载到系统提示词
  → Agent 进入待对话状态
```

### 流程三：消息处理（最高频链路）

```
消息从触发源进来（CLI 输入、HTTP API 调用，或定时到点）
  → Channel Adapter 转换成内部统一格式
  → Agent 查询 Session 上下文
  → 组装 LLM Prompt（Bootstrap + 长期记忆 + 对话历史 + 可用 Tool 列表）
  → 调用 LLM Provider 获取响应
  → [有 Tool 调用] → YokeOS 执行 Tool → 结果回传给 LLM 继续生成
  → 最终响应通过 Channel 发回给用户（或经 Notify 推送到通知渠道）
  → 所有动作落结构化日志，LLM 调用与 Tool 调用写入审计表
```

### 流程四：Tool 调用

```
LLM 通过 Function Calling 指明 Tool 名称和参数
  → YokeOS 从 Agent 工具池找到对应 Tool
  → 做参数校验和白名单校验
  → 内置 Tool：在 YokeOS 进程内执行（白名单约束下）
  → MCP Tool：通过 MCP 协议转发给对应 MCP server 执行
  → 执行结果（成功/失败、错误信息、可重试标识）回传给 Agent
  → Agent 把 Tool 结果作为新一轮 LLM 输入继续推理
```

### 流程五：Session 上下文管理

```
用户第一次跟 Agent 说话
  → YokeOS 用 Channel+用户+Agent 联合 ID 查活跃 Session
  → [无活跃 Session] → 创建新 Session，初始化空对话历史
  → [有活跃 Session] → 恢复 Session 上下文
后续消息追加到 Session 对话历史
  → [上下文超限] → 截断早期对话，保留近期（扩展阶段做总结压缩）
Session 超时无消息 → 结束，对话历史归档可查
```

---

## 10. 数据模型

### Agent 定义（AGENT.md frontmatter）

见 [5.2 定义一个 Agent](#52-定义一个-agentagentmd)。

### Session（持久化到 SQLite）

| 字段 | 类型 | 说明 |
|------|------|------|
| `session_id` | VARCHAR | 主键，channel+user+agent 联合生成 |
| `agent_name` | VARCHAR | 关联的 Agent 名称 |
| `channel` | VARCHAR | 接入渠道 |
| `user_id` | VARCHAR | 用户标识 |
| `messages_json` | TEXT | JSON 序列化的对话历史 |
| `status` | VARCHAR | `active` / `archived` |
| `created_at` | TIMESTAMP | 创建时间 |
| `last_active_at` | TIMESTAMP | 最后活跃时间 |
| `archived_at` | TIMESTAMP | 归档时间（可空） |

### Memory（文件形态，非数据库表）

长期记忆是 `.yokeos/memory/MEMORY.md` 一个 Markdown 文件，按追加方式写入，无结构化 schema。扩展阶段引入向量库后，Memory 才有结构化的 embedding 存储。

### Tool Invocation（记录每次 Tool 调用，day one 写入）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | BIGINT | 主键 |
| `session_id` | VARCHAR | 关联 Session |
| `tool_name` | VARCHAR | Tool 名称 |
| `input_json` | TEXT | 调用参数（JSON） |
| `result_json` | TEXT | 执行结果（JSON） |
| `success` | BOOLEAN | 是否成功 |
| `error_message` | TEXT | 错误信息（可空） |
| `duration_ms` | BIGINT | 执行耗时（毫秒） |
| `created_at` | TIMESTAMP | 调用时间 |

### LLM Call（记录每次 LLM 调用，day one 写入）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | BIGINT | 主键 |
| `session_id` | VARCHAR | 关联 Session |
| `provider` | VARCHAR | Provider 名称 |
| `model` | VARCHAR | 模型名 |
| `prompt_tokens` | INT | 输入 token 数 |
| `completion_tokens` | INT | 输出 token 数 |
| `total_tokens` | INT | 总 token 数 |
| `duration_ms` | BIGINT | 调用耗时（毫秒） |
| `created_at` | TIMESTAMP | 调用时间 |

---

## 11. 里程碑规划

YokeOS 第一阶段的实施按参照实现公开构建过程的课程节序组织（第 16→31 节），**节奏自定、顺序不乱**，不设日历时间盒。每节一个原子交付物；课型分流：代码课走完整规格流程并产码，评审课只做设计评审不产码，串联课不开新规格、只固化端到端验证，Demo 课做真实运行与发布。

| 节 | 课型 | 能力主线 | 可演示成果 |
|----|------|---------|-----------|
| 16 | 代码 | Provider 抽象（核心能力一） | 配置 Provider 与 API key，CLI 发消息拿到 LLM 回复 |
| 17 | 代码 | ReAct 循环（核心能力二） | Agent 一次对话内完成多步任务：思考 → 调 Tool → 观察 → 续推 |
| 18 | 代码 | CLI 命令行入口 | `yokeos chat` 多轮会话，可查上下文与 Tool 调用记录 |
| 19 | 代码 | Notify 通知（核心能力五·上） | Agent 干完活把结果推到 Webhook 通知渠道 |
| 20 | 代码 | Tool 体系与 MCP（核心能力四） | 内置 Tool 全量可用，接入外部 MCP server，白名单校验生效 |
| 21 | 评审 | Memory 设计评审（核心能力三） | 评审记录：业界方案对照与本底座记忆设计定稿（不产码） |
| 22 | 代码 | Memory 实现（核心能力三） | Agent 跨对话记住用户偏好并在后续对话用到 |
| 23 | 评审 | Sandbox 设计评审 | 评审记录：白名单沙箱设计定稿（不产码） |
| 24 | 代码 | Sandbox 实现 | 越权路径/命令/域名被拦截，拦截动作留痕可查 |
| 25 | 代码 | 定时任务（核心能力五·下） | Agent 按 cron 到点自跑，执行历史可查 |
| 26 | 代码 | Web Service 与管理台第一版（核心能力六） | REST 端点完整可用，管理台只读观察五页上线 |
| 27 | 串联 | 全流程串联（一）：打通 Agent 主流程 | CLI → ReAct → Tool → Notify 端到端打通（不开新规格） |
| 28 | 串联 | 全流程串联（二）：让底座自己跑得稳 | 端到端链路固化为集成测试，稳定复跑 |
| 29 | 代码 | 一个目录 = 一个 Agent | 放一个 Agent 目录即得到一个可用的业务 Agent |
| 30 | 代码 | 动态管理 | 一句话生成草稿 → 预览 → 创建 → 编辑 → 删除，全程免重启；管理台 Agent 管理页与工作区页上线 |
| 31 | Demo | 真实运行与发布 | 两个日跑 Demo 在真实环境上线，打包发布，项目主页可访问 |

各节以「可演示成果」为节级完成判据；某一节完不成时，立刻把末段功能挪到扩展功能，保证每节都有可演示成果。审计两表（`tool_invocations`、`llm_calls`）从第一个有 LLM 调用和 Tool 调用的节起就写入，不以「日志够了」为由推迟。

---

## 12. 风险与未决事项

### 已识别风险

| 风险 | 描述 | 应对措施 |
|------|------|---------|
| **核心功能范围风险** | 跟拍窗口 16 节是极紧的范围约束，某些功能可能比预期复杂 | 核心功能范围卡紧；某节完不成时立刻把末段功能挪到扩展功能，保证每节有可演示成果 |
| **Spring AI 兼容性风险** | Function Calling、Stream、Token 计数、错误码细节不一致 | 第一阶段先把 OpenAI 协议跑稳，其他 Provider 在扩展阶段做完整回归测试 |
| **Tool 执行安全风险** | 应用层白名单不是完整 Sandbox，可能影响 YokeOS 进程 | 严格限制内置 Tool 能力范围，第一阶段不建议在生产环境跑高敏感场景 |
| **Java 启动速度和内存占用** | Java 应用启动慢、内存占用大，影响体验 | 第一阶段先验证功能完整，扩展阶段引入 GraalVM Native Image |
| **社区接力的不确定性** | 扩展功能依赖社区贡献者，可能某些功能长期没人推进 | 项目维护方对核心扩展功能保持基本投入，社区共建功能靠社区 |
| **定位被误读的风险** | 社区可能问「以参照实现起步，跟模仿抄袭有什么区别」「第一阶段跟 OpenClaw、Hermes 有什么区别」 | 文档明确与参照实现的关系（同类、同栈、同锚点的后来者），差异化立在过程——可追溯的规格与验收证据；明确第一阶段是地基，不包装成完整企业级底座，也不掩饰起点 |
| **生态关系风险** | YokeOS 和 OpenClaw、Hermes 的关系 | 通过 SKILL.md 开放格式与 markdown 目录形态互通，生态互补不竞争；OpenClaw 偏个人、Hermes 偏小团队、YokeOS 定位严监管企业 |

### 未决事项

| 事项 | 说明 | 决议时间 |
|------|------|---------|
| Provider 抽象接口设计 | 直接用 Spring AI `ChatClient`，还是在 `ChatClient` 之上加一层 YokeOS 自己的抽象 | 技术方案阶段 |
| 底层存储选 SQLite 还是 H2 | SQLite 是嵌入式 C 实现，H2 是纯 Java | 技术方案阶段 |
| Bootstrap 文件加载顺序和优先级 | AGENTS.md、SOUL.md、USER.md 怎么组合进系统提示词 | 技术方案阶段 |
| 一句话生成的默认 Provider 配置形态 | 系统默认 provider/model 用什么配置键、缺省时回退到什么 | 技术方案阶段 |
| GraalVM Native Image 引入时机 | 扩展阶段还是社区阶段 | 第一阶段结束后 |

---

## 13. 验收标准

### 功能验收

核心功能（第 5 章）全部完成，每个功能模块至少有一个端到端测试用例覆盖：

- [ ] `yokeos init` 工作区初始化
- [ ] Agent 目录定义与管理（一个目录 = 一个 Agent，支持多 Agent 并存）
- [ ] 动态管理（一句话生成草稿不落盘、创建/编辑/删除免重启、直接丢目录可加载）
- [ ] Provider 抽象（至少跑通 DeepSeek 和 Kimi 两个，显式映射区分）
- [ ] ReAct 循环（多轮 Tool 调用、正确累积消息历史、达到最大迭代次数时正确终止）
- [ ] Memory 长期记忆（save_memory 写入、recall_memory 关键词检索、启动时注入 system prompt）
- [ ] 内置 Tool（文件、HTTP、Shell、save_memory、recall_memory、notify）
- [ ] 扩展 Tool 接入（方式一零代码 Agent 目录 + MCP 跑通；方式三 @Tool 注解示例跑通）
- [ ] MCP Client 集成、CLI Channel
- [ ] 通知与定时（notify 推 Webhook 通知渠道；`AgentScheduler` 到点自跑，与 CLI/Web 复用同一条执行链路）
- [ ] Web Service 核心端点全部跑通（会话 4 + Agent 调用与动态管理 7 + 工作区 2 + 信息查询 3 + 系统状态 2）
- [ ] Web 管理台第一版（只读观察五页 + Agent 管理页 + 工作区页）
- [ ] Session 持久化（SQLite，跨重启恢复）
- [ ] 12 个命令行工具
- [ ] 配置与密钥加载（`${ENV_VAR}` 注入、缺失或非法时清晰报错）

### 性能验收

通过压力测试验证：
- 单节点 10 个 Agent 稳定运行 4 小时
- 单节点 100 个并发 Session
- Session 创建 P99 延迟 < 200ms
- 内部转发开销 < 50ms

### 可运维性验收

- 完整的部署文档（新手 30 分钟内完成单节点部署）
- 命令行工具有清晰的帮助和错误提示
- 项目主页可访问，讲清楚 YokeOS 是什么、怎么快速开始

### 场景验收（两个 Demo）

早期按「一个 Demo 验证一个能力」拆了五个 Demo，但真实场景从来不是单一能力独立跑的——一个能打动人的 Agent，一定是多个能力叠在一起、自己到点跑起来的。改成两个**每日自动运行**的端到端 Demo，每个 Demo 横向串起多个核心能力，两个 Demo 加起来覆盖全部六个核心能力加定时任务这个第三触发源。两个 Demo 跑通是核心功能发布的**硬条件**：

| Demo | 验证能力 | 场景描述 | 验收标准 |
|------|---------|---------|---------|
| **Demo 一：每日天气** | 能力一+二（LLM + ReAct）、能力四（内置 HTTP Tool）、能力五（Notify + 定时任务） | 每天早上到点自动查天气、生成穿搭建议，经 Webhook 推送到企业 IM 群机器人 | 不需要人工触发，到点自动跑完整 ReAct 循环；查天气和推送各一次 HTTP 调用，都过 Sandbox 域名白名单且都写入 `tool_invocations`；`GET /api/v1/sessions/{id}` 能查到这次自动触发的完整对话记录 |
| **Demo 二：每日科技日报** | 能力四（扩展 Tool 方式一 Agent 目录零代码 + 方式二 MCP）、能力三（Memory）、能力五（定时任务） | 每天到点自动汇总当日科技新闻并推送，且日报内容会体现用户之前说过的关注方向（比如「更关注 AI 和芯片」） | 业务方全程不写 Java 代码，只写 `AGENT.md`（含 `schedules` 与按名引用的 Skill）并配置 `mcp_servers.yaml`；底座把引用到的 Skill 正文注入 system prompt；LLM 自己决定调新闻 MCP 工具、自己组织日报、自己调推送，YokeOS 不解析任务步骤；日报内容能体现 `MEMORY.md` 里记住的偏好 |

两个 Demo 都是「钟推」（`AgentScheduler` 到点自动触发），但都要能同时支持「人推」手动补跑一次做验证（`yokeos chat` 或 `POST /agents/{name}/invoke`），验证同一个 Agent 不管从哪个入口触发，走的都是同一条执行链路。

---

## 14. 总结

YokeOS 是基于 Java 实现的面向企业场景的 Agent 底座（Agent Harness OS），装在企业自己的 K8s 或服务器上，作为统一底座跑各种业务 Agent，共享一套渠道接入、模型路由、工具调用、记忆系统、沙箱执行能力。数据完全留在企业自己的基础设施，不锁任何云。

**YokeOS 的交付分三段：**

1. 第一阶段先用 Java 把 Agent 底座的单机运行时内核做扎实，以 OryxOS 为参照实现逐节复刻、等价验收，这一层在能力上对齐业界开源 Agent OS 的基础层
2. 扩展阶段补齐知识库与语义记忆、底座分布式，以及真正的差异化治理层（多租户、SSO、完整审计、Tool 治理）。**第一阶段是地基，企业级治理是终局。**
3. 长期对接 A2A 走向跨节点 Agent 协作，由社区共建陆续补齐

**第一阶段六个核心能力：**

- **对接 LLM**：Provider 抽象，让 Agent 能调任意主流大模型，运行时切换无 lock-in
- **ReAct 循环**：Agent 大脑，LLM 思考 + 工具执行，多步骤任务自主完成
- **Memory 两层记忆**：会话 + 长期 MEMORY.md，跨对话记住用户偏好和项目背景
- **工具体系**：内置文件/Shell/HTTP/记忆/通知工具，业务方通过 Agent 目录 + MCP 零代码扩展、MCP server 轻代码扩展、@Tool 注解重代码扩展
- **通知与定时**：执行结果推 Webhook 通知渠道，cron 到点自跑——继 CLI、REST API 之后的第三触发源
- **对外服务**：REST API 覆盖会话管理、Agent 调用与动态管理、信息查询、系统状态，附 Web 管理台第一版

**核心理念**：YokeOS 六个能力扎实落地，业务方放一个 Agent 目录（一份 `AGENT.md`）就能得到一个可用的业务 Agent，组合 MCP server 解决业务问题，通过 Web Service 接入已有系统，不需要写 Agent 后端代码。YokeOS 不绑定具体业务，业务方按自己的需求组合；安全（三重白名单沙箱、凭证不落地、审计两表 day one 落库）与「一个目录 = 一个 Agent」的配置体验，从第一天就在架构里。
