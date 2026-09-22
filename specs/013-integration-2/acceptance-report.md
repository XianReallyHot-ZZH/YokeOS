# 验收报告：全流程串联（二）让底座自己跑得稳（第 28 节 / specs/013-integration-2）

串联课（skill 第 0 步）：不开 spec-kit 流程，按技术方案 §12 对账口径把**钟推、重启恢复、多 Agent 并存**三支柱固化为集成测试（拍板①~⑤，2026-09-22 用户批准）。零生产代码改动——25 节已交付定时子系统全部建设件、管理端点归 ADR 0008 扩展位，本节纯「验」。

## 证据 1 · `mvn clean verify` 九模块全绿

`mvn clean verify`（2026-09-22，`/tmp/yokeos-verify-28.log`）**BUILD SUCCESS**，Reactor 十项（聚合 + 九模块）全 SUCCESS，gate 内 **317 条全绿 0 失败 0 跳过**（27 节 314 + 本节 gate 内新增 3：`MultiAgentIsolationTest` 3/3）。三层门禁（spotless / checkstyle / P3C / SpotBugs）随 verify 全过。

## 证据 2 · 教学文档 harness 映射对号

| 测试类 | 结果 | 关键回归点实证 |
|---|---|---|
| `SchedulerNotifyFlowIntegrationTest`（boot，integration 真 key） | 1/1（合跑 24/24 内） | 钟推全链：两次 `runNow` 触发 sessions 按 id 过滤**仍恰一条**且历史追加（坑一）；`task_executions`×2 / `run_count=2` / `last_status=success`；`http_get`≥2 + `notify` 恰 2 全成功；接收端两收（物理证据）。失败路径：坏域名渠道 `notify` `success=false` 且错误消息含 `evil.example.com`（24 节人话口径）、任务仍 `success`（坑二工具层）；好任务第三次照常执行（run_count=3、执行历史 success、新 llm_calls 落账——坑九口径，不锚第三次物理推送） |
| `RestartRecoveryIntegrationTest`（boot，integration mock 无 key） | 1/1 | 两代 `SpringApplicationBuilder` 上下文同 root/db（builder properties 零系统属性，坑四）：① `scheduled_tasks` `run_count=1`/`last_status=success`/`enabled` 保留（reconcile 不重置）② 执行历史 1 行关联钟推会话 ③ 两条会话（钟推+人推）完整历史 ④ `MEMORY.md` 双事实（钟推+人推写入）⑤ `llm_calls` 停机前后总数一致（不断档）。web 压 none、mock 常挂无 key（坑三） |
| `MultiAgentIsolationTest`（boot，gate 内 mock） | 3/3 | 工具隔离：`availableTools` A 含 `save_memory` / B 不含且含自己的 `read_file`（坑五：断言面在 PromptBuilder 组装面）；会话隔离：两 id 各自历史互不串、列表两态并见；定时隔离：C（boom fixture）`task_executions` `success=false` + `llm_calls` 失败落账（坑二 Provider 层）+ A 随后照常成功（坑八造法） |

坑↔回归：坑一→IT 两次触发恰一条断言 ✓ · 坑二→失败路径（工具层 success=false / 任务 success）与定时隔离（Provider 层 task failed）两层各锚各的 ✓ · 坑三→重启 IT 无 key 起两代上下文 ✓ · 坑四→零 System.setProperty ✓ · 坑五→availableTools 断言 ✓ · 坑六→全部远期 cron + runNow ✓ · 坑七→重叠锁不复测（25 节已钉）✓ · 坑八→FailingChatModel 造法实证（坏名 Agent 在 `AgentSchedulerTest` 之外另由启动校验拦）✓ · 坑九→第三次触发账面断言实证发现并修正 ✓。

## 证据 3 · 「本节交付物」存在性核对

- 代码：**无新生产类、零生产改动**（拍板①兑现，`git status` 仅 5 个新增文件 + 文档，无生产文件触碰）；
- 测试：`yokeos-boot/src/test/.../SchedulerNotifyFlowIntegrationTest.java`、`RestartRecoveryIntegrationTest.java`、`MultiAgentIsolationTest.java`（ls 全在）；
- 配置：生产 `application.yaml` 零改动（git 无触碰）；测试自钉属性形态（builder properties / `@DynamicPropertySource` / `@Primary` 覆盖）；
- 表：无新表、无表结构变更（宪法 7 ✓）；
- 文档：`docs/class/028-integration-2.md`（定稿含拍板记录 + 坑一~九）+ `docs/images/class-028-1.svg`（三支柱图，headless 渲染自检过）。

## 证据 4 · 前序节全部测试回归绿

证据 1 的 317 条含前序节全量 gate 测试。全仓 integration 合跑 `mvn -pl yokeos-boot -am test -Dgroups=integration -DexcludedGroups=` **24/24 全绿 0 跳过**（boot 22 = 27 节 20 + 本节 2；provider/cli 冒烟各 1），真 key 在场真调。**如实记录**：首轮合跑中 27 节 `HumanTriggerFlowIntegrationTest` 偶发一条失败（open-meteo 瞬时 `http_get(false)` → 模型重试路径二次 notify，逐字栈证在 `/tmp/yokeos-it-28c.log`）——与本节改动无关（零生产代码），单跑复验 5/5 绿、整仓重跑 24/24 绿；属外部网络 + 模型行为方差的已知偶发面，不放宽任何断言。

## 证据 5 · H4 七条全局不变量自查

1. Spring AI 自动执行/eager：本节零生产代码；钟推全链 IT `tool_invocations` 恰量断言即「不双调」的钟推版端到端实证 ✓
2. 三元组拼接单点 `SessionIds`：钟推会话 `scheduler:scheduler:{agent}` 全部经 `SessionManager` 内部拼接，测试只断言拼出的字面量，无第二拼接点 ✓
3. 审计两表 day one：三测试全部真库断言——成功失败两半都落（IT 失败路径 `success=false`、MultiAgent boom 的 `llm_calls` 失败行）✓
4. session 拼接按 18 节判：重启 IT 人推会话 `cli:restart-user:{agent}`、钟推会话同库并存 ✓
5. 新触发入口按 17 节判：本节零新触发入口；钟推 = 既有 `AgentScheduler.execute` 走同一 `AgentService.process`（重启 IT 第一代同时驱动钟推+人推两会话实证同引擎）✓
6. Sandbox：IT 涉外调用过域名白名单（open-meteo/127.0.0.1），坏域名被拦且留痕（`域名不在白名单内: evil.example.com`）；mock 链 `save_memory` 的 FILE_WRITE 过 file 白名单（工作区自动跟上）✓
7. 宪法 4 同步 + 虚拟线程：零异步栈；两代上下文与真 serve 的 cron 触发都走同步链路 ✓

## 证据 6 · 人工项当场跑完

真进程人工验收（25 节坑③ classpath 形态：boot classes 前置 + m2 刷新 + `KIMI_API_KEY` 哑值过连坐校验）：

- [x] **真 cron 自然触发**：真 serve（18087）+ 每分钟 cron，零人工调用下 11:17:30 自然触发——`run_count=1`/`success`/执行历史关联 `scheduler:scheduler:cron-agent`；再等一轮第二次自然触发：`run_count=2`、历史 2 行全成功、**会话仍恰 1 条**（复用）、`MEMORY.md` 标记×2；
- [x] **真进程级重启**：`kill` 再起 serve——四样回来：`scheduled_tasks` 状态（`enabled` 保留、reconcile 不重置）、执行历史 3 行、会话仍 1 条、`llm_calls` 4→6 不断档；且 `run_count` 2→3：重启后第一个 cron 到点**又自然触发一次**——「重启后定时回来了」的最强实证；
- [x] **多 Agent 目检**：三 Agent（alpha/beta/gamma）真 serve `GET /api/v1/profiles` 三态并见；
- [x] **凭证卫生**：`grep sk-` 新增文件零命中（唯一命中为教学文档人工项字面量自身）；
- [x] `mvn clean verify` 全量绿（317）+ 全仓 integration 24/24（证据 1/4）。

**剩余人工项 1 条（非阻断，31 节承接）**：Demo 前置环境清单（真实通知渠道 webhook 域名、飞书/企微渠道配置、记忆偏好预置）——依赖 31 节的真实企业 IM 渠道与账号，本节已完成清单文档化（教学文档 2.6 + 第五部分），31 节落配时逐项打勾。

## 实施偏差（全部有据）

1. **工具隔离断言面修正**（坑五）：起草时预判「B 的 save_memory 记『未注册的工具』失败」——H3 读码发现本仓 `ToolExecutor` 注入**全量注册表**不按 Profile 过滤（第一阶段语义：隔离边界 = PromptBuilder 点名清单），账目面断不出来；改为 `availableTools` 断言（LLM 可见面）。教学文档已同步。
2. **坑八造法修正**：坏 provider 名被 `AgentLoader` 启动校验整目录跳过（校验面 = `providerMap.keySet()`），`ghost` 名走不通；改测试本地 `FailingChatModel` 挂 `@Primary` 映射表（名 `boom`：过校验、调用必炸）。CLAUDE.md 坑表已回填。
3. **坑九新发现**：钟推会话复用下历史累积——第三次短间隔触发真模型判定「刚推过」不重推（物理推送停 2 次、账面全对）；「调度器不死」断言改锚账面（run_count/执行历史/新 llm_calls）。教学文档补坑九、CLAUDE.md 坑表已回填。
4. **19 节坑表述修正**：`@Test` 方法实际允许 snake_case（google_checks `SuppressionXpathSingleFilter` 豁免），真拦的是段内第二字符大写（`cFails`/`cHistory` 家族）——CLAUDE.md 该条已细化。
5. **参照差异（拍板①②，非本节新增）**：参照 28 的四端点 + 管理台页归 ADR 0008 扩展位；参照 `POST /schedules/{id}/run` 驱动改直调 `runNow`（25 节先例）；参照重启 IT 的 `oryxos.providers[0].name=mock` 属性注入在本仓无效（27 节偏差 1），靠 mock 内置常挂。
6. **27 节 IT 偶发一条**（证据 4 如实记录）：外部网络瞬断 + 模型重试方差，复验绿，零断言放宽。

## 结论

六项证据齐备：九模块 gate 317 全绿、全仓 integration 24/24 真调通过、三支柱各有一个固化测试一条命令重演、真 cron 自然触发与真进程级 kill/重启当场实证。需求 §11 行 28 可演示成果达成：**端到端链路固化为集成测试，稳定复跑**——钟推全链（含 notify 与失败隔离）`-Dtest='SchedulerNotifyFlowIntegrationTest'` 重演、重启不失忆 `-Dtest='RestartRecoveryIntegrationTest'` 重演、多 Agent 三隔离在 gate 内自动判；31 节 Demo 前置环境清单就绪。剩余人工项 1 条（31 节承接）——commit / 合流 / push 由人决定。
