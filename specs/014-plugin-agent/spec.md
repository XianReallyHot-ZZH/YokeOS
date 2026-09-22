# Feature Specification: 插件化 Agent——一个目录定义一个会自己跑的 Agent（第29节）

## Clarifications

<!-- 待 /speckit-clarify 回填 -->

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 丢一个目录即上线一个会自己跑的 Agent (Priority: P1)

业务方把一个 Agent 目录（`AGENT.md`：frontmatter 声明 provider / tools / skills / schedules + 正文任务指令，附属 `scripts/`、`REFERENCE.md`）放进 `.yokeos/agents/`，公共 Skill 库 `.yokeos/skills/<名>/SKILL.md` 就位；重启后该 Agent 出现在 Agent 列表（`yokeos profile list` / `GET /api/v1/profiles`），到它声明的 cron 到点自动跑完「想 → 调系统能力 → 答 → 推送」并审计留账。全程不写一行 Java、不动一行底座。

**Why this priority**: 这是本节存在意义——「一个目录 = 一个 Agent」的机制闭环（需求 §11 行 29 可演示成果），也是「凭什么叫 OS」的核心论点：OS = 内核之上能装、能跑任意多个程序的标准机制。

**Independent Test**: 往测试工作区 `agents/` 放 N 个合法目录，启动扫描后注册表恰出现 N 个 Agent、名字一一对应；带 `schedules` 声明的 Agent 其 Profile 携带定时并注册进调度器。

**Acceptance Scenarios**:

1. **Given** 工作区 `agents/` 放了 3 个合法 Agent 目录，**When** 启动扫描，**Then** 注册表恰有 3 个 Agent、不多不少、名字一一对应。
2. **Given** 某目录带 `schedules` 声明（id 必填 + profile 内唯一），**When** 注册，**Then** 定时按声明的 cron / 时区注册并留可注销句柄。
3. **Given** 某目录 `AGENT.md` 非法（如 provider 名不在实例清单），**When** 启动扫描，**Then** 该目录记错误日志被跳过，其余 Agent 照常注册，启动不阻断。

### User Story 2 - 点名 Skill 注入，产出强约束 (Priority: P1)

Agent 在 frontmatter 写 `skills: [名]` 按名引用公共库 `.yokeos/skills/<名>/SKILL.md`；组装 system prompt 时，点名的 Skill 正文整段注入（剥 SKILL.md frontmatter、每段带「## 技能（名）」段头、按声明序、位于 Bootstrap 之后 AGENT.md 正文之前）。产出严格按注入的规范组织；公共库改一处，全体引用 Agent 下次触发生效（零缓存现取）。

**Why this priority**: 公共 Skill 库按名引用是 YokeOS 与参照实现的已定案差异点（技 §11.1：组稿规范是治理资产，跨 Agent 共享、改一处全体生效），也是本节最大的代码增量（ContextLoader 留位至今）。

**Independent Test**: 公共库放两条 Skill、Agent 只点名一条，组装后 system prompt 恰含点名那条（带段头、无 frontmatter 残留），不含未点名那条。

**Acceptance Scenarios**:

1. **Given** 库中有 report-format 与 digest-format 两条，Agent 点名 `[report-format]`，**When** 组装 system prompt，**Then** prompt 含「## 技能（report-format）」段及其正文，不含 digest-format 任何内容，也不含 SKILL.md frontmatter 字样（如 `name:` 行）。
2. **Given** Agent 点名的 Skill 在公共库不存在，**When** 组装，**Then** 该条记 WARN 有痕跳过，其余段（Bootstrap、AGENT.md 正文、其余点名）照常注入，组装不失败。
3. **Given** 修改了某 SKILL.md 的正文，**When** 下一次组装 system prompt，**Then** 新内容生效（零缓存、不重启）。

### User Story 3 - 附属资源按需取用，常驻层最小化 (Priority: P2)

`REFERENCE.md` 与 `scripts/` 不预载、不进 system prompt；模型按 AGENT.md 正文指引用底座既有工具按需取用——`read_file` 读参考、`shell` 跑脚本；脚本只有产出进上下文、代码不进。没有新工具、没有能力库、没有全局索引。

**Why this priority**: 披露边界是 context 卫生与越权注入的守点——常驻层最小化（正文 + 点名 Skill），其余一切按需经既有工具。

**Independent Test**: Agent 目录放含标记串的 REFERENCE.md 与脚本文件，组装 system prompt 断言标记串不出现；AGENT.md 正文标记串出现。

**Acceptance Scenarios**:

1. **Given** Agent 目录有 `REFERENCE.md` 与 `scripts/reconcile.py`（内容各含标记串），**When** 组装 system prompt，**Then** 两文件内容均不出现（零预载）。
2. **Given** AGENT.md 正文写明任务指令，**When** 组装，**Then** 正文出现在 system prompt（常驻）。
3. **Given** Agent 目录内自带 `skills/` 子目录（参照实现形态残留），**When** 组装，**Then** 不读取不注入——公共库是唯一 Skill 注入来源。

### User Story 4 - 运行时注册原语，30 节的地基 (Priority: P2)

注册表补运行时方法：`exists`（按名查存在）、`remove`（存在移除返回 true、重复 remove 返回 false）；同名 register 维持后到覆盖（29 节定夺：覆盖是 30 节 PUT 更新的机制基础）。调度器补注销方法：按与注册侧同一 taskId 派生（`{profileName}:{id}`）找到句柄，cancel(false)（不打断执行中任务——正在跑的跑完落账）并移除句柄。本节只立原语，不做「remove 自动注销定时」的编排联动（归 30 节）。

**Why this priority**: 技 §11.2「运行时注册在第一阶段就立好」；30 节 DELETE / PUT 直接消费这两组原语。

**Independent Test**: register → exists 立即可见 → remove 后不可见且重复 remove 返回 false；registerProfile 后 unregisterProfile → 句柄移除且 cancel(false) 被调用。

**Acceptance Scenarios**:

1. **Given** 已注册 Agent，**When** remove，**Then** exists 为 false、get 为空；再次 remove 返回 false（幂等语义）。
2. **Given** 同名先后两次 register（不同内容），**When** 按名查询，**Then** 后到者可见（覆盖定夺）。
3. **Given** Agent 带定时已注册句柄，**When** 注销，**Then** 句柄被移除且 cancel(false) 被调用、不中断执行中任务。
4. **Given** Agent 无任何 schedules，**When** 注销，**Then** 空跑不报错。

### Edge Cases

- 点名 Skill 在公共库不存在 → WARN 跳过该条（不抛异常、不阻断加载），与其余注入互不影响。
- SKILL.md 缺 frontmatter 围栏 → 按无 frontmatter 处理，正文原样注入（剥壳逻辑对无围栏鲁棒，与 AGENT.md 同款）。
- SKILL.md 存在但读取失败（IO 错误）→ 显式抛错不静默（「规范悄悄丢了」类软故障最难查——与 17 节 Bootstrap「缺失 WARN 跳过、读失败抛错」同款分界）。
- 注销时句柄已不存在（重复注销 / 从未注册）→ 静默无操作，不报错。
- 坏 Agent 目录（缺 AGENT.md、frontmatter 未闭合）→ 记错误日志跳过，不拖垮其余 Agent（16 节既有行为，回归钉住）。

## Requirements *(mandatory)*

### Functional Requirements

- **FR1（点名 Skill 正文注入）**: 组装 system prompt 时，对 frontmatter `skills` 点名的每个名，读公共库 `.yokeos/skills/<名>/SKILL.md`，剥其 frontmatter 后整段注入；按声明序、每段带「## 技能（名）」段头；注入位置固定在 Bootstrap 之后、AGENT.md 正文之前；每次组装现取、零缓存。
- **FR2（点名不存在有痕跳过）**: 点名的 Skill 在公共库不存在时记 WARN 跳过该条（消息含 Skill 名），不阻断 Agent 加载与其余段注入——静默略过变有痕（20 节 tools 点名 WARN 同款哲学）。
- **FR3（注册表运行时方法）**: 注册表补 `exists`（按名查存在）与 `remove`（存在移除返回 true、重复 remove 返回 false）；同名 register 维持后到覆盖（29 节定夺，30 节 PUT 更新的机制基础）；运行时与启动扫描走同一段派生与注册代码、同一套校验（同一异常类型 + 同一消息）。
- **FR4（注销定时）**: 调度器补注销方法：按与注册侧同一 taskId 派生（`{profileName}:{id}`）找到句柄，cancel(false)（不打断执行中任务）并移除句柄；无 schedules 空跑不报错；句柄已不存在时静默无操作。
- **FR5（示例 Agent 目录）**: 交付示例 `daily-reconcile`（AGENT.md：含 skills 按名引用与 schedules + 任务正文；REFERENCE.md：字段字典与已知可接受差异；scripts/reconcile.py：纯标准库确定性比对脚本）与公共库 `report-format/SKILL.md`（agentskills.io 兼容 frontmatter + 组稿规范正文），落测试资源兼人工演示素材。
- **FR6（既有链路零改动）**: AgentLoader 派生、启动扫描装配、PromptBuilder、ReAct 一行不动——本节只有收口增量；「目录派生的 Agent 与启动扫描走同一段注册代码」保持结构性成立（harness 断言钉死）。

**明确不做（边界）**: Skill 按需加载的渐进式披露（元数据先注入、正文按需取）——扩展阶段；Agent 版本管理、Agent 市场 / 共享、完整同名冲突策略——扩展阶段；L3 脚本的容器 / 网络隔离——扩展阶段；文件监听热加载（WorkspaceWatcher）——30 节；Skill 库 CRUD 端点——不在第一阶段 19 端点清单内，库的填充靠手工放目录；「remove Agent 自动注销定时」的编排联动——30 节 AgentLifecycleService；新表、新端点、新配置键、新第三方依赖——本节皆无。

### Key Entities

| 实体 | 说明 |
|------|------|
| `AGENT.md` frontmatter `skills` 段 | 按名引用公共库的声明（16 节起已派生进 Profile，本节首次消费） |
| `SKILL.md` | 公共库条目：frontmatter（name / description，agentskills.io 兼容）+ 正文（注入的规范本体） |
| 公共 Skill 库 `.yokeos/skills/` | 跨 Agent 共享的能力实体集合；`yokeos init` 已建目录，填充靠手工放 |
| 注册表运行时原语 | exists / remove（幂等）+ 同名覆盖语义（29 节定夺） |
| 调度器句柄注销 | 同源 taskId 派生 + cancel(false) + 句柄移除 |
| 示例 Agent 目录 `daily-reconcile` | 四件套（AGENT.md + REFERENCE.md + scripts/ + 公共库一条 SKILL.md），测试 fixture 兼演示素材 |

## Success Criteria *(mandatory)*

### Measurable Outcomes

1. **点名注入精确性**：库中有 ≥2 条 Skill、Agent 点名 1 条时，注入恰 1 段（带段头）、frontmatter 泄漏 0 处、未点名内容泄漏 0 处（100%）。
2. **组装序固定**：identity → Bootstrap → Skill 段 → AGENT.md 正文的相对序，全部用例 100% 符合。
3. **附属资源零预载**：`scripts/` 与 `REFERENCE.md` 内容出现在 system prompt 的次数为 0。
4. **运行时原语语义**：remove 后可见性 100% 消失、重复 remove 返回 false；注销后句柄 100% 移除且 cancel 不打断执行中任务；无 schedules 注销空跑不报错。
5. **回归面**：教学文档 2.6 预判六坑（静默略过 / frontmatter 不剥 / 整库注入 / 段序错乱 / taskId 不同源 / 只移句柄不 cancel）各有至少一个回归测试钉死；`mvn clean verify` 九模块全绿、前序节测试零回退。
6. **人工项**：示例目录丢进真实工作区后 `yokeos profile list` 可见；`yokeos chat` 手动触发产出符合注入规范（锚「模型真用了规范」而非只锚有答复）；改 AGENT.md 正文不重启下次触发即生效。

## Assumptions

- SKILL.md 的填充靠手工放目录（scp / git / 编辑器），CRUD 端点不在第一阶段 19 端点清单内。
- Skill 正文整段注入是第一阶段形态；按需披露（元数据先注入）放扩展阶段。
- 注销原语不联动注册表与存储编排；30 节 AgentLifecycleService 统一编排（先定时后索引后归档）。
- taskId 派生抽共用复用 25 节注册侧同一函数，不复制第二份（防两处派生漂移）。
- 本节无新表、无新端点、无新配置键、无新第三方依赖；ContextLoader 注入位与剥 frontmatter 逻辑为 17 节既有资产。
- 示例脚本 `reconcile.py` 为纯标准库实现（CSV 比对、无外部 key），演示时经环境变量指向两库导出文件。
