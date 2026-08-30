# 快速开始

> **第一阶段进行中，随实现回写。** 本页描述的是从文档链蒸馏的**目标用法**——它随打包发布的运行时一起在第一阶段末尾落地。今天可用的部分：整套[立项文档链](https://github.com/XianReallyHot-ZZH/YokeOS/tree/master/docs)与每单元正在累积的验收证据。

## 目标用法：五分钟一个业务 Agent

YokeOS 的设计目标是：初始化工作区、写一份 `AGENT.md`、开始对话。第一阶段完成后，完整链路是这样的：

```bash
# 1 · 初始化工作区（幂等：已存在的目录和文件一律不覆盖）
yokeos init

# 2 · 创建一个 Agent 目录（生成最小 AGENT.md 模板）
yokeos profile create ops-agent

# 3 · 编辑 AGENT.md：frontmatter 配 Provider、工具、通知、定时；正文写任务指令

# 4 · 跟它对话
yokeos chat --profile ops-agent

# 或把能力暴露成 HTTP 服务
yokeos serve --port 8080        # REST API + Web 管理台
```

**前置条件**：Java 21，一个 LLM API key（DeepSeek / Qwen / Kimi / Ollama 等）。

初始化后的工作区：

```text
.yokeos/
├── agents/            # 每个子目录 = 一个 Agent
├── skills/            # 公共 Skill 库
├── output/            # Agent 产出物
├── memory/
│   └── MEMORY.md      # 长期记忆
├── sessions/          # 会话导出（真相源在 SQLite）
├── logs/              # 结构化日志
├── AGENTS.md          # Bootstrap：项目级 agent 行为说明
├── SOUL.md            # Bootstrap：默认 agent 人格定义
├── USER.md            # Bootstrap：用户偏好
├── mcp_servers.yaml   # MCP 配置
└── yokeos.db          # SQLite
```

三个 Bootstrap 文件在 Agent 启动时被自动加载进系统提示词：项目背景（AGENTS.md）、Agent 人格（SOUL.md）、用户偏好（USER.md）。

## 两个日跑 Demo：第一阶段的验收

早期按「一个 Demo 验证一个能力」拆过五个 Demo，但真实场景从来不是单一能力独立跑的——一个能打动人的 Agent，一定是多个能力叠在一起、自己到点跑起来的。最终收敛成两个**每日自动运行**的端到端 Demo，加起来覆盖全部六个核心能力加定时任务这个第三触发源：

### Demo 一：每日天气（光杆 AGENT.md）

每天早上 8 点，Agent 自动查天气、生成穿搭建议，经 Webhook 推送到企业 IM 群机器人：

1. `AgentScheduler` 按 `schedules` 声明的 cron 到点触发，走与人工触发**同一条执行链路**
2. LLM 决定调 `http_get` 拉天气——过域名白名单，写入审计表
3. LLM 看到天气生成穿搭建议，调 `notify(channel="team-im")` 推送——同样过白名单、落审计
4. 完整对话留在这次自动触发的 Session 里，`GET /api/v1/sessions/{id}` 可查

**验收要点**：全程不需要人工触发；两次涉外调用都有白名单校验与审计记录。

### Demo 二：每日科技日报（AGENT.md + 公共 Skill + MCP）

每天早上 9 点，Agent 自动汇总当日科技新闻并推送，**日报内容会体现用户之前说过的关注方向**（比如「更关注 AI 和芯片」）：

1. 业务方创建 `daily-tech-digest/AGENT.md`（按名引用公共组稿 Skill `digest-format`，配新闻 MCP server），公共组稿规范放 `.yokeos/skills/digest-format/SKILL.md`
2. 用户此前说过「更关注 AI 和芯片」，Agent 调 `save_memory` 写入 `MEMORY.md`
3. 到点后 system prompt 注入正文、记忆与 Skill 规范；LLM 自己决定调哪个新闻工具、自己组织日报
4. 因为看到记忆里的偏好，组稿自然侧重 AI 和芯片方向

**验收要点**：业务方全程不写一行 Java 代码；日报内容体现 `MEMORY.md` 里记住的偏好，验证长期记忆在跨天场景里生效。

两个 Demo 都是「钟推」（定时触发），但都能同时支持「人推」手动补跑（`yokeos chat` 或 `POST /agents/{name}/invoke`）——同一个 Agent 不管从哪个入口触发，走的都是同一条执行链路，行为一致、审计同构。

## 里程碑目标清单

第一阶段按参照实现公开构建过程的课程节序组织（第 16→31 节），**节奏自定、顺序不乱**，不设日历时间盒。每节以「可演示成果」为完成判据：

| 节 | 课型 | 能力主线 | 可演示成果 |
|----|------|---------|-----------|
| 16 | 代码 | Provider 抽象 | 配置 Provider 与 API key，CLI 发消息拿到 LLM 回复 |
| 17 | 代码 | ReAct 循环 | 一次对话内完成多步任务：思考 → 调 Tool → 观察 → 续推 |
| 18 | 代码 | CLI 命令行入口 | `yokeos chat` 多轮会话，可查上下文与 Tool 调用记录 |
| 19 | 代码 | Notify 通知 | Agent 干完活把结果推到 Webhook 通知渠道 |
| 20 | 代码 | Tool 体系与 MCP | 内置 Tool 全量可用，接入外部 MCP server，白名单校验生效 |
| 21 | 评审 | Memory 设计评审 | 评审记录定稿（不产码） |
| 22 | 代码 | Memory 实现 | Agent 跨对话记住用户偏好并在后续对话用到 |
| 23 | 评审 | Sandbox 设计评审 | 白名单沙箱设计定稿（不产码） |
| 24 | 代码 | Sandbox 实现 | 越权路径/命令/域名被拦截，拦截动作留痕可查 |
| 25 | 代码 | 定时任务 | Agent 按 cron 到点自跑，执行历史可查 |
| 26 | 代码 | Web Service 与管理台 | REST 端点完整可用，管理台只读观察五页上线 |
| 27 | 串联 | 全流程串联（一） | CLI → ReAct → Tool → Notify 端到端打通 |
| 28 | 串联 | 全流程串联（二） | 端到端链路固化为集成测试，稳定复跑 |
| 29 | 代码 | 一个目录 = 一个 Agent | 放一个 Agent 目录即得到一个可用的业务 Agent |
| 30 | 代码 | 动态管理 | 一句话生成 → 预览 → 创建 → 编辑 → 删除，全程免重启 |
| 31 | Demo | 真实运行与发布 | 两个日跑 Demo 在真实环境上线，打包发布，项目主页可访问 |

审计两表（`tool_invocations`、`llm_calls`）从第一个有 LLM 调用和 Tool 调用的节起就写入，不以「日志够了」为由推迟。

## 下一步

- [一个目录 = 一个 Agent](./agent)——AGENT.md 的完整形态
- [CLI 命令](./cli)——12 个命令与三种运行模式
- [路线图](./roadmap)——三阶段全景
