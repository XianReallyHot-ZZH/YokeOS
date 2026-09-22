# Data Model: 动态管理（第30节）

> Phase 1 产物。本节**零新表零 schema 变更**（宪法 7：手工建表脚本不动）；数据面是值对象 + 目录结构 + 配置键 + 审计复用点。

## 值对象（新增）

### AgentView（web DTO，create/get/list/put 返回）

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | String | Agent 名（= 目录名 = profileName） |
| `description` | String | 描述（frontmatter，可空） |
| `provider` | String | provider name（显式映射键） |
| `model` | String | 模型名（可空） |
| `tools` | List\<String\> | 声明的工具清单 |
| `hasSchedules` | boolean | 是否带定时（列表页标记用；详情看 agentMarkdown） |
| `agentMarkdown` | String | AGENT.md **全文**（编辑回填数据源，拍板⑧） |

### FileNode（web DTO，tree 返回）

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | String | 文件/目录名 |
| `path` | String | 相对 workspace root 的路径 |
| `type` | String | `dir` \| `file` |
| `children` | List\<FileNode\> | 目录的子节点（文件为 null/空） |

### 请求体 DTO

| record | 字段 | 消费端点 |
|--------|------|---------|
| `GenerateRequest` | `sentence` | POST /agents/generate |
| `CreateAgentRequest` | `name`, `agentMarkdown` | POST /agents |
| `UpdateAgentRequest` | `agentMarkdown` | PUT /agents/{name} |

## 目录结构（运行时文件系统）

```text
.yokeos/
├── agents/                  # 唯一真相源（既有）
│   └── <name>/AGENT.md      # create/update 写入点（AgentStore.write）
└── archive/                 # 新增：删除归档地（按需 mkdirs，init 不预建）
    └── <name>/              # DELETE 移入；重名后缀 -yyyyMMdd-HHmmss
```

## 配置键（yokeos-boot application.yaml，Spring Environment 绑定）

| 键 | 必填 | 缺省 | 语义 |
|----|------|------|------|
| `yokeos.agent-generation.provider` | 调用时校验 | 空（不阻断启动） | 生成用 provider name，指向显式映射表已注册项；空则 generate 端点 503 + 配置方法提示 |
| `yokeos.agent-generation.model` | 否 | 该 provider 默认模型 | 生成用模型名 |

与 provider 清单（SnakeYAML 原文直读，22 节占位策略）并存不悖：本键非密钥、无 `${ENV}` 占位，走 `@Value` 绑定（research D4）。

## 审计复用点（零新表）

| 表 | 本节写入方 | 说明 |
|----|-----------|------|
| `llm_calls` | generate 的一次 `ProviderService.chat` | sessionId = `agent-generation-{序号}`；成败都落账（success/error_message 口径同既有） |

create/update/delete/tree/file 是纯文件系统与内存操作，无 LLM/Tool 调用，不产生审计事件（审计两表口径锚在 LLM 与 Tool 调用，宪法 7 语义不变）。

## 状态迁移（Agent 生命周期）

```text
[不存在] --POST create--> [已注册]（目录在 agents/ + 注册表在 + 定时按需挂）
[已注册] --PUT update--> [已注册']（覆写 AGENT.md + 先注销旧定时再注册新）
[已注册] --DELETE--> [已归档]（目录在 archive/ + 注册表无 + 定时无）
[已归档] （终态：定义可查、审计可查、不可再 invoke——404）
[不存在] --丢目录(Watcher CREATE)--> [已注册]（同一段 register，含防重）
[已注册] --删目录(Watcher DELETE)--> [不存在]（注销 + 移索引，不归档）
```
