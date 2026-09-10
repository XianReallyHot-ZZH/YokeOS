<!--
Sync Impact Report
- Version change: (none) → 1.0.0  (initial ratification)
- Bump rationale: First formal adoption of the YokeOS constitution. MAJOR baseline.
- Principles defined (9):
    1. 自实现 ReAct 循环
    2. Spring AI 只用两件事 ⚠️（最容易被写错的一条）
    3. Provider 显式映射
    4. 同步执行 + 虚拟线程
    5. Tool 三合一
    6. Sandbox 接口先行
    7. SQLite + MEMORY.md，审计 day one
    8. 一个目录 = 一个 Agent
    9. 结构照抄，瑕疵不继承
- Added sections: 技术栈与架构约束; 开发流程与质量门禁; Governance
- Removed sections: none (initial)
- Templates checked:
    ✅ .specify/templates/plan-template.md   (Constitution Check gate is dynamic; no edit needed)
    ✅ .specify/templates/spec-template.md   (no constitution-specific tokens)
    ✅ .specify/templates/tasks-template.md  (no constitution-specific tokens)
- Follow-up TODOs: none
-->

# YokeOS Constitution

YokeOS 是用 Java 实现的面向企业场景的 Agent 底座（Agent Harness OS）：装在企业自己的基础设施上，
作为统一底座运行多个业务 Agent。本宪法定义九条不可违背的工程铁律，凌驾于一切个人偏好与临时
便利之上；所有代码、spec、plan 与实现必须遵守，原则冲突时以本文件为准。`CLAUDE.md` 是本宪法
的运行时版，两者双向一致。

## Core Principles

### 1. 自实现 ReAct 循环

`ReActLoop` MUST 由项目自己实现，MUST NOT 使用 Spring AI 的 Agent 抽象。核心循环约数十行
Java，完整掌握工作机制——循环行为（迭代上限、终止条件、上下文截断）必须可掌握、可定制。

**Rationale**: Agent 的核心竞争力在运行机制本身；交出去丧失控制权。

### 2. Spring AI 只用两件事 ⚠️（最容易被写错的一条）

Spring AI（含 Spring AI Alibaba）在 YokeOS 中只允许做两件事：(1) LLM Provider 的协议转换；
(2) `@Tool` 注解的 JSON Schema 生成。MUST 禁用自动 tool 执行与 eager 自动装配。调用方式
MUST 是 `chatModel.call(new Prompt(messages, options))`，返回的 tool call 由项目自己解析、
自己执行、自己回填。

**Rationale**: 最容易被写错的一条——启用自动执行会导致 tool 被调两次。

### 3. Provider 显式映射

多 Provider 并存时 MUST 维护 `provider name → ChatModel` 的显式映射；MUST NOT 做容器类型
扫描来区分 Provider。

**Rationale**: Bean 类型相同，类型扫描路由错乱。

### 4. 同步执行 + 虚拟线程

全程 MUST 同步阻塞，靠 Java 21 虚拟线程处理并发。MUST NOT 引入 Reactor / WebFlux /
`CompletableFuture` 等异步编程模型（唯一例外：第 25 节 `ThreadPoolTaskScheduler` 调度线程池）。

**Rationale**: 虚拟线程已够；异步让复杂度激增。

### 5. Tool 三合一

`YokeTool` MUST 作为所有 Tool 的统一抽象。内置 Tool、MCP Client、`ToolRegistry`、Sandbox、
Notify MUST 合并在 `yokeos-tool` 一个模块，MUST NOT 拆分。

**Rationale**: 共享同一抽象与注册表，拆开依赖混乱。

### 6. Sandbox 接口先行

`Sandbox.enforce(action)` 接口 MUST NOT 携带任何一档实现特有的概念。第一阶段只填
`WhitelistSandbox` 一档实现（路径 / 命令 / 域名三重白名单，校验真实路径）。MUST NOT 使用
`SecurityManager`（JDK 21 已不可用）。

**Rationale**: 未来换重隔离只加实现类，不改调用方。

### 7. SQLite + MEMORY.md，审计 day one

审计两表（`tool_invocations` / `llm_calls`）MUST 从第 16 节起写入落库，MUST NOT 以
「日志够了」为由推迟。表结构演进 MUST NOT 走 `ddl-auto=update`，MUST 手工维护建表脚本。

**Rationale**: 「日志够了」是假省——反解析返工成本更高。

### 8. 一个目录 = 一个 Agent

`AGENT.md` frontmatter MUST 经 `AgentLoader.deriveProfile()` 派生 Profile；正文与所引用
Skill 的正文 MUST 注入 system prompt。Skill MUST NOT 进 `ToolRegistry`、正文 MUST NOT 预载；
附属资源经既有工具按需取用。

**Rationale**: 配置即 Agent；Skill 是上下文资源，不是可执行 Tool。

### 9. 结构照抄，瑕疵不继承

九模块边界与依赖方向 MUST 镜像参照实现（oryxos）。参照已知工程瑕疵（如 CLI 入口零测试）
MUST 补上，MUST NOT 继承。

**Rationale**: 逐节对照是第一阶段使命；照抄的是设计，不是疏漏。

## 技术栈与架构约束

- 语言 / 运行时：Java 21（虚拟线程处理并发）；框架 Spring Boot 3.5.16（与参照逐节可比的
  刻意选择，升级 Boot 4 + Spring AI 2.0 列扩展阶段）。
- LLM 调用：Spring AI 1.1.8 + Spring AI Alibaba，仅用协议转换 + `@Tool` schema 生成（原则 2）。
- HTTP：Spring MVC + 虚拟线程；命令行 Picocli 4.7.6；YAML 解析 SnakeYAML。
- 持久化：SQLite（sqlite-jdbc 3.53.2.1）+ Spring Data JPA；MCP：MCP Java SDK。
- 日志：Logback + SLF4J 结构化 JSON，MUST NOT 使用 `System.out`。
- 管理台：Vue 3 + Vite，经 frontend-maven-plugin（Node v20.18.0）构建，第 26 节落地；
  API 文档 springdoc-openapi 2.6.0。
- 构建：Maven 多模块（groupId `com.yokeos`），fat JAR 单二进制部署。
- 九个模块的边界以 `CLAUDE.md` 模块结构表为准；跨模块契约（接口 + 值对象）MUST 放
  `yokeos-core`，由下游模块实现（依赖倒置），模块间 MUST NOT 循环依赖。
- 新增 Channel 或 Tool MUST 只加新模块，MUST NOT 改 `yokeos-core`。
- 代码注释中文为主，技术术语保留英文原词。

## 开发流程与质量门禁

- 采用 Spec-Driven Development：constitution → specify → (clarify) → plan → tasks →
  (analyze) → implement。一次只推进一节（第 16→31 节，课型分流：代码 / 评审 / 串联 / Demo）。
- 质量门禁 MUST 全绿方可合并：Spotless + 阿里 P3C + Checkstyle + SpotBugs / Find Security
  Bugs + PMD + OWASP Dependency-Check，全部接入 `mvn verify`；pre-commit 本地把关；
  CI 任一检查失败即阻断合并。
- 敏感配置 MUST 一律 `${ENV_VAR}` 占位、从环境变量解析，MUST NOT 明文写死；`ConfigLoader`
  启动校验必填项与格式，缺失或非法 MUST 清晰报错，MUST NOT 静默失败。

## Governance

- 本宪法凌驾于其它一切实践与个人偏好之上；原则冲突时以本文件为准。
- 写一次定下来，整个第一阶段不改。中途发现某条原则不对，停下来重新讨论——AI agent
  MUST NOT 自行修改宪法，修改宪法属设计变更。
- 修订走显式流程：语义化版本（MAJOR = 原则删除 / 重定义；MINOR = 新增原则或条款；
  PATCH = 措辞澄清）；修订 MUST 说明动机与影响；文件头 MUST 维护 Sync Impact Report。
- 修订 MUST 同步所有受影响文档：同步清单 MUST 包含 `docs/AiProgrammingGuide.md` 与
  `CLAUDE.md`——只改宪法不改下游文档，等于没改。
- `CLAUDE.md` 是本宪法的运行时版，两者 MUST 双向一致、不得矛盾。
- 合规审查：节级工作流的 H 系门禁与每节验收报告 MUST 核对实现是否符合本宪法；违背宪法
  条款的偏差 MUST 显式论证并经停点确认，不得静默通过。

**Version**: 1.0.0 | **Ratified**: 2026-09-10 | **Last Amended**: 2026-09-10
