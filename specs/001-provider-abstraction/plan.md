# Implementation Plan: Provider——对接大模型的统一入口（第16节）

**Branch**: `specs/001-provider-abstraction` | **Date**: 2026-09-10 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-provider-abstraction/spec.md`

## Summary

第 16 节交付 LLM 调用的统一入口：`AGENT.md` frontmatter 经 `AgentLoader` 派生 `Profile` 并注册；实例级 provider 清单显式映射到 `ChatModel`；上层经 `chat(sessionId, Profile, Prompt)` 一次调用，只翻译工具 schema 不执行（禁自动执行）；每次调用成败双路落审计表 `llm_calls`。技术路径：Spring AI 1.1.8 之上的薄包装（协议转换复用 Spring AI Alibaba/OpenAI 兼容 connector），SQLite 手工建表，全程同步阻塞 + 虚拟线程。

## Technical Context

**Language/Version**: Java 21（虚拟线程处理并发）

**Primary Dependencies**: Spring Boot 3.5.16；Spring AI 1.1.8 + Spring AI Alibaba（仅协议转换 + `@Tool` schema 生成，禁自动 tool 执行与 eager 自动装配——宪法 2）；DeepSeek / Kimi 经 OpenAI 兼容 starter 接入（见 [research.md](./research.md) D1）；SnakeYAML（frontmatter 解析）；sqlite-jdbc 3.53.2.1 + Spring Data JPA；Picocli（`yokeos init`）

**Storage**: SQLite——手工建表脚本 `schema.sql`（`llm_calls` 含 `success`/`error_message` + `tool_invocations` 两表），禁 `ddl-auto=update`（宪法 7）；文件系统——`.yokeos/` 工作区与 `AGENT.md`

**Testing**: JUnit 5 + Mockito 单测主体（mock `ChatModel`）：`AgentLoaderTest` / `ProviderServiceTest` / `ToolSchemaAdapterTest` / `LlmCallRepositoryTest`；`ProviderSmokeIT` 打 `@Tag("integration")`（CI 与本地默认跳过）；测试方法名英文、参照语义以 `@DisplayName` 保留；`LlmCallRepositoryTest` 建表执行 `schema.sql` 手工脚本。完成定义 = `mvn clean verify` 全绿

**Target Platform**: JVM（macOS + Windows 双平台开发环境）

**Project Type**: Maven 多模块 library + cli（本节触四个模块：yokeos-core / yokeos-provider / yokeos-storage / yokeos-cli）

**Performance Goals**: 按需求文档 §8 全局口径（单节点 ≥10 Agent、≥100 并发 Session、内部转发开销 ≤50ms），本节不设独立指标；同步阻塞 + 虚拟线程承载（宪法 4）

**Constraints**: 宪法 2（禁自动执行与 eager 自动装配）、宪法 3（显式映射不做类型扫描）、宪法 4（无 Reactor / WebFlux / CompletableFuture）、宪法 7（凭证 `${ENV_VAR}` 占位、审计 day one、手工建表）；语法禁区——避开 P3C/ASM 解析不了的 Java 18+ 语法形态（如增强 switch 的 `default ->` 写法）；**API 代差**——参照课程期 Boot 3.3.5 / Spring AI 1.0.0-M6，本仓 3.5.16 / 1.1.8，`ChatModel` 调用与自动执行关闭的确切写法以本地依赖核实为准（H3：核实不到不写）

**Scale/Scope**: 实例级 provider 清单个位数；Agent 数个位数起步；审计表只写不查（查询接口扩展阶段）

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | 原则 | 本节落点 | 状态 |
|---|------|---------|------|
| 1 | 自实现 ReAct 循环 | 本节不实现循环、不引入任何 Agent 抽象 | ✓ |
| 2 | Spring AI 只用两件事 | 仅协议转换 + schema 生成；`chat` 路径禁自动执行与 eager 自动装配，回归测试钉死 | ✓ |
| 3 | Provider 显式映射 | `Map<String, ChatModel>` 显式建表，不做容器类型扫描，路由测试钉死 | ✓ |
| 4 | 同步执行 + 虚拟线程 | `chat` 全程同步阻塞，无异步模型 | ✓ |
| 5 | Tool 三合一 | 本节不建 `yokeos-tool` 内容；`YokeTool` 最小接口落 core（见 research D4），完整体系归第 20 节 | ✓ |
| 6 | Sandbox 接口先行 | 本节无涉外 IO 执行（LLM HTTP 由 connector 承担）；Sandbox 归第 24 节 | ✓ |
| 7 | SQLite + 审计 day one | `llm_calls` 即建即写（含 `success`/`error_message`）；`tool_invocations` 建表；手工 `schema.sql` | ✓ |
| 8 | 一个目录 = 一个 Agent | `AgentLoader` 派生 `Profile`；`AGENT.md` 归 core 加载体系，绝不进 ToolRegistry | ✓ |
| 9 | 结构照抄，瑕疵不继承 | 模块落位镜像参照第 16 节；例外一处——审计契约接口按 YokeOS 成文规则上移 core（见 research D3），属「照抄的是设计不是疏漏」 | ✓ |

Phase 1 设计后复检：无新增违规（见 Complexity Tracking——空）。

## Project Structure

### Documentation (this feature)

```text
specs/001-provider-abstraction/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md        # Phase 1 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
├── contracts/           # Phase 1 output (/speckit-plan command)
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
yokeos-core/src/main/java/com/yokeos/core/
├── profile/
│   ├── Profile.java              # frontmatter 全字段记录类
│   ├── AgentLoader.java          # 扫 .yokeos/agents/，deriveProfile + 启动校验 provider 名
│   └── ProfileRegistry.java      # Map<String, Profile> 内存索引
├── tool/
│   └── YokeTool.java             # 最小接口（getName/getDescription/getInputSchema），第 20 节扩展
└── audit/
    └── LlmCallAuditor.java       # 跨模块契约接口（YokeOS 规则：契约放 core，见 research D3）

yokeos-provider/src/main/java/com/yokeos/provider/
├── ProviderService.java          # chat(sessionId, Profile, Prompt)：映射取模 → 调用 → 双路审计
├── ToolSchemaAdapter.java        # YokeTool schema → Spring AI 工具描述（只翻译不执行）
├── ProviderNotFoundException.java
└── ProvidersProperties.java      # yokeos.providers 清单绑定（name / api-key / base-url）

yokeos-storage/src/main/java/com/yokeos/storage/
├── LlmCall.java                  # JPA 实体（含 success / error_message）
├── LlmCallRepository.java
└── JpaLlmCallAuditor.java        # LlmCallAuditor 实现（依赖倒置）
yokeos-storage/src/main/resources/
└── schema.sql                    # llm_calls + tool_invocations 手工建表

yokeos-cli/src/main/java/com/yokeos/cli/
└── InitCommand.java              # yokeos init（幂等，Bootstrap 最小占位模板）

测试：各模块 src/test 对应五测试类；ProviderSmokeIT 落 yokeos-provider
```

**Structure Decision**: 九模块既有骨架内落位，本节触 core / provider / storage / cli 四模块（另触 `yokeos-boot` 的 `application.yaml`——仅补 `yokeos.providers` 示例配置段，无代码改动，analyze F1）。依赖方向：provider → core（消费 Profile 与契约接口）、storage → core（实现契约接口）、cli 独立文件操作（init 不启 Spring）；core 不依赖任何下游。镜像参照第 16 节，唯一例外：审计契约接口由参照的 provider 模块上移至 core（YokeOS 宪法级模块规则优先，research D3）。

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

无违规，无条目。
