# Feature Specification: Sandbox 三重白名单安全隔离——让 Agent 干活不闯祸（第24节）

**Feature Branch**: `specs/009-sandbox-implementation`

**Created**: 2026-09-20

**Status**: Draft

**Input**: 第24节需求（教学文档 `docs/class/024-sandbox-implementation.md` 一、二部分；设计依据 specs/008-sandbox-review 定稿 D1~D12 与张力裁决 5.1~5.3）

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 越权动作被拦截，拦截动作留痕可查 (Priority: P1)

20 节起 Agent 能读写文件、跑命令、发请求，但执行链上只有审计在留痕、没有校验在拦截——模型被提示注入或自己犯傻时，YokeOS 进程权限就是它的权限（需 §12 已识别「Tool 执行安全风险」）。本节在动作发生处拦一道三重白名单：Agent 被诱导读工作区外敏感文件（如 `~/.ssh` 私钥）→ 路径白名单校验真实路径出根，拒绝；模型拼 `../` 或经符号链接想逃出工作区 → 真实路径校验拒绝；Agent 误发起白名单外 shell 命令（如 `rm`）→ argv[0] 精确比对拒绝；Agent 请求白名单外域名 → host 解析 + 通配符匹配拒绝。每次拒绝：动作根本不发生（文件未建、进程未跑、请求未发），经既有失败路径落 `tool_invocations`（`success=false` + `error_message`），不可重试。

**Why this priority**: 这是本节可演示成果的直接口径（需 §11 第 24 节行「越权路径/命令/域名被拦截，拦截动作留痕可查」），也是多 Agent 共处一个底座的安全前提——审计（事后可查，day one 已落地）+ Sandbox（事前拦截，本节）合成 Tool 执行的完整安全闭环。

**Independent Test**: 三重校验各「允许 + 拒绝」成对 + 绕过场景单测（symlink 出根、`../` 穿越、命令变体、形似域名）；真链路人工演示（越权路径/命令/域名各一次，三次拒绝、`tool_invocations` 各一条、动作零发生）。

**Acceptance Scenarios**:

1. **Given** 路径白名单只含工作区，**When** Agent 尝试读白名单外路径（含经符号链接或 `../` 拼接抵达的），**Then** 拒绝、文件根本没被读、`tool_invocations` 落一条 `success=false` 且 `error_message` 人能读懂。
2. **Given** 命令白名单含 `ls`，**When** Agent 发起 `rm -rf /tmp/x` 或 `lsblk`（前缀形似变体），**Then** argv[0] 不在白名单、精确比对拒绝、不可重试、进程根本没跑。
3. **Given** 域名白名单含 `*.example.com`，**When** Agent 请求 `evil-example.com` 或 URL query 里夹带 `example.com` 字串，**Then** 点号边界与 host 解析双双挡下、请求根本没发。
4. **Given** 任意一类校验失败，**When** 拒绝发生，**Then** 恰落一条 `tool_invocations`（不为 Sandbox 单增审计逻辑），失败结果回填对话历史、模型下一轮可见。

### User Story 2 - 白名单内动作零打扰，底座不被自家沙箱拦死 (Priority: P2)

白名单不是拦得越多越安全——默认配置必须自洽：路径白名单含 `.yokeos/` 工作区（`save_memory` 写 `MEMORY.md`、产出物写 `output/` 照常放行）；底座自身基础设施（ProviderService 调 LLM、审计落库、会话持久化）不在 Sandbox 管辖（`api.deepseek.com` 不需要进域名白名单）；工作区路径随启动目录动态变化，默认白名单不写死绝对路径——换目录启动不被拦死。

**Why this priority**: 「白名单与底座自身打架」是本节最大的自伤风险（评审坑六 + 教学文档新增坑七）——默认配置漏工作区，`save_memory` 第一步就被自家沙箱拦掉，安全特性变成可用性事故。

**Independent Test**: 缺省配置下当前工作区内 `save_memory` 与产出物写入放行的单测；既有 E2E（chat 记忆、产出物写入）在默认白名单下回归全绿。

**Acceptance Scenarios**:

1. **Given** 缺省配置（未显式写白名单条目），**When** Agent 在当前工作区内写 `MEMORY.md` 或 `output/` 下产出物，**Then** 正常放行，不被自家沙箱拦截。
2. **Given** 在 `/tmp/demo` 下初始化的工作区，**When** 换一个目录启动 YokeOS，**Then** 默认白名单随启动目录动态解析，新工作区读写不被老绝对路径拦死。
3. **Given** Provider 调用 LLM API（如 `api.deepseek.com`），**When** 域名白名单不含它，**Then** 调用照常——底座基础设施不在 Sandbox 管辖（判据：动作是否由模型经工具调用可触发）。

### User Story 3 - 运维改白名单只改配置文件 (Priority: P3)

第一阶段白名单是配置文件形态：三组键（路径根 / 命令 / 域名）改白名单走配置变更、重启生效，不提供运行时管理端点。语义钉死：**空白名单 = deny-all（什么都不允许），不是「不校验」**——这条写进配置注释并钉回归测试。

**Why this priority**: 配置文件形态是评审定稿（[技 §6.7] 要点四；管理端点按 [技 §7.3] 显式偏差列扩展阶段，不悄悄补进第一阶段）；deny-all 语义若不钉死，「空 = 全放行」的默认会静默拆掉整道墙。

**Independent Test**: 三类白名单全空时一律拒绝的单测；配置注释含 deny-all 语义说明。

**Acceptance Scenarios**:

1. **Given** 三组白名单全为空，**When** 发起任一类动作，**Then** 一律拒绝（deny-all），绝不放行。
2. **Given** 运维要放开某个目录/命令/域名，**When** 修改配置文件并重启，**Then** 新白名单生效——没有运行时增删入口。
3. **Given** 域名白名单要覆盖裸域，**When** 只配了 `*.example.com`，**Then** 裸域 `example.com` 仍被拒（通配符只命中真子域，裸域需单独精确条目）。

### User Story 4 - 拒绝对模型可见，Agent 知道此路不通 (Priority: P4)

拒绝不是终点：`SandboxViolationException` 从工具抛出，被工具执行器既有 catch 接住转为不可重试失败结果（重试被拒动作毫无意义），`error_message`（如「命令不在白名单内: rm」）回填对话历史——模型下一轮看得到失败原因，从而改走白名单内的正路或向用户说明做不到。

**Why this priority**: 拒绝若无反馈，模型会反复撞墙或以为动作已成功；「失败原因对模型可见」是安全闸门与 ReAct 循环的衔接闭环（17 节收口路径的直接兑现，本节零新增收口代码）。

**Independent Test**: 工具抛运行时异常 → 结果不可重试 + 审计恰一条的收口单测（用抛异常的假工具，不依赖 Sandbox 类型）。

**Acceptance Scenarios**:

1. **Given** Agent 发起白名单外命令被拒，**When** 失败结果回填，**Then** 模型下一轮可见「命令不在白名单内」的明确原因，可改走正路（如改用 `read_file` 读白名单内文件）。
2. **Given** 拒绝已发生，**When** 工具执行器处理该失败，**Then** 不重试（不可重试失败一次即止）、`tool_invocations` 恰一条。

### Edge Cases

- 符号链接出白名单根：白名单内的链接指向白名单外目标——真实路径校验（`toRealPath`）拒绝；纯字符串前缀比对会放行（本仓严于参照的定稿点）。
- `../` 路径穿越：拼接后形似白名单内、标准化后爬出根——拒绝。
- 新建路径（目标不存在）：回溯最近存在的父目录取真实路径校验——白名单根下新建文件/目录正常放行，借道不存在深层路径的穿越被拒。
- 命令前缀形似变体：白名单 `ls` 遇 `lsblk`——精确比对拒绝（非前缀非包含）。
- 形似域名：白名单 `*.example.com` 遇 `evil-example.com`（`endsWith("example.com")` 为真）——点号边界拒绝。
- URL query 夹带：请求 URL 的查询串里含白名单域名字串——host 解析口径下不误放行。
- 畸形/解析失败的 URL：一律按拒绝处理，不漏出非 Sandbox 语义的异常。
- 三类白名单全空：deny-all，一律拒绝而非放行。
- 换目录启动：默认白名单随启动目录动态解析，不写死绝对路径。
- MCP 转发调用：第一阶段不过 enforce（四值动作类型无法分类 MCP server 语义）——信任边界 + 审计兜底，诚实标注为暴露面。
- `SqliteMemoryStore` 写库：进程内写库不涉外，无 Sandbox 动作。

## Requirements *(mandatory)*

### Functional Requirements

- **FR1（接口中立）**: `Sandbox` 接口唯一方法 `enforce(SandboxAction{type, target})`；`ActionType` 四值 `FILE_READ / FILE_WRITE / SHELL_COMMAND / HTTP_REQUEST`；接口不携带任何一档实现特有概念（不出现「白名单」「容器镜像」「VM 配置」），中立性以 microVM 反套校验；`FILE_READ` 与 `FILE_WRITE` 接口层分离、第一阶段实现同路由路径校验（接口先分、实现先合）。
- **FR2（路径白名单·真实路径）**: 已存在目标 `toRealPath` 后必须仍位于白名单根内（挡符号链接出根与 `../` 穿越）；新建目标回溯最近存在的父目录取真实路径校验；不做纯字符串 `normalize + startsWith` 比对。
- **FR3（命令白名单·精确比对）**: argv[0] 与可执行文件白名单精确比对（相等，非前缀非包含——前缀会误放行 `lsblk` 之类变体）。
- **FR4（域名白名单·host 解析 + 通配符）**: 先从 URL 解析出 host，再与白名单通配符匹配——`*.example.com` 只命中真子域、匹配带点号边界（`evil-example.com` 不得命中）、通配符不覆盖裸域（裸域需单独精确条目）；不拿整串 URL 或 host 做子串包含（query 夹带不得误放行）；畸形/解析失败的 URL 一律按拒绝处理。
- **FR5（拒绝语义）**: 校验失败抛 `SandboxViolationException`：动作不执行（IO 零发生——文件未建、进程未跑、请求未发），经工具执行器既有失败路径落 `tool_invocations`（`success=false` + `error_message`）、不可重试；不为 Sandbox 单独新增审计逻辑。
- **FR6（八处接线·动作发生处单一落点）**: `FileTools`×3（readFile/listDir=FILE_READ、writeFile=FILE_WRITE）、`ShellTools`（SHELL_COMMAND，argv[0]）、`HttpTools`×2（HTTP_REQUEST）、`NotifyTools`（HTTP_REQUEST，target=从 frontmatter 解析出的 webhook URL，共享域名白名单）、`MarkdownMemoryStore`（FILE_WRITE）、`Mem0MemoryStore`（HTTP_REQUEST）在各自方法开头调用 enforce；工具执行器只做违规收口（异常→不可重试失败→审计一条）、不重复 enforce；`SqliteMemoryStore` 无动作；MCP 转发调用第一阶段不过 enforce；底座自身基础设施（Provider 调 LLM、审计落库、会话持久化）不在管辖。
- **FR7（配置形态）**: 三组配置键 `yokeos.sandbox.file.allowed-paths` / `yokeos.sandbox.shell.allowed-commands` / `yokeos.sandbox.http.allowed-domains`（配置文件形态，改白名单走配置变更）；空白名单 = deny-all 而非「不校验」（写进配置注释并钉回归测试）；默认路径白名单含 `.yokeos/` 工作区（配置自洽硬前提）；默认形态不写死绝对路径（工作区随启动目录动态，默认条目相对形态按启动目录解析或键缺省时代码缺省=当前工作区根；具体默认清单留 plan 定稿）。
- **FR8（收口零新码）**: 工具执行器本节的全部动作 = 把留位注释改写为收口说明（`SandboxViolationException` 等工具异常在此转不可重试失败 + 审计留痕；enforce 落点在各工具动作发生处），代码逻辑零变化。

**明确不做（边界）**: 容器/microVM/WASM 隔离（升级信号：相对不可信/多租户→容器，完全不可信/规模化多租户→microVM；接口不变只新增实现类）；Tool Policy allow/deny（Profile `tools` 字段是雏形）；白名单管理端点（查看/增删改，[技 §7.3] 显式偏差列扩展阶段）；`ActionType` 不设 TIMEOUT/RESOURCE 值（超时=Shell 进程超时、资源=超长输出截断，均由工具层承载——张力二裁决）；MCP 转发调用 enforce（信任边界 + 审计兜底）；`SecurityManager`（JDK 21 已不可用）；交互式权限门（钟推无人值守没有人在旁边按 allow）；PermissiveSandbox 式临时放行装配；白名单运行时增删。

### Key Entities

| 实体 | 说明 |
|------|------|
| `Sandbox` 接口 | 唯一方法 `enforce(SandboxAction)`——「确保该动作只在受控环境发生」的意图表达，未来容器/microVM 只是新增实现类 |
| `SandboxAction` | 值对象 `{type, target}`：target 是纯字符串（路径/命令/URL 由 type 决定） |
| `ActionType` | 四值枚举 FILE_READ / FILE_WRITE / SHELL_COMMAND / HTTP_REQUEST（读写分离便于未来分权限） |
| `SandboxViolationException` | 校验失败异常（RuntimeException 子类）——经既有失败审计路径留痕，不新增审计逻辑 |
| `WhitelistSandbox` | 第一阶段唯一实现：三条校验方法（checkFilePath / checkShellCommand / checkHttpUrl 均 private，外部只见 enforce） |
| `yokeos.sandbox.*` | 三组配置键：file.allowed-paths / shell.allowed-commands / http.allowed-domains；空 = deny-all |
| 八处接线位 | 动作发生处的 enforce 调用点（FileTools×3 / ShellTools / HttpTools×2 / NotifyTools / MarkdownMemoryStore / Mem0MemoryStore）；工具执行器位 = 违规收口，非 enforce 调用点 |
| `tool_invocations` | 既有审计表——拒绝复用其失败路径（`success=false` + `error_message`），本节无新表 |

## Success Criteria *(mandatory)*

### Measurable Outcomes

1. 七个坑的回归点全部可自动化验证（`mvn test` 绿即打勾）：symlink 出根拒绝；`../` 穿越拒绝、白名单内新建文件放行；拒绝恰落一条 `tool_invocations` 且不可重试；白名单内命令放行、变体拒绝；形似域名拒绝、子域命中通配、query 夹带不误放行；拒绝后 IO 根本不发生；默认配置下 `save_memory` 正常放行 + 缺省配置随启动目录动态不拦死自己。
2. 三重白名单各「允许 + 拒绝」成对用例 100% 覆盖，绕过场景（穿越/变体/形似域名/夹带/畸形 URL）零放行。
3. 拒绝后 IO 零发生 100% 验证：文件未建（`Files.exists` 断言）、进程未跑、请求未发（mock 底层执行器 `never()` 断言）。
4. 白名单内既有行为回归零破坏：七个被改造类的既有测试改构造器后全绿；既有 E2E（chat 记忆、产出物写入、notify 推送）在默认白名单下全部通过。
5. `mvn clean verify` 九模块全绿；人工项：真链路拦截演示（越权路径/命令/域名各一次——三次拒绝、`tool_invocations` 各一条、`error_message` 可读、动作零发生）+ 接口中立性 microVM 反套自查结论记录。

## Assumptions

- 设计已冻结：specs/008-sandbox-review 定稿 D1~D12 与张力裁决 5.1~5.3（enforce 单一落点 / 超时资源归工具层 / Memory 档后端纳入覆盖面）——本节无新设计，只有定稿兑现。
- 开放事项 O1~O5（默认白名单具体条目、域名通配大小写与端口口径、命令条目形态、装配路径细化、拒绝消息粒度）留 plan 阶段定稿；本 spec 只锁两条硬约束（默认含 `.yokeos/` 工作区、空白名单 = deny-all）。
- 无新增第三方依赖（纯 JDK NIO + URI 解析 + 既有 SnakeYAML）；配置读取走 22 节 `MemoryProperties` 同款 classpath yaml 原文读取 + 构造注入（教学文档拍板①），不做 `@Component` 扫描装配。
- 参照的 `PermissiveSandbox`（就位前放行装配）、`SandboxWhitelist` 运行时增删、白名单管理端点均不引入（评审差异三，[技 §7.3]）。
- `ToolExecutor` 是本节唯一触碰的 core 类且只改注释（逻辑零变化）；七个接线类构造器注入 Sandbox 是当节明确列出的改造点（留位注释点名「24 节接线」，不触软门禁④）。
- 本仓路径校验严于参照钉版树终态（真实路径 `toRealPath` vs 字符串 `normalize+startsWith`）——评审 D4 定稿 + [需 §5.7/§8.5]「校验真实路径」背书，记为显式差异（瑕疵不继承）。
- 真链路拦截演示使用本机既有真 key（`DEEPSEEK_API_KEY`），走 `yokeos chat`。
