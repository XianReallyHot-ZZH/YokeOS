# Research: Sandbox 三重白名单（第24节，specs/009）

> Phase 0 产出。本节设计已由 specs/008 评审冻结（D1~D12 + 张力裁决 5.1~5.3），教学文档拍板①~⑤再封一层，research 范围收窄为：O1~O5 五项开放事项的 plan 级定稿（评审 §5.4 裁定归属）+ 四项实现决策。全部决策有文档链条款或本仓代码实情背书，无外部依赖需核实（零新增坐标——纯 JDK NIO/URI + 既有 SnakeYAML）。

## D1：零依赖核实与代码实情盘点

**Decision**: 零新增 Maven 坐标、零 pom 变更（含模块间依赖——yokeos-memory → yokeos-tool 22 节已备）。

**Rationale**: 路径校验 = `java.nio.file.Path`（`toRealPath`/`getParent`/`relativize`/`startsWith`）与 `Files.exists`；URL 解析 = `java.net.URI`（`create`/`getHost`）；配置读取 = 22 节 `MemoryProperties` 同款 SnakeYAML classpath 原文。改造面实情已逐类核对：`FileTools` 三方法（`@Tool` 注解管道，无显式构造器）、`ShellTools`（无参 public + 包私有 `Duration` 双构造）、`HttpTools`（无参 public + 包私有 `HttpClient` 双构造）、`NotifyTools`（`Map<String,NotifyChannelAdapter>` 单构造）、`MarkdownMemoryStore(Path, int)`、`Mem0MemoryStore(String, String)` + 包私有 `(RestClient)`——加 Sandbox 参数各自保留既有测试重载。构造调用点共 **15 文件 35 处**（含 `yokeos-cli/command/ToolListCommand`——无 Spring 场景，传空白名单 deny-all 实例即可，只列不执行）。

**Alternatives**: 引入安全路径校验库（如 OWASP 的路径穿越工具类）——拒绝（外部依赖换十几行 JDK 代码，违背零依赖与「自掌握」）。

## D2：checkFilePath 的对称真实路径解析（本节最关键的算法决策）

**Decision**: 目标与白名单根**过同一个 `resolveReal` 函数**再比对：`resolveReal(p)` = 从 `p` 沿 `getParent()` 回溯到最近存在祖先 `A`，返回 `A.toRealPath().resolve(A.relativize(p))`（不存在的尾段原样接回）；`checkFilePath` = `resolveReal(target.toAbsolutePath().normalize())` 对每个 `resolveReal(root)` 做 `startsWith`。真实化过程的任何 `IOException` 转抛 `SandboxViolationException`（**fail-closed**：校验完成不了就拒绝，绝不放行也不漏出异常类型）。

**Rationale**: ①单侧 `toRealPath` 有假拒绝陷阱：macOS 上 `/tmp → /private/tmp`、`/var → /private/var`（JUnit `@TempDir` 天然落在 `/var/folders/...`）——目标解析出真实前缀而白名单根停在字符串形态，`startsWith` 必假败；两侧同一函数保证对称，符号链接前缀目录下正常放行（新增回归用例）。②回溯最近存在祖先实现「新建路径校验父目录」（技 §6.7/D4）：目标不存在时其最近存在祖先（可能是白名单根本身或更上层）取真实路径、尾段原样接回——白名单根下新建放行、借道不存在深层路径的穿越仍拦。③`normalize()` 在真实化之前做，文本级 `../` 先折叠；符号链接出根由 `toRealPath` 兜住（链接本身存在即是最近存在节点，真实化必穿过它）。④参照钉版树终态是 `normalize().toAbsolutePath()+startsWith` 纯字符串比对（连其自身课件也自认「防犯傻不防攻击」）——本仓按 D4 严于参照（教学文档拍板③），假拒绝防线是「严」的必要补丁。

**Alternatives**: 目标单侧 `toRealPath`（存在时）+ 根字符串比对——拒绝（macOS 符号链接前缀必假拒绝，集成测试直接红）；构造时把根 `toRealPath`——拒绝（根可能尚不存在，且符号链接格局可在运行中变化，per-check 解析开销毫秒级以下可接受）。

## D3：O2 定稿——域名匹配的大小写与端口口径

**Decision**: host 与白名单条目**双侧** `toLowerCase(Locale.ROOT)` 后比对（DNS 大小写不敏感，统一小写是唯一可预测口径）；**端口不参与校验**——`URI.getHost()` 返回值天然不含端口（`https://api.example.com:8443/x` 的 host 就是 `api.example.com`），host 归一后比对即端口无关；通配 `*.example.com` 实现为 `host.endsWith(".example.com")`（点号边界内建于子串），裸域需单独精确条目（`host.equals(pattern)`），通配不覆盖裸域。

**Rationale**: D4 只定「解析 host + 通配符匹配」；O2 的细节以最小惊讶为原则：小写化消掉大小写绕过（`API.Example.COM`）；端口无关符合白名单语义管「去哪台主机」不管「哪个服务端口」——第一阶段不做端口级策略（扩展阶段随 Tool Policy 一并议）。

**Alternatives**: 大小写敏感精确比对——拒绝（host 大小写在 DNS 语义里无意义，敏感比对制造假拒绝）；端口也校验——拒绝（配置复杂度翻倍无第一阶段信号，白名单条目还得带端口语法）。

## D4：O3 定稿——命令条目形态与匹配口径

**Decision**: 条目与 argv[0] **字面精确比对**（`Set.contains`），裸名（`git`）与绝对路径（`/usr/bin/git`）是**两个不同条目**，不做 `which`/realpath 归一；本仓 shell 工具 argv 列表直传（20 节），enforce 的 target 就是 `command.get(0)` 本身，无参照「取命令首 token」的 split 环节。

**Rationale**: 「精确比对」语义的内建推论——归一（解析裸名到绝对路径再比）会把一条白名单放大成等价类，与 D4「非前缀非包含」的克制同源冲突；模型给什么形态就比什么形态，部署者在配置注释指引下按实际用法写条目（通常裸名）。归一还会引入对 `PATH` 环境的依赖，校验结果随环境漂移——安全规则要确定性。

**Alternatives**: 构造时 `which` 归一到绝对路径——拒绝（PATH 依赖、等价类放大、非确定）。

## D5：O1 定稿——默认白名单三组的具体形态

**Decision**: ①`yokeos.sandbox.file.allowed-paths` **键缺省/空时，代码补当前工作区根**（`YokeosRuntime` 的 `workspace()` 解析值——`@Value("${yokeos.root:.yokeos}")`，装配层知道工作区、白名单类不猜）；yaml 中该键**注释掉**并注明缺省行为与「覆盖时须自行包含工作区，否则 save_memory/产出物被自家拦截」（配置自洽责任随覆盖转移）。②`shell.allowed-commands` 显式空表 `[]`（deny-all）③`http.allowed-domains` 显式空表 `[]`（deny-all），注释提醒「切 mem0 档须把 mem0 host 加入本清单」（D9 后半）。

**Rationale**: ①工作区随 `yokeos.root`/cwd 动态（坑七），静态 yaml 写不了；代码缺省取**同一个属性**是单一事实源——集成测试把 `yokeos.root` 指到 TempDir 时白名单自动跟上，不会自家拦死。缺省含工作区是 D9 硬前提，由此构造性满足。②③命令/域名默认空 = deny-all 是诚实姿势：不存在有原则的默认命令集（给 `ls` 是拍脑袋），LLM API 域名**不需要**进白名单（D8：ProviderService 是底座基础设施不在管辖）——第一阶段默认把「模型能主动触发的出口」全部关死，要开哪条由部署者显式开（诚实标注三条同源：劝阻级防线宁可严默认）。

**Alternatives**: yaml 显式写 `allowed-paths: [.yokeos]`——拒绝（相对条目与 `yokeos.root` 覆盖值脱钩，覆盖工作区位置时白名单指错地方）；代码缺省追加而不允许移除工作区——拒绝（安全闸门必须能锁死工作区，追加语义剥夺了这个能力）。

## D6：O4 定稿——装配路径细化

**Decision**: `YokeosRuntime` 新增 `@Bean Sandbox sandbox()`（Spring 单例）：`SandboxProperties.load(classpath application.yaml)` 读三组清单 → `file` 组为空时补 `workspace()` → 构造 `WhitelistSandbox`；`tools()` 与 `memoryService()` 两 Bean 增参注入同一实例。`ToolListCommand`（不启 Spring 的文件操作命令）传 `new WhitelistSandbox(空清单)` ——只列工具名不执行，deny-all 实例即可。`SandboxProperties` 形态照 `MemoryProperties`：record + 静态 `load(InputStream)`（SnakeYAML 原文，键缺省给空表）。

**Rationale**: 拍板①定大方向（无 `@Component`/`@ConfigurationProperties`，宪法 3 同精神）；`@Bean` 单例保证两次注入同一实例（无重复构造、未来加缓存/计数只改一处）；`ToolListCommand` 的 18 节分类（不需要 Spring 上下文的命令直接文件操作）不破坏——白名单类是纯构造无容器依赖。

**Alternatives**: 每个消费 Bean 各自 `SandboxProperties.load` 构造一份——拒绝（配置读两次、实例两份，与「底座单例」心智不符）；给 `yokeos-tool` 开 `@ComponentScan`——拒绝（宪法 3 同精神明令手工装配）。

## D7：O5 定稿——拒绝消息口径（含凭证卫生）

**Decision**: 异常消息 = 编译期常量前缀 + 动态目标进异常构造器，四类形态：路径 `路径不在白名单内: <rawPath>`、命令 `命令不在白名单内: <argv0>`、域名 `域名不在白名单内: <host>`（**只显 host 不显整串 URL**）、解析失败 `URL 无法解析或不含主机名，按拒绝处理`（**不回显原始 URL**）。不回显白名单清单内容。

**Rationale**: CRLF 门禁唯一通过形态（19/20 节同款）；**「URL 不回显」是 19 节坑二的直接合规**——WebhookNotifyAdapter javadoc 明文「webhook URL 本身即凭证——不进异常消息（error_message 会落审计表）、不进日志」，而 NotifyTools 的 enforce target 恰是完整 webhook URL：解析失败消息若回显原文，凭证就进了 `tool_invocations.error_message`；域名拒绝只显 host（host 非凭证，企业微信/飞书域名公开）。路径与命令目标非凭证，直显便于模型自查改路。

**Alternatives**: 消息带白名单提示（「允许的有 …」）——拒绝（帮探测边界 + 清单可能长）；整串 URL 进消息——拒绝（坑二违例）。

## D8：Memory 两档后端的 enforce 覆盖方法

**Decision**: `MarkdownMemoryStore` 仅 `append` enforce（FILE_WRITE，留位字面）；`Mem0MemoryStore` 的 **append / load / recallByKeyword 三方法统一** enforce（HTTP_REQUEST，target=base-url）。`InMemoryMemoryStore`（测试基建）不经 sandbox。`SqliteMemoryStore` 无动作（22 节口径）。

**Rationale**: D8 覆盖面原则「模型可经工具调用触发的动作发生处」的逐方法推演——mem0 档三方法都是**出站 HTTP**（数据外泄风险类），`recallByKeyword` 由 `recall_memory` 工具触发（模型可触发）、`append` 由 `save_memory` 触发、`load` 由门面组装调用，出站动作不区分触发者一律过闸（host 相同校验成本零差异）；markdown 档 `load`/`recallByKeyword` 是**进程内文件读**且 `load` 属自动组装（底座行为非模型动作）——023 评审表只钉 append（张力三的议题就是「写路径」），不扩大。进程内读自家 MEMORY.md 无外泄面。

**Alternatives**: mem0 也只 append（留位字面最小实现）——拒绝（recall 路径的出站请求绕过白名单，D8 原则漏覆盖）；markdown 三方法全 enforce（含 FILE_READ）——拒绝（超出评审钉的范围，load 是底座自动组装非模型触发）。

## D9：ShellTools 超时注释的澄清（不新增配置键）

**Decision**: 20 节 `ShellTools` 注释「默认超时 30 秒（配置化随 24 节 shell 白名单配置一并处理）」**不兑现配置化**——本节接线时把该句改写为「超时经构造器注入（默认 30s），配置化列扩展阶段」；不新增 `yokeos.sandbox.shell.timeout` 之类配置键。

**Rationale**: 张力二裁决（拍板④/评审 5.2）已定：超时由工具层承载、`ActionType` 不设 TIMEOUT/RESOURCE 值——24 节不给超时加任何配置语义；spec FR7 三键之外的新键触软门禁①（交付物清单之外的对外概念）。构造器注入（既有包私有重载）已满足测试需要，运行时改超时的信号第一阶段不存在。

**Alternatives**: 顺手加 timeout 配置键兑现 20 节注释——拒绝（软门禁①：spec 无此 FR、评审无此定稿、注释是 20 节的预期而非本节交付物；留痕即改注释，不改语义）。

## 汇总：O1~O5 清零 + NEEDS CLARIFICATION 清零

| 开放事项 | 定稿 | 落点 |
|---|---|---|
| O1 默认白名单条目 | file 键缺省=代码补 `yokeos.root` 解析值；shell/http 默认空=deny-all；yaml 注释载明自洽责任 | D5 |
| O2 域名大小写/端口 | 双侧 `toLowerCase(Locale.ROOT)`；端口天然不参与（`getHost()` 不含端口） | D3 |
| O3 命令条目形态 | 字面精确比对，裸名/绝对路径为不同条目，不归一 | D4 |
| O4 装配路径 | `@Bean Sandbox` 单例 + 两消费 Bean 增参；ToolListCommand 传 deny-all 实例 | D6 |
| O5 拒绝消息粒度 | 常量前缀四形态；URL 不回显、域名只显 host（19 节坑二合规） | D7 |

Technical Context 无 NEEDS CLARIFICATION 残留——设计依据 specs/008 D1~D12 冻结，O1~O5 全部落定，实现决策 D1~D9 有文档链条款或本仓代码实情背书。
