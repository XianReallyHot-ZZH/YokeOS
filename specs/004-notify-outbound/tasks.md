---
description: "Task list for feature implementation"
---

# Tasks: Notify——结果主动送出去的统一出口（第19节）

**Input**: Design documents from `/specs/004-notify-outbound/`（plan.md / research.md D1~D7 / data-model.md / contracts/notify.md / quickstart.md）

**Tests**: TDD 纪律显式启用——测试任务先于或伴随对应实现任务（验收 harness 先行），实现与测试在同一任务内闭环、该模块测试红了当场修；集成冒烟不新开类（对话级联动归 18 节 CLI 既有冒烟框架）。测试方法名英文，教学文档语义以 `@DisplayName` 保留。

**Organization**: 出站抽象与 webhook 实现（US1+US2）→ notify 工具与三处接线（US3）→ Polish。分批明文：InOrder「校验先于发送」顺序回归留 24 节（Sandbox 未就位，`NotifyToolsTest` 文件头注释注明）；参照 specs/004「实现顺序说明」同款纪律。

## Phase 1: Setup

- [x] T001 记录改造前基线：`mvn test` BUILD SUCCESS——实际基线 **126** 全绿（003 报告口径 125 + 合流后补强 d859b91 增 1，偏差记验收报告）；零 pom 变更确认
- [x] T002 [P] 写前核实（H3）：`ToolExecutor.java:87` 仅 catch `RuntimeException`（research D1 前提成立）；`javap` 无需（本节零 Spring AI 交互）

## Phase 2: US1+US2 出站抽象与 webhook 实现（P1，harness 先行）

**Goal**: 接口先行 + 一档 webhook；发送失败绝不装成功；换目标零代码。
**Independent Test**: `mvn test -pl yokeos-tool -am -Dtest='WebhookNotifyAdapterTest'` 全绿。

- [x] T003 [P] [US1] [US2] WebhookNotifyAdapterTest：`yokeos-tool/src/test/java/com/yokeos/tool/notify/WebhookNotifyAdapterTest.java`——JDK `com.sun.net.httpserver.HttpServer` 起本地假 webhook（port 0 自动分配，handler 记录方法/路径/body 按用例回 200/500）：①发送后收到恰好一次 POST、body 含 content、Content-Type 为 JSON（US1，`@DisplayName("发送后收到一次POST_body携带推送内容")`）；②两个不同 url 的 target 分别发往各自地址（US1/换渠道零代码，`@DisplayName("换通知目标只改配置零代码")`）；③假 webhook 返回 500 → 异常上抛不吞（US2，`@DisplayName("webhook返回5xx_异常向上抛不静默吞掉")`）；④config 缺 url → `IllegalArgumentException` 点名、假 webhook 零请求（US2）
- [x] T004 [P] [US1] 抽象两件：`yokeos-tool/src/main/java/com/yokeos/tool/notify/NotifyChannelAdapter.java`（接口，唯一方法 `void send(NotifyTarget target, String content)`，Javadoc 语汇零渠道特有词）+ `NotifyTarget.java`（record：`channelType` + `Map<String,String> config`，compact ctor `Map.copyOf` 防御副本）
- [x] T005 [US1] [US2] WebhookNotifyAdapter：`yokeos-tool/src/main/java/com/yokeos/tool/notify/WebhookNotifyAdapter.java`——构造注入 JDK `HttpClient`（connectTimeout 10s，默认构造便捷方法同 `HttpGetTool` 口径）；`send`：`config.get("url")` 缺失/空白抛 `IllegalArgumentException` 点名（零请求）；POST `{"content": ...}`（Jackson 序列化或手拼转义，request timeout 10s，research D7）；`response.statusCode()` 非 [200,300) 或 `IOException` → 包 `UncheckedIOException` 上抛（research D1：HttpClient 不自动抛非 2xx，checked 异常必须包装进 ToolExecutor 既有 catch 路径）；每文件落盘即 `mvn -q -pl yokeos-tool spotless:apply`
- [x] T006 [US2] 阶段门禁：`mvn test -pl yokeos-tool -am -Dtest='WebhookNotifyAdapterTest' -Dsurefire.failIfNoSpecifiedTests=false` 绿，红了当场修

## Phase 3: US3 notify 工具与三处接线（P2）

**Goal**: `notify` 内置 Tool：渠道解析（name 匹配）+ 沙箱检查位注释 + 委托发送；AgentLoader type 三态；CLI 注册。
**Independent Test**: `mvn test -pl yokeos-tool -am -Dtest='NotifyToolsTest'` 与 `mvn test -pl yokeos-core -am -Dtest='AgentLoaderTest'` 全绿。

- [x] T007 [P] [US3] NotifyToolsTest（可测集全量）：`yokeos-tool/src/test/java/com/yokeos/tool/NotifyToolsTest.java`——mock `NotifyChannelAdapter`（`Map.of("webhook", adapter)` 构造），`@BeforeEach` `ProfileContext.set(profile)`、`@AfterEach` 必 `ProfileContext.clear()`（坑四纪律）：①notify_channels 未配置 → 失败 ToolResult 点名 + adapter 零调用（`@DisplayName("notify_channels未配置_明确报错不静默失败")`）；②channel 缺省/空白/字面量 default → 第一个渠道（`@DisplayName("channel参数缺省_取第一个渠道")`）；③channel 显式传 name → 命中指定渠道；④指定 name 不存在 → 失败点名、零调用、不回退；⑤ProfileContext 无值 → 失败点名；⑥content 缺失 → 失败点名；⑦成功路径 → `verify(adapter).send(目标匹配, 内容)` 且结果 `content="已推送"`。**文件头注释注明：InOrder「发送前必须先过白名单校验」（enforce 先于 send）待 24 节 Sandbox 就位后补入本类**
- [x] T008 [US3] NotifyTools：`yokeos-tool/src/main/java/com/yokeos/tool/NotifyTools.java`——implements `YokeTool`（research D3）：`getName()="notify"`、`getDescription()`、`getInputSchema()` 手写 JSON Schema（content 必填/channel 可选，与 contracts/notify.md §二逐字一致）；构造注入 `Map<String, NotifyChannelAdapter>`（拍板⑤，`Map.copyOf`）；`execute(JsonNode)`：content 缺失/空白→error 点名（retryable=false）→`ProfileContext.current()` null→error 点名→notifyChannels 空→error 点名 Profile 名→`resolveChannel`（channel 空白或 `default`→第一个；否则按 `NotifyChannelConfig.name()` 匹配，拍板①；未命中→error 点名不回退）→按条目 type 从 Map 取实现（无→error 点名已装配类型集）→**24 节沙箱检查位注释**（`Sandbox.enforce(HTTP_REQUEST, url)` 与 `http_post` 共享白名单，enforce 先于 send，24 节接线）→`adapter.send(target, content)`→`ToolResult.ok("已推送")`；发送异常不 catch 上抛（ToolExecutor 既有路径落审计，宪法 7）
- [x] T009 [US3] 阶段门禁：`mvn test -pl yokeos-tool -am` 全绿
- [x] T010 [P] [US3] AgentLoaderTest 补 type 三态用例：`yokeos-core/src/test/java/com/yokeos/core/profile/AgentLoaderTest.java`——①frontmatter `type: webhook` 显式声明 → 派生成功；②省略 `type` → 缺省 `webhook`（既有用例已覆盖缺省形态，确认即可）；③`type: email` → 该渠道剔除（派生 Profile 的 notifyChannels 不含该条）、其余渠道与其余字段照常、错误日志点名（logback `ListAppender` 挂 AgentLoader logger，仓内首次引入，注释注明）
- [x] T011 [US3] AgentLoader type 三态实现：`yokeos-core/src/main/java/com/yokeos/core/profile/AgentLoader.java` `toProfile` 的 notify 段——type 读取 frontmatter（null/空白→`"webhook"`）；支持集 `Set.of("webhook")` 外的值记 SLF4J error（编译期常量消息，渠道名与类型进参数）并跳过该条目，不阻断启动（拍板②；`channels()` 辅助或新私有方法解析，结构照既有风格）
- [x] T012 [US3] 阶段门禁：`mvn test -pl yokeos-core -am -Dtest='AgentLoaderTest'` 绿；既有 notify 派生用例（`AgentLoaderTest:85-87` 三断言）零改动通过
- [x] T013 [US3] YokeosRuntime 注册：`yokeos-cli/src/main/java/com/yokeos/cli/YokeosRuntime.java` `tools()` Map 增 `"notify"` → `new NotifyTools(Map.of("webhook", new WebhookNotifyAdapter()))`；同步核对 `ToolListCommand` 相关测试断言（`ProviderToolListCommandTest`）——若断言工具集需同步补 `notify` 条目（语义扩充非削弱，注明理由）
- [x] T014 [US3] 阶段门禁：`mvn test -pl yokeos-cli -am` 全绿（18 节 CLI 测试零回归）

## Phase 4: Polish & 收尾

- [x] T015 全仓硬门禁：`mvn clean verify` 九模块全绿（测试数 = 125 基线 + 本节新增；Spotless/P3C/Checkstyle/SpotBugs/FindSecurityBugs 全过），红了修实现不改规则
- [x] T016 H4 全局不变量逐条自查 + 教学文档「本节交付物」逐项 ls/grep 存在性核对 + 接口语汇 grep（`grep -ri "wecom\|feishu\|dingtalk" yokeos-tool/src/main` 零命中）+ 凭证卫生抽查（quickstart.md 命令）
- [x] T017 验收报告：`specs/004-notify-outbound/acceptance-report.md`（结构照 specs/003：六项证据 DoD + harness 映射表 + 分批说明——InOrder 留 24 节、对话级联动归既有冒烟 + 实施偏差节——JDK HttpClient/name 匹配两处拍板偏差 + 剩余人工项：真群机器人真推一条、接口中立性自查）；CLAUDE.md 常见陷阱表回填（如有新坑）

## Dependencies

- T001/T002 先行；T003 先于或伴随 T004/T005（harness 先行）；T007 先于或伴随 T008；T010 先于或伴随 T011。
- Phase 2 → Phase 3 的 T007/T008（NotifyTools 依赖 NotifyChannelAdapter/NotifyTarget 类型）；T013 依赖 T008。
- 并行机会：T003 与 T004 不同文件可并行；T010/T011（yokeos-core）与 T007/T008（yokeos-tool）不同模块可并行。

## Implementation Strategy

- MVP = Phase 2（US1+US2：抽象 + webhook + 失败口径一次覆盖）；Phase 3 补工具与接线（US3）。
- 每阶段门禁当场修红；本节结束时 yokeos-tool 首次出现 notify 子包，全仓测试数 125 + 本节新增（估算 +14：4+7+3）。
