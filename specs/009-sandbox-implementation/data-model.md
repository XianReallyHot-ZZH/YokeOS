# Data Model: Sandbox 三重白名单（第24节，specs/009）

> Phase 1 产出。本节**无新表**（拒绝复用 `tool_invocations` 既有失败路径——D6/宪法 7），数据模型 = 两个值对象 + 三组配置键 + 既有审计表的复用口径。值对象签名出处：specs/008 D1/D3；配置键 D5 全键。

## 1. 值对象：`SandboxAction` 与 `ActionType`

```java
public record SandboxAction(ActionType type, String target) {}

public enum ActionType { FILE_READ, FILE_WRITE, SHELL_COMMAND, HTTP_REQUEST }
```

| ActionType | target 语义 | 接线位（D7） | 校验方法 |
|-----------|------------|-------------|---------|
| `FILE_READ` | 文件/目录路径字符串 | `FileTools.readFile` / `FileTools.listDir` | `checkFilePath` |
| `FILE_WRITE` | 文件路径字符串 | `FileTools.writeFile`、`MarkdownMemoryStore.append` | `checkFilePath`（与 FILE_READ 同路由——接口先分、实现先合，D3） |
| `SHELL_COMMAND` | **argv[0] 本身**（非整条命令串） | `ShellTools.shell` | `checkShellCommand` |
| `HTTP_REQUEST` | 完整 URL 字符串 | `HttpTools.httpGet`/`httpPost`、`NotifyTools.notify`（webhook URL）、`Mem0MemoryStore` 三出站方法（base-url） | `checkHttpUrl` |

**target 是纯字符串，是路径/命令/URL 由 type 决定**——接口不携带任何一档实现特有概念（宪法 6 / D1）；`SandboxViolationException` 为 `RuntimeException` 子类，无额外字段（message 即 `error_message` 素材，口径见 [contracts/sandbox.md](./contracts/sandbox.md) §4）。

## 2. 配置键：`yokeos.sandbox.*`（boot application.yaml）

| 键 | 类型 | 缺省 | 说明 |
|----|------|------|------|
| `yokeos.sandbox.file.allowed-paths` | List\<String\> | **键缺省/空 → 代码补 `yokeos.root` 解析值**（当前工作区根，research D5） | 路径白名单根清单；条目可相对（按启动目录 `toAbsolutePath` 解析）可绝对；**覆盖时须自行包含工作区**（否则 `save_memory`/产出物写入被自家拦截——配置自洽，D9/坑六） |
| `yokeos.sandbox.shell.allowed-commands` | List\<String\> | `[]`（deny-all） | 可执行命令白名单；条目与 argv[0] **字面精确比对**（裸名 `git` 与 `/usr/bin/git` 是两个条目，不归一——research D4） |
| `yokeos.sandbox.http.allowed-domains` | List\<String\> | `[]`（deny-all） | 域名白名单；`*.example.com` 只命中真子域（点号边界）、不覆盖裸域；host 双侧小写、端口不参与（research D3）；**切 mem0 档须把 mem0 host 加入本清单**（D9 后半） |

**空语义**：任一清单为空 = 该类动作全部拒绝（deny-all），**不是「不校验」**——写进 yaml 注释并钉回归测试（spec US3）。三键经 `SandboxProperties.load` classpath yaml 原文读取（22 节 `MemoryProperties` 同款，拍板①——不走 Boot 绑定、无 `@ConfigurationProperties`）；改白名单走配置变更重启生效，无运行时管理入口（D12/差异三）。

## 3. 既有数据（本节复用、零改动）

| 数据 | 复用口径 |
|------|---------|
| `tool_invocations`（16 节建表） | 拒绝留痕的唯一去向：`SandboxViolationException` 从工具方法上抛 → `ToolExecutor.attemptOnce` 既有 catch 转 `ToolResult.error(…, retryable=false)` → 落一条 `success=false` + `error_message`（17 节收口链，本节零新增审计逻辑；拒绝恰一条、不可重试——坑二回归点） |
| `.yokeos/` 工作区（18 节 init） | 默认路径白名单的缺省值来源（`yokeos.root` 解析值——`memory/`、`output/`、`agents/`、`skills/` 全在内）；`USER.md` 只读边界不受影响（Sandbox 只拦不授——白名单内也仍无任何写 USER.md 的代码路径，22 节坑三口径不变） |

## 4. 无新表声明

本节不建任何表、不改任何既有表结构、无建表脚本——「不为 Sandbox 单独新增审计逻辑」（D6）的直接推论。审计表的列语义（`success` / `error_message`）不变，只是开始出现 `success=false` 且 `error_message` 为 Sandbox 拒绝消息的行。
