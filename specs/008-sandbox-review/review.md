# 评审文档：Sandbox 设计评审（第 23 节，specs/008-sandbox-review）

> **课型**：评审课（拒绝产码）。**产出定位**：业界方案对照 + 本项目设计定稿，作第 24 节 `/speckit-specify` 的素材——「评审产文档、文档产 spec」。
> **评审输入**：[需] `docs/DemandAnalysis.md` §5.7/§5.8/§6.3/§8.5/§11/§12 · [技] `docs/TechnicalSolution.md` §6.7/§7.3 · [宪] CLAUDE.md 宪法 5/6/7 · [指] `docs/AiProgrammingGuide.md` §4.2/§4.4 · [参] 参照库课件第 23 节（业界数据为其 2026 年口径，未另行联网核实——拍板记录⑥）· [码] 前序节留位现状（`ToolExecutor` 17 节、`NotifyTools` 19 节、内置三 Tool 20 节、Memory 档后端 22 节）。
> **教学文档**：`docs/class/023-sandbox-review.md`（定稿于 2026-09-20，含四张配图，拍板记录见其头部）。

---

## 1. 评审范围与方法

**范围**：核心能力四的 Sandbox 部分——[技 §6.7] 全部条款（接口签名、`WhitelistSandbox` 三条校验方法、升级路线、要点一~四）+ 支撑它的 [需 §5.7] Sandbox 安全隔离小节、[需 §5.8] 推送过域名白名单、[需 §8.5] 安全非功能项、[宪 6] 接口先行。不含扩展阶段方案细节（容器/microVM 只评审「接口与信号是否留对」），不含 Tool Policy（[需 §6.3] 扩展项）。

**方法（三读对照法 + 留位对表）**：逐条读 [技 §6.7] → 每个设计点对照业界方案问一句「业界怎么做、我们为什么这样/不这样」→ 对照前序节代码里预埋的**八处检查位**逐位对号（Sandbox 评审独有的第四读——落点与覆盖面必须与已落地的留位严丝合缝）→ 落「定稿」（§3）或提「差异」（§4）与「张力」（§5）。文档链内部冲突**修文档优先**；本次评审发现的三处张力经用户拍板裁决（教学文档拍板记录③④⑤），不回改文档链原文，裁决留痕于本文档。

**结论形态**：定稿清单 D1~D12（§3）+ 差异记录三条（§4）+ 张力裁决三条与开放事项 O1~O5（§5）+ 第 24 节 specify 素材要点（§6）。

## 2. 业界方案对照

### 2.1 概念对照：业界术语 → YokeOS 对应物

| 业界概念 | 业界含义 | YokeOS 第一阶段对应物 | 采纳/偏离 |
|---|---|---|---|
| 文件系统隔离 | 不能乱读写宿主文件 | 路径白名单 `checkFilePath`（**真实路径**校验，非字符串前缀） | 采纳（应用层形态） |
| 网络隔离 | 不能随便对外连接 | 域名白名单 `checkHttpUrl`（host 解析 + 通配符） | 采纳（应用层形态） |
| 进程/系统调用隔离 | 不能执行危险系统操作 | 命令白名单 `checkShellCommand`（argv[0] 精确比对）；argv 直传杜绝 shell 语法拼接 | 采纳最简形态；seccomp 级随容器档扩展 |
| 资源隔离（防炸弹） | 不能吃光 CPU/内存/磁盘 | Shell 进程超时（20 节已落地）+ 超长输出截断 | **缩放形态**（张力二裁决）；cgroups 完整形态扩展 |
| 应用层白名单校验（第一档） | 动作前用代码拦一道，劝阻不关押 | `WhitelistSandbox`（第一阶段唯一实现） | 采纳，为主角档位 |
| 容器隔离（第二档） | namespace + cgroups + seccomp | （无，扩展阶段一） | 接口预留，信号驱动 |
| microVM（第三档） | Firecracker / Kata / gVisor，独立轻量内核 | （无，扩展阶段二） | 接口预留，信号驱动 |
| 纵深防御 | 沙箱是组合不是开关 | 白名单 + 域名白名单（出口控制）+ 超时截断（资源）+ 审计两表 + Profile `tools`（最小权限） | 采纳，五配套第一阶段各有对应物 |
| 混淆代理（confused deputy） | 高权限 Agent 被注入诱骗滥用权限，白名单防不住 | 诚实标注：白名单非安全边界，防误操作限可用面 | 采纳结论；缓解靠审计 + 最小权限 |
| AgentDojo 基准 | 注入能否劫持 Agent 越权调工具的可打分化 | （不引入） | 只取其结论作定位依据 |
| 能力安全模型（capability） | 最小权限授予能力令牌 | Profile `tools` 字段限定每个 Agent 工具面 | 雏形采纳；完整 Tool Policy 扩展 |
| terminal-backend isolation vs whole-process wrapping（Hermes 解剖） | 只沙箱化 shell/文件工具管不住 MCP 子进程等进程内暴露面 | enforce 只覆盖工具执行路径 = 前者形态；MCP 子进程诚实记录为暴露面 | 采纳提醒（差异二），扩展阶段容器化兜底 |
| 交互式权限门（allow/ask/deny，Claude Code 主防线） | 每个危险操作人工确认 | 纯拒绝制（白名单外一律拒绝），无 ask 通道 | **偏离**：钟推无人值守场景没有人在旁边按 allow；权限门是有人盯着的本地工具的形态 |

### 2.2 系统对照：业界方案与真实系统 → YokeOS 的取舍

| 系统 | 业界做法 | YokeOS 采纳 | YokeOS 偏离/不采纳 | 理由 |
|---|---|---|---|---|
| **Claude Code** | 一档权限门 + 二档 OS 轻隔离叠加（macOS Seatbelt、Linux bubblewrap + seccomp，文件限工作目录、网络白名单、子进程继承）；抽出 `sandbox-runtime` 开源 | **威胁模型站位**：可信机器上防误操作与注入、不跑陌生代码——与 YokeOS 私有部署同类，它没直奔 microVM 印证第一档起步对路；域名白名单、文件限工作目录的思路同源 | 不做交互式权限门（无人值守）；第一阶段不上 OS 轻隔离档 | OS 沙箱依赖平台工具（`sandbox-exec`/bubblewrap），与「零外部依赖、单二进制」的第一阶段定位冲突；OS 档列为扩展候选（与容器档同段位） |
| **Hermes Agent**（NousResearch） | 隔离档做成六种可插拔 terminal backend（local/Docker/SSH/Singularity/Modal/Daytona）；「对抗恶意 LLM 的唯一安全边界是操作系统」；whole-process wrapping（Docker / NVIDIA OpenShell）才是真兜底 | ①接口墙思路（可插拔实现，同一套设计母题）；②「进程内校验只是启发式」的诚实定位；③暴露面提醒——MCP 子进程、进程内直调不在第一阶段 enforce 范围 | 不做六后端可切换；第一阶段不做 whole-process wrapping | 六后端是「跑谁的代码都可能有」的通用底座形态；YokeOS 第一阶段跑企业自配 Agent，一档够用，信号驱动再升 |
| **云解释器与云沙箱**（OpenAI Code Interpreter 用 gVisor；E2B 用 Firecracker；Daytona 用 gVisor 冷启约 90ms；Modal 沙箱跑 GPU） | 跑完全不可信代码、多租户、规模化 → 直奔 microVM 黄金标准 | microVM 作为扩展阶段二的候选实现方向（Firecracker/Kata/gVisor 进升级表） | 第一阶段不上、不预留实现代码 | 威胁模型根本不同：跑陌生代码 vs 跑企业自配 Agent。选型由信任等级决定，不是越强越好 |
| **学界共识**（confused deputy / AgentDojo / capability-based） | 白名单挡不住蓄意攻击是可论证的，不是工程疏忽 | 定位口径：第一阶段白名单**防误操作、限可用面**，对抗性安全靠 OS 级隔离（扩展）+ 审计（已 day one）+ 最小权限（`tools` 雏形）三者合力 | — | 「劝阻不是关押」写进定稿（D11），对外叙述不包装成强隔离 |

**对照得出的三个总判断**（评审立场）：

1. **选型由「跑谁的代码」决定，不由档位高低决定**——Claude Code（可信机器）→ Hermes（可切换）→ 云解释器（陌生代码）三级站位，YokeOS 在最左档附近，是威胁模型想清楚后的选择。
2. **YokeOS 第一阶段不缺纵深，缺的是强隔离那一段**——白名单（本节定稿）、出口控制（域名白名单）、资源（超时+截断）、审计（day one）、最小权限（`tools`）五配套第一天就在；容器/microVM 一段留待信号（D10）。
3. **白名单的诚实定位比它的强度更重要**——它防模型犯傻，防不住蓄意绕过（confused deputy 可论证）；把这条写死（D11 + [需 §12] 风险行）比任何「看起来很安全」的包装都更符合企业自部署的信任契约。

## 3. YokeOS 设计定稿（评审冻结清单）

以下 D1~D12 逐条引 [技 §6.7]（及宪法 6/7）条款，评审确认**冻结**为第 24 节实现依据。每条右侧为业界对照锚点（§2）。

| # | 定稿条款 | 出处 | 业界锚点 |
|---|---|---|---|
| D1 | **`Sandbox` 接口签名**：唯一方法 `enforce(SandboxAction action)`；`SandboxAction = { type: ActionType, target: String }`；`ActionType` 四值 `FILE_READ \| FILE_WRITE \| SHELL_COMMAND \| HTTP_REQUEST`。接口签名不出现「白名单」「容器镜像」「VM 配置」等任何一档实现特有的词 | 宪法 6 + 技 §6.7 | 接口墙（Hermes 可插拔） |
| D2 | **enforce 语义定稿**：读作「确保该动作只在受控环境发生」——白名单档的实现是「校验放行或拒绝」，未来容器/microVM 档的实现可以是「把动作路由进隔离环境执行」；**签名不变，语义重心由实现定义**。中立性校验法：拿 microVM 实现反向套签名，套得进去才算中立（`enforce`/`SandboxAction` 均不含检查对象词） | 评审补全（差异一的正面锚） | microVM 反套（参照课件 §13） |
| D3 | **FILE_READ 与 FILE_WRITE 分离**：接口层预留按读/写分权限的维度；第一阶段 `WhitelistSandbox` 两 case 同路由 `checkFilePath`（同一份路径白名单）——接口先分、实现先合 | 技 §6.7 注 | 方向想清楚、实现只做当下 |
| D4 | **`WhitelistSandbox` 第一阶段唯一实现，三条校验方法**：`checkFilePath`（路径标准化后比对白名单；**已存在目标 `toRealPath` 后必须仍位于白名单根内**，新建路径回溯最近存在的父目录取真实路径——不做纯字符串 `normalize+startsWith`）；`checkShellCommand`（argv[0] 与可执行文件白名单**精确比对**，非前缀非包含）；`checkHttpUrl`（先解析出 host，再与域名白名单**通配符匹配**，不拿整串 URL 或 host 做子串包含）。任意校验失败抛 `SandboxViolationException` | 技 §6.7 + 指 §4.4 跑偏表 | 坑一/三/四（§6.3） |
| D5 | **配置键全键映射**：`yokeos.sandbox.file.allowed-paths` / `yokeos.sandbox.shell.allowed-commands` / `yokeos.sandbox.http.allowed-domains`（[技 §6.7] 短名 `file.allowed_paths` 等的全键展开，随 22 节 `yokeos.memory.*` 先例）。白名单是配置文件形态（`application.yaml`），改白名单走配置变更；**白名单管理端点列扩展阶段**（要点四 / 技 §7.3 显式偏差，评审重申不悄悄加进第一阶段） | 技 §6.7 要点四 + §7.3 | 差异三 |
| D6 | **违规走既有审计路径，不为 Sandbox 单增审计逻辑**：`SandboxViolationException` 经 `ToolExecutor` 既有 `RuntimeException` 转换为**不可重试**失败结果，落 `tool_invocations`（`success=false` + `error_message`）；拒绝的动作根本不执行（文件不建、请求不发） | 技 §6.7 + 宪法 7 | 坑二/五 |
| D7 | **enforce 单一落点 = 动作发生处**（张力一裁决）：`FileTools`（readFile/writeFile/listDir）、`ShellTools`、`HttpTools`（get/post）、`NotifyTools`（HTTP_REQUEST，共享 `http.allowed-domains`）、`MarkdownMemoryStore`（FILE_WRITE）、`Mem0MemoryStore`（HTTP_REQUEST）在各自方法开头调用；`ToolExecutor` 的留位仅作**违规收口**（D6），不重复 enforce；24 节把 `ToolExecutor.java:78` 注释改写为收口说明 | 技 §6.7 + 留位对表 | 坑二；[需 §5.8] |
| D8 | **覆盖面原则**：enforce 挂在「模型可经工具调用触发的动作发生处」；底座自身基础设施（`ProviderService` 调 LLM、审计落库、会话持久化）**不在 Sandbox 管辖**；**MCP 转发调用第一阶段不过 enforce**（四值 ActionType 无法分类 MCP server 语义），治理靠信任边界（配一个 MCP server = 信任其作者，与解释器同款逻辑）+ 审计 day one 兜底；该暴露面诚实标注（张力三裁决 + 差异二） | 评审定稿（技 §6.7 要点三同构） | Hermes 暴露面提醒 |
| D9 | **白名单配置自洽硬前提**：默认路径白名单必须含 `.yokeos/` 工作区（`memory/`、`output/` 在内），否则 `save_memory` 与产出物写入被自家沙箱拦截；切 mem0 档则域名白名单须含其 host。24 节回归锚「默认配置下 `save_memory` 正常放行」 | 评审补全 | 坑六 |
| D10 | **升级路线与信号**（接口不变，只新增实现类）：扩展阶段一容器隔离（namespace+cgroups+seccomp），信号 = 要跑相对不可信代码或多租户；扩展阶段二 microVM（Firecracker/Kata/gVisor），信号 = 要跑完全不可信代码或规模化多租户。**没有信号不上重档** | 技 §6.7 升级表 | 信号驱动（选型判断①） |
| D11 | **诚实标注三条**（[需 §12] 风险行同源）：①第一阶段白名单是劝阻级防线，防误操作、限可用面，防不住蓄意绕过，不建议跑完全不可信代码、不建议对外多租户；②Profile `tools` 字段是 Tool 治理雏形，完整 allow/deny Tool Policy 放扩展阶段；③解释器信任边界——把 Python/Bash 列入 `shell.allowed-commands` 等于授予模型 YokeOS 进程用户的代码执行权，argv 直传只挡 shell 语法拼接、不隔离解释器自身的文件与网络行为，**装一个带脚本的 Agent = 信任这个 Agent 的作者** | 技 §6.7 要点一/二/三 | 学界共识（§2.2） |
| D12 | **第一阶段不做**：容器/microVM/WASM 隔离（[需 §6.3] 扩展）；Tool Policy allow/deny；白名单管理端点（D5）；`ActionType` 不设 TIMEOUT/RESOURCE 值（张力二裁决：超时 = Shell 超时、资源 = 超长截断，均由工具层承载，完整配额随容器档）；MCP 调用 enforce（D8）；`SecurityManager`（JDK 21 已不可用，宪法 6 明令）；交互式权限门（§2.1 偏离行） | 需 §6.3/§8.5 + 技 §6.7 | 克制原则 |

**定稿总口径（对外叙述用）**：第一阶段 Sandbox = 应用层三重白名单（路径/命令/域名）+ 既有审计路径收口；它是纵深防御的第一层与 Tool 执行的统一闸门，不是安全边界——强隔离按信号升级，接口永不变。

## 4. 与参照实现的差异记录

与参照课件（oryxos 第 23 节）立场的显式分叉，全部有文档链背书，评审只做留痕：

| # | 差异 | 参照立场 | YokeOS 立场 | 理由 |
|---|---|---|---|---|
| 差异一 | 接口形态 | 「在受控环境里执行动作」+ **per-call 策略对象**（含基础/容器/microVM 隔离等级维度）；调用方为 ToolExecutor 单点 | `enforce(SandboxAction)` **守门语义**（D2），无策略对象；白名单是部署级配置，经构造注入实现类；调用方是**八处动作发生处**（D7），非执行器单点 | 宪法 6 已钉 `enforce(action)` 字面量；20 节工具已按「检查位 + 工具自执行」落地，执行权留在工具侧；换档靠换实现类 + 配置选档，不靠接口传参。未来若需 per-call 策略，属接口演进 = 设计变更走宪法修订，不由实现顺手扩 |
| 差异二 | MCP 覆盖面 | 解剖 Hermes 后主张 whole-process wrapping 才是真兜底，提醒「只隔离 shell ≠ 隔离了 Agent」 | 第一阶段 MCP 转发调用**不过 enforce**（无法分类），信任边界 + 审计兜底，暴露面写入 D8 诚实标注 | 参照的提醒全盘采纳为定位依据；whole-process wrapping 与容器化 MCP 子进程同归扩展阶段（信号驱动），第一阶段不预留实现 |
| 差异三 | 白名单管理形态 | 参照窗口内交付了沙箱白名单管理端点 | 配置文件形态（`application.yaml`），管理端点按 [技 §7.3] 显式偏差列扩展阶段 | YokeOS 18 端点清单（[需 §5.10]）与管理台页面清单均未包含它；第一阶段「改白名单走配置变更」够用，上游赢在范围收敛（ADR 0008 同款纪律） |

## 5. 张力与遗留裁决

### 5.1 张力一：enforce 落点（技 §6.7 ↔ 17 节 `ToolExecutor` 留位字面）

[技 §6.7]「`FileTools`、`ShellTools`、`HttpTools` 在各自 `execute` 方法开头调用 `sandbox.enforce`」 vs `ToolExecutor.java:78` 留位注释「24 节**在此接线**」——照字面在执行器再调一次 enforce 会与 per-tool 落点重复校验、审计两条，且对无法分类的 MCP 调用强套四值报错。

**裁决（2026-09-20 拍板③）：动作发生处单一落点（D7）；`ToolExecutor` 的位 = 违规收口（D6），不是第二个 enforce 调用点。** 依据：①技 §6.7 明文 per-tool；②执行器只见 name + argumentsJson，ActionType 分类只有动作发生处自己知道；③拒绝落一条审计、且不重试（重试被拒动作毫无意义）。24 节把该注释改写为收口说明（改注释不是改接口，不触软门禁②）。

### 5.2 张力二：超时与资源限制归属（需 §5.7 ↔ 技 §6.7）

[需 §5.7] 把「执行超时和资源占用限制」列在 Sandbox 安全隔离小节下；[技 §6.7] 三条校验方法没有超时/资源维度。

**裁决（2026-09-20 拍板④）：按技归属。** 超时由 `ShellTools` 进程超时承载（20 节已落地）、资源占用由超长输出截断承载（read_file/http 截断）；完整资源配额（CPU/内存）随容器档进扩展阶段；`ActionType` 第一阶段不设 TIMEOUT/RESOURCE 值（D12）。需求文档**不回改**，本裁决为 24 节唯一依据。

### 5.3 张力三：Memory 档后端的覆盖面（技 §6.7 点名清单 ↔ 22 节留位）

[技 §6.7] 只点名 FileTools/ShellTools/HttpTools 三组接线，而 22 节已在 `MarkdownMemoryStore`（FILE_WRITE）、`Mem0MemoryStore`（HTTP_REQUEST）留位、`SqliteMemoryStore` 明文无动作；[技 §5] 对 Memory 写路径过不过 Sandbox 无背书。

**裁决（2026-09-20 拍板⑤）：留位纳入覆盖面，定稿为 D8 普遍原则**（模型可经工具调用触发的动作发生处都过 enforce；底座基础设施不管辖），配套 D9 配置自洽硬前提。依据：①留位已存在，拆位等于改 22 节交付物语义；②「一切模型可触发的落盘与对外请求过同一道闸」是统一心智模型，NotifyTools 同构（[需 §5.8] 明文背书）；③模块依赖无障碍——22 节为 `ToolRegistry.registerAnnotated` 已引入 `yokeos-memory → yokeos-tool`，单向无循环。

### 5.4 开放事项（不阻塞第 24 节开工，plan 阶段定稿）

| # | 事项 | 现状 | 归属 |
|---|---|---|---|
| O1 | 默认白名单具体条目（哪些路径/命令/域名进默认清单） | [技 §6.7] 只定三组配置键与自洽原则（D9），未列默认值 | 24 节 plan 定稿（硬约束：默认含 `.yokeos/` 工作区） |
| O2 | 域名通配符精确语义（`*.example.com` 是否匹配裸域、大小写、端口处理） | D4 只定「解析 host + 通配符」，细节未展开 | 24 节 plan 细化 |
| O3 | 命令白名单条目形态（裸名 `git` vs 绝对路径 `/usr/bin/git`，匹配口径） | D4 只定「精确比对」，条目形态未定 | 24 节 plan 细化 |
| O4 | `Sandbox`/`WhitelistSandbox` 引用的装配路径（boot Bean 构造注入如何送达三个内置 Tool 与两个 Memory 档后端） | 留位注释只说「接」，注入方式未定 | 24 节 plan 定稿（约束：构造注入、无容器类型扫描——宪法 3 同精神） |
| O5 | 拒绝时 `error_message` 的内容口径（对模型可见：违规原因说到什么粒度，是否回显白名单提示） | D6 只定「走既有失败审计」，消息格式未定 | 24 节 plan 细化 |

## 6. 结论：第 24 节 specify 素材要点

### 6.1 六段式骨架预填（供 `/speckit-specify` 组装参数，只写 WHAT/WHY）

```text
第24节需求：Sandbox 安全隔离——让 Agent 干活不闯祸
背景与价值。20 节起 Agent 能读写文件、跑命令、发请求，但执行链上只有审计在留痕、
  没有校验在拦截——模型被提示注入或自己犯傻时，YokeOS 进程权限就是它的权限
  （需 §12 已识别「Tool 执行安全风险」）。Sandbox 是 Agent 底座多 Agent 共处的安全前提：
  在动作发生处拦一道三重白名单（路径/命令/域名），拒绝走既有审计留痕（宪法 7）。
用户场景。
  ① Agent 被诱导读工作区外的敏感文件（如 ~/.ssh 私钥）→ FILE_READ 校验真实路径
     出白名单根，拒绝且 tool_invocations 留痕。
  ② 模型拼 ../ 或经符号链接想逃出工作区写文件 → toRealPath 校验拒绝；白名单内
     新建文件（父目录回溯校验）正常放行。
  ③ Agent 只被授权查天气与推送，误发起 shell 删除命令 → argv[0] 不在命令白名单，
     精确比对拒绝，不重试。
功能需求（候选，specify 时按此裁剪）。
  FR1 Sandbox 接口：enforce(SandboxAction{type, target})，ActionType 四值
     （FILE_READ/FILE_WRITE/SHELL_COMMAND/HTTP_REQUEST），接口不携带任何一档
     实现特有概念
  FR2 路径白名单校验真实路径：已存在目标 toRealPath 后仍在白名单根内；新建目标
     回溯最近存在父目录；挡 ../ 穿越与符号链接
  FR3 命令白名单：argv[0] 与白名单精确比对（非前缀非包含）
  FR4 域名白名单：解析 host 后通配符匹配（不拿整串 URL 做子串）
  FR5 校验失败抛 SandboxViolationException：动作不执行，经既有失败路径落
     tool_invocations（success=false + error_message），不可重试
  FR6 八处接线位在动作发生处调用 enforce：FileTools×3 / ShellTools / HttpTools×2 /
     NotifyTools / MarkdownMemoryStore / Mem0MemoryStore
  FR7 配置键 yokeos.sandbox.file.allowed-paths / shell.allowed-commands /
     http.allowed-domains；默认路径白名单含 .yokeos/ 工作区（配置自洽：
     save_memory 与产出物写入不被自家拦截）
  FR8 ToolExecutor 只做违规收口（异常→不可重试失败→审计），不重复 enforce
明确不做（边界）。容器/microVM/WASM 隔离（升级信号见定稿 D10）；Tool Policy
  allow/deny；白名单管理端点；ActionType 不设 TIMEOUT/RESOURCE（超时与截断由
  工具层承载）；MCP 转发调用 enforce（信任边界 + 审计兜底）；SecurityManager；
  交互式权限门。
验收标准。自动化由 24 节验收 harness 承载（mvn test 全绿），关键回归点 = 6.3
  坑↔测试表逐条；人工项：需 §11 第 24 节行「越权路径/命令/域名被拦截，
  拦截动作留痕可查」可演示。
依赖与假设。前序交付物：ToolExecutor（17）、NotifyTools（19）、FileTools/
  ShellTools/HttpTools 与 ToolRegistry（20）、Memory 档后端（22，含
  yokeos-memory → yokeos-tool 依赖）；无新增第三方依赖；设计依据 =
  specs/008-sandbox-review/review.md 定稿 D1~D12 与张力裁决 5.1~5.3。
```

### 6.2 设计依据索引（specify/plan 取材跳转表）

| 素材 | 位置 |
|---|---|
| 接口签名、enforce 语义、FILE 读写分离 | 本文档 §3 D1~D3 |
| 三条校验方法规则细化 | 本文档 §3 D4 |
| 配置键全键与白名单管理形态 | 本文档 §3 D5 |
| 违规审计路径与拒绝不重试 | 本文档 §3 D6 |
| 八处接线位清单与收口口径 | 本文档 §3 D7 + 教学文档二·第四表 |
| 覆盖面原则与 MCP 诚实边界 | 本文档 §3 D8 |
| 配置自洽（默认白名单含工作区） | 本文档 §3 D9 |
| 升级路线与两个信号 | 本文档 §3 D10 + 配图 class-023-4 |
| 诚实标注三条（劝阻级/Tool Policy 雏形/解释器信任边界） | 本文档 §3 D11 |
| 模块落位参照（`yokeos-tool` 三合一） | [技 §10] 模块表 + 宪法 5 |
| 开放事项 O1~O5 | 本文档 §5.4 |

### 6.3 坑 ↔ 24 节回归测试点（评审点名，harness 承接）

| 坑 | 症状 | 修复（定稿锚点） | 24 节回归测试点 |
|---|---|---|---|
| 坑一：路径校验只做字符串前缀比对 | 符号链接、`../` 拼接、cwd 漂移绕过白名单 | 真实路径校验（D4：toRealPath 在根内；新建回溯最近存在父目录） | symlink 出根拒绝；`../` 穿越拒绝；白名单内新建文件放行 |
| 坑二：enforce 落点错位或重复 | 同一动作校验两次、审计两条；MCP 被强套四值报错 | 单一落点 + 执行器收口（D7/裁决 5.1） | 拒绝恰落一条 `tool_invocations`；拒绝不重试 |
| 坑三：命令白名单前缀/子串匹配 | 白名单 `ls` 放行 `lsblk` 等变体 | argv[0] 精确比对（D4） | 在单命令放行；不在单的变体拒绝 |
| 坑四：域名校验不解析 host 不守边界 | `example.com.evil.com` 或 URL 夹带域名串绕过 | host 解析 + 通配符匹配（D4） | 伪造后缀拒绝；子域命中通配；query 夹带不误放行 |
| 坑五：拒绝只打日志不落审计或吞异常继续 | 违规动作照样发生且无痕 | 异常上抛终止 + 既有失败审计（D6/宪法 7） | 拒绝后 IO 根本不发生（文件未建、请求未发）+ 审计一条 |
| 坑六：白名单与底座自身打架 | 默认白名单漏 `.yokeos/`，save_memory/产出物写入被自家拦截；mem0 档域名漏 host 自断 | 配置自洽硬前提（D9） | 默认配置下 `save_memory` 正常放行；mem0 host 在单时放行 |

### 6.4 评审结论

**通过。** [技 §6.7] 设计经业界对照与留位对表后维持原样冻结（D1~D12，其中 D2/D8/D9 为评审补全的定稿），无需回改文档链；三处张力已裁决留痕；五项开放事项移交 24 节 plan 定稿；24 节可凭本文档直接起手 specify。
