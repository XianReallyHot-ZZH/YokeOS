# CLI 命令

***一句话定位：`yokeos` 命令行是整个底座的入口——初始化工作区、管理 Agent、交互对话、启动服务，12 个子命令覆盖日常操作。***

## 12 个命令

| 类别 | 命令 | 说明 |
|------|------|------|
| 启动和状态 | `yokeos init` | 初始化 `.yokeos/` 工作区（幂等，不覆盖已存在内容） |
| 启动和状态 | `yokeos status` | 查看配置和运行状态 |
| 启动和状态 | `yokeos chat [--profile <name>]` | 交互式多轮对话，可查上下文与 Tool 调用记录 |
| 启动和状态 | `yokeos serve [--port 8080]` | 启动 HTTP API 服务与 Web 管理台 |
| 启动和状态 | `yokeos gateway` | 启动多渠道守护进程 |
| Agent 管理 | `yokeos profile list` | 列出所有 Agent |
| Agent 管理 | `yokeos profile create <name>` | 创建新 Agent（生成最小 AGENT.md 模板） |
| Agent 管理 | `yokeos profile show <name>` | 查看 Agent 定义 |
| Agent 管理 | `yokeos profile delete <name>` | 删除 Agent 目录 |
| 查询 | `yokeos provider list` | 列出已配置的 Provider |
| 查询 | `yokeos tool list` | 列出已注册的 Tool |
| 查询 | `yokeos session list` | 列出会话历史 |

> 命令组名沿用 `profile`，操作的是 `.yokeos/agents/` 下的 Agent 目录——[一个目录 = 一个 Agent](./agent)。

## 三种运行模式

| 模式 | 命令 | 说明 |
|------|------|------|
| 交互对话 | `yokeos chat` | 开发调试和日常使用的主要方式；`--message "xxx"` 发单条消息后退出 |
| HTTP API | `yokeos serve` | 指定端口（默认 8080）开放 REST API 与 Web 管理台，定时任务随之常驻调度 |
| 守护进程 | `yokeos gateway` | 常驻后台，同时服务多个 Channel |

三种模式共享同一份 Agent 配置和 Session 存储，差异只是接入层。

## 启动设计

命令分两类：不需要 Spring 上下文的（`init`、`profile list`）直接走文件操作，启动快；需要 LLM 调用的（`chat`、`serve`、`gateway`）才启动 Spring 上下文。每个子命令一个独立的命令类，入口模块同样有测试覆盖——入口逻辑是用户第一次接触 YokeOS 的地方。

## 配置与密钥

- 敏感配置（API key、MCP server 凭证）通过**环境变量**注入，不明文写死在配置里：配置里用 `${ENV_VAR}` 占位，加载时从环境变量解析
- 配置加载时做基础校验（必填项、格式），缺失或非法时给出清晰报错，**不静默失败**
- 完整的加密存储、密钥轮转、对接企业 KMS/Vault 放扩展阶段

## 第一阶段边界

- IM Channel（企业微信、飞书、钉钉、Slack）放扩展阶段，经 Channel Adapter 插件机制扩展，底层都调 Web Service 的 Agent 接口、不重复实现 Agent 逻辑
- 流式输出（SSE）与异步工具调用放扩展阶段
