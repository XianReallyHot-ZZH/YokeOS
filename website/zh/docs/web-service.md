# 对外服务（能力六：REST API + Web 管理台）

***一句话定位：Web Service 是 YokeOS 的对外完整门面——业务系统用 HTTP 调一下就能用上 Agent，不用关心内部怎么实现；这是企业把 AI 能力嵌入已有业务系统的唯一通道。***

## 它解决什么

没有对外服务，YokeOS 只是一个 CLI 工具，无法跟企业现有业务系统集成。有了它：告警系统经 HTTP 调运维 Agent 做事件驱动的分诊；客服系统把会话接到客服 Agent；任何能发 HTTP 请求的语言都能接入——Java、Python、Go、前端，第一阶段不出 SDK 也不需要。

## 18 个核心端点

统一前缀 `/api/v1`，按五组组织：

| 组 | 端点 | 说明 |
|------|------|------|
| 会话管理 | `POST /sessions` | 创建会话 |
| 会话管理 | `POST /sessions/{id}/messages` | 发消息（触发 ReAct 循环） |
| 会话管理 | `GET /sessions/{id}` | 查历史 |
| 会话管理 | `DELETE /sessions/{id}` | 归档会话 |
| Agent 调用与动态管理 | `POST /agents/generate` | 一句话生成定义草稿（不落盘、不注册，人在环预览） |
| Agent 调用与动态管理 | `POST /agents` | 创建 Agent（落盘 + 注册，免重启） |
| Agent 调用与动态管理 | `GET /agents`、`GET /agents/{name}` | 列出 / 查看定义 |
| Agent 调用与动态管理 | `PUT /agents/{name}` | 更新定义 |
| Agent 调用与动态管理 | `DELETE /agents/{name}` | 删除（归档，不物理删） |
| Agent 调用与动态管理 | `POST /agents/{name}/invoke` | 无状态调用 |
| 工作区 | `GET /workspace/tree` | 工作区目录树 |
| 工作区 | `GET /workspace/file` | 只读查看工作区文件 |
| 信息查询 | `GET /profiles` | 列运行配置 |
| 信息查询 | `GET /memory` | 查长期记忆 |
| 信息查询 | `GET /tools` | 列可用 Tool |
| 系统状态 | `GET /health` | 健康检查 |
| 系统状态 | `GET /info` | 运行信息 + Provider 状态 |

统一响应信封：`{ "code", "message", "data", "timestamp" }`，成功与错误共用；标准 HTTP 状态码加内部错误码（400 参数错误、404 不存在、500 内部错误、503 Provider 故障）。

## Web 管理台第一版

与 REST 同端口、同进程托管的 Vue 3 单页应用，给运营方一个不碰命令行的窗口：

- **只读观察五页**：会话、Agent（Profile）、Tool、长期记忆、系统状态（含各 Provider 连通情况）——数据全部来自只读端点，界面不设写入口
- **Agent 管理页**：列表 + 查看/编辑/删除 + 「一句话新建 → 预览可改 → 创建」流程（与 REST 同一组 API）
- **工作区页**：目录树 + 只读浏览文件（服务端做防目录穿越校验）

管理台以「看得见、管得了 Agent」为界，只调用同一组 REST 端点，不带独立后端逻辑。

## 业务系统集成四模式

| 模式 | 方式 | 适用 |
|------|------|------|
| 同步调用 | `POST /agents/{name}/invoke` 等返回 | Stateless 短任务 |
| 会话保持 | 先创建 Session，后续多次发消息 | 连续对话 |
| Webhook 触发 | 告警系统、CI/CD 通过 Webhook 调 Agent | 事件驱动 |
| 跨语言集成 | 任何能发 HTTP 请求的语言 | 通用集成 |

## 第一阶段边界

- **不做**认证机制（无认证假设内网）、流式响应 SSE、WebSocket、RBAC 权限、限流——放扩展阶段
- 定时任务管理端点（任务查询/执行历史/立即补跑/启停）与白名单管理端点**显式列为扩展规划位**（见 ADR 0008）：第一阶段的可查性由 `scheduled_tasks`/`task_executions` 落库加 Session 查询与审计表承接，白名单走配置文件形态
- 请求限制：单条消息最大 32KB，Session 历史最多返回最近 100 条，Agent 调用最长 60 秒超时
