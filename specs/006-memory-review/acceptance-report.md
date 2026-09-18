# 验收报告：Memory 设计评审（第 21 节，specs/006-memory-review）

> **课型**：评审课（拒绝产码）。**日期**：2026-09-18。**分支**：`specs/006-memory-review`。
> **可演示成果口径**（[需 §11] 第 21 节行）：评审记录：业界方案对照与本底座记忆设计定稿（不产码）——即 `specs/006-memory-review/review.md`。
> 代码课的六项证据 DoD（mvn 全绿、harness 对号等）不适用于本节；本报告按评审课等价物逐项验收。

## 一、交付物存在性核对

| 交付物 | 证据 | 结论 |
|---|---|---|
| 教学文档 `docs/class/021-memory-review.md` | 26,633 字节，五段式评审课形态，头部拍板记录已回填（2026-09-18） | ✅ |
| 评审文档 `specs/006-memory-review/review.md` | 21,340 字节，六段结构齐全（范围方法 / 概念+项目对照 / 定稿 D1~D11 / 差异三条 / 张力裁决+开放事项 O1~O5 / 22 节 specify 素材含坑↔回归测试表） | ✅ |
| 验收报告（本文件） | — | ✅ |
| 无 spec 三件套、无代码/测试/配置/表 | 见第二节反向核对 | ✅（符合指南 §4.2 评审节形态） |

## 二、「不产码」反向核对

- `git status --short`：仅 `?? docs/class/021-memory-review.md` 与 `?? specs/006-memory-review/` 两项，**零代码文件被触碰**；
- `yokeos-memory/src` 仍只有地基占位 `MemoryPackageSanityTest.java`；
- 前序交付物未动（H0 存在性检查通过：`ProviderService`/`ReActLoop`/`PromptBuilder`/`ToolExecutor`/`AgentService`/`SessionManager`/`ToolRegistry`/`AnnotatedToolAdapter` 全部在位）。

## 三、评审自查清单 11 条逐条结论（教学文档第四部分）

| # | 自查问题 | 一句话结论 | 锚点 |
|---|---|---|---|
| 1 | 短期/长期、上下文压缩/记忆压缩的区别 | 短期=工作台（Session）、长期=仓库（外部存储用时检索回）；上下文压缩管当下窗口、记忆压缩管长期库 | review §2.1 |
| 2 | 接口墙焊死没 | 焊死：ReAct/PromptBuilder 只见 `MemoryService`，后端形态无感 | D1 |
| 3 | 两层两分区口径 + 换后端动几处 | 会话+长期两层、长期内核心/归档两分区；换档只改 `memory.backend` 一行，上层零改动 | D4 + 定稿总口径 |
| 4 | 手动 save_memory 的三个依据 | ①「漏记」无真实信号；②ReAct 里主动调 Tool 路径已有、工程量近零；③自动提炼多一套触发判断+每次多一次模型调用 | D11 偏离依据① |
| 5 | 三档各自定位一句话 | markdown=零依赖人可读的单机默认档；sqlite=结构化查询的升级档（`memory_entries`）；mem0=自托管智能记忆的外部集成档（数据不出域前提） | D4 |
| 6 | 四条契约各防哪个坑 | 契约一（不缓存）防坑二；契约二（核心区永不截断）防坑一/五；契约三（scope 显式）防坑四；契约四（关键词不复杂化）防坑五的越界升级 | D3 ↔ §6.3 |
| 7 | 上向量三信号 + 进程内为何没有 | 关键词找不准 / 量过千 / 跨会话精准召回真实需求；LanceDB Java 本地未 GA、其余要外部进程或 PG、JVector 待验证（技 §9.1） | D11 偏离依据② |
| 8 | 自造 vs 集成锚点 | 「记忆是不是核心差异化能力」——Letta 同层竞品只参照绝不集成；Mem0 是库、可自托管、数据不出域，故能进三档 | §2.2 |
| 9 | USER.md / MEMORY.md | 前者用户手写、只读、初始设定；后者 Agent 经 save_memory 写入、读写、成长记录；都进 system prompt 但来源生命周期不同 | D8 |
| 10 | 两处张力裁决 | 张力一以技为准（每次组装现读）；张力二 `MemoryTools` 随能力三落位 `yokeos-memory`、注册 ToolRegistry（宪法 5 管基础设施合块的解读） | §5.1/§5.2 |
| 11 | 坑一~六的回归测试点 | 六坑六点逐条成表（超长截断/写入即见/USER.md 只读/scope 缺省归档/检索限归档/手工建表） | §6.3 |

**11/11 全部可答且有锚点。**

## 四、评审结论与移交

- **结论：通过。** [技 §5] 经业界对照维持原样冻结为 D1~D11，文档链零回改（两处张力以裁决留痕方式解决，未触碰原文）。
- **移交第 22 节**：六段式 specify 骨架预填（review §6.1）、取材跳转表（§6.2）、坑↔回归测试表（§6.3）、开放事项 O1~O5（§5.3，plan 阶段定稿）。
- **文档链新增引用关系**：教学文档与评审文档互相引用；CLAUDE.md / 技术方案 / 需求文档未改动。

## 五、实施偏差

无。评审文档结构、评审文档文件名（`review.md`）、两处张力裁决均按教学文档拍板记录①~⑤执行；评审过程中唯一的增补是 D11 补入两条偏离依据（自查第 4/7 条要求评审文档自足，不算结构偏差）。

## 六、验证命令（可复制）

```bash
# 不产码反向核对（预期：仅 docs/class/021-memory-review.md 与 specs/006-memory-review/ 两项未跟踪）
git status --short

# yokeos-memory 仍为占位（预期：仅 MemoryPackageSanityTest.java 一个文件）
find yokeos-memory/src -type f

# 评审文档六段结构齐全（预期：## 1.~## 6. 六节全命中）
grep -c '^## ' specs/006-memory-review/review.md

# 定稿与素材锚点抽查（预期：均有命中）
grep -n 'memory.backend' specs/006-memory-review/review.md
grep -n '坑 ↔ 22 节回归测试点\|坑 ↔' specs/006-memory-review/review.md
```
