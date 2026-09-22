# 验收报告：插件化 Agent——一个目录定义一个会自己跑的 Agent（第 29 节 / specs/014-plugin-agent）

**日期**：2026-09-22 · **分支**：`specs/014-plugin-agent` · **课型**：代码课（收口节）

## 证据 1 · `mvn clean verify` 九模块全绿

```
Reactor Summary: YokeOS / Core / Provider / Storage / Tool / Memory /
                 CLI Channel / Web / CLI / Boot —— 全部 SUCCESS
Tests run 合计 335, Failures 0, Errors 0（BUILD SUCCESS, 02:25 min）
```

yokeos-core 80 用例含本节 5 新测试类 18 用例；三层门禁（Spotless/P3C/Checkstyle/SpotBugs+FSB/PMD）全过。integration tag 按既有口径默认排除（28 节固化的 24 个集成用例不受影响）。

## 证据 2 · 教学文档 harness 映射对号

| 本仓测试类（教学文档第四部分） | 用例 | 结果 | 参照映射 |
|---|---|---|---|
| `SkillInjectionTest`（core/context，新） | 4 | ✓ | ProgressiveDisclosureTest 的 Skill 侧（公共库形态重写） |
| `ProgressiveDisclosureTest`（core/context，新） | 3 | ✓ | 参照同名（附属资源部分） |
| `AgentScanRegisterTest`（core/profile，新） | 4 | ✓ | 参照同名 |
| `ProfileRegistryRuntimeTest`（core/profile，新） | 3 | ✓ | 参照同名 |
| `AgentSchedulerUnregisterTest`（core/agent，新） | 4 | ✓ | 参照 AgentSchedulerRegisterTest 注销侧 |
| `AgentLoaderTest`（16 节存量） | 11 | ✓ 回归 | 参照 DeriveProfileTest + AgentLoaderTest（拍板④不单列） |
| `ContextLoaderTest`（17 节存量） | 5 | ✓ 回归 | 组装序回归（新增 Skill 段不破坏既有断言） |

**坑↔回归测试逐个过**：坑① Skill 静默略过 → `missingSkillWarnsAndSkipsWithoutBlocking` ✓；坑② frontmatter 不剥 → `referencedSkillInjectedOthersSkipped`（断言 `name:` 字样零出现）✓；坑③ 整库注入 → 同用例（未点名零出现）✓；坑④ 段序错乱 → 同用例（Bootstrap→Skill→正文 indexOf 链）✓；坑⑤ taskId 不同源 → `taskIdSharesDerivationWithRegister`（跨 Agent 同 id 互不误伤）✓；坑⑥ 只移句柄不 cancel → `unregisterCancelsHandleWithoutInterrupt`（verify `cancel(false)`）✓。

## 证据 3 · 「本节交付物」存在性核对

| 交付物 | 核对 |
|---|---|
| `ContextLoader.appendSkills` | ✓ `yokeos-core/.../context/ContextLoader.java`（loadSystemPrompt 接线 + 私有 appendSkills，WARN 常量消息 + 名字进异常） |
| `ProfileRegistry.exists/remove` | ✓ `yokeos-core/.../profile/ProfileRegistry.java`（remove 幂等返回 boolean；javadoc 落 29 节定夺两笔） |
| `AgentScheduler.unregisterProfile` | ✓ `yokeos-core/.../agent/AgentScheduler.java`（复用 `taskIdOf`、cancel(false)、句柄移除、静默语义） |
| 5 新测试类 | ✓ `ls yokeos-core/src/test/java/com/yokeos/core/{context,profile,agent}/` 五类在位 |
| 示例 Agent 目录四件套 | ✓ `yokeos-core/src/test/resources/fixture/029/workspace-example/`（AGENT.md 含 `skills: [report-format]` + `schedules: reconcile-morning`；REFERENCE.md；scripts/reconcile.py；skills/report-format/SKILL.md） |
| 配置/表 | ✓ 零新表、零新端点、零新配置键（唯一依赖变更见实施偏差③） |

## 证据 4 · 前序节全部测试回归绿

本验证即 feature 分支上的九模块全量（证据 1）——16~28 节全部存量测试（core 80 / 全仓 335）零回退；`ContextLoaderTest`/`AgentSchedulerTest`/`AgentLoaderTest` 等被本节触碰类的邻接测试全绿。

## 证据 5 · H4 七条全局不变量自查

1. Spring AI 自动执行/eager：本节零 Spring AI 面（appendSkills/unregisterProfile 纯文件与句柄操作），存量 `callWithToolSchemaDisablesAutoExecution` 回归绿 ✓
2. 三元组拼接单点 `SessionIds`：本节零新会话路径；真跑两轮人推会话均 `cli:{user}:daily-reconcile` 经既有单点 ✓
3. 审计两表 day one：真跑全程落账——第一轮 12 条 `success=false` shell（路径错）+ 第二轮 10 条 `success=true` + 30 行 `llm_calls`（成败两半俱全，**失败排障全靠审计表反解**——宪法 7 价值现场实证）✓
4. session 拼接按 18 节判：真跑 `session_id` 形态符合；`truncateByTurn` 轮界截断回归绿 ✓
5. 新触发入口按 17 节判：本节零新触发入口；`AgentScanRegisterTest` 固化「扫描注册与既有链路同源」（FR6 零改动）✓
6. Sandbox：本节零新涉外执行面；demo 真跑经**改配置副本**放行 `python3`（仓库 deny-all 缺省语义未动）；H4 grep：`grep -rn "Skill" yokeos-tool/src/main/java/` **零命中**（宪法 8：Skill 不进 tool 模块）✓
7. 宪法 4 同步 + 虚拟线程：appendSkills 同步文件读、unregisterProfile 同步句柄操作，零异步栈 ✓

## 证据 6 · 人工项当场跑完

真进程人工验收（25 节坑③ classpath 形态：boot classes 前置 + `mvn install` 刷新 m2 + `KIMI_API_KEY` 哑值过连坐校验；工作区 `/tmp/yoke-demo-029`）：

- [x] **丢目录即上线**：fixture 复制进空工作区 → `yokeos profile list` 输出 `daily-reconcile`——需求 §11 行 29 可演示成果的机器证据，全程零 Java；
- [x] **Skill 注入真模型证据（SC6 核心锚点）**：`chat --profile daily-reconcile` 真调 DeepSeek，产出报告**逐字按注入的 report-format 规范组织**——标题「【对账 P2】2026-09-21 差异 2 笔」、总览行两库对照、按 kind 分组列条目、**TEST-9001 按 REFERENCE.md 已知差异剔除**、定级依据完整复算（P0 不满足/P1 不满足→P2）、处置建议收尾；
- [x] **脚本产出进、代码不进**：模型仅引用 reconcile.py 输出的 JSON 数字（360.49/380.50）下结论，脚本代码零出现；`tool_invocations` 10 条 shell 全 success；
- [x] **正文即时生效**：第一轮正文路径错（Agent 目录内相对路径）→ 修 fixture 正文为工作区根相对路径 → 下一轮触发脚本即跑通——改正文不重启即生效的活证据；
- [x] **凭证卫生**：`grep -rn "sk-" yokeos-core/src/test/resources/fixture/` 零命中（`${OPS_WEBHOOK_URL}` 占位）；
- [x] `mvn clean verify` 九模块全绿（证据 1）。

**剩余人工项 2 条（非阻断，可选强证据）**：① 短 cron 钟推真跑——定时链路由 28 节存量 `SchedulerNotifyFlowIntegrationTest` 承载（教学文档第五部分已注明此替代口径）；② notify 真推送——demo 未配 webhook 真值，模型对推送失败的正确处理（停止重试并汇报）已实证，真渠道推送归 31 节 Demo 落配。

## 实施偏差（全部有据）

1. **taskId 抽共用零动作**：tasks T011 写「从 registerProfile 抽私有共用」——核对发现 25 节已抽好 `taskIdOf`（registerProfile 在用），直接复用；
2. **不加 `hasScheduledTask` 公共探针**（拍板③兑现）：注销语义经 mock `ScheduledFuture` verify `cancel(false)` 断言，产品 API 面最小；
3. **SpotBugs 可变性判定连锁**（新坑，已回填 CLAUDE.md 坑表）：`ProfileRegistry` 增 `remove` mutator 后全部构造持有方 `EI_EXPOSE_REP2` 过线——`AgentService`/`CliChannel` 补类级注解、web 三 Controller 注解 value 补 `EI_EXPOSE_REP2`、`yokeos-channel-cli/pom.xml` 补 `spotbugs-annotations`（provided，web 模块同款先例）；
4. **fixture 正文脚本路径修正**（真跑实证）：`scripts/reconcile.py` → `.yokeos/agents/daily-reconcile/scripts/reconcile.py`（shell cwd 是工作区根），教学文档 §1.3 同步；
5. **T004 补 analyze F1 用例**：`skillWithoutFrontmatterInjectedAsIs`（SKILL.md 缺围栏正文原样注入）；
6. **测试方法名避坑**：`scanYieldsExactlyNAgents` → `scanYieldsAllDroppedAgents`（Checkstyle `AbbreviationAsWordInName` 拦连续大写，19 节坑表预言命中）。

## 结论

第 29 节六项证据 DoD 全部满足：九模块全量绿、harness 对号零缺口、交付物齐全、前序零回退、H4 七条过、人工项完成（剩余 2 条可选项有替代口径与承接节）。「一个目录 = 一个 Agent」机制收口完成——Skill 库按名注入、运行时注册原语就位，30 节（动态管理七端点 + 一句话生成 + WorkspaceWatcher）的地基齐备。
