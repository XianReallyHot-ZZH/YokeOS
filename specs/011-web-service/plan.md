# Implementation Plan: Web Service 与管理台第一版——对外门面（第26节）

**Branch**: `specs/011-web-service` | **Date**: 2026-09-21 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/011-web-service/spec.md`

## Summary

第 26 节交付对外门面：REST 11 个端点随 `yokeos serve` 接线（会话 5 含列表补位、invoke 1、信息查询 3、系统状态 2；统一信封 + 统一异常出口 + OpenAPI 文档），以及 Web 管理台第一版（`/admin` Vue 3 只读五页，与 REST 同端口同进程托管，SPA 服务端回落）。技术路径：Spring MVC + 虚拟线程之上的薄 Controller 层（参数校验 / 响应包装 / 兜错三件事之外不碰），发消息与 invoke 复用 17 节编排入口（人推三入口同一引擎）；core 三小扩（会话列表 / 归档 / 记忆全文读，无表结构变更）；前端经 frontend-maven-plugin 进 Maven 构建链，一条命令出全量 fat JAR。

## Technical Context

**Language/Version**: Java 21（虚拟线程，boot 已开 `spring.threads.virtual.enabled`）；前端 Vue 3 + Vite（Node v20.18.0 经 frontend-maven-plugin 1.15.1 构建，CLAUDE.md 技术栈表钉版）

**Primary Dependencies**: Spring Boot 3.5.16（`spring-boot-starter-web` 已在 yokeos-web）；springdoc-openapi 2.6.0（已在，本节首次真实消费——Swagger UI）；**新增模块依赖一处**：yokeos-web → yokeos-tool（`ToolRegistry` 是含 MCP 注册的运行时真相源，见 [research.md](./research.md) D6）；无 Spring AI 新触点（宪法 2 无新落点，eager 装配坑 16 节已化解，见 D9）

**Storage**: 无新表、无表结构变更（宪法 7 手工脚本不动）——`sessions` 表（18 节）既有 `status` / `archived_at` / `last_active_at` 列本节启用读写；SQLite 并发由 18 节 busy_timeout 承接

**Testing**: 三层——`@WebMvcTest` 切片单测主体（mock AgentService / SessionManager / ProfileRegistry / ToolRegistry / MemoryService，显式声明被测 Controller）+ 存储层单测（临时 SQLite / 临时目录，形态照 18/22 节）+ `@Tag("integration")` 冒烟（`@SpringBootTest` 真上下文不依赖模型，`@Primary` 覆盖 tools Bean 自备白名单——24 节坑同款）；11 个测试类清单与坑↔回归对应见 spec 验收标准与教学文档第四部分；测试方法名英文 camelCase 避连续大写、中文语义进 `@DisplayName`；完成定义 = `mvn clean verify` 九模块全绿（验收至少一次不带 `-Dfrontend.skip=true`）

**Target Platform**: JVM（fat JAR 内嵌管理台静态资源，macOS + Windows 双平台开发环境）

**Project Type**: Maven 多模块 web-service（本节触 yokeos-core / yokeos-memory / yokeos-storage / yokeos-web 四模块代码 + 仓根 `.gitignore` + 文档同步三处 + 项目内 skill 一个；yokeos-cli 零代码改动）

**Performance Goals**: 承接需求文档 §8 全局口径（单节点 ≥10 Agent、≥100 并发 Session），本节不设独立指标；虚拟线程承载（宪法 4），人工项含并发冒烟

**Constraints**: 宪法 4（全程同步阻塞，无 WebFlux / CompletableFuture，60s 硬中断不做——research D10）；宪法 9（结构照抄参照 26 节 + 两处瑕疵不继承：invoke 固定三元组、`@Qualifier Map` 快照——research D3/D6）；语法禁区——避开 P3C/ASM 解析不了的 Java 18+ 语法形态，测试类名 ≤1 连续大写（冒烟类 …IntegrationTest 不用 …IT），日志消息必须编译期常量（CRLF 门禁）动态内容进异常堆栈；管理台构建期下载 Node（CI/新机首跑慢属预期）

**Scale/Scope**: 11 端点 + 管理台五页；内网单实例、无认证假设；Agent 写侧 6 端点与工作区 2 端点明确不在本节（29/30 正题）

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | 原则 | 本节落点 | 状态 |
|---|------|---------|------|
| 1 | 自实现 ReAct 循环 | 零循环改动；Web 消息与 invoke 复用 17 节 `AgentService.process` 同一入口，测试钉「恰调一次」 | ✓ |
| 2 | Spring AI 只用两件事 | 本节无 Spring AI 新触点；eager 装配坑 16 节已用裸依赖化解——serve 无需任何 `autoconfigure.exclude`（research D9，与参照课件的差异点） | ✓ |
| 3 | Provider 显式映射 | 不触 Provider 层；`/info` 的 Provider 名单读 ProfileRegistry（已配置口径） | ✓ |
| 4 | 同步执行 + 虚拟线程 | 虚拟线程 boot 已开；全链同步阻塞，无异步栈；504 仅口径占位、真实超时由 provider 层承载（research D10） | ✓ |
| 5 | Tool 三合一 | `ToolRegistry` 仅消费不拆分；yokeos-web → yokeos-tool 是下游消费依赖，非模块拆分 | ✓ |
| 6 | Sandbox 接口先行 | 本节无新执行路径：HTTP 入站请求不属 Sandbox 四类 ActionType 管辖（出站工具调用仍走既有链路），白名单语义不变 | ✓ |
| 7 | SQLite + 审计 day one | 无新表无迁移（sessions 既有列启用）；审计两表随 process 链路已有，Web 入口天然继承 | ✓ |
| 8 | 一个目录 = 一个 Agent | 不触 AgentLoader；invoke 先查 ProfileRegistry 属读注册表，非新配置概念 | ✓ |
| 9 | 结构照抄，瑕疵不继承 | 镜像参照 26 节（六 Controller / 信封复用 / 异常扩展 / WebConfig 托管 / frontend-maven-plugin / 风格 skill）；不继承两处：invoke 固定三元组共享历史（改一次性会话，D3）、web 模块绕开 tool 依赖的 Map 快照（改直依真相源，D6） | ✓ |

Phase 1 设计后复检：无新增违规（见 Complexity Tracking——空）。

## Project Structure

### Documentation (this feature)

```text
specs/011-web-service/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md        # Phase 1 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
├── contracts/           # Phase 1 output (/speckit-plan command)
│   └── rest-api.md      # 11 端点 + 信封 + 错误码 + /admin 托管行为
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
yokeos-core/src/main/java/com/yokeos/core/
├── session/
│   ├── SessionManager.java         # 接口补 listRecent(int) / archive(String)（本节声明改造点，拍板⑤）
│   ├── SessionSummary.java         # 新 record：sessionId/agentName/channel/userId/status/lastActiveAt
│   └── InMemorySessionManager.java # 同步补两实现（测试用）
└── memory/
    └── MemoryService.java          # 接口补 readAll()

yokeos-memory/src/main/java/com/yokeos/memory/
├── LongTermMemoryStore.java        # 三档后端契约补 readAll()（以 22 节现有契约为准，H3）
├── MarkdownMemoryStore.java        # 回 MEMORY.md 原文（两分区原貌）
├── SqliteMemoryStore.java          # 按注入同口径拼装
├── Mem0MemoryStore.java            # 与 buildContext 同源
└── MemoryServiceImpl.java          # readAll 透传

yokeos-storage/src/main/java/com/yokeos/storage/
└── JpaSessionManager.java          # listRecent（last_active_at 倒序 + 上限）/ archive（置 status + archived_at，未命中 false）

yokeos-web/src/main/java/com/yokeos/web/
├── GlobalExceptionHandler.java     # 扩展：+领域 404 / +显式 503 / +504；既有四映射与 sanitize 纪律不动
├── WebConfig.java                  # /admin redirect→forward→回落三件套 + 两档缓存 + CORS 全开
├── controller/
│   ├── SessionApiController.java   # 5 端点（create/send/list/history/archive）
│   ├── AgentApiController.java     # 仅 POST /{name}/invoke（javadoc 注明 29/30 扩展位）
│   ├── ProfileApiController.java   # GET /profiles
│   ├── MemoryApiController.java    # GET /memory
│   ├── ToolApiController.java      # GET /tools
│   └── SystemApiController.java    # GET /health + GET /info
├── controller/dto/                 # CreateSessionRequest/MessageRequest/MessageResponse/
│                                   #   SessionView/SessionSummaryView/ProfileView/ToolView/InfoView（record）
└── error/                          # SessionNotFound/ResourceNotFound/ProviderUnavailable/AgentTimeout 四异常

yokeos-web/src/main/frontend/       # Vue 3 + Vite 工程（package.json/vite.config.js/index.html/
│                                   #   src/App.vue 五页单文件/src/main.js/src/styles/tokens.css）
└── （vite base '/admin/'，build.outDir → ../resources/static/admin）

yokeos-web/pom.xml                  # + yokeos-tool 依赖 + frontend-maven-plugin 三 execution
                                    #   （generate-resources：install-node-and-npm / npm install / npm run build）
                                    #   + frontend.skip 属性（默认 false）
.gitignore                          # + node_modules/ + 前端构建产物

.claude/skills/yokeos-admin-ui/SKILL.md   # 管理台风格与工程约定 skill（拍板③，30 节复用）

文档同步（拍板①②随本节落）：
├── docs/TechnicalSolution.md       # §13 行 26 改分节口径；§7.2 补 GET /api/v1/sessions（18→19）
├── docs/DemandAnalysis.md          # §5.10 端点表补列表端点行（18→19）
└── CLAUDE.md                       # Web Service API 节端点数 18→19
```

**Structure Decision**: 九模块既有骨架内落位，代码触 core / memory / storage / web 四模块；依赖方向：web → core（编排入口/注册表/会话/记忆契约）+ **web → tool（本节新增，ToolRegistry 真相源，research D6）** + web → storage（既有）；memory / storage 实现 core 契约（依赖倒置）；无循环依赖；cli 零改动（serve 已交付，REST 随上下文自动接线，javadoc 顺手更新）。镜像参照第 26 节模块形态（controller/dto/error/config 分包、frontend 工程内嵌 web 模块、产物 static/admin）。

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

无违规，无条目。
