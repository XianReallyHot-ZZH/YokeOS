# Data Model: Provider——对接大模型的统一入口（第16节）

Phase 1 产物。表结构出处：技术方案 §9.2（含 2026-09-10 拍板①补列）；字段语义以该节为权威。

## 表：`llm_calls`（审计，day one 即建即写）

| 列 | 类型 | 约束 / 说明 |
|----|------|------------|
| `id` | INTEGER PK AUTOINCREMENT | 主键 |
| `session_id` | TEXT NOT NULL | 关联 Session（第 18 节建表；本节写入方传参） |
| `provider` | TEXT NOT NULL | provider 名 |
| `model` | TEXT NOT NULL | 模型名 |
| `prompt_tokens` | INTEGER | 输入 token 数 |
| `completion_tokens` | INTEGER | 输出 token 数 |
| `total_tokens` | INTEGER | 总 token 数 |
| `success` | BOOLEAN NOT NULL | 成败标识（失败事故必须在库里有痕迹） |
| `error_message` | TEXT | 失败原因（可空；成功时为 NULL） |
| `duration_ms` | INTEGER NOT NULL | 调用耗时（毫秒） |
| `created_at` | TEXT NOT NULL | 调用时间（ISO-8601） |

校验规则：`success=false` 时 `error_message` 必非空；成功与失败**都**各写一行；写入失败不吞异常（审计失败应上抛或落日志，不允许静默丢审计）。

## 表：`tool_invocations`（审计，本节建表不写入）

| 列 | 类型 | 约束 / 说明 |
|----|------|------------|
| `id` | INTEGER PK AUTOINCREMENT | 主键 |
| `session_id` | TEXT NOT NULL | 关联 Session |
| `tool_name` | TEXT NOT NULL | Tool 名称 |
| `input_json` | TEXT | 调用参数（JSON） |
| `result_json` | TEXT | 执行结果（JSON） |
| `success` | BOOLEAN NOT NULL | 是否成功 |
| `error_message` | TEXT | 错误信息（可空） |
| `duration_ms` | INTEGER NOT NULL | 执行耗时（毫秒） |
| `created_at` | TEXT NOT NULL | 调用时间 |

写入者 = 第 17 节 `ToolExecutor`；Sandbox 拒绝也走此表（`success=false`，第 24 节接线）。

## 值对象：`Profile`（frontmatter 派生，全字段承载）

出处：需求文档 §5.2 + 技术方案 §8.2。

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | String | Agent 名（= 目录名，注册键） |
| `description` | String | 描述 |
| `identity.agentName` / `identity.prompt` | String | 人格与系统提示词（本节仅承载） |
| `provider.name` / `provider.model` / `provider.temperature` | String / String / Double | 本节消费段；temperature 透传不解释 |
| `tools` | List\<String\> | 承载（消费归第 20 节） |
| `skills` | List\<String\> | 承载（按名引用公共 Skill 库，消费归第 29 节） |
| `mcpServers` | List\<String\> | 承载 |
| `channels` | List | 承载 |
| `notifyChannels` | List | 承载（消费归第 19 节） |
| `schedules` | List | 承载（消费归第 25 节） |
| `bootstrap` | List\<String\> | 承载（消费归第 17/18 节） |
| `settings.maxIterations` / `settings.maxHistoryTurns` | int / int | 默认 10 / 20（承载，消费归第 17 节） |

派生规则：`AgentLoader` 启动扫描 `.yokeos/agents/*/AGENT.md`；本节校验仅一条——`provider.name` 必须存在于全局 provider 清单，否则该 Agent 记错误日志跳过（不阻断启动、不注册）；坏 YAML / 缺 frontmatter 同样跳过并留痕。配置修改重启生效（clarify Q1）。

## 配置：`yokeos.providers`（application.yaml 全局清单）

出处：技术方案 §3.1/§3.2 + 拍板③。

```yaml
yokeos:
  providers:
    - name: deepseek            # 唯一 provider 名（映射键）
      api-key: ${DEEPSEEK_API_KEY}
      base-url: https://api.deepseek.com   # OpenAI 兼容端点，可选
    - name: kimi
      api-key: ${KIMI_API_KEY}
      base-url: https://api.moonshot.cn/v1
```

校验规则：`name` 唯一且非空；`api-key` 必须为 `${ENV_VAR}` 占位形态（明文即启动报错）；启动时环境变量缺失 → 清晰报错不静默（ConfigLoader 职责，FR7）。
