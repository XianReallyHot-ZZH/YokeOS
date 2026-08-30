# 工具体系与沙箱（能力四：让 Agent 能干事）

***一句话定位：Agent 通过工具操作外部世界——九个内置工具覆盖最短链路，每次调用过三重白名单沙箱、全程留痕；业务方按门槛从低到高三档扩展。***

## 它解决什么

Agent 的价值最终落在「能实际操作系统」上：读日志、发请求、写文件、推通知。工具是 Agent 的手，而手必须有边界——没有白名单和审计的工具调用，在企业环境里过不了安全审查。**基于这个能力**：给 Agent 接入企业自己的 ERP、CRM、CMDB，让它真正能干企业的活；接 GitHub、Jira、Confluence 做研发助手；接 Prometheus、SSH 做运维自愈；业务方零代码扩展，纯 markdown 就能上线新场景。

## 怎么工作

### 统一抽象：YokeTool

内置工具、`@Tool` 注解扩展、MCP 工具都被包装成统一的 `YokeTool` 实例注册到 `ToolRegistry`（约定 `getName` / `getDescription` / `getInputSchema` / `execute` 四个方法），ReAct 循环不感知工具来源。

![Tool 调用流程：LLM 决定调用 → YokeOS 执行 → 外部世界 → 结果回填](/images/docs-tool-flow.svg)

### 内置工具（九个，分五组）

| 组 | 工具 | 说明 |
|------|------|------|
| 文件 | `read_file` `write_file` `list_dir` | 路径白名单内读写列目录 |
| Shell | `shell` | 命令白名单 + argv 直传（不经 Shell 解释拼接）+ 超时 |
| HTTP | `http_get` `http_post` | 域名白名单 |
| 记忆 | `save_memory` `recall_memory` | 长期记忆读写（归 Memory 模块） |
| 通知 | `notify` | 推送到 Agent 配置的通知渠道 |

九个工具覆盖「让 Agent 能读写文件、跑命令、调外部 API、记事、往外推通知」的最短链路。

### 扩展三档，门槛从低到高

| 方式 | 门槛 | 做法 | 适用场景 |
|------|------|------|---------|
| 方式一 | 零代码 ⭐ 主推 | 写 Agent 目录 + 复用社区现成 MCP server | 描述意图，LLM 自己组合调用 |
| 方式二 | 轻代码 | 任何语言写 MCP server，配在 `mcp_servers.yaml` | 接入企业自有系统（ERP、CRM） |
| 方式三 | 重代码 | `@Tool` 注解写 Java Spring Bean，进程内直调 | 深度集成，性能最好 |

> 选择原则：能用方式一就不用方式二，能用方式二就不用方式三。

![扩展 Tool 三档：零代码 AGENT.md 目录+MCP、轻代码自写 MCP server、重代码 @Tool Java Bean，门槛从低到高](/images/docs-plugin-tool-tiers.svg)

### 沙箱：接口先行，白名单起步

`Sandbox` 接口只表达「在受控环境里执行一个动作」这一个意图（`enforce(action)`，四类动作：文件读/文件写/Shell 命令/HTTP 请求），签名里不出现任何一档实现特有的概念。第一阶段唯一实现 `WhitelistSandbox`：

![Sandbox 校验流程：内置工具执行前调 enforce，通过则执行，拒绝则抛异常走既有审计路径](/images/docs-sandbox-flow.svg)

- **文件**：路径标准化后比对白名单，处理 `../` 穿越；已存在目标用真实路径校验仍在白名单根内
- **Shell**：可执行文件精确白名单；argv 直传不解释 Shell 语法
- **HTTP**：解析 host 后做通配符匹配（`notify` 推送共享同一份域名白名单）

校验失败抛异常，工具执行终止，异常走既有审计路径写入 `tool_invocations`（`success=false`），不为沙箱单独立审计。**不使用 Java SecurityManager**——它在 JDK 17 起废弃、JDK 21 已不可用。

## 目标用法示例

零代码做一个「每天早上推送 GitHub PR 评审进度到 Slack」：

1. 建 `.yokeos/agents/daily-pr-digest/`，写一份 `AGENT.md`（frontmatter 声明 provider、`mcp_servers: [github-mcp, slack-mcp]`、`schedules`，正文写任务指令）
2. 复用社区现成的 MCP server，配置在 `mcp_servers.yaml`
3. 需要固定产出格式，在 frontmatter 按名引用公共 Skill

整个过程不写一行代码。

## 第一阶段边界

- 应用层白名单是「劝阻级」防线：防模型犯傻误操作，防不住蓄意绕过；不建议跑完全不可信代码或对外多租户
- **装一个带脚本的 Agent = 信任这个 Agent 的作者**：解释器列入命令白名单即授予代码执行权限，解释器发起的网络请求不经过域名白名单
- 容器隔离（namespace + cgroups）与 microVM 按升级信号驱动，接口不变只新增实现类
- 白名单走配置文件形态，管理端点放扩展阶段
