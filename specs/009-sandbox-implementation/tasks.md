---
description: "Task list for feature implementation"
---

# Tasks: Sandbox 三重白名单安全隔离——让 Agent 干活不闯祸（第24节）

**Input**: Design documents from `/specs/009-sandbox-implementation/`（plan.md / research.md D1~D9 / data-model.md / contracts/sandbox.md / quickstart.md）

**Tests**: TDD 纪律显式启用——测试任务先于或伴随对应实现任务（验收 harness 先行），实现与测试在同一任务内闭环、该模块测试红了当场修；集成冒烟单列（并入 boot 既有 `@Tag("integration")` 类，不新建类）。测试方法名英文，教学文档语义以 `@DisplayName` 保留。

**Organization**: 接口墙 + 唯一实现 + 规则级 harness（Foundational——US1/US2/US3 的规则验收都在 `WhitelistSandboxTest`）→ 八处接线与拒绝留痕（US1，MVP）→ Memory 两档接线 + 装配与缺省自洽（US2）→ 配置三键 + boot 集成装配段与真链路冒烟（US3）→ 收尾。US4（拒绝对模型可见、不可重试）不单列相：收口语义锚 T014、回填可见锚 T024 冒烟——既有 ReAct 链路行为（17 节），零新增逻辑。分批明文：① 构造调用点 15 文件 35 处随各自接线任务同节更新（下游模块的编译完整性在 T021/T024 愈合，阶段门禁全用 `-pl <模块> -am` 不触下游）；② `SandboxProperties` 不设专属测试类（plan 未列——键缺省空表与 deny-all 由 `WhitelistSandboxTest` 空清单参数锚、yaml 读取由 boot 集成与 quickstart 场景三演示锚）；③ 坑七（缺省随 `yokeos.root` 动态）锚在 cli 装配测试（T021）；④ 真链路越权冒烟并入 `ToolSystemEndToEndIntegrationTest`（工具系统端到端最贴题，不新建类）。

## Phase 1: Setup

- [x] T001 记录改造前基线：`mvn test` BUILD SUCCESS 全绿——记录测试数（22 节合流后基线），确认零 pom 变更状态（research D1：本节零坐标、零模块依赖变更）——实测 444 tests / 0 failures / 0 errors / 0 skipped

## Phase 2: Foundational——接口墙、唯一实现与规则级 harness

**Goal**: `yokeos-tool/tool/sandbox` 包六件全落地（宪法 6 兑现）+ `WhitelistSandboxTest` 规则级 harness 全绿——三条校验规则、全部绕过场景、deny-all、对称真实路径解析（后续所有 US 的公共前提）。
**Independent Test**: `mvn test -pl yokeos-tool -am -Dtest=WhitelistSandboxTest` 全绿。

- [x] T002 [P] 四件套接口与值对象（纯类型，D1 字面量）：`yokeos-tool/src/main/java/com/yokeos/tool/sandbox/` 下 `Sandbox.java`（唯一方法 `void enforce(SandboxAction)`，javadoc 钉「宪法 6：不携带任何一档实现特有概念 + microVM 反套口径 D2」）、`SandboxAction.java`（record `(ActionType type, String target)`）、`ActionType.java`（FILE_READ/FILE_WRITE/SHELL_COMMAND/HTTP_REQUEST 四值，javadoc 记 D3 接口先分实现先合）、`SandboxViolationException.java`（RuntimeException 子类，构造器透传 message + 可带 cause 的重载——fail-closed 包装用）
- [x] T003 [P] WhitelistSandboxTest（测试先行，依赖 T002 类型）：`yokeos-tool/src/test/java/com/yokeos/tool/sandbox/WhitelistSandboxTest.java`——`@Nested` 三类 + 两个特殊组，只经 `enforce` 公共入口断言（三个 `check*` private 不直测）：①路径组——白名单内放行（`@DisplayName("白名单内路径_读写放行")`）、白名单外拒绝、`../` 穿越拒绝（`@DisplayName("相对路径穿越_爬出白名单目录_被拦")`，坑一）、**symlink 出根拒绝**（`allowed` 内建链接指向 `outside`，`@DisplayName("符号链接出白名单根_真实路径校验拒绝")`，坑一·严于参照）、白名单内新建文件回溯放行（`@DisplayName("白名单内新建文件_回溯父目录校验放行")`，坑一）、**符号链接前缀目录不假拒绝**（白名单根与目标都在 `@TempDir`（macOS `/var/folders` 符号链接前缀）下正常放行，`@DisplayName("符号链接前缀目录_对称解析不假拒绝")`，research D2）；②命令组——精确条目放行（`ls -la`）、白名单外拒绝（`rm -rf /`）、**变体拒绝**（`ls` 在单但 `lsblk` 拒，`@DisplayName("白名单内命令的形似变体_拒绝")`，坑三）、绝对路径与裸名不同条目（`/bin/ls` 拒，`@DisplayName("裸名与绝对路径是不同条目_不归一")`，research D4）；③域名组——通配真子域命中（`api.example.com`、`a.b.example.com`）、**形似域名拒绝**（`evil-example.com`，`@DisplayName("形似域名不得命中通配符_点号边界")`，坑四）、通配不覆盖裸域 + 精确条目命中裸域、query 夹带不误放行（`https://other.com/x?url=api.example.com` 拒）、大小写不敏感（`API.Example.COM` 放行，research D3）、端口无关（`:8443` 放行）、畸形 URL 拒绝（`not-a-url`，`@DisplayName("畸形URL无主机名_拒绝")`）；④空白名单 deny-all 组——三类全空一律拒绝（`@DisplayName("三类白名单全空_一律拒绝而非放行")`，坑六反向）
- [x] T004 WhitelistSandbox + SandboxProperties（实现，T003 转绿）：`yokeos-tool/src/main/java/com/yokeos/tool/sandbox/WhitelistSandbox.java`——构造时三清单各归各位（路径根 `toAbsolutePath().normalize()`、命令 `Set.copyOf`、域名模式原样）；`enforce` 按 type switch 路由（FILE_READ/FILE_WRITE 同路由 `checkFilePath`——D3）；`checkFilePath` = 对称真实路径解析（contracts/sandbox.md §2.1 算法：`resolveReal(target)` 对每个 `resolveReal(root)` 做 `startsWith`；`resolveReal` 回溯最近存在祖先 `toRealPath` + 尾段接回；IOException → `SandboxViolationException` fail-closed；祖先全不存在 → 拒绝）；`checkShellCommand` = `Set.contains` 精确比对；`checkHttpUrl` = `URI.create` 解析（异常转拒绝、消息不回显 URL）→ host 双侧 `toLowerCase(Locale.ROOT)` → `matchesDomain`（`*.` 前缀 → `endsWith(".example.com")` 点号边界；否则 equals）——三个 `check*` 均 private；消息口径 contracts §4 四形态（常量前缀 + 动态值进异常构造器；域名只显 host）。`SandboxProperties.java`——record 三组 `List<String>` + 静态 `load(InputStream)`（SnakeYAML classpath 原文，22 节 `MemoryProperties` 同款；键缺省给空表）
- [x] T005 阶段门禁：`mvn test -pl yokeos-tool -am` 全绿（T003 全过 + 既有工具测试零回归——本阶段未触工具类）

## Phase 3: US1 八处接线与拒绝留痕（P1）🎯 MVP

**Goal**: 六个前序留位类接线（构造注入 + 方法首行 enforce）+ ToolExecutor 收口注释改写——越权动作被拦截、IO 零发生、拒绝走既有审计。
**Independent Test**: `mvn test -pl yokeos-tool -am` + `mvn test -pl yokeos-core -am` 全绿（坑二/五回归点在位）。

- [x] T006 [P] [US1] FileToolsTest 更新（测试先行）：`yokeos-tool/src/test/java/com/yokeos/tool/builtin/FileToolsTest.java`——既有用例构造改传「白名单含 `@TempDir`」的真 `WhitelistSandbox`；新增拦截回归：mock 全拒 `Sandbox`（`doThrow(new SandboxViolationException(...))`）→ readFile/writeFile/listDir 三方法均抛 `SandboxViolationException` 且 **IO 零发生**（`assertFalse(Files.exists(目标))`——校验不过文件根本不建，`@DisplayName("白名单拒绝时_文件动作零发生")`，坑五）；白名单外真实路径拒绝用例（真 WhitelistSandbox 只含 TempDir，读 `/etc/hosts` 拒且未读）
- [x] T007 [US1] FileTools 接线：`yokeos-tool/src/main/java/com/yokeos/tool/builtin/FileTools.java`——`private final Sandbox sandbox` + 构造器 `FileTools(Sandbox)`；三方法首行兑现留位注释（readFile=FILE_READ、writeFile=FILE_WRITE、listDir=FILE_READ，target=入参 path 原文；先 enforce 后 IO，注释由「24 节接」改写为已接线）；T006 转绿
- [x] T008 [P] [US1] ShellToolsTest 更新（测试先行）：`yokeos-tool/src/test/java/com/yokeos/tool/builtin/ShellToolsTest.java`——既有用例构造改 `(sandbox, Duration)`（保留毫秒级超时注入）；新增拦截回归：①真 WhitelistSandbox 空命令白名单 + `["touch", 目标文件]` → 抛 `SandboxViolationException` 且 `assertFalse(Files.exists(目标))`（`@DisplayName("白名单外命令_进程根本没跑")`，坑五·IO 零发生）；②命令 `ls` 在单放行原用例迁入白名单构造
- [x] T009 [US1] ShellTools 接线：`yokeos-tool/src/main/java/com/yokeos/tool/builtin/ShellTools.java`——构造器 `ShellTools(Sandbox)`（委托 `(sandbox, DEFAULT_TIMEOUT)`）+ 包私有 `(Sandbox, Duration)`；`shell` 方法在入参校验之后、ProcessBuilder 之前 enforce（`SHELL_COMMAND, command.get(0)`——argv[0]，无 split）；**超时注释澄清（research D9）**：「默认超时 30 秒（配置化随 24 节 shell 白名单配置一并处理）」改写为「超时经构造器注入（默认 30s），配置化列扩展阶段（张力二裁决：超时归工具层承载）」；T008 转绿
- [x] T010 [P] [US1] HttpToolsTest 更新（测试先行）：`yokeos-tool/src/test/java/com/yokeos/tool/builtin/HttpToolsTest.java`——既有用例构造改 `(sandbox, httpClient)`；新增拦截回归：mock 全拒 Sandbox + mock `HttpClient` → httpGet/httpPost 均抛 `SandboxViolationException` 且 `verify(httpClient, never()).send(any(), any())`（`@DisplayName("白名单外域名_请求根本没发出")`，坑五）
- [x] T011 [US1] HttpTools 接线：`yokeos-tool/src/main/java/com/yokeos/tool/builtin/HttpTools.java`——构造器 `HttpTools(Sandbox)`（委托既有 HttpClient 默认构造）+ `(Sandbox, HttpClient)` 包私有；httpGet/httpPost 各自首行 enforce（`HTTP_REQUEST, url`，先于 URI 构造）；T010 转绿
- [x] T012 [P] [US1] NotifyToolsTest 更新（测试先行）：`yokeos-tool/src/test/java/com/yokeos/tool/NotifyToolsTest.java`——既有用例构造增参；新增拦截回归：mock 全拒 Sandbox + mock `NotifyChannelAdapter` → notify 抛 `SandboxViolationException` 且 `verify(adapter, never()).send(any(), any())`（`@DisplayName("域名白名单外的推送_根本没发出")`，坑五；ProfileContext 既有测试基建复用）
- [x] T013 [US1] NotifyTools 接线：`yokeos-tool/src/main/java/com/yokeos/tool/NotifyTools.java`——构造器增参 `(Map<String, NotifyChannelAdapter>, Sandbox)`；渠道解析之后、`adapter.send` 之前 enforce（`HTTP_REQUEST, resolved.config().get("url")`——与 http_post 共享域名白名单，[需 §5.8]；url 缺失时跳过 enforce 直接走 adapter 的缺 url 报错——校验有目标才有意义）；T012 转绿
- [x] T014 [US1] ToolExecutorTest 收口回归（测试先行）+ ToolExecutor 注释改写（同任务闭环，core 不依赖 Sandbox 类型）：`yokeos-core/src/test/java/com/yokeos/core/agent/ToolExecutorTest.java`——新增：抛 `RuntimeException` 的假工具 → 结果 `retryable=false` 且一次即止（`@DisplayName("工具异常转为不可重试失败_不重试")`，坑二）+ 审计恰一条（mock auditor `times(1)`，`@DisplayName("拒绝恰落一条审计记录")`，坑二）；`yokeos-core/src/main/java/com/yokeos/core/agent/ToolExecutor.java`——第 78 行留位注释「Sandbox 白名单校验位：24 节在此接线…」改写为收口说明（`SandboxViolationException` 等工具异常在此转不可重试失败 + 审计留痕；enforce 落点在各工具动作发生处——D6/D7、张力一裁决），**代码逻辑零变化**
- [x] T015 [US1] yokeos-tool 其余构造调用点同步：`yokeos-tool/src/test/java/com/yokeos/tool/YokeToolContractTest.java`（及 grep 复核模块内其余 `new FileTools/ShellTools/HttpTools/NotifyTools` 调用点）——统一传「白名单含测试目标」的真 `WhitelistSandbox`（research D1：以 grep 为准）
- [x] T016 [US1] 阶段门禁：`mvn test -pl yokeos-tool -am` + `mvn test -pl yokeos-core -am` 全绿（22 节基线零回归——tool/core 两模块）

## Phase 4: US2 Memory 两档接线与装配自洽（P2）

**Goal**: MarkdownMemoryStore/Mem0MemoryStore 接线（D8 覆盖方法）+ YokeosRuntime 装配（sandbox Bean + 缺省补工作区——坑六/坑七兑现）。
**Independent Test**: `mvn test -pl yokeos-memory -am` + `mvn test -pl yokeos-cli -am` 全绿（坑六放行回归 + 缺省随 yokeos.root 动态）。

- [x] T017 [P] [US2] MarkdownMemoryStoreTest 更新（测试先行）：`yokeos-memory/src/test/java/com/yokeos/memory/MarkdownMemoryStoreTest.java`——既有用例构造增参（白名单含 `@TempDir` 的真 WhitelistSandbox）；新增：①mock 全拒 → `append` 抛 `SandboxViolationException` 且 MEMORY.md 未被创建/未动（`@DisplayName("白名单拒绝时_记忆文件零发生")`，坑五）；②白名单含记忆目录 → `append` 正常放行（`@DisplayName("白名单含工作区_记忆写入正常放行")`，坑六）；`MemoryStoreContractTest` 的 markdown 工厂构造调用点同步增参（替身 mem0 档不经 sandbox——`InMemoryMemoryStore` 无构造变化）
- [x] T018 [US2] MarkdownMemoryStore 接线：`yokeos-memory/src/main/java/com/yokeos/memory/MarkdownMemoryStore.java`——构造增参 `(Path memoryDir, int maxArchiveChars, Sandbox sandbox)`；`append` 首行兑现留位（`FILE_WRITE, memoryFile.toString()`）；`load`/`recallByKeyword` 进程内读不 enforce（research D8，javadoc 记口径）；T017 转绿
- [x] T019 [P] [US2] Mem0MemoryStoreTest 更新（测试先行）：`yokeos-memory/src/test/java/com/yokeos/memory/Mem0MemoryStoreTest.java`——既有用例构造增参（包私有 RestClient 重载同增）；新增：mock 全拒 Sandbox + mock RestClient → append/load/recallByKeyword 三方法均抛 `SandboxViolationException` 且 `verify` REST 零调用（`@DisplayName("白名单外主机_三种Mem0操作零出站")`，坑五·D8）
- [x] T020 [US2] Mem0MemoryStore 接线：`yokeos-memory/src/main/java/com/yokeos/memory/Mem0MemoryStore.java`——public 构造增参 `(String baseUrl, String apiKey, Sandbox sandbox)`、包私有重载同增；append/load/recallByKeyword 三出站方法各自首行 enforce（`HTTP_REQUEST, baseUrl`——research D8：出站 HTTP 不分触发者一律过闸；留位注释兑现并扩展为三方法）；T019 转绿
- [x] T021 [US2] YokeosRuntime 装配 + 缺省自洽：`yokeos-cli/src/main/java/com/yokeos/cli/YokeosRuntime.java`——新增 `@Bean Sandbox sandbox()`（`SandboxProperties.load(classpath)` → **file 组空则补 `workspace()`**（`yokeos.root` 解析值，research D5/坑七）→ `new WhitelistSandbox(...)`，Spring 单例）；`tools()` 四工具构造与 `memoryService()` markdown/mem0 分支构造统一增参注入同一实例（research D6）；`yokeos-cli/src/main/java/com/yokeos/cli/command/ToolListCommand.java` 调用点传空白名单 deny-all 实例（只列不执行）；cli 装配测试（`YokeosRuntimeAssemblyTest` 或同位测试）新增断言：sandbox Bean 存在且缺省配置下其路径白名单含 `workspace()` 解析值（`@DisplayName("缺省路径白名单_随工作区根动态解析")`，坑七）
- [x] T022 [US2] 阶段门禁：`mvn test -pl yokeos-memory -am` + `mvn test -pl yokeos-cli -am` 全绿（22 节既有 Memory 契约/E2E 单测零回归）

## Phase 5: US3 配置三键与 boot 集成装配（P3）

**Goal**: yaml 三键落位（缺省语义与自洽提醒注释）+ boot 五集成测试装配段更新 + 真链路越权冒烟——配置形态与 deny-all 语义可演示。
**Independent Test**: `mvn test -pl yokeos-boot -am` 全绿（五个集成测试在新装配下零回归）。

- [x] T023 [US3] boot application.yaml 三键：`yokeos-boot/src/main/resources/application.yaml` 增 `yokeos.sandbox` 段——`file.allowed-paths` **注释掉**并注明「缺省=工作区根（随 `yokeos.root`）；覆盖时须自行包含工作区，否则 save_memory/产出物写入被自家拦截」（D5/坑六）；`shell.allowed-commands: []` 与 `http.allowed-domains: []` 显式空表 + 「空=deny-all 而非不校验」注释 + 「切 mem0 档须把 mem0 host 加入域名清单」提醒（D9 后半）
- [x] T024 [US3] boot 五集成测试装配段更新 + 真链路越权冒烟：`ToolMcpSmokeIntegrationTest`/`ReActSmokeIntegrationTest`/`MemoryEndToEndIntegrationTest`/`ToolSystemEndToEndIntegrationTest`/`NotifyEndToEndIntegrationTest` 的装配段统一改传「白名单含测试工作区/TempDir」的真 `WhitelistSandbox`（grep 复核全部构造调用点愈合，research D1 的 15 文件 35 处至此清零）；`ToolSystemEndToEndIntegrationTest` 新增真链路越权冒烟用例（`@Tag("integration")` 既有形态）：真 LLM 链路诱导一次白名单外动作（如域名白名单外的 http_get 或空命令白名单下的 shell）→ 断言 `tool_invocations` 落一条 `success=false` 且 `error_message` 为 Sandbox 拒绝消息（`@DisplayName("真链路越权动作_拦截留痕一条")`，D6 端到端 + US4 模型可见收口）
- [x] T025 [US3] 阶段门禁：`mvn test -pl yokeos-boot -am` 全绿；`mvn -pl yokeos-boot -am test -Dgroups=integration -DexcludedGroups=` 冒烟可跑（缺真 key 时 `assumeTrue` 跳过并注明，不失败）

## Phase 6: 收尾（六项证据 DoD）

- [x] T026 全仓硬门禁：`mvn clean verify` 九模块全绿（测试数 = 22 节基线 + 本节新增；Spotless/P3C/Checkstyle/SpotBugs/FindSecurityBugs 全过——CRLF 门禁重点盯 Sandbox 消息形态），红了修实现不改规则
- [x] T027 H4 全局不变量逐条自查 + 教学文档「本节交付物」逐项 ls/grep 存在性核对 + 宪法专项 grep：`grep -rn "@Component" yokeos-tool/src/main/java/com/yokeos/tool/sandbox/` 零命中（显式构造，宪法 3 同精神）、`grep -n "yokeos-tool" yokeos-core/pom.xml` 零命中（收口不依赖 Sandbox 类型）、`grep -rn "sk-" --include='*.java' --include='*.yaml' yokeos-*/src` 零明文（宪法 7）、`grep -rn "internalToolExecutionEnabled(true)\|\.tools(" yokeos-*/src/main` 零命中（宪法 2）、`grep -rn "import reactor" yokeos-*/src/main` 零命中（宪法 4）、八接线位 grep 逐位对号（`sandbox.enforce` 在 FileTools×3/ShellTools/HttpTools×2/NotifyTools/MarkdownMemoryStore/Mem0MemoryStore×3）
- [x] T028 验收报告：`specs/009-sandbox-implementation/acceptance-report.md`（结构照 specs/007：六项证据 DoD + harness 映射表 + 坑一~七回归点逐个过 + 实施偏差节 + 剩余人工项——真链路拦截演示、默认配置自身体感、microVM 反套自查）；CLAUDE.md 常见陷阱表回填（如有新坑）；对话内输出三段式变更总结

## Dependencies

- T001 先行；T002 无依赖（纯类型）。
- Phase 2 内：T003 依赖 T002（类型可编译）；T004 依赖 T003（测试转绿）；T005 收口。
- Phase 3 内：T006→T007、T008→T009、T010→T011、T012→T013 四组测试先行配对（组间 [P] 并行）；T014 独立（core，不依赖 Sandbox 类型）；T015 依赖 T007/T009/T011/T013（工具构造器定形后统一收口调用点）；T016 收口。
- Phase 4 内：T017→T018、T019→T020 配对；T021 依赖 T007/T009/T011/T013/T018/T020（全部构造器定形）；T022 收口。
- Phase 5 内：T023 无依赖；T024 依赖 T021（装配形态定）与各接线任务（构造调用点愈合）；T025 收口。
- 编译完整性说明：下游模块（cli/boot）在 Phase 3 期间编译暂裂，属预期——阶段门禁全用 `-pl <模块> -am`（只建本模块 + 上游），T021/T024 愈合，T026 全仓收口。
- 并行机会：T002/T003 与 Phase 1 无耦合；T006/T008/T010/T012 四组同模块不同文件 [P]；T017/T019 跨模块 [P]。

## Implementation Strategy

- MVP = Phase 2 + Phase 3（规则级 harness + 八处接线——「越权被拦截留痕可查」最小可演示单元）；Phase 4 补 Memory 档与装配自洽（坑六/坑七）；Phase 5 配置形态与真链路冒烟（US3/US4 收口）；Phase 6 收尾。
- 每阶段门禁当场修红；本节结束时 yokeos-tool 新增 sandbox 包六件 + 四工具类改造、yokeos-memory 两档改造、yokeos-core 仅注释、yokeos-cli 装配、boot yaml 三键，全仓测试数 = 22 节基线 + 本节新增（估算 +25 左右：WhitelistSandboxTest ~14 + 工具/档位/收口拦截回归 ~10 + 装配/冒烟断言）。
- 人工项（quickstart 场景三/四）在 T028 记录：真链路拦截演示（越权三连 + sqlite 留痕核对）、默认配置自身体感、microVM 反套自查结论。
