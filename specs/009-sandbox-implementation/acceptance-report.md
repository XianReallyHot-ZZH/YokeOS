# 验收报告：Sandbox 三重白名单（第 24 节，specs/009-sandbox-implementation）

> 课型：代码课。spec：[spec.md](./spec.md) · plan：[plan.md](./plan.md) · tasks：28/28 ✅ · 设计依据：specs/008-sandbox-review 定稿 D1~D12 与张力裁决 5.1~5.3（O1~O5 本节 plan 定稿，research D3~D7）。
> 日期：2026-09-20 · 分支：`specs/009-sandbox-implementation`。

## 一、六项证据 DoD

### 1. `mvn clean verify` 九模块全绿

```
[INFO] BUILD SUCCESS（exit 0）
总测试 248（23 节基线 222 + 本节新增 26 离线用例），Failures 0 / Errors 0 / Skipped 0
```

门禁全过：Spotless + P3C（PMD）+ Checkstyle + SpotBugs/FSB（PMD 两处拦下后修复，见 §四）。集成冒烟另跑（真 key，8 用例全绿，见 §六）。

### 2. 教学文档 harness 映射逐类对号

| 测试类 | 教学文档锚点 | 结果 |
|---|---|---|
| `WhitelistSandboxTest`（16，新建） | 三类 `@Nested`「放行+拒绝」成对 + 绕过（穿越 / symlink 出根 / 新建回溯 / 符号链接前缀不假拒绝 / 变体 / 裸名绝对路径不同条目 / 形似域名 / 通配不覆盖裸域 / query 夹带 / 大小写端口 / 畸形 URL）+ deny-all；只经 `enforce` 公共入口 | ✅ |
| `FileToolsTest`（7 = 既有 5 + 新 2） | mock 全拒三方法抛异常且文件未建（坑五·IO 零发生）+ 真白名单外路径拒且消息点名 | ✅ |
| `ShellToolsTest`（6 = 5 + 1） | 真 deny-all + touch 本可建文件——进程根本没跑、文件根本没建（坑五） | ✅ |
| `HttpToolsTest`（5 = 4 + 1） | mock 全拒 + mock HttpClient `verify(never()).send`——请求根本没发出（坑五） | ✅ |
| `NotifyToolsTest`（8 = 7 + 1） | 真 deny-all——推送根本没发出；拒绝消息只显 host、URL 即凭证不回显（坑五 + 19 节坑二） | ✅ |
| `MarkdownMemoryStoreTest`（7 = 5 + 2） | 拒绝时 MEMORY.md 未被创建（坑五）+ 白名单含工作区写入放行（坑六） | ✅ |
| `Mem0MemoryStoreTest`（5 = 4 + 1） | 三出站方法拒绝后 get/post 双 never（坑五·research D8） | ✅ |
| `ToolExecutorTest`（9 = 8 + 1） | 抛「拒绝消息」的假工具 → 不可重试一次即止 + 审计恰一条（坑二；不依赖 Sandbox 类型） | ✅ |
| `YokeosRuntimeAssemblyTest`（5 = 4 + 1） | 缺省（file 组空）→ 补 `yokeos.root` 解析值——真 Bean save_memory 落盘不被自家拦（坑六）+ 区外仍拒（坑七） | ✅ |
| boot 五集成 + `CliFullFlowTest`（装配段更新） | 传「白名单含测试目标」的真 `WhitelistSandbox`；真链路照常零回归 | ✅ |
| `ToolSystemEndToEndIntegrationTest`（2 = 1 + 1，integration） | **真链路越权冒烟**：真 DeepSeek 强引导 http_get 白名单外域名 → 恰一条 `success=false` + 「域名不在白名单内」可读（D6 端到端 + US4 模型可见） | ✅ |

**坑 ↔ 回归点逐个过**：

| 坑 | 回归断言 | 结果 |
|---|---|---|
| 坑一 字符串前缀比对 | symlink 出根拒绝 + `../` 穿越拒绝 + 新建回溯放行 + **符号链接前缀目录（macOS TempDir）不假拒绝**（对称 `resolveReal`，research D2） | ✅ |
| 坑二 落点错位/重复 | ToolExecutorTest「不可重试一次即止 + 审计恰一条」；enforce 单一落点 11 处 grep 对号（§3） | ✅ |
| 坑三 前缀/子串匹配 | `ls` 在单但 `lsblk` 拒；裸名与绝对路径不同条目 | ✅ |
| 坑四 域名不守边界 | `evil-example.com` 拒 + 通配不覆盖裸域 + query 夹带不放行 + 畸形 URL 拒 | ✅ |
| 坑五 拒绝不留痕/吞异常 | 六类「IO 零发生」（文件未建 / 进程未跑 / 请求未发 / 推送未发 / REST 未发）+ 收口审计恰一条 | ✅ |
| 坑六 白名单自家打架 | 缺省配置 save_memory 经真 Bean 落盘 + 白名单含工作区放行 + deny-all 反向锚 | ✅ |
| 坑七 默认写死绝对路径 | 缺省随 `yokeos.root` 动态（装配测试 @TempDir 工作区自适）+ 集成测试指 TempDir 不拦死 | ✅ |

### 3. 「本节交付物」逐项存在性核对

- 代码新增 6 件 `ls yokeos-tool/src/main/java/com/yokeos/tool/sandbox/` 全 OK：`Sandbox` / `SandboxAction` / `ActionType` / `SandboxViolationException` / `WhitelistSandbox` / `SandboxProperties`。
- 改造 7 类 enforce 接线 grep 对号（11 处）：FileTools×3（FILE_READ×2/FILE_WRITE）· ShellTools×1（SHELL_COMMAND, argv[0]）· HttpTools×2 · NotifyTools×1（webhook URL）· MarkdownMemoryStore×1（FILE_WRITE）· Mem0MemoryStore×3（HTTP_REQUEST，research D8）；`ToolExecutor.java` 收口注释已改写（逻辑零变化，张力一裁决兑现）。
- 装配：`YokeosRuntime.sandbox()` @Bean（SandboxProperties.load + file 空补 `workspace()`）+ `tools()`/`memoryService()` 注入；`ToolListCommand` 传 deny-all 实例。
- 配置：`application.yaml` 三键落位（shell/http 空表 deny-all + 注释；file 注释掉载明缺省与覆盖自洽责任）。
- 表：无新表 ✅（与交付物清单一致——拒绝复用 `tool_invocations` 既有失败路径）。

### 4. 前序节测试回归绿

全仓 `mvn clean verify` 248 全绿（16~23 节用例零回归）；四阶段门禁（T005/T016/T022/T025）均在前序全绿后推进；构造调用点 16 文件全部同步（tasks 原计 15 文件 35 处 + 实施中发现的 `CliFullFlowTest`，见 §三）。

### 5. H4 七条全局不变量逐条自查

| # | 不变量 | 自查 |
|---|---|---|
| 1 | 宪法 2：无自动 tool 执行 | 零 Spring AI 新接触面；grep `internalToolExecutionEnabled(true)\|\.tools(` 4 处命中均为假阳性（`profile.tools()` 字段访问 ×2 / MCP SDK getter / javadoc 提及），无 ChatClient 工具注册链 ✅ |
| 2 | 宪法 4：零异步 | `import reactor` main 零命中；`toRealPath`/`Files.exists`/`URI.create` 全同步 ✅ |
| 3 | 宪法 5：Tool 三合一 | Sandbox 六件全落 `yokeos-tool/tool/sandbox` 包，未拆模块、未进 core ✅ |
| 4 | 宪法 6：接口先行 | `enforce(SandboxAction)` 签名零实现特有词；**microVM 反套自查**（本节完成，结论见 §五）✅ |
| 5 | 宪法 7：审计复用 | 拒绝走 `tool_invocations` 既有失败路径（零新增审计逻辑、无新表）；`ddl-auto: none` 不变 ✅ |
| 6 | 宪法 3 同精神：显式装配 | `WhitelistSandbox` 无 `@Component`（sandbox 包 grep 零命中）、`@Bean` 手工构造注入 ✅ |
| 7 | 依赖方向 | `yokeos-core` 未新增对 yokeos-tool 依赖（pom + 源码双 grep 零命中——收口只认 RuntimeException）；tool/memory 单向既有 ✅ |

### 6. 人工项当场跑完 + 剩余清单

当场完成：接口中立性 microVM 反套自查（§五）· 凭证卫生 grep（`sk-` 零明文，唯一命中为 16 节「明文被拒」反向用例假值）· 装配卫生 grep（§5 第 6 条）· 集成冒烟全量（8 用例真 key 全绿）。

**合流后补强（2026-09-21，用户指项）**：`SandboxEndToEndIntegrationTest`——真模型六步串联三重白名单，**三维各一对「拒绝 + 放行」**（/etc/hosts 拒+SOUL.md 放 / whoami 拒+echo 放 / example.com 拒+open-meteo 放），原「剩余人工项」的三连演示由真模型自动化承载（30.7s 实跑通过，javadoc 含 debug 断点导读——兼作 24 节源码阅读用例）。

**剩余人工项（1 项）**：交互式 `yokeos chat` 体感演示（quickstart 场景三完整人工走一遍，约 5 分钟）——链路与留痕形态已全部由自动化承载，此项仅为交互体感。

## 二、分批说明（留后续节）

- 白名单管理端点、Tool Policy、容器/microVM 档：[技 §7.3]/[需 §6.3] 扩展阶段（评审差异三/D12），26 节端点清单不含它们。
- `SqliteMemoryStore` 档无 Sandbox 动作（22 节口径）；若未来库级校验需求出现，属新 spec。
- 26 节 Web 端点读取审计表时，Sandbox 拒绝行（`success=false` + 拒绝消息）自然可见——无需本节预置。

## 三、实施偏差

| # | 偏差 | 理由与处置 |
|---|------|-----------|
| 1 | `WhitelistSandbox.enforce` 增 default 分支（抛 `IllegalArgumentException`） | P3C `MissingSwitchDefault` 硬拦；语义为未来新增枚举值 fail-closed 兜底，D1 签名与四值语义不变 |
| 2 | `Mem0MemoryStore` 包私有测试构造从 `(RestClient)` 扩为 `(RestClient, String, Sandbox)` | enforce 目标需要 base-url 字符串；测试构造签名扩展属接线改造自然部分 |
| 3 | `NotifyEndToEndIntegrationTest` 域名白名单补 `127.0.0.1` | webhook 接收端绑 `127.0.0.1` 而非 `localhost`——host 精确条目两形态各自在单（D4 语义直接应用，非放宽） |
| 4 | `CliFullFlowTest`（tasks 未列的第 16 个构造调用点文件）增 `@Primary realTools` Bean 自备白名单 | 真 `YokeosRuntime` 上下文在 classpath 无 yaml 时域名 deny-all，真 http_get 用例被自家拦——测试自备白名单（与 boot 五集成同款适配），生产缺省语义不变；教训（清点以「构造调用」grep 为准还不够，起真装配上下文的测试也要过一遍）已回填 CLAUDE.md 陷阱表 |
| 5 | `WhitelistSandboxTest` 新增「符号链接前缀目录不假拒绝」用例（教学文档已列，research D2 的直接锚） | 非偏差，列出以示 harness 覆盖面对称解析算法 |

## 四、门禁拦下了什么、怎么修的（过程证据）

1. **Spotless ↔ Checkstyle 对 switch 内首注释缩进要求互斥**（google-java-format 要 8 空格、CommentsIndentation 要 4/6）：唯一双过形态 = 注释放 switch 语句之前。已回填 CLAUDE.md。
2. **P3C `SwitchStatementRule` 对 Java 14+ 箭头 switch 的 default 识别不了**（default 实际在位仍报缺）：`@SuppressWarnings("PMD.SwitchStatementRule")` + javadoc 记理由（17 节 Impl 命名同款先例）。已回填 CLAUDE.md。
3. **`"*."` 提常量时把 `substring(1)` 误改为 `substring(WILDCARD_PREFIX.length())`（=2）**：点号边界语义被剥掉，`lookalikeDomainDoesNotMatchWildcard` 当场红——harness「测的重点是绕得过绕不过」的价值实证；修复回只剥 `*` 留 `.example.com`。
4. **集成期两处测试适配**（§三偏差 3/4）与**两轮模型方差**：ToolSystem 一轮全迭代耗在 write_file（复跑即绿）、ProviderSmoke 一次 608s 挂起空回复（16 节直连用例零沙箱接触，复跑 3.5s 绿）——均为真模型/网络瞬态，非代码回归。

## 五、接口中立性自查（microVM 反套，教学文档三·第五步）

**问**：`Sandbox.enforce(SandboxAction)` 换 `KataMicroVmSandbox` 实现，签名要加方法吗？调用方要改吗？

**结论**：不需要。VM 档把「路由进隔离环境执行」作为 `enforce` 的实现体：`FILE_READ/FILE_WRITE` → 挂载卷内路径映射、`SHELL_COMMAND` → VM 内 argv 执行、`HTTP_REQUEST` → VM 出口代理；`SandboxAction{type, target}` 对该语义够用（target 仍是路径/argv[0]/URL 字符串），11 处接线调用一行不改，只换实现类 + 配置选档。**墙立住了**（宪法 6 / D2 的口径成立）。

## 六、验证命令（可复制）

```bash
# 全量门禁（预期：BUILD SUCCESS，总测试 248）
mvn clean verify

# 本节模块单跑（预期：tool 76 / memory 35 / core 45 全绿）
mvn -pl yokeos-tool -am test
mvn -pl yokeos-memory -am test
mvn -pl yokeos-core -am test

# 三重规则与绕过（预期：16 用例全绿——symlink 出根/穿越/变体/形似域名全拒）
mvn -pl yokeos-tool -am test -Dtest=WhitelistSandboxTest

# 收口：不可重试 + 审计恰一条
mvn -pl yokeos-core -am test -Dtest=ToolExecutorTest

# 集成冒烟（真 key；预期 8 用例全绿，缺 key 自动 skip）
source ~/.zshrc
mvn -pl yokeos-boot -am test -Dgroups=integration -DexcludedGroups= -Dsurefire.failIfNoSpecifiedTests=false

# 24 节专属：三维白名单一拒一放真模型串联（合流后补强；debug 阅读源码的导读用例）
mvn -pl yokeos-boot -am test -Dgroups=integration -DexcludedGroups= \
    -Dtest='SandboxEndToEndIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false

# 不变量 grep（预期全部零命中 / 指定输出）
grep -rn "@Component" yokeos-tool/src/main/java/com/yokeos/tool/sandbox/   # 0
grep -n "yokeos-tool" yokeos-core/pom.xml                                   # 0
grep -rn "sk-" --include='*.java' --include='*.yaml' yokeos-*/src           # 仅 16 节反向用例假值 1 处
grep -rn "sandbox.enforce" yokeos-tool/src/main yokeos-memory/src/main      # 11 处接线对号

# 留痕核对（演示后）
sqlite3 .yokeos/yokeos.db \
  "SELECT tool_name, success, error_message FROM tool_invocations ORDER BY id DESC LIMIT 3;"
```

## 七、方法论对照

- **评审课 → 代码课的管线价值**：23 节评审冻结的 D1~D12 让本节 specify 零澄清、plan 只需定 O1~O5——「评审产文档、文档产 spec」全链兑现；接入过程未发现评审漏项（D2 中立性、D8 覆盖面在实现期均直接可用）。
- **TDD 纪律的当场回报**：§四第 3 条——常量提取引入的点号边界回归被预置的绕过用例分钟级抓住，红在单测而非集成。
- **「瑕疵不继承」的量化**：参照钉版树终态 `normalize+startsWith` 挡不住的 symlink 出根，本仓 `toRealPath` 对称解析拦截（`symlinkEscapingRootIsRejected` 在位且绿）。
