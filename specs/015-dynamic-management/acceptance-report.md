# 第 30 节验收报告：动态管理——一句话生成、上传即上线

> specs/015-dynamic-management · 分支 `specs/015-dynamic-management` · 2026-09-22 · 六项证据 DoD

## 证据 1 · `mvn clean verify` 九模块全绿

不带 `-Dfrontend.skip=true` 的全量构建（管理台两页产物真实进包，坑八）：

```text
YokeOS / Core / Provider / Storage / Tool / Memory / CLI Channel / Web / CLI / Boot 全部 SUCCESS
BUILD SUCCESS · Total time: 2 min 19 s
```

测试数（surefire 汇总）：core **110** · provider 20 · storage 39 · tool 60 · memory 38 · channel-cli 10 · web **53** · cli 33 · boot 7（默认单测）= **370**；integration 显式触发：`AgentLifecycleIntegrationTest` **3/3**（免重启闭环 + 真丢目录 Watcher 拾取 + generate 真模型含草稿直投 create——assumeTrue 真 key 真跑非跳过）。

## 证据 2 · 教学文档 harness 映射表逐类对号

| 测试类 | 用例数 | 对号 | 备注 |
|---|---|---|---|
| `AgentLifecycleServiceTest`（core，新） | 14 | ✓ | create 按序 / 冲突零写入 / **回滚且定时 never**（坑一）/ **delete InOrder 时序**（坑二）/ **update 先注销后注册**（坑三）/ **FR-016 防重**（clarify Q1）/ generate 六用例（**不落盘不注册 / 围栏剥离 / 非法 400 / 配置缺失 IllegalState**——坑五） |
| `AgentStoreTest`（core，新） | 5 | ✓ | write 建/覆写 / archive 按需建+重名不覆盖 / delete 物理删 |
| `WorkspaceWatcherTest`（core，新） | 5 | ✓ | CREATE→同段 register / DELETE→注销 / **竞态延迟重试**（30 节新坑的回归）/ 耗尽放弃不外溢 / 普通文件忽略 |
| `AgentApiControllerTest`（web，26 节存量扩展） | 17 | ✓ | **既有 invoke 4 用例零改动**（零回退）+ 13 新用例（5 端点薄转发恰一次 / 400-404-503 对号 / **name 白名单 400 且 lifecycle never**） |
| `WorkspaceApiControllerTest`（web，新） | 5 | ✓ | tree 两支可展开 / file 正常 200 / **穿越 ../ 与绝对路径两形态 400**（坑四）/ 不存在 404 |
| `AgentLifecycleIntegrationTest`（boot，integration 新） | 3 | ✓ | **真上下文免重启闭环 + 归档目录实证** / **真丢目录 ≤10s 轮询拾取 + 手工删下线** / **generate 真模型草稿直投 create**（SC-003，analyze M1） |

**坑↔回归逐个过**：坑一回滚 → `createRegisterFailsRollsBackWrittenDir` ✓；坑二时序 → `deleteUnregistersTimerBeforeTouchingRegistryAndDir` ✓；坑三不并跑 → `updateScheduleChangedUnregistersBeforeRegister` + `registerUnregistersOldTimerBeforeRegisteringSameName`（FR-016）✓；坑四穿越 → `fileRelativeTraversalRejected`/`fileAbsolutePathRejected` + curl 实证 ✓；坑五错误码三态 → generate 六用例 + `/generate` 端点三用例 ✓；坑六竞态 → `handleChangeRetriesWhenContentNotReady` + 集成轮询 ✓；坑七切片形态 → WebSliceTestBoot 沿用 ✓；坑八前端全量构建 → 证据 1 ✓。

## 证据 3 · 「本节交付物」存在性核对

| 交付物 | 核对 |
|---|---|
| `AgentLifecycleService`（六方法 + AUTHOR_PROMPT + stripCodeFences） | ✓ `yokeos-core/.../agent/AgentLifecycleService.java` |
| `WorkspaceWatcher`（start/loop/handleChange + 有界重试） | ✓ 同目录 |
| `AgentStore`（write/read/archive/delete） | ✓ 同目录 |
| `AgentLoader.parse`（analyze H1，新增公共方法） | ✓ `yokeos-core/.../profile/AgentLoader.java`（expectedName null = 草稿宽松形态） |
| `AgentApiController` 扩 6 端点（generate + create/list/get/put/delete；invoke 零改动） | ✓ `yokeos-web/.../controller/AgentApiController.java` |
| `WorkspaceApiController` + `FileNode` + DTO 四件 | ✓ controller/dto/ 下 `AgentView`/`GenerateRequest`/`GenerateResponse`/`CreateAgentRequest`/`UpdateAgentRequest`/`FileNode` |
| `YokeosRuntime` 三 Bean + 单线程 daemon 执行器 | ✓ agentStore/agentLifecycleService/watcherExecutor/workspaceWatcher(initMethod=start) |
| 配置键 `yokeos.agent-generation.provider/.model` | ✓ `yokeos-boot/src/main/resources/application.yaml`（缺失不阻断启动，技 §3.3） |
| 前端 Agent 管理页 + 工作区页 | ✓ `frontend/src/components/AgentsPage.vue`/`WorkspacePage.vue` + App.vue 挂载（七页导航）；`npm run build` 产物落 static/admin |
| 教学文档两图 | ✓ `docs/images/class-030-1/2.svg`（arch-diagram 全流程：机检 OK + headless 截图 + 视觉模型复核通过） |
| 端点收口 | ✓ 19 端点全在（8 新 + invoke + 26 节 11）；零新表、零新 Maven 依赖 |

## 证据 4 · 前序节全部测试回归绿

证据 1 的全量 verify 即含 16~29 节全部存量（core 110 含 29 节 26 用例回归、AgentLoaderTest 10 用例对 parse 重构零回退；web 53 含 26 节全部切片；cli 33 含装配回归）——零回退。

## 证据 5 · H4 七条全局不变量自查

1. **Spring AI 自动执行/eager**：本节零直接 Spring AI API 面——generate 经既有 `ProviderService.chat`（协议转换层，宪法 2）；无新 starter；存量 `callWithToolSchemaDisablesAutoExecution` 回归绿 ✓
2. **三元组拼接单点 SessionIds**：generate 的 sessionId（`agent-generation-*`）是审计关联键非会话键，不经三元组、不进 Session——不与 H4② 冲突；本节零新会话路径（invoke 沿用 26 节）✓
3. **审计两表 day one**：generate 每次调用落 `llm_calls`——**真 serve 实证成功（success=1）与失败（401→success=false）两侧都落账**（sessionId 前缀断言 + sqlite 直查）；create/update/delete 是文件系统操作不属审计两表口径 ✓
4. **session 拼接按 18 节判**：零新会话形态；invoke 集成回归绿 ✓
5. **新触发入口按 17 节判**：本节零新触发入口（Watcher 是录入路径非触发源；定时仍走 25 节链路）✓
6. **Sandbox**：无新涉外执行面；workspace file 防穿越（normalize + startsWith，技 §11.3 钉版）curl 实证两形态 400 ✓
7. **宪法 4 同步 + 虚拟线程**：请求链路全同步；Watcher 是基础设施守护线程（25 节调度池同类例外，Spring 管理单线程 daemon 执行器 + destroyMethod 关停，chat 命令 JVM 可退出）；重试经执行器排队非异步框架 ✓

## 证据 6 · 人工项当场跑完（真 serve，25 节坑③ classpath 形态，工作区 /tmp/yoke-demo-030）

- [x] **免重启闭环（quickstart B）**：curl create 200 → **不重启 GET 列表即见** `['curl-agent']` → PUT 200 → name 白名单 `../evil` → 400 → DELETE 200 → **`.yokeos/archive/curl-agent` 目录实证** → GET 404；
- [x] **丢目录即上线（quickstart C）**：serve 运行中 `cp -r` 目录进 `agents/` → **4 秒内列表出现** `['dropped-agent']` → `rm -rf` → 4 秒内下线 `[]`，archive 无新归档（手工删不归档）；
- [x] **一句话生成（quickstart D）**：真 DeepSeek key 下 generate 200——草稿为规范 AGENT.md（`name: beijing-weather-outfit`，frontmatter/identity/正文齐全）；哑 key 401 → 503（Provider 故障族，26 节口径）；两次调用均落 `llm_calls`（成功失败两侧）；
- [x] **防目录穿越（quickstart E）**：`../../etc/passwd` → 400、`/etc/passwd` → 400、正常路径（`AGENTS.md`、`archive/curl-agent/AGENT.md`）→ 200；tree 两支结构正确（archive 可展开）；
- [x] **凭证卫生**：`grep -rn "sk-"` 四模块 src 唯一命中为 28 节既有故障注入常量（`sk-deliberately-wrong-27`，非真凭证、非本节引入）；
- [x] 不带 frontend.skip 全量构建（证据 1）。

**剩余人工项 1 条（非阻断）**：管理台浏览器走查（场景 F：Agent 管理页「一句话新建 → 预览改 → 创建 → 编辑 → 删除」+ 工作区页浏览）——需人眼目验 UI（视觉 token/三态/响应式），页面产物已构建进包、全部端点 curl 实证可达；建议用户起 serve 后走一遍，或随 31 节 Demo 课一并目验。

## 实施偏差（全部有据）

1. **Watcher 有界延迟重试（超出教学文档「WARN 跳过」口径）**：教学文档坑六原假设「CREATE 先到、后续事件二次注册收敛」在 macOS 不成立（写子目录内文件不触发父级事件——参照回写 5.2.3 同结论但其无真丢目录测试故未暴露，本节 T018 集成测试实证死路）→ 补 5×500ms 执行器排队重试主动收敛，SC-002「丢目录即上线」的承诺才真正可兑现；已回填 CLAUDE.md 坑表；
2. **`AgentLoader.parse` 三参形态**（contracts 原两参）——provider 名单校验需要名单入参；另补 `expectedName=null` 草稿宽松形态（generate 无权威名）；
3. **`AgentStore.read` + `lifecycle.readMarkdown`**（contracts 未列）——AgentView 的 agentMarkdown 编辑回填需要文件读取数据源（拍板⑧落地的自然推论），web 不直碰文件系统（core 管文件纪律）；
4. **前端两页拆独立组件**（26 节五页内联 App.vue 的形态演化）——两页体量（表单/预览/确认/树）内联会使 App.vue 翻倍难维护；App.vue 仅加 pages 两项 + 挂载分支，既有五页零改动；
5. **`AgentView` record 补紧凑构造器 + 类型级 SuppressFBWarnings**——SpotBugs 对 List 字段的可变性误报（Profile/ProviderRequest 先例同款，全量 verify 实证后修复）；
6. **T020「CLAUDE.md 实施节奏标注」未做**——CLAUDE.md 无逐节完成标注惯例（29 节同款无），坑表回填 5 条已完成（见 CLAUDE.md 常见陷阱表末 5 行）。

## 结论

第 30 节六项证据 DoD 全部满足：九模块全量绿（370 单测 + 3 integration）、harness 对号零缺口、交付物齐全（19 端点收口）、前序零回退、H4 七条过、人工项完成（剩余 1 条浏览器目验非阻断有承接口径）。「动态管理」收口完成——一句话生成（独立配置键 + 人在环预览）、API 建管免重启（回滚/时序/防重三险钉死）、丢目录即上线（Watcher + 竞态重试收敛）、工作区只读浏览（防穿越）四件套就位；第一阶段 19 端点全部交付，31 节 Demo 课（fat JAR + 两个日跑 Demo + 主页）的地基齐备。
