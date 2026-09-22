# Research: 插件化 Agent（第29节）

**Branch**: `specs/014-plugin-agent` | **Date**: 2026-09-22

本节为收口节，无外部技术未知项（零新依赖、无 Spring AI 面、无 API 代差面）。research 聚焦八个设计决策点——全部有文档链依据或既有节先例，Decision / Rationale / Alternatives 逐项记录。

## D1 · Skill 注入段的格式

**Decision**: 每个点名的 Skill 注入一段：`## 技能（<名>）` 段头 + 剥掉 frontmatter 后的 SKILL.md 正文，按 frontmatter `skills` 声明序排列。

**Rationale**: 段头沿用 17 节 Bootstrap 角色 header 形态（`## 项目约定（AGENTS.md）` 等）——模型能分清「注入的规范」与 Agent 自己的话；剥 frontmatter 复用 `ContextLoader.stripFrontmatter`（17 节既有，对无围栏鲁棒），agentskills.io 的 `name`/`description` 元数据只剥不解析（目录名才是按名引用的键）。

**Alternatives**: 无段头裸注入（段界模糊，模型可能把规范当正文指令）；解析 frontmatter 并注入 description（元数据对模型无行动价值，白占 context）。

## D2 · 点名 Skill 不存在的处置

**Decision**: WARN 有痕跳过该条，不阻断 Agent 加载与其余段注入。日志形态：编译期常量消息 + Skill 名进异常堆栈（`log.warn("常量", new IllegalArgumentException("skill=" + name))`）。

**Rationale**: 20 节 tools 点名 WARN 同款先例（19 节「静默略过」坑的正解 = 静默变有痕）；Skill 是增强不是必需——坏点名拖垮 Agent 加载过重。CRLF 门禁唯一通过形态即此（CLAUDE.md 坑表）。

**Alternatives**: 抛错拒绝加载（过度——notify 渠道剔除、schedules 坏条目剔除都是「有声剔除优于炸整 Agent」哲学）；静默跳过（19 节坑家族明令禁止）。

## D3 · SKILL.md 缺失与读失败的分界

**Decision**: 路径不是常规文件（缺失/是目录）→ 按 D2 WARN 跳过；存在但 `Files.readString` 抛 IOException → 抛 `UncheckedIOException`（组装失败显式上抛）。

**Rationale**: 17 节 Bootstrap 同款分界——「缺失 WARN 跳过、读失败抛错」：文件没了可能是还没写，读失败（权限/磁盘/并发删除半途）是系统级软故障，「规范悄悄丢了」最难查。

**Alternatives**: 读失败也 WARN 吞掉（软故障静默，违背「异常不吞」纪律）；缺失也抛错（同 D2 反对理由）。

## D4 · 示例 Agent 目录的落位

**Decision**: `yokeos-core/src/test/resources/fixture/029/workspace-example/`（`agents/daily-reconcile/` 三文件 + `skills/report-format/SKILL.md`）。测试经 classpath 复制到 `@TempDir` 后使用（保持测试自净）；人工演示时同源复制进真实工作区。

**Rationale**: 一份资产两用——测试 fixture 保真（真目录形态而非全内联字符串），演示素材与被测对象同源（「参照物」价值，参照 29 节把 `daily-reconcile` 当交付物的做法）；落测试域不进生产 classpath、不动 `yokeos init` 模板。

**Alternatives**: 仓库根 `examples/`（引入新顶层目录，超出教学文档交付物清单——软门禁①）；测试内全内联字符串（丢失资产复用与演示价值）；`docs/class/` 附资产（文档域混入可执行资产，性质不符）。

## D5 · unregisterProfile 的 cancel 语义

**Decision**: `ScheduledFuture.cancel(false)`——只取消后续排期，不打断正在执行的任务。

**Rationale**: 注销语义 = 「不再排期」；正在跑的那次让它跑完落账（`task_executions` 审计完整性优先——半途打断会产生无终态的执行记录）。正在跑的那次结束后 ReentrantLock/句柄已移除，不会再次排期。

**Alternatives**: `cancel(true)` 中断执行中线程（审计半途、资源清理不可控）。

## D6 · taskId 派生共用与公共探针取舍

**Decision**: 把 25 节注册侧循环内的 taskId 派生抽成私有方法（`{profileName}:{id}` 同一格式），`registerProfile` 与 `unregisterProfile` 共用；**不加** `hasScheduledTask` 公共访问器——测试经 mock `ScheduledFuture` verify `cancel(false)` 断言注销语义。

**Rationale**: 抽共用防「两处派生漂移」（坑⑤：注销找不到句柄、定时变僵尸）；不加公共探针是 API 最小化——参照为测试便利加探针，非产品语义，30 节消费方（DELETE/PUT 编排）也不需要它。此为对参照的显式偏差，记实施偏差。

**Alternatives**: 照抄 `hasScheduledTask`（为测试污染产品 API）；注销处复制一份派生逻辑（漂移隐患）。

## D7 · skills 同名重复声明（`skills: [a, a]`）

**Decision**: 照声明序原样处理，不去重、不加 WARN。

**Rationale**: 16 节 frontmatter 列表派生（`strList`）即原样语义，本节消费面保持一致；重复注入两次的后果直观可见（prompt 里出现两段），配置错误由 Agent 作者自查，不值得加噪音。

**Alternatives**: 派生时去重（引入「声明序但去重」的隐性规则，与既有列表字段行为不一致）。

## D8 · Agent 目录内 `skills/` 子目录（参照形态残留）

**Decision**: `ContextLoader.appendSkills` 只读公共库 `.yokeos/skills/<名>/SKILL.md`；Agent 目录内的 `skills/` 子目录不读不注入——它属于附属资源，按需经 `read_file` 取用（与 `REFERENCE.md` 同层）。

**Rationale**: 技 §11.1 定案的差异点——公共库是唯一 Skill 注入来源（治理资产跨 Agent 共享）；参照把 skills 放 Agent 目录内的形态不采纳。User Story 3 场景 3 钉死此边界。

**Alternatives**: 兼读 Agent 目录内 skills/（两套来源，治理失控——正是技 §11.1 拍板要避免的散落）。

## 依赖核实（H3）

- 本节零新坐标；`mvn -pl yokeos-core -am dependency:resolve` 在 implement 首任务时冒烟核实 reactor 可解析（纪律照走，预期直接通过）。
- 无 Spring AI / MCP / 新 SDK 面——`javap` 核实需求为零（唯一碰的「外部格式」是 SKILL.md 的 markdown frontmatter，剥壳逻辑 17 节既有）。
