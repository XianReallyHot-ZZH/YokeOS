# 第19节验收报告：Notify——结果主动送出去的统一出口

**分支** `specs/004-notify-outbound` · **日期** 2026-09-15 · **教学文档** `docs/class/019-notify.md` · **spec** `specs/004-notify-outbound/spec.md`

## 六项证据 DoD

### 1. `mvn clean verify` 九模块全绿 ✅

`BUILD SUCCESS`（Total time 01:31 min），测试 **139** 个全过（基线 126 + 本节新增 13，见实施偏差①④），含 Spotless / P3C(PMD) / Checkstyle / SpotBugs / FindSecurityBugs 全部门禁。模块分布：

| 模块 | 测试数 | 说明 |
|---|---|---|
| yokeos-core | 40 | 38 基线 + AgentLoaderTest 补 2（type 缺省 / 不支持剔除） |
| yokeos-provider | 14 | 零改动 |
| yokeos-storage | 24 | 零改动 |
| yokeos-memory | 1 | 骨架占位零改动 |
| yokeos-tool | 12 | 1 基线（Sanity）+ WebhookNotifyAdapterTest 4 + NotifyToolsTest 7 |
| yokeos-channel-cli | 10 | 零改动 |
| yokeos-web | 6 | 零改动 |
| yokeos-cli | 30 | 含新工具集下 CLI 全流程回归（tools Map 增 notify 后 PromptBuilder/冒烟全绿） |
| yokeos-boot | 2 | 零改动 |

### 2. 教学文档 harness 2+1 测试类逐一对号 ✅

| 测试类 | 关键回归（坑↔测试）落地 |
|---|---|
| `WebhookNotifyAdapterTest`（4 用例） | ①POST 恰一次+body 含 content+Content-Type JSON ②两 url 各发各的（换渠道零代码）③**5xx → UncheckedIOException 上抛不吞（坑一）**④缺 url → IllegalArgumentException 点名+零请求 |
| `NotifyToolsTest`（7 用例） | ①**notify.channels 未配置 → 点名+adapter 零调用（坑一）**②缺省/空白/`default` → 第一个渠道（三形态一用例钉死）③显式 name 命中指定渠道（拍板①）④未知名点名不回退 ⑤无 Agent 上下文点名 ⑥content 缺失点名 ⑦成功 `已推送`+目标匹配；@AfterEach 必 `ProfileContext.clear()`（**坑四纪律写在测试里**）；文件头注明 **InOrder「enforce 先于 send」顺序回归留 24 节**（Sandbox 未就位，分批明文） |
| `AgentLoaderTest`（+2 用例） | type 三态（拍板②）：显式 webhook（既有用例零改动覆盖）/ 省略缺省 webhook / `email` 剔除+错误日志点名（ListAppender 首次引入）+其余渠道与字段照常 |

### 3. 「本节交付物」逐项存在性核对 ✅

- 代码：`ls` 证实 `yokeos-tool/.../notify/{NotifyChannelAdapter,NotifyTarget,WebhookNotifyAdapter}.java` + `yokeos-tool/.../NotifyTools.java`；`AgentLoader.java` 三态（`SUPPORTED_NOTIFY_TYPES`/`notifyChannels` 4 处命中）；`YokeosRuntime.java:154` 注册 `"notify"`
- 测试：上表 3 类存在且非空
- 配置：frontmatter `notify.channels` 契约钉于 `contracts/notify.md` §三（`name`/`type`/`config.url`、`${ENV_VAR}` 占位）；**无新全局配置键**（域名白名单归 24 节）
- 表：无新表——`tool_invocations` 既有路径承载（yokeos-tool 主代码 audit 相关代码 0 行，审计全在 core `ToolExecutor`，零新增审计逻辑）
- 接口语汇：`grep -ri "wecom|feishu|dingtalk" yokeos-tool/src/main` **零命中**

### 4. 前序节回归 ✅

16/17/18 节全部测试断言零改动全绿（上表零改动模块 + core 38 基线用例）。两处声明过的触碰：`AgentLoaderTest` 既有 notify 三断言（`AgentLoaderTest:88-90`）零改动通过——硬编码改三态读取后显式 `type: webhook` 语义不变；`ToolListCommand` 清单与 `ProviderToolListCommandTest` 补 `notify` 断言（语义扩充非削弱，T013 注明）。

### 5. H4 七条全局不变量自查 ✅

| # | 不变量 | 结论 |
|---|---|---|
| ① | 涉外 IO 过 Sandbox | notify 是新涉外 IO（出站 HTTP POST）——检查位注释已留（`NotifyTools.java:85-86`，enforce 先于 send、与 http_post 共享白名单、24 节接线），HttpGetTool 位原样 |
| ② | 审计成败都落库 | notify 经 `ToolExecutor` 既有路径（发送异常上抛→catch RuntimeException→`ToolResult.error`→`tool_invocations` success=false）；yokeos-tool 主代码零审计代码（grep 0 命中） |
| ③ | 无明文 key | webhook URL 零明文（真实域名 grep 零命中；测试全用 `hooks.example.com` 假地址与本地回环）；异常消息不含 URL（坑二——error_message 会落审计表）；既有 `sk-plaintext` 命中系 16 节「明文被拒」守点测试的故意哑值 |
| ④ | session_id 只在一处拼 | `grep -F '+ ":" +'` 全库唯一命中 `SessionIds.compose`（与 18 节结论一致） |
| ⑤ | 无异步编程模型 | `CompletableFuture/reactor/WebFlux` 九模块主代码零命中；HttpClient.send 同步阻塞（宪法 4） |
| ⑥ | 无 Spring AI 自动执行 | 本节零 Spring AI 交互；`prompt().tools` 形态零命中 |
| ⑦ | 新触发入口汇入统一处理入口 | notify 是工具不是触发入口；CLI 既有入口零改动（`AgentService.process` 链路原样） |

### 6. 收尾三件 ✅

**门禁拦下了什么、怎么修的**（过程差异化的直接证据）：

- **Checkstyle 方法名双违例**（T006 首跑）：`webhookReturns5xx_WrapsAsUncheckedIOException` 触发 `MethodName`（禁下划线）+ `AbbreviationAsWordInName`（`IO` 连续两大写）——参照钉版树的 snake_case 测试名风格不能照抄本仓门禁；改 camelCase（`webhookReturns5xxExceptionPropagates`），中文原语义已在 `@DisplayName`。已回填 CLAUDE.md 常见陷阱表。
- **MissingJavadocMethod**（T009 首跑）：`NotifyTools` 构造器缺 Javadoc——补 `@param` 说明。
- **Spotless 格式违例**（T009 复跑）：编辑后未再 `spotless:apply`——重跑即绿，纪律教训是「每次 Edit 后立即 apply」。

**方法论对照**：坑↔回归测试一一对应（坑一×2 用例、坑四写进测试结构、坑二进实现约束）；「接口先行」落地为 `NotifyChannelAdapter` 语汇 grep 守点；「实现顺序分批」（参照课件明文）落地为 InOrder 顺序回归留 24 节 + 对话级联动归既有冒烟——不提前、不遗漏。

**剩余人工项**（harness 已判卷，这几项待人工过）：

- [ ] 真实群机器人真推一条（选**天然兼容 `{"content":...}` 的渠道**，如 Discord/自建回显——企微/飞书需专用 payload 格式，教学文档坑三）；URL 走环境变量
- [ ] 接口中立性思维自查：换企业微信官方 SDK 实现，`NotifyChannelAdapter.send(NotifyTarget, String)` 签名不需要改
- [ ] `yokeos chat` 对话触发演示（拍板④）：配 notify.channels 的 Agent + 说「把测试消息推一下」（需真 LLM key）

## 实施偏差

1. **基线 126 非 125**：003 验收报告写就后合入补强 commit d859b91（CliFullFlowTest 双用例）——本节按实际 126 记，本节完成 139。
2. **JDK HttpClient 替代 RestClient**（拍板③）：零新依赖；实现级后果已消化——HttpClient 非 2xx 不自动抛（显式状态码检查）+ checked IOException 包 UncheckedIOException 进 `ToolExecutor` 既有 catch 路径（research D1）。
3. **channel 按 name 匹配**（拍板①）：参照按 type 匹配的推理建立在其渠道模型无 name 字段之上，本仓三字段模型不成立。
4. **新增测试 13 而非估算 14**：type 显式声明用例既有「合法 frontmatter 全字段派生」已覆盖（`AgentLoaderTest:89`），不重复建。
5. **测试方法名 camelCase**：Checkstyle `MethodName`/`AbbreviationAsWordInName` 双规则（见收尾三件）；参照 snake_case 风格属「瑕疵不继承」。
6. **analyze 三 LOW 的落实**：B1 同名重复配置取第一个命中（`NotifyTools.resolveChannel` 注释注明）；F1 错误文案点名段名 `notify.channels`（与 frontmatter 键一致，参照文案 `notify_channels` 未沿用）；C1 body 序列化选 ObjectMapper。
7. **ToolListCommand 与其测试同步补 notify**（T013 预告的语义扩充）：诚实清单口径「只列已交付的」随交付更新。

## 收尾动作

- CLAUDE.md 常见陷阱表回填 1 条（Checkstyle 测试方法名）。
- tasks.md 全 17 项勾结（T001~T017）。
