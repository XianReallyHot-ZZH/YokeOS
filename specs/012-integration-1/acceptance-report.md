# 验收报告：全流程串联（一）人推主流程（第 27 节 / specs/012-integration-1）

串联课（skill 第 0 步）：不开 spec-kit 流程，按技术方案 §12 对账口径把**人推链路**固化为分层集成测试。拍板四项（2026-09-21）：①对账场景纳入 Notify（需求 §11 行 27 字面，31 节 Demo 一人推预演）②mock 不进生产清单（内置常挂）③LiveApiProbe 引入④本目录仅验收报告。

## 证据 1 · `mvn clean verify` 九模块全绿

`mvn clean verify`（2026-09-22 22:00 前后）**BUILD SUCCESS**，九模块测试 62+20+39+76+38+10+32+33+4 = **314 全绿，0 失败 0 跳过**（26 节 305 + 本节新增 9 条：MockChatModelTest 5、ProvidersPropertiesTest +1、ProviderMapMockWiringTest 1、MockProviderFlowTest 1、MockAgentEndToEndTest 1）。三层门禁（spotless / checkstyle / P3C / SpotBugs）随 verify 全过。

## 证据 2 · 教学文档 harness 映射对号

| 测试类 | 结果 | 关键回归点实证 |
|---|---|---|
| `MockChatModelTest`（provider，gate） | 5/5 | 坑一：判轮两分支（复用会话旧 tool 行不误判）、tool call JSON 形态与转义、事实截到行尾 |
| `ProviderMapMockWiringTest`（cli，gate） | 1/1 | 拍板②守点：mock 常挂显式映射表、生产清单零改动 |
| `ProvidersPropertiesTest`（扩） | 4/4 | 坑二：mock 保留名显式配置免凭证校验 |
| `MockProviderFlowTest`（boot，gate） | 1/1 | 对账四断言：ReAct 恰两轮、save_memory 恰一次、MEMORY.md 真写（含「北京」）、审计真库 llm_calls 恰 2 / tool_invocations 恰 1（坑四按 sessionId 过滤）；console 三端点查得回 |
| `MockAgentEndToEndTest`（boot，gate） | 1/1 | `@SpringBootTest(RANDOM_PORT)` 真起 HTTP：建会话→发消息→查回会话/记忆/工具/列表/审计闭环；hermetic 三件（root/db 自钉、清单零注入） |
| `HumanTriggerFlowIntegrationTest`（boot，integration 真 key） | 5/5 | 支柱一：`http_get` ≥1 全成功 + `notify` **恰 1** + 接收端真收到 POST + llm_calls ≥2 + 历史 ≥4 + 列表可见；支柱二：真模型 save_memory→/memory 含事实；支柱三：/tools 含对账三件；三面同源：cli/web 同引擎同库列表两态并见；坑六：错 key → llm_calls `success=false` 落账 |
| `LiveApiProbe`（boot，黑盒不进 gate） | 1/1 | 探活跳过；打运行中 serve（18086）：随机 userId 建会话→mock 全链→会话/记忆/工具/列表全查回 |

坑↔回归：坑一→MockChatModelTest 判轮用例 ✓ · 坑二→ProvidersPropertiesTest ✓ · 坑三→IT AGENT.md 点名四件 + @Primary tools 全注册（实证：支柱二首跑因缺 save_memory 红，补齐后绿——坑三当场复现并修复）· 坑四→三个对账类全部按 sessionId 过滤 ✓ · 坑五→MockAgentEndToEndTest 清单零注入 ✓ · 坑六→IT 失败路径用例 ✓。

## 证据 3 · 「本节交付物」存在性核对

- 代码：`yokeos-provider/…/MockChatModel.java`、`ProvidersProperties.validate` mock 特判、`YokeosRuntime.providerMap()` 内置常挂（ls 证据齐）；
- 测试：七文件 ls 全 OK（见执行记录）；demo 对账 Agent 以测试内 AGENT.md 形态交付（IT/E2E 内嵌 weather-push-agent 与 mock-agent）；
- 配置：生产 `application.yaml` 零改动（git diff 无触碰）；测试自钉 `@DynamicPropertySource`；
- 表：无新表、无表结构变更（宪法 7 ✓）；
- 文档：`docs/class/027-integration-1.md`（定稿含拍板记录）+ `docs/images/class-027-1.svg` 人推八站图。

## 证据 4 · 前序节测试回归绿

证据 1 的 314 条含九模块全部既有测试（前序节全量）；全仓 integration 合跑 `mvn test -Dgroups=integration -DexcludedGroups=` **22 条全绿 0 跳过**（boot 20 含本节 5 + provider/cli 冒烟），真 key 在场（`source ~/.zshrc`）真调通过。

## 证据 5 · H4 七条全局不变量自查

1. Spring AI 自动执行/eager：本节 mock 是自实现 `ChatModel`（无自动执行触点）；IT 真链 `tool_invocations` 恰量断言即「不双调」的端到端实证 ✓
2. 三元组拼接单点 `SessionIds`：本节零新入口（CLI/web/invoke 全既有）；实测 `web:default:mock-agent`、`cli:xianreallyhotzzh:mock-agent` 拼接口径不变 ✓
3. 审计两表 day one：对账主题本身——三层测试全部真库恰量断言（llm_calls/tool_invocations），成功失败两半都落（IT 失败路径 `success=false`）✓
4. session 拼接按 18 节判：见 2；LiveApiProbe 随机 userId 每跑新会话实证拼接活性 ✓
5. 新触发入口按 17 节判：本节零新触发入口；三入口同一 `AgentService.process`（IT 三面同源用例直证）✓
6. Sandbox：mock 链 save_memory 的 FILE_WRITE 过白名单（坑七：file 白名单空时自动补 `yokeos.root`，MockAgentEndToEndTest 实证不被自家拦）；IT 涉外两调（http_get→open-meteo、notify→127.0.0.1）全过域名白名单 ✓
7. 宪法 4 同步 + 虚拟线程：零异步栈；`MockChatModel.call` 同步实现；serve 真 8 站全链同步跑通 ✓

## 证据 6 · 人工项当场跑完

无 key mock 手动走一遍（serve 18086，25 节坑③ classpath 形态 + m2 刷新后）：

- [x] serve 起（`-Dyokeos.root/-Dyokeos.db.dir` 指临时工作区；kimi 连坐坑照旧哑值）→ `/api/v1/health` ok；
- [x] `LiveApiProbe -Dyokeos.base-url=http://localhost:18086` 绿（黑盒全链）；
- [x] sqlite3 逐表对账：probe 会话 `llm_calls` 恰 2（provider=mock）+ `tool_invocations` 恰 1（save_memory 成功）+ `sessions` 1 条——不多不少；
- [x] CLI 面：`yokeos chat --profile mock-agent --message "记住：…"` 输出 mock 终答、`cli:xianreallyhotzzh:mock-agent` 落库，与 web 会话同库并存；
- [x] `/admin` 302→index.html（26 节口径；本节零前端改动，五页 26 节已目检）；
- [x] 真 key 面：IT 5/5 覆盖（天气穿搭推送对账 + 记忆 + 工具 + 三面同源 + 失败路径），重于单发 curl；
- [x] 凭证卫生：`grep -rn "sk-"` 仅两处负向测试假字面量（16 节同款口径）；
- [x] `mvn clean verify` 全量 + 全仓 integration 22 条（证据 1/4）。

剩余人工项 0 条。

## 实施偏差（全部有据）

1. **mock 挂载从「清单条目驱动」改「内置常挂」**（plan 层设计修正）：本仓 providers 清单是 classpath yaml 原文手工读取（16 节占位策略），`@SpringBootTest(properties=…)` 注入清单条目无效——改为 `providerMap()` 构造后 `putIfAbsent("mock", new MockChatModel())`，生产 yaml 零改动语义不变，`ProviderMapMockWiringTest` 钉死；教学文档 3.2 已同步。
2. **三个参照测试类名被 Checkstyle 连续大写拦**（`AbbreviationAsWordInName`）：`MockAgentE2ETest`→`MockAgentEndToEndTest`、`HumanTriggerFlowIT`→`HumanTriggerFlowIntegrationTest`（tag 排除机制下语义不变）、`LiveApiIT`→`LiveApiProbe`（不进 gate 改靠「无 Test 后缀」而非 IT 后缀，surefire 默认 include 不匹配）。
3. **`MockChatModel` 带 tool call 构造经 builder**：1.1.8 `AssistantMessage` 四参构造 protected（参照 0.x 三参直构的 API 代差）；`Prompt(Message, Map)` 构造亦不存在。
4. **参照 console 段断言 `messageCount` 不适用**：本仓 26 节会话摘要投影无此字段，断言改 sessionId/agentName/status。
5. **审计对账强于参照**：参照 mock auditor `verify(times)` 改为真库按 sessionId 过滤恰量计数（坑四的根治形态）。
6. **`/info` providers 口径不受影响**：26 节拍板④「列 Profile 引用到的 provider」——mock 仅在有 Agent 引用时出现（常挂 map 不污染）。

## 结论

六项证据齐备、九模块 314 全绿（另全仓 integration 22/22）、四层 harness + 黑盒探针全部真跑通过；需求 §11 行 27 可演示成果达成：**CLI → ReAct → Tool → Notify 端到端打通**——一句「天气穿搭」进、两轮 LLM + 两次涉外工具（查天气、推送）出、三张表账目不多不少、三入口同一份数据、无 key 自测随时可跑。剩余人工项 0 条——commit / 合流 / push 由人决定。
