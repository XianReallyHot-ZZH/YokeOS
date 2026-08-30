# 一个目录 = 一个 Agent

***Agent 的定义本体是一个目录：`.yokeos/agents/<name>/` 下的一份 `AGENT.md`——frontmatter 是这个 Agent 的运行配置，正文是任务指令。一个目录就是一个完整可用的业务 Agent，不是写代码写出来的。***

## 它解决什么

传统做法里，上一个业务 Agent 要写后端代码：接消息、调模型、管上下文、拼工具、处理重试。在 YokeOS 里，这些全部由底座供应；业务方只描述**要做什么**（正文）和**怎么跑**（frontmatter），放一个目录就得到一个可用的业务 Agent。多个 Agent 在同一个实例上并存，各自有独立的配置、工具和记忆——这是「OS」在第一阶段的最小体现。

## AGENT.md 的形态

```markdown
---
name: ops-agent
description: 运维助手
identity:
  agent_name: 运维小欧
  prompt: 你是一个专业的运维助手...
provider:
  name: deepseek          # 对应 ProviderService 里的显式映射 key
  model: deepseek-chat
  temperature: 0.7
  api_key: ${DEEPSEEK_API_KEY}   # 从环境变量读取，不明文写死
tools:
  - read_file
  - shell
  - http_get
  - save_memory
  - recall_memory
  - notify
skills:
  - runbook-format        # 按名引用公共 Skill 库
mcp_servers:
  - github-mcp
notify:
  channels:
    - name: team-im
      type: webhook
      config: {}
schedules:
  - cron: "0 0 8 * * ?"   # 到点自跑（第三触发源）
bootstrap:
  - AGENTS.md
  - SOUL.md
  - USER.md
settings:
  max_iterations: 10
  max_history_turns: 20
---

你是一个专业的运维助手。被触发时……（Agent 的任务指令正文，注入 system prompt）
```

frontmatter 各字段的职责：

| 字段 | 职责 |
|------|------|
| `identity` | Agent 名称与人格（系统提示词） |
| `provider` | 绑定哪个 Provider 与模型；API key 走 `${ENV_VAR}` 占位 |
| `tools` | 可用 Tool 名称列表，底座据此过滤工具子集 |
| `skills` | 按名引用公共 Skill 库（`.yokeos/skills/<名>/`），正文注入 system prompt |
| `mcp_servers` | 引用的 MCP Server 列表 |
| `notify` | 通知渠道（第一阶段内置 Webhook 适配） |
| `schedules` | cron 定时规则（第三触发源） |
| `bootstrap` | 引导文件列表（AGENTS.md / SOUL.md / USER.md） |
| `settings` | `max_iterations`（默认 10）、`max_history_turns`（默认 20） |

## 怎么工作：派生 Profile 与运行时注册

底座的一切都吃 `Profile`。`AgentLoader.deriveProfile(agentDir)` 把 `AGENT.md` 的 frontmatter 派生成底座认识的 `Profile`，注册进 `ProfileRegistry`；有 `schedules` 的交 `AgentScheduler` 注册定时。启动扫描和运行期新增走**同一段注册代码**——目录就位即上线、删除即下线，全程免重启。

`ContextLoader` 每次组装 prompt 时现取三段内容注入 system prompt（不缓存，改正文即时生效）：Bootstrap 文件（AGENTS.md → SOUL.md → USER.md 固定顺序）、按名引用的 Skill 正文、`AGENT.md` 正文。附属资源（`scripts/` 脚本、`REFERENCE.md` 参考）不预载，按正文指引经底座既有工具（`read_file` / `shell`）按需取用。

![两条录入路径一段注册代码：API 创建与手工丢目录都汇到同一个注册入口](/images/docs-agent-lifecycle.svg)

## 动态管理：三条等价入口

Agent 目录的增删改查全程免重启，三条入口等价：

1. **REST API**：一句话生成定义草稿（`POST /api/v1/agents/generate`，原样返回预览、**不落盘、不注册**），人过一眼、可改（尤其定时时刻、工具权限），确认后 `POST /api/v1/agents` 落盘并注册
2. **Web 管理台**：Agent 管理页走同一组 API，覆盖「一句话新建 → 预览可改 → 创建 → 编辑 → 删除」闭环；另有工作区页只读浏览目录树与文件
3. **直接丢目录**：把写好的 Agent 目录放进 `.yokeos/agents/`，底座实时监听目录变化，校验并加载，即插即用

`.yokeos/agents/` 是唯一真相源；生成动作落审计，LLM 产出非法定义时返回明确的校验错误，不静默失败。

## 工作区结构

Agent 目录生活在一个 `.yokeos/` 工作区里（`yokeos init` 创建，幂等不覆盖）：

```text
.yokeos/
├── agents/            # 每个子目录 = 一个 Agent
├── skills/            # 公共 Skill 库（每个子目录一个 SKILL.md）
├── output/            # Agent 产出物
├── memory/
│   └── MEMORY.md      # 长期记忆
├── sessions/          # 会话导出（真相源在 SQLite）
├── logs/              # 结构化日志
├── AGENTS.md          # Bootstrap：项目级 agent 行为说明
├── SOUL.md            # Bootstrap：默认 agent 人格定义
├── USER.md            # Bootstrap：用户偏好（只读，agent 不写）
├── mcp_servers.yaml   # MCP 配置
└── yokeos.db          # SQLite
```

## Skill：可复用的能力模板

可复用的程序性知识放公共 Skill 库：`.yokeos/skills/<name>/` 每个子目录一份 SKILL.md，兼容 agentskills.io 开放标准。Agent 在 frontmatter 用 `skills: [名]` 按名引用，底座组装 system prompt 时把引用到的 Skill 正文整段注入，强约束产出。边界清晰：**Skill 不是 Tool**，不进工具注册表；加载与注入归上下文层。

## 目标用法示例

[快速开始](./quick-start)里的两个日跑 Demo 各演一种定义丰富度：**每日天气** Agent 是光杆 `AGENT.md`；**每日科技日报** Agent 在 frontmatter 按名引用公共组稿 Skill、配 MCP server。

## 第一阶段边界

- 一句话生成的草稿是预览，不做草稿审批流、Agent 版本管理、跨实例同步
- Skill 正文整段注入；按需加载的渐进式披露（元数据先注入、正文按需取）放扩展阶段
- 带脚本的 Agent 需要信任作者——解释器列入命令白名单即授予代码执行权限，容器级隔离放扩展阶段
