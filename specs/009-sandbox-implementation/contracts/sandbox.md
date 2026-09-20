# Contract: Sandbox 接口、三条校验规则与八接线位（第24节，specs/009）

> Phase 1 产出。本节对外契约三层：`Sandbox` 接口（动作发生处唯一依赖）、`WhitelistSandbox` 三条校验规则（第一阶段唯一实现）、拒绝消息口径（进 `tool_invocations.error_message` 对模型可见）。签名出处：宪法 6、specs/008 D1~D8、research D2~D8。值对象与配置键见 [data-model.md](../data-model.md)。

## 1. `Sandbox` 接口（落 yokeos-tool/tool/sandbox，八接线位唯一依赖）

```java
public interface Sandbox {
    void enforce(SandboxAction action);   // 确保该动作只在受控环境发生（D2：守门语义——白名单档=校验放行或拒绝，
}                                          //   容器/microVM 档=把动作路由进隔离环境执行；签名不变，语义重心由实现定义）
```

| 项 | 契约 |
|----|------|
| 前置条件 | `action` 非 null、`type` 非 null、`target` 非空（接线位各自保证——工具方法入参校验在前） |
| 行为 | 按 `type` 路由校验；通过即静默返回，**失败抛 `SandboxViolationException`（RuntimeException）**；校验过程自身异常（IO/解析失败）一律转 `SandboxViolationException`（fail-closed） |
| 后置条件 | 通过后**不承诺**动作一定执行（只做守门）；拒绝后动作**根本不发生**（IO 零发生由接线位「enforce 在方法首行」保证） |
| 中立性 | 签名不出现「白名单/容器镜像/VM 配置」——microVM 实现反套自查（D1/D2，验收报告记录结论） |
| 实现数 | 第一阶段恰好一个 `WhitelistSandbox`；升级=新增实现类 + 配置选档，接口与本契约不变（D10） |

## 2. `WhitelistSandbox` 三条校验规则（唯一实现；三个 `check*` 均 private，外部只见 `enforce`）

### 2.1 `checkFilePath`——真实路径（本仓严于参照的定稿点，research D2）

```
checkFilePath(rawPath):
  target = resolveReal( Path.of(rawPath).toAbsolutePath().normalize() )   // 文本级 ../ 先折叠
  allowed = 存在白名单根 root：target.startsWith( resolveReal(root) )      // 目标与根同一 resolveReal——对称
  !allowed → 抛 SandboxViolationException

resolveReal(p):                                                            // 对称真实路径解析
  A = p 沿 getParent() 回溯到的最近存在祖先（Files.exists）
  A 为 null → 视为不可校验，拒绝（fail-closed）
  返回 A.toRealPath().resolve( A.relativize(p) )                            // 解开符号链接；不存在尾段原样接回
  toRealPath 抛 IOException → 转抛 SandboxViolationException（fail-closed）
```

| 场景 | 行为 |
|------|------|
| 白名单内已存在文件/目录 | 放行（真实路径在根内） |
| 白名单内新建（目标不存在） | 回溯最近存在父目录校验——根下新建放行 |
| `../` 拼接穿越（标准化后出根） | 拒绝 |
| 符号链接指向根外（链接在根内、目标在外） | 拒绝（`toRealPath` 穿过链接后出根）——纯字符串比对放行的场景 |
| 白名单根本身处于符号链接前缀目录（macOS `/var/folders` TempDir、`/tmp`） | **放行**（双侧同一 `resolveReal`，无假拒绝） |
| 路径根为相对条目（如 `.yokeos`） | 构造时 `toAbsolutePath().normalize()` 按启动目录解析 |

### 2.2 `checkShellCommand`——argv[0] 精确比对（research D4）

```
checkShellCommand(argv0):
  allowedCommands.contains(argv0) == false → 抛 SandboxViolationException
```

| 场景 | 行为 |
|------|------|
| argv[0]=`ls`，白名单含 `ls` | 放行 |
| argv[0]=`lsblk`，白名单只含 `ls` | 拒绝（非前缀非包含） |
| argv[0]=`/usr/bin/git`，白名单含 `git` | 拒绝（裸名与绝对路径是不同条目，不归一） |

### 2.3 `checkHttpUrl`——host 解析 + 通配符点号边界（research D3/D7）

```
checkHttpUrl(url):
  host = URI.create(url).getHost()        // 解析失败 → 拒绝（消息不回显 URL）；host null/空白 → 拒绝
  h = host.toLowerCase(Locale.ROOT)        // 端口天然不参与（getHost() 不含端口）
  allowed = 存在条目 pattern：matchesDomain(h, pattern.toLowerCase(Locale.ROOT))
  !allowed → 抛 SandboxViolationException

matchesDomain(host, pattern):
  pattern 形如 *.example.com → host.endsWith(".example.com")   // 点号边界：evil-example.com 不命中；裸域不命中
  其他 → host.equals(pattern)                                   // 精确条目命中裸域
```

| 场景 | 行为 |
|------|------|
| `https://api.example.com/v1`，白名单 `*.example.com` | 放行（真子域） |
| `https://a.b.example.com/deep`，同上 | 放行（深层子域） |
| `http://evil-example.com/x`，同上 | 拒绝（点号边界——`endsWith("example.com")` 经典漏洞） |
| `http://example.com/x`，白名单只有 `*.example.com` | 拒绝（通配不覆盖裸域；要裸域另加精确条目） |
| `https://api.example.com/x?url=evil.com`，同上 | 放行但与 evil.com 无关（只看 host，query 夹带不影响判定）；query 里夹 `example.com` 字串**不构成**对其他 host 的放行 |
| `https://API.Example.COM:8443/x`，同上 | 放行（双侧小写；端口不参与） |
| `not-a-url` / `http://[bad` | 拒绝（畸形一律拒绝，消息不回显原文） |
| 三类清单全空 | 一律拒绝（deny-all，非「不校验」） |

## 3. 八处接线位（动作发生处单一落点——D7/张力一裁决）

| # | 接线位 | ActionType | target 形态 | enforce 位置 |
|---|--------|-----------|------------|-------------|
| 1~3 | `FileTools.readFile` / `writeFile` / `listDir` | FILE_READ / FILE_WRITE / FILE_READ | 方法入参 `path` 原文 | 各方法首行（先 enforce 后 IO） |
| 4 | `ShellTools.shell` | SHELL_COMMAND | `command.get(0)`（argv[0]） | 方法首行（入参校验之后、ProcessBuilder 之前） |
| 5~6 | `HttpTools.httpGet` / `httpPost` | HTTP_REQUEST | 方法入参 `url` 原文 | 各方法首行（URI 构造之前） |
| 7 | `NotifyTools.notify` | HTTP_REQUEST | `resolved.config().get("url")`（webhook URL） | `adapter.send` 之前（渠道解析之后）；与 `http_post` 共享同一域名白名单（[需 §5.8]） |
| 8 | `MarkdownMemoryStore.append` | FILE_WRITE | `memoryFile.toString()` | 方法首行（readSections 之前）；`load`/`recallByKeyword` 进程内读不 enforce（research D8） |
| 8b | `Mem0MemoryStore.append` / `load` / `recallByKeyword` | HTTP_REQUEST | `baseUrl` | 三出站方法各自首行（research D8：出站 HTTP 不分触发者一律过闸） |

**不在管辖**（D8 覆盖面原则——判据「动作是否由模型经工具调用可触发」之外的底座基础设施）：`ProviderService` 调 LLM（`api.deepseek.com` 不需要进白名单）、审计落库、会话持久化、`SqliteMemoryStore` 进程内写库、MCP 转发调用（信任边界 + 审计兜底，诚实标注的暴露面）。

**收口位（非 enforce 调用点）**：`ToolExecutor.attemptOnce` 既有 catch 把 `SandboxViolationException` 等 RuntimeException 转 `ToolResult.error(…, retryable=false)` → `tool_invocations` 一条（`success=false` + `error_message`）→ 结果回填对话历史模型下一轮可见；**不可重试**（重试被拒动作无意义）。本节对该类的动作 = 留位注释改写为收口说明，逻辑零变化。

## 4. 拒绝消息口径（O5 定稿，research D7；进 `error_message` 对模型可见）

| 类别 | 消息形态（常量前缀 + 动态目标进异常构造器） | 凭证卫生 |
|------|------------------------------------------|---------|
| 路径 | `路径不在白名单内: <rawPath>` | 路径非凭证，直显便于模型改路 |
| 命令 | `命令不在白名单内: <argv0>` | 同上 |
| 域名 | `域名不在白名单内: <host>` | **只显 host 不显整串 URL**——webhook URL 即凭证（19 节坑二），不得进 `error_message`/日志 |
| URL 解析失败 | `URL 无法解析或不含主机名，按拒绝处理` | **不回显原始 URL**（同上） |
| 路径真实化失败 | `路径真实化失败，按拒绝处理: <rawPath>` | fail-closed 兜底，不漏异常类型 |

不回显白名单清单内容（不帮枚举边界）。所有消息经异常构造器传递（CRLF 门禁唯一通过形态——19/20 节同款），Sandbox 代码自身零日志语句。

## 5. 装配契约（O4 定稿，research D6）

`YokeosRuntime` 的 `@Bean Sandbox sandbox()`：`SandboxProperties.load(classpath application.yaml)` → `file` 组空则补 `workspace()`（`yokeos.root` 解析值，research D5）→ 构造 `WhitelistSandbox`（Spring 单例）；`tools()` 与 `memoryService()` 两 Bean 注入同一实例。`WhitelistSandbox` 无 `@Component`、不进组件扫描；`ToolListCommand` 等无 Spring 场景传空白名单 deny-all 实例（只列不执行）。构造器注入是六个接线类的**当节明确改造点**（前序留位注释点名「24 节接线」），既有测试 15 文件 35 处调用点同节更新。
