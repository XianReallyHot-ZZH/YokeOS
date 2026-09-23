# 第 31 节验收报告：Demo——两个日跑 Agent 上线、打包、第一个版本

> specs/016-demo-release · 分支 `specs/016-demo-release` · 2026-09-23 · 六项证据 DoD（Demo 课：真实运行与发布）

## 证据 1 · `mvn clean verify` 九模块全绿

出货状态（含全部本节改动与结构化回传修复）：

```text
YokeOS / Core / Provider / Storage / Tool / Memory / CLI Channel / Web / CLI / Boot 全部 SUCCESS
BUILD SUCCESS · Total time: ~3 min
```

测试数（surefire 汇总）：core **112** · provider 22 · storage 39 · tool 76 · memory 38 · channel-cli 10 · web 53 · cli 33 · boot 7 = **390**（默认单测）；integration 显式触发：boot 26/26 + provider 冒烟 1 + cli 全链 1 **全绿**（含真 DeepSeek 的 `SchedulerNotifyFlowIntegrationTest` 两连触发「推送不多不少」对账、`HumanTriggerFlowIntegrationTest` 支柱一对账）。

## 证据 2 · 对账清单逐条过（Demo 课 harness = 真跑对账）

**Demo 一：每日天气**（需 §13 Demo 一验收标准；演示工作区 `~/yokeos-demo`，serve 18080 常驻）：

| # | 验收点 | 证据 |
|---|--------|------|
| 1 | 不需要人工触发 | 短 cron 20:22 钟推：`scheduled_tasks` run_count 自增、`task_executions` success=1 ✓ |
| 2 | 到点自动跑完整 ReAct | 20:22:03 收件天气播报；审计 55 http_get(1510ms) + 56 notify(3ms) **恰各一次** ✓ |
| 3 | 查天气与推送各一次 HTTP、都过白名单 | 同上两条 success=1（域名 api.open-meteo.com / localhost 均在单）✓ |
| 4 | 都写入 tool_invocations | sqlite 查询贴报告（id 55/56 + 53/54 人推侧）✓ |
| 5 | `GET /api/v1/sessions/{id}` 查到完整对话 | `scheduler:scheduler:weather-daily` session 2 条工具消息 + 收尾 ✓ |
| 6 | 人推补跑一致 | invoke 200（5.7s 收敛：http_get + notify 各一次 + 正常终止文本）✓ |
| 7 | 光杆 AGENT.md | `.yokeos/agents/weather-daily/` 仅 AGENT.md；经 30 节 API generate（真模型草稿）→ 人在环预览改定 → create ✓ |

**Demo 二：每日科技日报**（需 §13 Demo 二验收标准）：

| # | 验收点 | 证据 |
|---|--------|------|
| 1 | 全程零 Java，只写 AGENT.md + Skill + mcp_servers.yaml | 资产 = markdown ×2 + yaml ×1 + python（方式二自写 MCP，业务侧合法轻代码）；`cp` 丢入 `.yokeos/agents/` 即上线（Watcher 拾取，不重启列表可见）✓ |
| 2 | Skill 正文注入 system prompt | 日报产出严格符合 digest-format 规范（总览句 + 条目行 + ★ 标记 + 无口号）；机制 29 节单测已钉 ✓ |
| 3 | LLM 自己决定调新闻工具 | `fetch_tech_news`（MCP）真调审计行（21:20 两连人推 6462/2081ms；钟推 2733ms）✓ |
| 4 | 自己组稿、自己调推送 | notify 审计行各恰一次；日报文本为模型组织（每次措辞不同）✓ |
| 5 | 日报体现 MEMORY.md 偏好 | 种记忆经 save_memory 落核心区「更关注 AI 和芯片」；★ 条目排最前（Claude/GPT-6/Ryzen）✓ |
| 6 | `GET /api/v1/agents` 查得到 | 列表含 `daily-tech-digest` ✓ |
| 7 | 人推补跑一致 | 两连 invoke 200（11.3s / 5.8s，各恰 fetch+notify 一次）✓ |
| 8 | 短 cron 钟推「不多不少」 | 21:25 钟推：审计恰 2 行（71 fetch + 72 notify）、收件恰 1 条、run_count 自增、task_executions success ✓ |

**真实 cron 就位**：weather `0 0 8 * * *` / digest `0 0 9 * * *`（Asia/Shanghai），serve 常驻守住；连续多日观察列上线后运维项（拍板⑥）。

## 证据 3 · 「本节交付物」存在性核对

| 交付物 | 核对 |
|---|---|
| `examples/demo/` 四件套 + README | ✓ weather-daily/AGENT.md、daily-tech-digest/AGENT.md（tools 点名 fetch_tech_news/http_get/notify/save_memory + 显式收尾指令）、skills/digest-format/SKILL.md、news-mcp/news_mcp.py（钉 `mcp<2` 说明） |
| fat JAR `yokeos-boot-0.1.0.jar` | ✓ 87MB 单文件（依赖 + application.yaml + 管理台静态页）；`--version` → 0.1.0；init/chat/serve 全走 jar |
| pom release 化 0.1.0 | ✓ 父 pom + 九模块 + CLI version 字面量同步 |
| 演示域名缺省白名单 | ✓ application.yaml：api.open-meteo.com / webhook.site / hn.algolia.com / localhost（注释标演示用途） |
| README Quick Start 真实口径 + 完成态 | ✓ `java -jar` 起手五步 + examples/demo 取用 + Phase 1 delivered |
| website 完成态回写 | ✓ en/zh quick-start、what、roadmap 六页（Phase 1 delivered · v0.1.0） |
| 指南 §4.2 citation 修正 | ✓ §14→§13 |
| 教学文档 + 配图 | ✓ docs/class/031-demo-release.md（9 项拍板全批准）+ docs/images/class-031-1~4.svg（机检 OK + 截图亲验） |
| 结构化回传修复（用户拍板） | ✓ ProviderRequest(systemPrompt, history, tools)、Message +toolCalls/toolCallId、ToolCallRequest +id、SpringAiProviderService 结构化组装 + 存量降级、MockChatModel 判轮升级；技 §4.2 + CLAUDE.md 同步 |

## 证据 4 · 前序节全部测试回归绿

结构化回传触碰 17 节契约（ProviderRequest/Message/Session/appendToolResult）与 27 节 MockChatModel——证据 1 全量 390 单测 + integration 26/26 全绿即回归凭证；28 节固化的真模型对账测试（SchedulerNotifyFlow「推送不多不少」）在结构化历史下通过（fixture 报文补 28 节归档的「无论历史」口径，见实施偏差 ⑥）。

## 证据 5 · H4 七条全局不变量自查

1. **Spring AI 自动执行/eager**：修复只动消息组装侧，`internalToolExecutionEnabled(false)` 零触碰；存量回归 `callWithToolSchemaDisablesAutoExecution` 绿 ✓
2. **三元组拼接单点 SessionIds**：零新会话路径（demo 走既有 invoke/scheduler session）✓
3. **审计两表 day one**：本节全部对账即靠两表反解（tool_invocations 66→72 行全程可查；llm_calls generate/invoke/scheduler 三形态落账）——宪法 7 的价值在本节两次排障中现场兑现 ✓
4. **session 拼接按 18 节判**：scheduler:scheduler:{agent} 复用不冒新 ✓
5. **新触发入口按 17 节判**：零新触发源（钟推人推同链路当面对账）✓
6. **Sandbox**：白名单预置四域名；坏域名拦截路径 28 节集成回归绿；本节无新涉外面（news-mcp 是 Agent 作者侧进程，信任边界 29 节口径未变）✓
7. **宪法 4 同步 + 虚拟线程**：修复全程同步阻塞；无 Reactor/CompletableFuture 引入 ✓

## 证据 6 · 人工项当场跑完（真 serve、真 DeepSeek、本机收件端点）

- [x] **两 Demo 人推收敛**：weather 5.7s（恰 2 工具调用+终止）、digest 两连 11.3s/5.8s（各恰 2 工具调用）——修复前 digest 曾 2 次复读烧穿 10 轮、钟推 8 连 notify，修复后**零复读**；
- [x] **两 Demo 钟推**：weather 20:22（恰 2 审计行）、digest 21:25（恰 2 审计行 + session 内 assistant.toolCalls↔tool.toolCallId 协议配对可见）；
- [x] **webhook 收件**：本机 receiver（localhost:8899 → inbox.jsonl）收到天气播报 ×3、科技日报 ×4（人推+钟推），日报 ★ 偏好条目排前；
- [x] **30 分钟干净走查**：干净目录 + fat JAR + demo 资产，init → 放资产 → serve → 两 Demo 人推全通——**总耗时 65 秒**（含两次真 LLM 调用），零卡点；
- [x] **API 建管路径**：generate 真模型草稿（预览发现幻觉工具名 `weather`/`push` 等，人在环改定——30 节设计价值现场兑现）→ create 200 → 列表即见；
- [x] **凭证卫生**：`grep -r "sk-"` 唯一命中为 16 节既有测试常量 `sk-plaintext`（非真凭证、非本节引入）；全程 `${ENV_VAR}` 占位；
- [x] fat JAR 冒烟：`--version` 0.1.0、`init` 幂等、serve 三入口。

**剩余人工项 2 条（非阻断）**：① 管理台浏览器走查（30 节遗留场景 F——serve 常驻 18080，建议用户走一遍七页）；② `git tag v0.1.0` + push + GitHub Release 由**人**执行（总纪律：AI 不自动 commit/push/tag）。

## 实施偏差（全部有据）

1. **结构化回传根因修复（超出 Demo 课「不产码」常规口径，用户诊断+拍板）**：钟推日报连推 8 版暴露复读方差 → 用户定位 `new Prompt(promptText, options)` 拉平历史（字节码实证 `new UserMessage(text)`，协议视角每轮都是第一轮）→ 按拍板实施 B 方案：`ProviderRequest(systemPrompt, history, tools)` + `Message` 补 `toolCalls`/`toolCallId` + provider 结构化组装（SystemMessage 首位 + AssistantMessage(toolCalls) + ToolResponseMessage(id)，存量无 id 降级 user 文本兜底）；技 §4.2 与 CLAUDE.md 机制段已同步；修复后两连人推 + 钟推零复读、28 节真模型对账通过；
2. **MCP 工具必须进 frontmatter `tools:` 点名**（19 节坑的 MCP 变体）：不点名则模型侧 schema 无此工具、`/api/v1/tools` 显示全局注册表误导人——Demo 二曾 http_get 兜底连调 29 次而 fetch_tech_news 零调用；资产已点名 + 坑表回填；
3. **MCP Python SDK 2.x 改名 FastMCP→MCPServer**：`pip install mcp` 默认 2.x 直接 ModuleNotFoundError；钉 `"mcp<2"`（资产 README + 教学文档同步）+ 坑表回填；
4. **webhook.site API 被本网络劫持**（返回无关 JSON）：演示收件端点改本机 receiver（localhost:8899），白名单缺省加 `localhost`（拍板⑦的加法偏差，注释标明；生产自行裁剪）；
5. **Demo 资产正文演进**：两 Agent 正文补「同一取数只做一次」「推送后一句话收尾不再操作」（28 节坑的资产级拆解）；digest 工具集补 save_memory（种记忆合法需要）与 fetch_tech_news（点名）；
6. **`SchedulerNotifyFlowIntegrationTest` fixture 报文补「无论历史如何都要重新执行」**：结构化历史让模型正确看见「已推过」，暴露 28 节坑的测试侧形态——按 28 节归档口径补报文，断言不放宽（仍「不多不少」）；
7. **MockChatModel 判轮升级**（27 节交付物连带收口）：拉平文本行前缀判轮 → 消息序列最后一条 instanceof 判轮，语义不变；`MockChatModelTest` 随形态重写（含存量降级分支新用例）；
8. **两处门禁适配**：PMD `SwitchStatementRule` 箭头 switch default 误报（24 节同款类级抑制 + javadoc 记理由）；SpotBugs RCN 对 `UserMessage.getText()` @NonNull 判空误报（17 节坑族口径直取）。

## 结论

第 31 节六项证据 DoD 全部满足：九模块全量绿（390 单测 + 30 integration）、两 Demo 对账表逐条过（人推/钟推/审计/收件/偏好/免重启全绿）、交付物齐全（fat JAR v0.1.0 + demo 资产 + 六页完成态回写）、前序零回退（含 17/27 节契约改造的全量回归）、H4 七条过、人工项完成（剩余 2 条非阻断有承接口径）。**第一阶段收官**：一个能跑的 Agent 底座、一套定义 Agent 的机制、两个每天自己干活的真实 Agent、一个打了包的版本、一个说实话的主页；过程中真跑暴露并根治了拉平历史导致的复读方根因（结构化回传），16 份节级规格与验收报告完整落档。tag `v0.1.0` 与 push 由人执行。
