# OryxOS 开发过程考古报告

> 目的：为 YokeOS 第一阶段复刻 OryxOS 作者的 AI 编程（SDD / Spec-Driven Development）过程提供**可执行的证据链**。
> 数据源：`vendors/oryxos`（git submodule，只读，上游 https://github.com/oryx-labs/oryxos）。
> 考古日期：2026-08-27。所有结论均附 git 命令，可直接复跑验证。

---

## 0. 一个必须先说清的"考古学陷阱"：64 个 commit 不是全部历史

子模块当前 checkout 在 **`class-30` 分支，HEAD = `45fa211`**，这条线性历史恰好 **64 个 commit**（与任务给定的数字一致）。
但 submodule 内**已经 fetch 下来了 299 个 commit 的 `origin/main`**，以及 17 个 `origin/class-*` 分支和 4 个 release tag。

```bash
cd vendors/oryxos
git rev-list --count HEAD          # 64
git rev-list --count origin/main   # 299
git merge-base --is-ancestor 45fa211 origin/main && echo YES   # YES
git branch -a && git tag           # class-05~class-31, fix/*, release/0.1.3-ci; v0.1.0~v0.1.3-RELEASE
```

**结论**：`class-N` 分支是作者"每节课一个分支"的原始工作分支；`HEAD` 停在 `class-30` 分支尖端，因此 **第 30 节的 merge commit（`963d4f1`）和第 31 节 / v0.1.0 发布（`bb0e7ef`）不在这 64 个 commit 里**。本报告以 64 个 commit 为主体（第 16~29 节完整、第 30 节半），并在第 8 节补齐 `origin/main` 上的第 30/31 节与发布阶段。

---

## 1. 完整时间线：64 个 commit

命令：`git log --reverse --date=format:'%Y-%m-%d %H:%M' --pretty=format:'%h|%ad|%p|%s'`

类型判定：`M` = merge commit（`git log --merges` 可列出全部 16 个）。

| # | hash | 日期 | 类型 | message 首行 |
|---|------|------|------|--------------|
| 1 | `ff60bdf` | 2026-06-06 | init | Initial commit |
| 2 | `8c851e2` | 06-28 12:13 | dev | dev —— **工程骨架：9 模块 + 官网 + 4 份设计文档，61 文件 / +8270 行** |
| 3 | `01efd90` | 06-28 12:28 | dev | dev |
| 4 | `e5922bc` | 06-28 14:15 | dev | dev |
| 5 | `4db9c54` | 06-28 14:23 | dev | dev |
| 6 | `fa4c1a5` | 06-28 14:28 | dev | dev |
| 7 | `066fc80` | 06-28 14:39 | dev | dev |
| 8 | `8223c66` | 06-28 15:01 | dev | dev |
| 9 | `c65d2b6` | 06-28 15:09 | dev | dev |
| 10 | `39b460d` | 06-28 15:11 | dev | dev |
| 11 | `21c198c` | 06-28 15:15 | dev | dev |
| 12 | `ebe50b0` | 06-28 15:21 | chore | chore: update scripts (1 file(s)) |
| 13 | `c1f93bd` | 06-28 15:27 | chore | chore: update docs (1 file(s)) |
| 14 | `9131480` | 06-28 15:27 | docs | Fix duplicate description in TechnicalSolution.md （人类手写，Porter Zhang） |
| 15 | `76a6911` | 06-28 15:28 | docs | Update provider name to ChatModel mapping section （人类手写） |
| 16 | `52fbe12` | 06-28 15:48 | chore | chore: update docs,website (26 file(s)) |
| 17 | `251c491` | 06-28 15:50 | chore | chore: update website (3 file(s)) |
| 18 | `825925f` | 06-28 15:53 | chore | chore: update website (6 file(s)) |
| 19 | `354ca48` | 06-28 15:55 | chore | chore: update website (3 file(s)) |
| 20 | `36a1787` | 06-28 16:00 | dev | dev |
| 21 | `03f65ba` | 06-28 16:37 | chore | chore: sync docs,README.md scripts,website (6 changed, 3 deleted) |
| 22 | `885d554` | 06-29 14:27 | chore | chore: update CLAUDE.md,docs (2 file(s)) |
| 23 | `bf2f1ff` | 06-30 14:00 | chore | chore: update .claude (1 file(s)) —— `class-05` 分支尖端 |
| 24 | `d40944f` | 06-30 17:44 | chore | chore: update …（22 文件）—— **工程地基：CI/editorconfig/pre-commit/P3C** |
| 25 | `d9dd7cd` | 07-01 10:57 | chore/M | chore: update .editorconfig,.github … |
| 26 | `fb0f02d` | 07-02 09:57 | chore | chore: update .claude,.specify specs (40 file(s)) —— **Spec-Kit 安装 + 宪法 v1.0.0** |
| 27 | `1b69aae` | 07-08 20:19 | chore | chore: remove 12 file(s) in specs —— **删除早期"三合一"试跑 spec** |
| 28 | `df3ce64` | 07-08 20:19 | chore/M | chore: update .claude,.specify specs (40 file(s)) |
| 29 | `efbcce6` | 07-09 12:55 | chore | chore: update …（95 文件）—— **31 份课件 `docs/class/` 一次性入库** |
| 30 | `7d29471` | 07-09 12:58 | chore/M | chore: update …（95 文件） |
| 31 | `c9dafa7` | 07-09 13:43 | chore | chore: update docs (1 file(s)) |
| 32 | `00bdc31` | 07-09 14:53 | chore | chore: sync .claude,docs —— `class-14` 尖端 |
| 33 | `f8276e8` | 07-09 15:10 | chore/M | chore: sync .claude,docs —— `class-15` 尖端 |
| 34 | `5797796` | 07-09 15:47 | chore | chore: update scripts (1 file(s)) —— **第 16 节的分支起点** |
| 35 | `c21d55f` | 07-10 11:19 | chore | chore: update …（41 文件）—— **第 16 节全部实现 + specs/001**，`class-16` 尖端 |
| 36 | `ed92220` | 07-10 11:21 | **feat/M** | **feat: 第16节：Agent Provider 原理解析、实现与代码讲解** |
| 37 | `2afae1d` | 07-11 00:21 | chore | chore: sync …（44 changed, 5 deleted）—— 第 17 节 ReAct，`class-17` 尖端 |
| 38 | `71a0609` | 07-11 00:22 | chore/M | chore: sync … —— **第 17 节 merge，但 message 是通用 sync 而非 feat** |
| 39 | `34666f5` | 07-11 09:30 | chore | chore: update …（37 文件）—— 第 18 节 + `my-agent/` 首现，`class-18` 尖端 |
| 40 | `9441ff8` | 07-11 09:33 | **feat/M** | **feat: 第18节：CLI 功能概述、实现思路与代码讲解** |
| 41 | `8f2c2dd` | 07-11 11:19 | chore | chore: update .specify,docs oryxos-tool,specs (22) —— 第 19 节，`class-19` 尖端 |
| 42 | `481e671` | 07-11 11:20 | **feat/M** | **feat:第19节：Notify 模块 原理解析、实现与代码讲解** |
| 43 | `2b1e015` | 07-11 16:07 | chore | chore: update …（45 文件）—— 第 20 节，`class-20` 尖端 |
| 44 | `1611810` | 07-11 16:09 | **feat/M** | **feat: 第20节：Tool 体系 原理解析、实现与代码讲解** |
| 45 | `aa781d2` | 07-12 15:02 | chore | chore: update …（35 文件）—— 第 22 节 Memory，`class-22` 尖端 |
| 46 | `8691926` | 07-12 15:03 | **feat/M** | **feat: 第22节：Memory 实现与代码讲解** |
| 47 | `f9ee33a` | 07-12 17:42 | chore | chore: update …（32 文件）—— 第 24 节 Sandbox，`class-24` 尖端 |
| 48 | `9aa9730` | 07-12 17:43 | **feat/M** | **feat: 第24节：Sandbox 实现与代码讲解** |
| 49 | `bef8a97` | 07-14 23:37 | chore | chore: update .specify,oryxos-cli oryxos-core,specs (14) —— 第 25 节，`class-25` 尖端 |
| 50 | `3004664` | 07-14 23:39 | **feat/M** | **feat: 第25节：定时任务模块 原理解析、实现与代码讲解** |
| 51 | `58f4b2c` | 07-15 13:08 | chore | chore: update docs (6 file(s)) |
| 52 | `f809d2d` | 07-16 11:37 | chore | chore: update …（60 文件）—— 第 26 节 Web，`class-26` 尖端 |
| 53 | `f40a467` | 07-16 11:38 | **refactor/M** | **refactor: 第26节：Web Service 与第一版管理平台 实现与代码讲解** |
| 54 | `6b5509e` | 07-17 12:00 | chore | chore: update docs,website (10 file(s)) |
| 55 | `39532e7` | 07-17 14:21 | chore | chore: sync README.md,config …（22 changed, 4 deleted） |
| 56 | `20dc2ed` | 07-17 14:23 | chore | chore: sync oryxos-provider,scripts —— 第 27 节，`class-27` 尖端 |
| 57 | `00fc9d7` | 07-17 14:25 | **feat/M** | **feat: 第27节：全流程串联（一）打通 Agent 主流程** |
| 58 | `bcb14f3` | 07-17 20:33 | chore | chore: update …（24 文件） |
| 59 | `0a9292a` | 07-18 11:00 | chore | chore: sync CLAUDE.md,docs website —— 第 28 节，`class-28` 尖端 |
| 60 | `10860b0` | 07-18 11:05 | feat/M | feat: 更新文档 —— **第 28 节 merge，message 退化成"更新文档"** |
| 61 | `c2a4a66` | 07-18 13:47 | chore | chore: sync .specify,CLAUDE.md docs,website（24 changed, 1 deleted） |
| 62 | `24a9533` | 07-18 15:17 | chore | chore: sync …（46 changed, 1 deleted）—— 第 29 节，`class-29` 尖端 |
| 63 | `a99f299` | 07-18 15:17 | **feat/M** | **feat: 第29节：插件化 Agent 一个目录定义一个会自己跑的 Agent** |
| 64 | `45fa211` | 07-18 18:17 | chore | chore: sync …（54 changed, 1 deleted）—— **第 30 节实现，`class-30` 尖端 = HEAD，尚未 merge** |

**汇总**：`git log --pretty=format:'%s' | sed -E 's/^([a-z]+)(\(.*\))?:.*/\1/' | sort | uniq -c`
→ `39 chore` / `11 dev` / `10 feat` / `1 refactor` / 3 其他（Initial commit + 2 人类手写 docs）。

**节奏**：06-06 空 repo → 06-28 一天 20 个 commit（文档与骨架期）→ 06-30~07-09 工程地基与课件入库 → **07-10~07-18 九天完成第 16~30 节全部代码**（07-11 单日 8 个 commit、跨 4 节课）。

---

## 2. 课程大纲重建（YokeOS 第一阶段直接可用）

来源：`git log --all --reverse --pretty=format:'%h|%d|%s' --grep='第.*节'` + `ls specs/` + `git ls-tree -r --name-only HEAD -- docs/class`（31 份课件）。

`specs/` 目录用**顺序编号 001~011**（与节号解耦），`docs/class/` 用**节号 1~32** 命名。两者对齐如下。

| 节 | 课件标题 | spec | 落位模块 | 交付物 / 关键类 | git 证据 |
|----|---------|------|---------|----------------|----------|
| 1~9 | 方法论与认知课（pptx）：给学员的一封信 / 课程架构 / 认知力 / 选型力七层架构 / **6 方法论内功：SDD·Harness·Loop Engine** / 7 工作流设计力 / 8 Agent 架构拆解 / 9 看懂 Agent OS：OryxOS 定位 | — | — | 无代码，只产出 `docs/DemandAnalysis.md`、`IndustryResearch.md`、`TechnicalSolution.md` | `8c851e2` |
| 10 | 从需求分析到任务拆解 | — | — | `docs/DemandAnalysis.md` | 课件 pptx |
| 11~12 | （第 11 节课件缺失）开发前的工程准备 | — | — | Maven 多模块骨架、9 模块 | `8c851e2` |
| 13 | Spec-Kit 教程——原理、用处、用法、时机 | — | `.specify/` | speckit 安装（workflow-registry `installed_at: 2026-07-01T03:16:47Z`） | `fb0f02d` |
| 15 | 完成基础模块开发——用 init skill 起工程地基 | — | 全模块 | CI / Spotless / P3C / Checkstyle / SpotBugs / FindSecBugs / OWASP / pre-commit | `d40944f` `oryxos-init` skill |
| **16** | **Agent Provider 原理解析、实现与代码讲解** | `001-provider-abstraction` | core+provider+storage | `Profile`/`ProfileLoader`/`ProfileRegistry`、`ProviderService`、`LlmCall`+Repository（llm_calls 表）、`ToolSchemaAdapter` | `ed92220` |
| **17** | **ReAct 原理解析、实现与代码讲解** | `002-react-loop` | core+storage | `ReActLoop`/`PromptBuilder`/`ToolExecutor`/`AgentService`/`ProfileContext`/`ContextLoader`、`ToolInvocation`（tool_invocations 表） | `71a0609`（merge 时 message 退化） |
| **18** | **CLI 功能概述、实现思路与代码讲解** | `003-cli-entry` | cli+channel-cli+core+storage | `OryxOsCli`+12 命令、`CliChannel`、`SessionManager`/`Session`（sessions 表）、**`my-agent/` 首现** | `9441ff8` |
| **19** | **Notify 模块 原理解析、实现与代码讲解** | `004-notify-outbound` | tool | notify 包（Adapter/Target/Webhook/`NotifyTools`） | `481e671` |
| **20** | **Tool 体系 原理解析、实现与代码讲解** | `005-tool-system` | core+tool | `ToolRegistry`、内置 Tool、MCP Client | `1611810` |
| 21 | Memory 原理解析、业界方案与 OryxOS 设计**评审** | — | — | **评审课，不产码**，是 22 的 specify 素材 | 课件 md |
| **22** | **Memory 实现与代码讲解** | `006-memory-pluggable` | memory | 全部落 `oryxos-memory` | `8691926` |
| 23 | Sandbox 原理解析、业界方案与 OryxOS 设计**评审** | — | — | **评审课，不产码**，是 24 的 specify 素材 | 课件 md |
| **24** | **Sandbox 实现与代码讲解** | `007-sandbox-whitelist` | tool | sandbox 包：路径/命令首 token/域名通配三张白名单 | `9aa9730` |
| **25** | **定时任务模块 原理解析、实现与代码讲解** | `008-scheduled-tasks` | core | `AgentScheduler`/`ScheduleConfig` | `3004664` |
| **26** | **Web Service 与第一版管理平台 实现与代码讲解** | `009-web-service` | web + bin + config | Controller、异常处理、static-admin、`bin/start.sh` | `f40a467`（**唯一用 `refactor:`**） |
| **27** | **全流程串联（一）打通 Agent 主流程** | （不开新 spec） | boot | 对账清单固化成 `@Tag("integration")` 的 `HumanTriggerFlowIT` | `00fc9d7` |
| **28** | **全流程串联（二）让底座自己跑得稳** | （不开新 spec） | boot | `SchedulerFlowIT` / `RestartRecoveryIT` | `10860b0`（message 退化） |
| **29** | **插件化 Agent 一个目录定义一个会自己跑的 Agent** | `010-folder-agent` | core + `.oryxos/` | `AGENT.md` frontmatter → `deriveProfile`、运行时注册 | `a99f299` |
| **30** | **动态管理 Agent 一句话生成、上传即上线** | `011-agent-lifecycle` | web + core | `AgentApiController`、`AgentLifecycleService`、`/api/v1/agents` | `963d4f1`（在 origin/main，不在 HEAD） |
| **31** | **天气、科技日报、GitHub 日报 Agent 开发与演示 发布、打包、第一个版本** | （Demo 课） | — | `weather-daily`（走 30 节 API）+ `daily-tech-digest`（手写文件）、打包发布 | `bb0e7ef` = tag `v0.1.0-RELEASE` |
| 32 | 第一阶段总结、后续规划与社区推进 | — | — | — | `origin/main` |

### 课型分流表（作者明确区分了 4 种课，流程不同）

来自 `.claude/skills/oryxos-lesson-dev/SKILL.md` 第 0 步：

| 课型 | 节号 | 处理方式 |
|------|------|---------|
| 代码课 | 16,17,18,19,20,22,24,25,26,29,30 | 完整 7 步 SDD 流程 |
| **评审课** | 21,23 | **拒绝产码**——它是下一节（22/24）的 specify 素材 |
| **串联课** | 27,28 | 不开新 feature，逐条执行课件对账清单并固化成 `@Tag("integration")` E2E 测试 |
| **Demo 课** | 31 | 按课件定义两个 Agent + 调试对账 + 发布清单，不做常规模块开发 |

> **复刻要点**：这套"课型分流"是作者流程的关键设计——不是每一节都开 spec。YokeOS 第一阶段应照抄这张表。

---

## 3. SDD 流程痕迹

### 3.1 核心发现：`feat:` 是 merge commit，代码在它前面的 `chore:` 里

这是整个考古最反直觉、也最重要的结论。

```bash
git log --merges --date=format:'%m-%d %H:%M' --pretty=format:'%h %p %ad %s'
# ed92220 f8276e8 c21d55f 07-10 11:21 feat: 第16节：Agent Provider 原理解析、实现与代码讲解
# 9441ff8 71a0609 34666f5 07-11 09:33 feat: 第18节：CLI 功能概述、实现思路与代码讲解
# ...

git diff --name-only c21d55f ed92220 | wc -l     # 0  （两棵树完全一致）
git log -1 --format='%h parent=%p' origin/class-16
# c21d55f parent=5797796   ← class-16 从 main 的 5797796 分出，只有一个 commit
```

**结构**：每一节课 = `class-N` 分支上**恰好 1 个 commit**（整个节的全部产出被压成一个快照）+ main 上 1 个 merge commit（`feat: 第N节：…`）。

验证每个分支只有 1 个 commit：
```bash
for b in 16 17 18 19 20 22 24 25 26 27 28 29 30; do
  echo "class-$b: $(git log -1 --format='%h parent=%p' origin/class-$b)"; done
# 全部 parent 都是当时 main 的 tip → 每个分支只有 1 个 commit
```

**推论**：作者真实的 SDD 迭代（specify → clarify → plan → tasks → 停点 → implement → 验收）**发生在 AI 会话与工作树里，git 看不到**；入库时被同步工具压成一个快照。**git 历史只能证明"每节一个原子交付物"，无法证明中间过程**（详见第 8 节盲区）。

### 3.2 feat / chore 交替模式（完整 12 节映射）

| 节 | 分支尖端（chore，含全部代码+spec） | merge commit（feat/refactor） | 间隔 |
|----|------|------|------|
| 16 | `c21d55f` 07-10 11:19 | `ed92220` 07-10 11:21 | **2 分钟** |
| 17 | `2afae1d` 07-11 00:21 | `71a0609` 07-11 00:22 | 1 分钟（**message 退化成 chore: sync**） |
| 18 | `34666f5` 07-11 09:30 | `9441ff8` 07-11 09:33 | 3 分钟 |
| 19 | `8f2c2dd` 07-11 11:19 | `481e671` 07-11 11:20 | 1 分钟 |
| 20 | `2b1e015` 07-11 16:07 | `1611810` 07-11 16:09 | 2 分钟 |
| 22 | `aa781d2` 07-12 15:02 | `8691926` 07-12 15:03 | 1 分钟 |
| 24 | `f9ee33a` 07-12 17:42 | `9aa9730` 07-12 17:43 | 1 分钟 |
| 25 | `bef8a97` 07-14 23:37 | `3004664` 07-14 23:39 | 2 分钟 |
| 26 | `f809d2d` 07-16 11:37 | `f40a467` 07-16 11:38 | 1 分钟 |
| 27 | `20dc2ed` 07-17 14:23 | `00fc9d7` 07-17 14:25 | 2 分钟 |
| 28 | `0a9292a` 07-18 11:00 | `10860b0` 07-18 11:05 | 5 分钟（**message 退化成 feat: 更新文档**） |
| 29 | `24a9533` 07-18 15:17 | `a99f299` 07-18 15:17 | <1 分钟 |

1~2 分钟的间隔证明：**两个 commit 是同一个动作的两个产物**——先是自动同步工具打 `chore:` 快照，紧接着人/AI 打 `feat:` merge 并写课件标题。第 17/28 节的 message 退化说明 `feat:` 这一步是**手动**的，不是脚本。

### 3.3 spec 先行还是实现先行？

**同 commit 内同时落地，但文件内部有明确的先后时间戳证据。**

```bash
for d in 001-provider-abstraction 002-react-loop 003-cli-entry 004-notify-outbound \
         005-tool-system 006-memory-pluggable 007-sandbox-whitelist 008-scheduled-tasks \
         009-web-service 010-folder-agent 011-agent-lifecycle; do
  git log --all --reverse --diff-filter=A --format='%h %ad' --date=short -- specs/$d/spec.md | head -1
done
```
结果：**每一个 spec 目录的首个 commit 都与该节实现是同一个 commit**（001↔`c21d55f`，003↔`34666f5`，004↔`8f2c2dd`，005↔`2b1e015`，006↔`aa781d2`，007↔`f9ee33a`，008↔`bef8a97`，009↔`f809d2d`，010↔`24a9533`，011↔`45fa211`）。

但 **spec 内容早于代码**，证据有三：

1. **`spec.md` 的 `Created` 字段早于 commit 日期**。`specs/001-provider-abstraction/spec.md`：
   > **Created**: 2026-07-09 —— 而 commit `c21d55f` 是 2026-07-10。
   > 且 spec.md 的 `**Feature Branch**: class-16` 直接记录了分支名。

2. **`tasks.md` 里显式写了 TDD 排序**（见第 6 节）。

3. **`acceptance-report.md` 是独立产物**，写在同一 spec 目录里，`日期: 2026-07-09`。

**真实时序**（第 16 节为例）：
```
07-09  spec.md / plan.md / tasks.md 生成（specify→plan→tasks，含停点）
07-09  implement + 验收报告
07-10 11:19  一次性入库（chore 快照）
07-10 11:21  merge 成 feat: 第16节
```
即 **spec 先行（同一天早些时候），入库时与实现合流**。

### 3.4 `.specify/` 是什么：GitHub Spec Kit（speckit）

```bash
cat .specify/workflows/workflow-registry.json
# "name": "Full SDD Cycle", "author": "GitHub", "installed_at": "2026-07-01T03:16:47Z"
# "requires": { "speckit_version": ">=0.8.5", "integrations": { "any": ["claude","copilot","gemini","opocode"] } }
```

目录结构与其透露的工作流：

```
.specify/
├── feature.json                    # 当前活动 feature 指针 → {"feature_directory": "specs/011-agent-lifecycle"}
├── init-options.json               # speckit init 时的选项
├── integration.json
├── integrations/
│   ├── claude.manifest.json        # Claude Code 集成清单
│   └── speckit.manifest.json
├── memory/
│   └── constitution.md             # ★ 项目宪法，v1.1.0，8 条原则
├── scripts/bash/
│   ├── create-new-feature.sh       # 建分支 + specs/{NNN}-<slug>/
│   ├── setup-plan.sh               # 生成 plan.md
│   ├── setup-tasks.sh              # 生成 tasks.md
│   ├── check-prerequisites.sh      # 门禁：检查前置产物是否齐
│   └── common.sh
├── templates/
│   ├── spec-template.md  plan-template.md  tasks-template.md
│   ├── constitution-template.md  checklist-template.md
└── workflows/
    ├── speckit/workflow.yml        # specify→gate→plan→gate→tasks→implement
    └── workflow-registry.json
```

`workflow.yml` 里有两个 `type: gate`（review-spec / review-plan，`on_reject: abort`）——**人审门禁是工具原生能力，不是作者发明的**。

### 3.5 宪法（Constitution）：`.specify/memory/constitution.md` v1.1.0

这是整个 SDD 体系的"不可违背铁律"，也是作者 AI 编程过程的**最高层控制文件**。8 条原则：

| # | 原则 | 是否 NON-NEGOTIABLE |
|---|------|--------------------|
| I | 自实现 ReAct 循环，不用 Spring AI 的 Agent 抽象 / `ChatClient` 自动工具执行 | ★ |
| II | Spring AI 只做协议转换 + `@Tool` Schema 生成 | ★ |
| III | Provider 显式 `name → ChatModel` 映射表，不靠扫 Bean 类型 | |
| IV | 一个目录 = 一个 Agent；`AGENT.md` 由 ContextLoader 加载，不作为 Tool | |
| V | 审计 Day One 落库（`tool_invocations` + `llm_calls`） | ★ |
| VI | 安全是地基：`SandboxChecker` 白名单，不用 SecurityManager | ★ |
| VII | 同步执行 + 虚拟线程，禁止 Reactor/`CompletableFuture` | |
| VIII | 配置即 Agent，实例无状态、状态外置 | |

关键治理细节：
- 文件**顶部保留 Sync Impact Report 注释**，记录每次版本变更的理由。v1.0.0→v1.1.0 的理由是：
  > 「技术栈与架构约束新增『模块结构可按需演进』条款（**用户在第17节 tasks 停点批准**）」
  —— **这是"停点等用户确认"真实发生过的唯一书面证据**。
- 宪法还内置「开发流程与质量门禁」章节，明文规定：
  > constitution → specify → (clarify) → plan → tasks → (analyze) → implement。一次只推进一个特性。
- `Ratified: 2026-07-01 | Last Amended: 2026-07-10`

```bash
git log --all --date=short --format='%h %ad %s' -- .specify/memory/constitution.md
# fb0f02d 2026-07-02  ← v1.0.0 首次入库（Spec-Kit 安装同 commit）
# 2afae1d 2026-07-11  ← v1.1.0（第 17 节后）
# c2a4a66 2026-07-18
# 182140c 2026-07-27
```

### 3.6 三份"过程沉淀文档"（作者自己写的流程说明书）

| 文件 | 作用 |
|------|------|
| `.claude/skills/oryxos-lesson-dev/SKILL.md` | **流程的可执行版本**。输入节号 → 7 步全自动 + 硬/软门禁 |
| `docs/class/Harness 设计：用门禁保证每个功能符合预期.md` | 门禁设计哲学：**「能机器判的绝不留给人，机器判不了的绝不自行发挥」**，H0~H6 七道门禁 |
| `docs/class/Spec-Kit 执行指导：从课件到代码（以第16节为例）.md` | 手动兜底流程，含 `/speckit-specify` 的完整喂料模板 |

### 3.7 七道门禁 H0~H6（`docs/class/Harness 设计….md`）

| 门禁 | 类型 | 内容 | 挂在流程哪一步 |
|------|------|------|---------------|
| **H0** 开工纪律 | 软 | 必读课件 + TechnicalSolution 对应章节 + 前序交付物清单；做依赖存在性检查（grep 前序类是否存在） | 开工前 |
| **H1** 设计保真 | 软（最高优先级） | 只创建"本节交付物"点名的 public 类/配置键/表/端点；已定字面量逐字保真；"有几样先别做"=禁止实现清单 | specify / plan |
| **H2** 跨节契约 | 软+硬 | 前序节公共接口只可用不可修改；本节结束前**前序所有节测试必须仍全绿** | 收尾 |
| **H3** API 真实性 | 软 | 第三方 API 先 `mvn dependency:tree` / 翻 jar 源码核实存在，核实不到不写 | 每任务写前 |
| **H4** 全局不变量 | 硬（6 条） | ①涉外 IO 首行过 `Sandbox.enforce` ②LLM/工具成败都落审计表 ③grep 无明文 key ④`session_id` 只在 SessionManager 拼 ⑤无 Reactor/CF/自建线程池 ⑥无 Spring AI 自动工具执行 | 每节完成前逐条自查 |
| **H5** 实现质量 | 硬为主 | 异常不吞；不过度设计；注释只写"为什么"；避开 P3C/ASM 解析不了的 Java 18+ 语法 | 随写随查 |
| **H6** 完成定义与反作弊 | 硬 | 节级完成 = 6 项证据齐全；**不得删断言/`@Disabled`/放宽阈值让测试变绿** | 收尾 |

**反漂移四形态**（作者总结的 AI 逐节开发真实风险）：发明 / 顺手优化 / 提前实现 / 臆造 API。

### 3.8 `.claude/skills/` —— 12 个 skill 的完整清单

```bash
ls .claude/skills/
# oryxos-admin-ui  oryxos-init  oryxos-lesson-dev        ← 作者自研 3 个
# speckit-analyze  speckit-checklist  speckit-clarify  speckit-constitution
# speckit-converge speckit-implement  speckit-plan     ← Spec Kit 官方 9 个
# speckit-specify  speckit-tasks  speckit-taskstoissues
```

`oryxos-lesson-dev` 的 7 步流程（**这是 YokeOS 应直接复刻的执行器**）：

```
第 0 步  输入校验 + 课型分流（16~31 表外直接报错）
第 1 步  H0：读三样 + 依赖存在性检查 + 确认在 feature 分支上
第 2 步  /speckit-specify  （只喂 WHAT/WHY，不带类名和技术栈）
第 3 步  /speckit-clarify  （答案只从课件和技术方案找，找不到→软门禁停下）
第 4 步  /speckit-plan     （固定技术栈句 + 模块落位表 + 测试策略句 + 语法禁区句）
第 5 步  /speckit-tasks + 固定软停点（任务清单 ↔ 课件交付物自动比对，停下等确认）
第 6 步  /speckit-analyze（建议跑）+ /speckit-implement（H3/H1/H5/H5-DoD 门禁）
第 7 步  节级收尾：六项证据 DoD + 验收报告 + 三段式变更总结
```

**总纪律原文**：全程**不自动 commit / push / 运行 package.sh**，同步时机由用户决定。——这解释了 3.2 里为什么 commit 间隔只有 1~2 分钟：是用户手动触发的一次同步动作。

---

## 4. 提交粒度与节奏

### 4.1 每节 commit 数

| 维度 | 数据 |
|------|------|
| 每节课在分支上的 commit 数 | **恒等于 1**（整节压成一个快照） |
| 每节课在 main 上的 commit 数 | **2**（1 个 chore 快照 + 1 个 feat merge） |
| 单日最大节数 | 07-11 一天 4 节（17/18/19/20） |
| 代码课总耗时 | 第 16~30 节共 **9 天**（07-10 ~ 07-18） |

### 4.2 变更规模抽样（`git show --stat`）

| 节 | commit | 文件数 | 行数变化 | 备注 |
|----|--------|--------|---------|------|
| 骨架 | `8c851e2` | 61 | +8270 | 9 模块 pom+1 个 stub 类 + VitePress 官网 + 4 份设计文档 |
| 16 | `c21d55f` | 41 | +2145 / −3 | Provider 全套 + specs/001 全套（9 个文件）|
| 22 | `aa781d2` | 35 | +1876 / −99 | Memory |
| 26 | `f809d2d` | 60 | +3707 / −38 | Web Service + 管理台 + bin/config |
| 29 | `24a9533` | 47 | +1579 / −157 | 插件化 Agent |

**单节稳定在 1500~3700 行新增、14~60 个文件**。spec 目录固定贡献 6~9 个文件（spec/plan/tasks/research/data-model/contracts/quickstart/checklists/acceptance-report）。

### 4.3 message 语言与格式约定

**机器生成的两种形态**（39 个 chore）：
```
chore: update <顶层目录列表，逗号分组、空格分隔> (N file(s))
chore: sync   <顶层目录列表> (N changed, M deleted)
```
例：`chore: update .specify,docs oryxos-boot,oryxos-cli oryxos-core,oryxos-memory oryxos-storage,specs (35 file(s))`

**人写的 lesson merge**（11 个）：
```
<type>: 第N节：<课件标题原文>
```
type 几乎全是 `feat:`，仅第 26 节用 `refactor:`（因为是改造既有 Web 层）。

**作者信息分布**（`git log --pretty=format:'%an <%ae>' | sort | uniq -c`）：
```
45  Your Name <you@example.com>        ← 默认 git 身份，说明作者没配 user.name（AI/本地环境提交）
17  Robert <317307889@qq.com>
 2  Porter Zhang <porterzhang2021@outlook.com>   ← 唯二人类手写 commit（9131480、76a6911，英文 message）
```

**瑕疵/不一致**（复刻时应修正）：
- 第 17 节 merge message 退化成 `chore: sync …`，第 28 节退化成 `feat: 更新文档`
- `481e671` 的 `feat:` 后缺空格（`feat:第19节：`）
- 61 个 commit 的作者是未配置的默认身份
- `dev`（11 个）完全无信息量，出现在 06-28 文档期

---

## 5. 模块诞生顺序：自底向上

### 5.1 骨架先行：9 个模块在**同一个 commit** 里诞生

```bash
git show --name-status --format='' 8c851e2 | grep 'oryxos-'
# A oryxos-boot/pom.xml + src/.../OryxOsApplication.java
# A oryxos-channel-cli/pom.xml + ChannelCliModule.java
# A oryxos-cli/pom.xml + OryxOsCli.java
# A oryxos-core/pom.xml + OryxTool.java + ToolResult.java
# A oryxos-memory/pom.xml + MemoryModule.java
# A oryxos-provider/pom.xml + ProviderModule.java
# A oryxos-storage/pom.xml + StorageModule.java
# A oryxos-tool/pom.xml + ToolModule.java
# A oryxos-web/pom.xml + WebModule.java
```
每个模块只有 pom + **1 个空壳类**。共 10 个 java 文件。同 commit 还带上了 VitePress 官网和 4 份设计文档。

**这是明确的"自顶向下定骨架、自底向上填肉"混合策略**：先用一次 commit 把整个空间结构和依赖方向锁死（含 `docs/TechnicalSolution.md`、`docs/images/architecture.svg`），再逐节填内容。

### 5.2 各模块"首次实质实现"时序

`git log --all --reverse --diff-filter=A --format='%h %ad %s' -- '<mod>/src/main/java'`

| 顺序 | 节 | commit | 日期 | 模块 | 说明 |
|------|----|--------|------|------|------|
| 0 | 前置 | `d40944f` | 06-30 | （全部 9 个，仅工程配置） | CI / P3C / pre-commit / editorconfig |
| 1 | 16 | `c21d55f` | 07-10 | **oryxos-core**（profile 包）、**oryxos-provider**、**oryxos-storage** | Profile + Provider + llm_calls 审计表 |
| 2 | 17 | `2afae1d` | 07-11 00:21 | oryxos-core（ReAct）、oryxos-storage（tool_invocations） | 运行时内核 |
| 3 | 18 | `34666f5` | 07-11 09:30 | **oryxos-cli**、**oryxos-channel-cli**、oryxos-boot、**my-agent/**（首现） | 第一个用户入口 + 第一个 Agent 目录 |
| 4 | 19 | `8f2c2dd` | 07-11 11:19 | **oryxos-tool**（notify 包） | tool 模块首次有真代码 |
| 5 | 20 | `2b1e015` | 07-11 16:07 | oryxos-tool（ToolRegistry/内置 Tool/MCP）、oryxos-cli | 工具体系 |
| 6 | 22 | `aa781d2` | 07-12 15:02 | **oryxos-memory** | 骨架期就存在，第 6 个才被填 |
| 7 | 24 | `f9ee33a` | 07-12 17:42 | oryxos-tool（sandbox 包） | 安全地基，但物理上放在 tool 里 |
| 8 | 25 | `bef8a97` | 07-14 23:37 | oryxos-core（scheduler） | 回填 core |
| 9 | 26 | `f809d2d` | 07-16 11:37 | **oryxos-web**、**bin/**、**config/** | 最后一个模块 + 部署脚本 |
| 10 | 27~28 | `20dc2ed` `0a9292a` | 07-17~18 | oryxos-boot（IT）、oryxos-provider（改名 `SpringAiProviderServiceImpl`） | 串联与稳定 |
| 11 | 29 | `24a9533` | 07-18 15:17 | my-agent/.oryxos、oryxos-core（运行时注册） | 插件化 |
| 12 | 30 | `45fa211` | 07-18 18:17 | oryxos-web（AgentApiController）、oryxos-core（LifecycleService） | 动态管理 |

### 5.3 构建策略结论

**核心优先，入口次之，最外围（Web/部署）最后。**

依赖顺序严格符合宪法第 I/II 条：先有 `provider`（能调 LLM）→ `core`（能跑 ReAct）→ `cli`（能被人用）→ `tool`（能干活）→ `memory`（能记住）→ `sandbox`（能安全）→ `web`（能被管理）。

值得注意的三个细节：
1. **`oryxos-sandbox` 没有独立成模块**——宪法 v1.1.0 明文允许"允许新建模块（如把沙箱独立为 `oryxos-sandbox`）"，但作者最终把它放进 `oryxos-tool`，说明"模块结构可演进"条款是预留而非必须。
2. **`oryxos-memory` 在骨架期就存在，但第 6 个才实现**——骨架不是"用到才建"，而是**一开始就画好完整模块图**（含空壳类），让 `pom.xml` 的依赖方向从 day one 就被 Maven 锁死。这是防"模块间循环依赖"的工程手段。
3. **`my-agent/` 在第 18 节（第 3 个代码节）就出现**——作者很早就有"用一个真实 Agent 吃自己的狗粮"的意识，而不是最后才做 demo。

---

## 6. 测试实践

### 6.1 测试文件何时首现？与实现同 commit

```bash
git log --all --reverse --diff-filter=A --format='%h %ad %s' --date=short -- '*src/test/*' | head -1
# c21d55f 2026-07-10 chore: update .claude,.specify oryxos-core,oryxos-provider … (41 file(s))
```
**第一个测试文件与第一批实现代码在同一个 commit**。也就是说：**git 历史层面看不到独立的"红"commit，没有任何 commit 级 TDD 证据**。

但 **tasks.md 内部有明确的红→绿排序指令**，这是 TDD 在任务粒度的直接证据。`specs/001-provider-abstraction/tasks.md` 开头：

> **Tests**: 用户显式要求 harness 先行——每个实现任务的测试任务紧邻其前（红→绿）；课件三个中文名关键回归测试必须原样落地。

具体任务对：
```
- [x] T005 [P] 先写测试 …/ProfileLoaderTest.java：①合法 YAML 全字段解析…⑤加载后可从 ProfileRegistry 按 name 查到
- [x] T006 实现 …/ProfileLoader.java …，使 T005 全绿

- [x] T008 [P] [US1] 先写测试 …/ToolSchemaAdapterTest.java：…
- [x] T009 [US1] 实现 …/ToolSchemaAdapter.java …，使 T008 全绿

- [x] T010 [P] [US1] 先写测试 …/ProviderServiceTest.java：**课件回归①** 按名路由_两个provider不串台 …
- [x] T011 [US1] 定义审计接口 … 实现 ProviderService.java …，使 T010 当前用例全绿
```

**结论：任务级 TDD（测试任务显式排在实现任务之前），commit 级 squash 掉了痕迹。**

### 6.2 课件可追溯性：中文测试名 + `@DisplayName` 保真

tasks.md 与 `acceptance-report.md` 都要求**课件里写出代码的关键回归测试必须原样落地**：
- 测试**方法名**必须是英文（`callWithToolSchema_disablesAutoExecution`）
- 课件中文原文（`按名路由_两个provider不串台`）通过 `@DisplayName` 保留
- 理由：**「测试方法名必须是英文——P3C/ASM 解析不了的 Java 18+ 语法形态，静态检查是构建门禁」**（中文方法名会被 P3C/PMD 拦）

三个必须原样落地的中文回归（第 16 节）：
```
按名路由_两个provider不串台        → verify 另一家 never()
调用失败_审计必须留下success为false的记录 → 先落账再上抛
带工具schema调用_请求里关闭了自动执行  → ArgumentCaptor 断言 proxyToolCalls=true
```

### 6.3 测试框架与运行方式

```bash
grep -oE '<artifactId>(junit|spring-boot-starter-test|assertj|mockito|testcontainers)[^<]*' */pom.xml | sort -u
# 所有 6 个含代码的模块都只有 spring-boot-starter-test（JUnit 5 + AssertJ + Mockito 内置）
# 没有用 Testcontainers
```

技术栈：
| 用途 | 技术 |
|------|------|
| 单测 | JUnit 5（spring-boot-starter-test）、Mockito mock ChatModel、`@TempDir` 造 profiles 目录 |
| JPA 测试 | `@DataJpaTest` + 手工 `schema.sql` + SQLite（**不用** hibernate.ddl-auto=update） |
| 集成冒烟 | `@Tag("integration")`，surefire 默认排除，CI 跳过 |
| 端到端 | `oryxos-boot/src/test/`：`MockAgentE2ETest`、`MockProviderFlowTest`、`HumanTriggerFlowIT`、`SchedulerFlowIT`、`RestartRecoveryIT`、`ScheduledTaskE2ETest`、`LiveApiIT` |
| 运行 | `mvn clean verify`（Spotless → Checkstyle → compile → test → pmd/p3c → spotbugs+findsecbugs） |

测试类数量（`git ls-tree -r --name-only HEAD -- <m>/src/test | grep -c '\.java$'`）：
```
oryxos-core: 17   oryxos-tool: 14   oryxos-boot: 7   oryxos-web: 7
oryxos-storage: 6   oryxos-provider: 5   oryxos-memory: 5     共 61 个测试类
oryxos-cli: 0     oryxos-channel-cli: 0     ← CLI 模块零测试（靠 boot 层 E2E 覆盖）
```

### 6.4 验收报告：每节一份，六项证据 DoD

`specs/001-provider-abstraction/acceptance-report.md`（节选）：
```
# 第16节验收报告：Provider——对接大模型的统一入口
**日期**: 2026-07-09 | **分支**: `class-16` | **任务**: 20/20 完成（tasks.md 全勾）

## 六项证据 DoD
### 1. `mvn clean verify` 全绿 ✅
… 测试 23 个全过：
ProfileLoaderTest        6/6   (oryxos-core)
ProviderServiceTest      7/7   (oryxos-provider)
…
过程中被门禁拦下并修复：SpotBugs 6 项（CRLF 日志净化改为 replace(char,char) 形态、
getFileName() 判空）——安全规则未做任何排除。
```

这份报告把**「门禁拦下了什么、怎么修的、有没有走捷径」**全部留痕——是复刻时最值得抄的产物。

### 6.5 CI 与本地门禁

```yaml
# .github/workflows/ci.yml
# 注释明确写出顺序：spotless:check (validate) → checkstyle → compile → test → pmd/p3c → spotbugs+findsecbugs (verify)
- run: mvn -B clean verify
- name: dependency-check
  run: mvn -B clean verify -DskipTests -Dowasp.skip=false -DnvdApiKey="${NVD_API_KEY}"
```
```yaml
# .pre-commit-config.yaml —— 本地两道 Spotless hook，格式不过直接 fail commit
```

---

## 7. 结论

### 7.1 值得复刻的过程实践 Top 5

**① 先写宪法，再写代码 —— 并让宪法"活着"**
`.specify/memory/constitution.md` v1.1.0，8 条原则 + 4 条 NON-NEGOTIABLE，文件顶部保留 Sync Impact Record 记录每次变更的版本、理由、波及的模板。任何一次原则扩充都要「在对应特性 plan 中声明并同步 CLAUDE.md 与 TechnicalSolution」。
> YokeOS 做法：第一阶段先产出自己的 constitution（哪怕只有 5 条），并强制在文件头维护变更记录。**这是整个 SDD 体系的锚点，没有它，后面所有门禁都失去了仲裁标准。**

**② "课型分流"——不是每节都开 spec**
作者把 32 节分成 4 类课：代码课（11 节，走完整 7 步）、评审课（21/23，**拒绝产码**，只做下节的 specify 素材）、串联课（27/28，不开新 feature，只固化 E2E 测试）、Demo 课（31，只做真实运行+发布）。
> YokeOS 做法：照抄这张分流表。**避免"为了流程而流程"**——评审课和串联课如果强行开 spec，会产生大量低价值 artifact。

**③ 七道门禁 H0~H6，硬软二分，哲学一句话：「能机器判的绝不留给人，机器判不了的绝不自行发挥」**
- 硬门禁（测试/构建/存在性核对）不过不放行，不许绕；
- 软门禁（6 类情况）**立即停下报告、等确认**，不得自行发挥——特别是「需要创建'本节交付物'清单之外的任何对外概念」；
- **反作弊条款**：不得删断言、`@Disabled`、放宽阈值让测试变绿。
> YokeOS 做法：把这个哲学写进 CLAUDE.md / skill，并把 H4 六条全局不变量改成 YokeOS 自己的不变量。**这是对抗"每节局部合理、整体漂移"的唯一手段。**

**④ 每节一个 feature 分支 + 一次 squash 入库 + 一个 feat merge 做里程碑**
`class-N` 分支 1 commit + main 上 1 merge commit（`feat: 第N节：<课件标题>`）。git 历史因此**极其干净**：每个 merge commit 就是一节课的完整交付物，`git diff main@{n} main@{n+1}` 直接得到该节的全部产出，天然支持"前序节回归全绿"的 H2 门禁。
> YokeOS 做法：沿用。但**修正作者的三个瑕疵**——(a) 给 `class-17`/`class-28` 补上规范的 feat message；(b) 先配好 `git config user.name/email`；(c) 不再使用无信息量的 `dev` 作为 message。

**⑤ 测试任务显式排在实现任务之前 + 每节产出验收报告**
tasks.md 明文 `用户显式要求 harness 先行——每个实现任务的测试任务紧邻其前（红→绿）`；`acceptance-report.md` 记录六项证据 DoD，**包括"门禁拦下了什么、怎么修的、有没有走捷径"**（如 SpotBugs 6 项的修复，"安全规则未做任何排除"）。
> YokeOS 做法：tasks.md 模板里强制 "T00n 先写测试 / T00n+1 实现，使 T00n 全绿" 成对出现；每节产出一验收报告。**报告里的"被门禁拦下并修复"段落是流程有效性的最硬证据。**

---

**（次优先，同样值得抄）**
- **唯一权威设计源 + 冲突不自行仲裁**：`docs/TechnicalSolution.md` 是唯一权威，课件与它冲突时停下报告，**不得自行仲裁**。
- **语法禁区句**：`避开 P3C/ASM 解析不了的 Java 18+ 语法形态——静态检查是门禁的一部分，解析失败等于构建失败`。这是作者实踩后的教训，直接写进 plan 模板。
- **模块骨架一次画完**：9 个模块（含空壳类）在同一天、同一个 commit 里建齐，用 Maven 依赖方向锁死架构，避免后续模块间循环依赖。
- **`my-agent/` 早期出现**：第 3 个代码节就有真实 Agent 目录，持续吃自己的狗粮。
- **课件入库 + 课件即 spec 原料**：`docs/class/` 31 份课件在写代码**前一天**（07-09）一次性入库，第二天的每节课 specify 都从中取材——课件第一/二部分喂 specify，第三部分喂 plan，"本节交付物"喂 tasks 的核对清单，"验收 harness" 喂测试策略。

### 7.2 公开仓库观测不到的盲区清单

git 只记录了**结果**，以下**过程**需要用课程内容（课件原文、直播回放、作者讲解）补齐：

| # | 盲区 | 现有证据 | 需要课程补充什么 |
|---|------|---------|----------------|
| 1 | **完整的 AI 会话 prompt 与回复** | 只有 `docs/prompt/01.md`、`01-01.md`、`prompt.md` 三个文件 | 作者每节课实际喂给 Claude Code 的完整 prompt、追加了哪些澄清、模型失败了几次、怎么改口的。**这是最核心的盲区** |
| 2 | **`chore: update/sync … (N file(s))` 这个 commit message 生成器** | 仓库里 `grep -rl "file(s)"` 无命中（只有无关的 package.sh） | 是 git alias？Claude Code hook？还是作者手敲的脚本？它决定了"快照入库"这个动作的触发时机 |
| 3 | **第 1~15 节的真实过程** | 只有 11 个无信息量的 `dev` commit + 31 份课件 pptx/md | 前 15 节（认知力/选型力/方法论内功/工作流设计力/Agent 架构拆解/看懂 Agent OS）作者具体做了什么产出、怎么从需求走到 9 模块骨架 |
| 4 | **停点上的用户决策过程** | 只有 tasks.md 里的 `【停点已确认项】` 标记和宪法 Sync Impact Report 里一句「用户在第17节 tasks 停点批准」 | 每次软门禁停下时，作者问了什么、AI 给了几个选项、为什么选这个。**这是"人握方向"的具体形态** |
| 5 | **clarify 阶段的真实问答** | specs/*/checklists/requirements.md 有结构化结果 | 作者被问了几个问题、答案是怎么从课件里找出来的、找不到时怎么办 |
| 6 | **第 30/31 节与发布阶段** | 在 `origin/main`（第 65~299 个 commit）、`origin/class-31`、tag `v0.1.0~v0.1.3-RELEASE` | 需切到 `origin/main` 另行考古：第 31 节 Demo、0.1.1~0.1.3 的社区化阶段（PR #18/#62/#74、issue 模板、admin auth、MCP/Agent Skill 渐进加载等） |
| 7 | **commit 级 TDD 是否真发生过** | 只有 tasks.md 的排序指令 | 作者实际是不是先跑红再实现？有没有过"写完实现补测试"的回退？ |
| 8 | **spec/plan/tasks 的生成 prompt 模板细节** | `oryxos-lesson-dev/SKILL.md` 第 2~5 步给了骨架 | 实际跑 `/speckit-specify` 时喂的完整原文（课件第一/二部分是怎么提炼成"第16节需求"那段话的） |
| 9 | **作者的真实成本/耗时/token/工具版本** | 无 | 每节课实际花了多久、用了哪个模型、Claude Code 版本、有没有用 worktree/并行 |
| 10 | **失败的/被推翻的尝试** | 只有一处：`1b69aae` 删除 `specs/001-provider-react-loop/`（早期"三合一"试跑产物，12 个文件） | 还有哪些方案被推翻了、为什么。这是最有学习价值的部分，git 几乎不留痕 |
| 11 | **review/变更总结的真实输出** | skill 第 7 步规定"三段固定结构"（改动点/重点 review 清单/如何验证），但要求"直接输出在对话里，**不另开文件**" | 这些导读内容没有留档，只能从课件或回放看 |

---

## 8. 附：`origin/main` 上的第 30/31 节与发布阶段（超出 64 commit 范围）

```bash
git log --reverse --date=short --pretty=format:'%h|%ad|%s' 45fa211..origin/main | head -20
```

| hash | 日期 | 内容 |
|------|------|------|
| `963d4f1` | 07-18 | **feat: 第30节：动态管理 Agent 一句话生成、上传即上线**（= `45fa211` 的 merge） |
| `8f12f11` | 07-20 | chore: remove 80 file(s) in docs,website |
| `b09b2e5` | 07-20 | Merge pull request **#18** from oryx-labs/class-31 |
| `bd79e44` | 07-22 | chore: update …（75 文件）Makefile 首现 |
| `bb0e7ef` | 07-22 | **release: 第31节：… 发布、打包、第一个版本** = tag `v0.1.0-RELEASE` |
| `49df0ba` | 07-23 | feat(web): add admin console authentication (012-web-auth) |
| `e68bbc8` | 07-23 | release: 发布完善0.1.1 版本 = tag `v0.1.1-RELEASE` |
| `454b2c0` | 07-24 | chore: add bug_report & feature_request issue templates |
| `90176d8` | 07-24 | fix(bin): start.sh 静默退出——find 代替 ls\|head |
| `fbe8c87` | 08-10 | docs: 补全 CHANGELOG 0.1.2 段并触发发布 (#74) = tag `v0.1.2-RELEASE` |
| `23f3ba1` | 08-18 | release: 0.1.3 = tag `v0.1.3-RELEASE` |

可见课程在 07-22 结束（第 31 节 + v0.1.0），之后进入**社区化阶段**：GitHub Flow（PR + issue 模板 + CHANGELOG）、admin 认证、Agent Skill 渐进加载、Provider baseUrl 兼容、start.sh 健壮性等真实运维问题。这一阶段已经是标准的开源项目协作流程，不再是"逐节 SDD"。

---

## 9. 复刻验证命令速查

```bash
cd vendors/oryxos

# 完整 64 commit 时间线
git log --reverse --date=format:'%Y-%m-%d %H:%M' --pretty=format:'%h|%ad|%p|%s'

# 所有 lesson commit（含 merge 关系）
git log --all --reverse --pretty=format:'%h|%p|%d|%s' --grep='第.*节'

# 所有 merge commit（feat 都是 merge）
git log --merges --date=format:'%m-%d %H:%M' --pretty=format:'%h %p %ad %s'

# 证明 feat merge 与前面的 chore 快照树完全一致
git diff --name-only c21d55f ed92220 | wc -l        # 0

# 每个模块首次出现 Java 源码
for m in oryxos-boot oryxos-channel-cli oryxos-cli oryxos-core oryxos-memory \
         oryxos-provider oryxos-storage oryxos-tool oryxos-web; do
  git log --all --reverse --diff-filter=A --format='%h %ad %s' --date=short -- "$m/src/main/java" | head -1
done

# 每个spec 首次出现（与实现同 commit）
for d in specs/*/; do git log --all --reverse --diff-filter=A --format='%h %ad' --date=short -- "$d/spec.md" | head -1; done

# 第一个测试文件
git log --all --reverse --diff-filter=A --format='%h %ad %s' --date=short -- '*src/test/*' | head -1

# 单节变更规模
git show --stat c21d55f | tail -3

# 宪法与 spec-kit
cat .specify/memory/constitution.md
cat .specify/workflows/workflow-registry.json
cat .claude/skills/oryxos-lesson-dev/SKILL.md
```
