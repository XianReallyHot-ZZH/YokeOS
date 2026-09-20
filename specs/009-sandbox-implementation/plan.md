# Implementation Plan: Sandbox 三重白名单安全隔离（第24节）

**Branch**: `specs/009-sandbox-implementation` | **Date**: 2026-09-20 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/009-sandbox-implementation/spec.md`

## Summary

第 24 节交付 Sandbox 实现（spec 冻结自 specs/008 评审 D1~D12 与张力裁决 5.1~5.3，O1~O5 在本 plan 定稿）：`yokeos-tool` 的 `com.yokeos.tool.sandbox` 包全新增六件——`Sandbox` 接口（唯一方法 `enforce(SandboxAction)`，宪法 6 中立签名）+ `SandboxAction`/`ActionType` 值对象 + `SandboxViolationException` + `WhitelistSandbox` 唯一实现（三条 `check*` 均 private；路径校验用**对称真实路径解析** `resolveReal`，目标与白名单根过同一函数——严于参照钉版树终态的字符串比对，挡 symlink 出根与 `../` 穿越，也防 macOS `/var → /private/var` 前缀错配的假拒绝）+ `SandboxProperties` 配置载体（22 节 `MemoryProperties` 同款 classpath yaml 原文读取，拍板①）。七处接线兑现前序留位：`FileTools`×3 / `ShellTools`（argv[0]）/ `HttpTools`×2 / `NotifyTools`（target=webhook URL，host 拒绝消息不回显 URL——19 节坑二「webhook URL 即凭证」合规）/ `MarkdownMemoryStore`（append·FILE_WRITE）/ `Mem0MemoryStore`（三出站方法·HTTP_REQUEST）——构造器注入 + 方法首行 enforce；`ToolExecutor` 唯一动作是把 17 节留位注释改写为收口说明（逻辑零变化，张力一裁决）。装配在 `YokeosRuntime`：`@Bean Sandbox` 单例注入 `tools()`/`memoryService()` 两 Bean；`file.allowed-paths` 键缺省时代码补 `yokeos.root` 解析值（单一事实源，坑七不写死绝对路径）。O1 定稿：路径缺省=工作区根、命令/域名缺省=空（deny-all 诚实姿势）；O2：host 双侧 `toLowerCase(Locale.ROOT)`、端口天然不参与（`URI.getHost()` 不含端口）；O3：argv[0] 字面精确比对不归一；O5：常量前缀 + 动态值进异常构造器。无新表（拒绝复用 `tool_invocations` 失败路径）、零 pom 变更、零新增第三方依赖。

## Technical Context

**Language/Version**: Java 21（虚拟线程处理并发）

**Primary Dependencies**: **零新增坐标、零 pom 变更**——路径校验纯 JDK NIO（`Path.toRealPath`/`Files.exists`）、URL 解析纯 `java.net.URI`、配置读取复用既有 SnakeYAML（22 节 `MemoryProperties` 同款）；yokeos-memory → yokeos-tool 依赖 22 节已备；yokeos-core 不新增依赖（`ToolExecutor` 收口只认 RuntimeException，不 import Sandbox 类型）。工具类改造只加构造参数与一行 enforce，`@Tool` schema 生成管道（20 节）不动。

**Storage**: 无新表——拒绝复用 `tool_invocations` 既有失败路径（`success=false` + `error_message`，D6「不为 Sandbox 单增审计逻辑」）；配置新增 `yokeos.sandbox.file.allowed-paths` / `shell.allowed-commands` / `http.allowed-domains` 三键进 boot `application.yaml`（D5 全键；缺省语义与自洽提醒见 research D5）。

**Testing**: JUnit 5 + Mockito 单测主体（`@TempDir` 真文件 + mock 全拒 Sandbox，离线全跑）：`WhitelistSandboxTest`（新建，`@Nested` 三类「放行+拒绝」成对 + 绕过场景：`../` 穿越 / symlink 出根 / 新建回溯放行 / `lsblk` 变体 / 形似域名点号边界 / 通配不覆盖裸域 / query 夹带 / 畸形 URL / 空白名单 deny-all / **符号链接前缀目录（macOS TempDir）下根与目标对称解析放行**；只经 `enforce` 公共入口断言，三个 `check*` private 不直测）；`FileToolsTest`/`ShellToolsTest`/`HttpToolsTest`/`NotifyToolsTest` 更新（构造器 + mock 全拒 → 抛 `SandboxViolationException` 且 IO 零发生：文件未建 `assertFalse(Files.exists)`、进程未跑、HTTP 未发——坑五；真 WhitelistSandbox 下原有用例全绿）；`MarkdownMemoryStoreTest`/`Mem0MemoryStoreTest` 更新（拒绝时 MEMORY.md 未动 / REST 未发；白名单含工作区时放行——坑六）；`ToolExecutorTest` 更新（抛 RuntimeException 假工具 → 不可重试 + 审计恰一条——坑二，不依赖 Sandbox 类型）；boot 五个集成测试装配段更新（传「白名单含测试工作区」的真 `WhitelistSandbox`）。集成冒烟真链路一次越权动作（`@Tag("integration")`）。测试方法名 camelCase、中文语义 `@DisplayName`。完成定义 = `mvn clean verify` 九模块全绿（前序零回归——15 文件 35 处构造调用点同步更新是本节最大的回归面）。

**Target Platform**: JVM（macOS 开发环境；symlink 语义跨平台一致，测试对 macOS TempDir 的符号链接前缀天然覆盖）

**Project Type**: Maven 多模块 library + cli（本节触四模块：yokeos-tool / yokeos-memory / yokeos-core（仅注释）/ yokeos-cli + boot yaml）

**Performance Goals**: 每次工具调用前多几次文件系统 stat 级调用（`resolveReal` 的存在性回溯），毫秒级以下——技 §14 不设本节独立指标；白名单清单进程内常驻，无 per-call 解析开销。

**Constraints**: 宪法 5（Sandbox 全部落 `yokeos-tool` 三合一模块，`tool/sandbox` 包）、宪法 6（接口签名不携带任何一档实现特有概念，microVM 反套自查；不用 `SecurityManager`）、宪法 7（拒绝走既有审计路径零新增逻辑；无新表无建表脚本）、宪法 4（全同步——`toRealPath`/`Files.exists` 阻塞调用，无异步）；CRLF 门禁（异常消息=编译期常量前缀 + 动态值进异常构造器；19 节坑二：**webhook URL 即凭证不进任何消息**——URL 解析失败消息不回显原文、域名拒绝只显 host）；语法禁区——避开 P3C/ASM 解析不了的 Java 18+ 语法形态；测试类名 ≤1 连续大写；`WhitelistSandbox` 无 `@Component`、不进组件扫描（宪法 3 同精神，拍板①）。

**Scale/Scope**: 白名单条目量级——路径根个位数、命令/域名条目十级；`WhitelistSandbox` 进程内单例；无多副本共享需求（白名单是配置文件形态，随部署走）。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | 原则 | 本节落点 | 状态 |
|---|------|---------|------|
| 1 | 自实现 ReAct 循环 | 不动循环；enforce 在工具方法内（动作发生处），不碰 ReActLoop/ToolExecutor 执行流（后者仅改注释） | ✓ |
| 2 | Spring AI 只用两件事 | 不新增 Spring AI 接触面——六件新类纯 JDK；工具类只加构造参数与 enforce 行，`@Tool` schema 管道（20 节）不动；零新增 starter | ✓ |
| 3 | Provider 显式映射 | 不涉及 ChatModel；同精神适用：`WhitelistSandbox` 手工构造注入（YokeosRuntime `@Bean`），无 `@Component`/类型扫描（research D6、拍板①） | ✓ |
| 4 | 同步执行 + 虚拟线程 | `Path.toRealPath`/`Files.exists`/`URI.create` 全同步阻塞；零 Reactor、零 CompletableFuture | ✓ |
| 5 | Tool 三合一 | Sandbox 全套落 `yokeos-tool`（`tool/sandbox` 包，宪法 5 点名成员）；不拆模块、不进 core | ✓ |
| 6 | Sandbox 接口先行 | 本节是该原则的兑现节：`enforce(SandboxAction)` 签名不出现「白名单/容器/VM」字样（D1）；第一阶段唯一实现 `WhitelistSandbox`，校验真实路径；无 `SecurityManager`；升级路线=只新增实现类（D10） | ✓ |
| 7 | SQLite + 审计 day one | 拒绝复用 `tool_invocations` 既有失败路径（17 节收口已落地），零新增审计逻辑、无新表；「Sandbox 拒绝也走此表」自 16 节 `ToolInvocationAuditor` 注释预告，本节兑现 | ✓ |
| 8 | 一个目录 = 一个 Agent | 不涉及（白名单是实例级配置，无 Agent 维度；Profile `tools` 点名是 Tool 治理雏形——D11 诚实标注，本节不扩） | ✓ |
| 9 | 结构照抄，瑕疵不继承 | 落位镜像参照（`tool/sandbox` 包、`@Nested` 测试组织、三 `check*` private）；参照钉版树终态三处不继承：`normalize+startsWith` 字符串比对（改 `toRealPath` 真实路径——需 §5.7/§8.5 与 D4 定稿）、`PermissiveSandbox` 临时装配（本仓无裸奔期，拍板②）、`SandboxWhitelist`/管理端点（评审差异三）；命令白名单去 split（本仓 argv 直传，参照首 token 切分是其字符串形态包袱） | ✓ |

Phase 1 设计后复检：无新增违规（见 Complexity Tracking——空）。

## Project Structure

### Documentation (this feature)

```text
specs/009-sandbox-implementation/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output（O1~O5 定稿 + 五项实现决策 D1~D9）
├── data-model.md        # Phase 1 output（值对象 + 配置键 + 无新表声明）
├── quickstart.md        # Phase 1 output（单测/坑回归抽查/真链路拦截演示）
├── contracts/
│   └── sandbox.md       # Sandbox 接口契约 + 三条校验规则 + 八接线位 + 拒绝消息口径
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
yokeos-tool/src/main/java/com/yokeos/tool/
├── sandbox/                                          # 新增包：宪法 5 三合一成员、宪法 6 兑现
│   ├── Sandbox.java                                  # 新增：接口——void enforce(SandboxAction)，无实现特有概念
│   ├── SandboxAction.java                            # 新增：record(type, target)
│   ├── ActionType.java                               # 新增：FILE_READ / FILE_WRITE / SHELL_COMMAND / HTTP_REQUEST
│   ├── SandboxViolationException.java                # 新增：RuntimeException 子类（复用既有失败审计路径）
│   ├── WhitelistSandbox.java                         # 新增：唯一实现——enforce 按 type 路由三个 private check*；
│   │                                                  #   checkFilePath=对称真实路径解析 resolveReal（research D2）；
│   │                                                  #   checkShellCommand=argv[0] 精确比对；checkHttpUrl=host 小写
│   │                                                  #   +通配点号边界（D3）；deny-all；fail-closed
│   └── SandboxProperties.java                        # 新增：配置载体——classpath yaml 原文读取（MemoryProperties 同款）
├── builtin/
│   ├── FileTools.java                                # 修改：+构造器(Sandbox)；readFile/listDir=FILE_READ、
│   │                                                  #   writeFile=FILE_WRITE 三检查位兑现（先 enforce 后 IO）
│   ├── ShellTools.java                               # 修改：+构造器(Sandbox)（保留 Duration 包私有重载）；shell 首
│   │                                                  #   行 enforce(SHELL_COMMAND, command.get(0))；超时注释澄清（D9）
│   └── HttpTools.java                                # 修改：+构造器(Sandbox)（保留 HttpClient 包私有重载）；get/post
│                                                      #   两处 enforce(HTTP_REQUEST, url)
└── NotifyTools.java                                   # 修改：+构造器(Sandbox)；enforce(HTTP_REQUEST, url)——url 取
│                                                      #   resolved.config().get("url")，先于 adapter.send（19 节坑二：
│                                                      #   拒绝消息只显 host 不回显 URL）

yokeos-tool/src/test/java/com/yokeos/tool/
├── sandbox/
│   └── WhitelistSandboxTest.java                     # 新增：@Nested 三类 + 绕过 + deny-all + 对称解析回归
├── builtin/
│   ├── FileToolsTest.java                            # 更新：构造器 + mock 全拒 IO 零发生 + 白名单内原用例全绿
│   ├── ShellToolsTest.java                           # 更新：同上（进程未跑）
│   └── HttpToolsTest.java                            # 更新：同上（请求未发）
├── NotifyToolsTest.java                              # 更新：同上（推送未发）
└── YokeToolContractTest.java                         # 更新：构造调用点（白名单含目标的真 WhitelistSandbox）

yokeos-memory/src/main/java/com/yokeos/memory/
├── MarkdownMemoryStore.java                          # 修改：+构造器参数 Sandbox；append 首行
│                                                      #   enforce(FILE_WRITE, memoryFile.toString())——留位兑现
└── Mem0MemoryStore.java                              # 修改：+构造器参数 Sandbox；append/load/recallByKeyword 三出站
│                                                      #   方法首行 enforce(HTTP_REQUEST, baseUrl)（research D8）

yokeos-memory/src/test/java/com/yokeos/memory/
├── MarkdownMemoryStoreTest.java                      # 更新：拒绝时 MEMORY.md 未动；白名单含工作区放行（坑六）
├── Mem0MemoryStoreTest.java                          # 更新：拒绝时 REST 未发
└── MemoryStoreContractTest.java                      # 更新：三档工厂构造调用点（mem0 替身不经 sandbox 的口径见 D8）

yokeos-core/src/main/java/com/yokeos/core/agent/
└── ToolExecutor.java                                 # 修改（本节唯一 core 触碰）：留位注释改写为收口说明——
                                                       #   SandboxViolationException 等 RuntimeException 在此转
                                                       #   不可重试失败 + 审计留痕；enforce 落点在各工具（D6/D7）

yokeos-core/src/test/java/com/yokeos/core/agent/
└── ToolExecutorTest.java                             # 更新：收口回归——抛 RuntimeException 假工具 → 不可重试
                                                       #   + 审计恰一条（不依赖 Sandbox 类型）

yokeos-cli/src/main/java/com/yokeos/cli/
├── YokeosRuntime.java                                # 修改：+ sandbox() @Bean（SandboxProperties.load；file 键缺省
│                                                      #   补 workspace() 解析值——research D5）；tools()/memoryService()
│                                                      #   两 Bean 注入 Sandbox；ToolExecutor 构造不变
└── command/ToolListCommand.java                      # 修改：构造调用点——传空白名单 deny-all 实例（只列不执行）

yokeos-boot/src/main/resources/application.yaml       # 修改：+ yokeos.sandbox 三键（file 注释说明缺省=工作区根；
                                                       #   shell/http 空表=deny-all；mem0 档需自配 host 提醒）

yokeos-boot/src/test/java/com/yokeos/boot/            # 更新（五个集成测试装配段）：ToolMcpSmoke / ReActSmoke /
                                                       #   MemoryEndToEnd / ToolSystemEndToEnd / NotifyEndToEnd——
                                                       #   传「白名单含测试工作区/TempDir」的真 WhitelistSandbox
```

**Structure Decision**: 九模块既有骨架内落位，本节触 tool / memory / core（注释）/ cli 四模块 + boot yaml，**零 pom 变更、零根 pom 变更**。依赖方向全部既有：tool 内自洽（sandbox 包无新依赖）；memory → tool（22 节已备，Markdown/Mem0 构造注入 Sandbox）；core 零新依赖（收口只认 RuntimeException，Sandbox 类型不进 core）；cli 装配（YokeosRuntime 既有 `@Bean` 面加一个 `sandbox()`，两个消费 Bean 增参）。`tool/sandbox` 包镜像参照 `io.oryxos.tool.sandbox`；六个被改造类的构造器扩展全部由前序留位注释点名（「24 节接线」），属当节明确改造点；既有测试 15 文件 35 处构造调用点同节更新（以 grep 为准），前序零回归是门禁。

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

无违规，无条目（与参照的三处显式差异——真实路径校验、不引 PermissiveSandbox、不做运行时白名单管理——分别有 [需 §5.7/§8.5]+D4、拍板②、评审差异三背书，属定稿兑现非违规）。
