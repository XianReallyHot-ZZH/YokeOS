# Implementation Plan: 动态管理——一句话生成、上传即上线（第30节）

**Branch**: `specs/015-dynamic-management` | **Date**: 2026-09-22 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/015-dynamic-management/spec.md`

## Summary

第 29 节立住「一个目录 = 一个 Agent」与运行时注册原语，本节补最后入口：8 个新端点（Agent 动态管理 6 + 工作区只读浏览 2，19 端点收口）+ `WorkspaceWatcher` 实时监听 + 管理台 Agent 管理页与工作区页。技术路线：**编排者 + 监听器 + 目录管家三新类落 `yokeos-core` agent 包**（参照钉版树同位），API create 与 Watcher 事件汇到同一段 `AgentLifecycleService.register(agentDir)`（防重收口：先注销同名旧定时再注册，FR-016）；generate 走既有 `ProviderService.chat` 一次性调用（独立配置键 `yokeos.agent-generation.provider/.model`，缺失不阻断启动、调用时 503 不静默回退）；删除按「注销定时 → 移出索引 → 归档 `.yokeos/archive/`」时序；工作区浏览防目录穿越（normalize + startsWith）。零新表（generate 落既有 llm_calls）、零新 Maven 依赖（WatchService 是 JDK 内置）。

## Technical Context

**Language/Version**: Java 21（虚拟线程；Watcher 监听线程是基础设施守护线程，宪法 4 由 25 节确立的例外口径）

**Primary Dependencies**: Spring Boot 3.5.16（MVC）+ Spring AI 1.1.8（仅经既有 `ProviderService.chat` 协议转换，本节零直接 Spring AI API 面）+ `java.nio.file.WatchService`（JDK 内置）+ Vue 3 + Vite（frontend-maven-plugin 1.15.1 / Node v20.18.0，26 节钉版）

**Storage**: 既有 SQLite 零变更；文件系统是本节主存储面——`.yokeos/agents/`（唯一真相源）+ `.yokeos/archive/`（归档，按需建）；配置键 `yokeos.agent-generation.provider` / `.model` 落 yokeos-boot `application.yaml`（Spring Environment 绑定，`@Value` 缺省空串）

**Testing**: JUnit 5 + Mockito（`InOrder` 钉时序、异常注入钉回滚；primitive matcher 用 `anyBoolean()/anyLong()`——25 节坑）+ `@WebMvcTest` 切片（`WebSliceTestBoot` 引导 + `@Import` + `@MockBean`）+ `@Tag("integration")` 显式触发（真上下文免重启闭环 + 真丢目录轮询断言 + generate 真模型 `assumeTrue`）

**Target Platform**: JVM（`yokeos serve` 同进程内）；管理台 `/admin` SPA 同端口托管

**Project Type**: Maven 多模块企业单体（九模块），本节触及 yokeos-core / yokeos-web / yokeos-cli / yokeos-boot 四模块 + 前端工程

**Performance Goals**: 丢目录到列表可见 ≤10 秒（SC-002，Watcher 实时性）；create 返回后立即可查（免重启）；其余承需求 §8 既有口径，本节不新增指标

**Constraints**: 全程同步请求链路（宪法 4）；错误码沿用 `GlobalExceptionHandler` 既有映射零扩展（400/404/503）；单条消息 32KB 上限口径沿用；日志编译期常量 + 动态值进异常堆栈（CRLF 门禁）

**Scale/Scope**: 单节点 ≥10 Agent（需求 §8 既有）；AgentView 列表全量返回（第一阶段规模内不做分页，技 §7.3 分页归扩展位）

**写前已核实的代码事实**（H3 纪律，2026-09-22）：
- `ProviderRequest` 是 12 行 record（`promptText` + `availableTools`），**无 `of` 静态工厂**——紧凑构造器 null 工具清单缺省空清单，generate 用 `new ProviderRequest(prompt, null)`。
- `Profile` 是 12 参 record（name/description/identity/provider/tools/skills/mcpServers/channels/notifyChannels/schedules/bootstrap/settings），紧凑构造器 null 容全缺省——generate 临时 Profile 用 `new Profile("agent-generation", null, null, new Profile.ProviderConfig(genProvider, genModel, null), null×7, null)`。
- `YokeosRuntime`（yokeos-cli）是 `@SpringBootApplication(scanBasePackages="com.yokeos")` 显式 `@Bean` 装配风格，workspace root 取 `@Value("${yokeos.root:.yokeos}")` + 私有 `workspace()`——三新 Bean 挂这里同源取 root。
- `AgentScheduler.registerProfile` 对同 taskId 是 `scheduledTasks.put` 覆盖句柄**不 cancel 旧排期**（25 节实现核实）——FR-016 防重收口在编排者的直接动因。
- `AgentApiController` 既有 invoke 端点（26 节），类级 `@SuppressFBWarnings` 含 `EI_EXPOSE_REP2` justification 先例；`ResourceNotFoundException` 在 `com.yokeos.web.error`。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | 原则 | 本节落点 | 状态 |
|---|------|---------|------|
| 1 | 自实现 ReAct 循环 | 不触循环；generate 是**一次性单轮 LLM 调用**（无 tool、无迭代），不经过也不需要 ReActLoop | ✓ |
| 2 | Spring AI 只用两件事 | generate 经既有 `ProviderService.chat`（协议转换层），本节零直接 Spring AI API 面、零新 starter；无自动 tool 执行面（generate 不带工具） | ✓ |
| 3 | Provider 显式映射 | 生成用 provider 走 `yokeos.agent-generation.provider` 指向**已注册 provider name**（复用既有显式映射表路由），无类型扫描；缺配置 503 不静默回退（技 §3.3） | ✓ |
| 4 | 同步执行 + 虚拟线程 | 请求链路（create/update/delete/generate/tree/file）全程同步；Watcher 监听循环是**基础设施守护线程**（25 节 `ThreadPoolTaskScheduler` 同类例外口径），Spring 管理执行器承载、destroyMethod 关停，不引入 Reactor/CompletableFuture | ✓ |
| 5 | Tool 三合一 | 不触 `yokeos-tool`；本节零新 Tool（generate 不是 Tool、Agent 目录不是 Tool） | ✓ |
| 6 | Sandbox 接口先行 | 无新涉外执行面；workspace file 端点自校验防穿越（resolve→normalize→startsWith root，技 §11.3 钉版形态），越界 400 | ✓ |
| 7 | SQLite + 审计 day one | 零新表零 schema 变更；generate 落既有 `llm_calls`（sessionId 前缀 `agent-generation`，成败都落账）；create/update/delete 无 LLM 调用无审计事件（目录文件操作不属审计两表口径） | ✓ |
| 8 | 一个目录 = 一个 Agent | **主条之一**：动态管理不改定义形态——AGENT.md 仍经 `deriveProfile` 派生、正文仍经 ContextLoader 注入；Skill 不进 ToolRegistry；Agent 目录不进 Tool 体系；`.yokeos/agents/` 唯一真相源、三录入同源（FR-011/016） | ✓ |
| 9 | 结构照抄，瑕疵不继承 | 三新类镜像参照钉版树同位（core/agent 包 + 测试同构）；四处显式偏差记录于 research：①不采参照第五部分演化（拍板①）②create/PUT 收 AGENT.md 全文（拍板②）③register 防重收口（FR-016，参照无显式处理）④教学文档/javadoc 记参照坑「WatchService 不监听子目录内文件改动」为边界依据 | ✓ |

Phase 1 设计后复检：无新增违规（见 Complexity Tracking——空）。

## Project Structure

### Documentation (this feature)

```text
specs/015-dynamic-management/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md        # Phase 1 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
├── contracts/           # Phase 1 output (/speckit-plan command)
│   └── java-api.md      # REST 端点 + core 公共方法契约
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
yokeos-core/src/main/java/com/yokeos/core/agent/
├── AgentLifecycleService.java   # 新：register(防重收口)/create(回滚)/update(先注销后注册)/
│                                #   delete(时序)/unregisterByDir/generate(配置校验→chat→剥围栏→解析校验)
├── WorkspaceWatcher.java        # 新：WatchService 监听循环（Spring 执行器承载）+ handleChange 包级可见
└── AgentStore.java              # 新：write(name, markdown)/archive(name 重名时间戳)/delete(agentDir)

yokeos-core/src/test/java/com/yokeos/core/agent/
├── AgentLifecycleServiceTest.java   # 新（全 mock 协作者 + InOrder + 异常注入）
├── WorkspaceWatcherTest.java        # 新（handleChange 直调三用例）
└── AgentStoreTest.java              # 新（@TempDir 真文件操作）

yokeos-web/src/main/java/com/yokeos/web/controller/
├── AgentApiController.java          # 扩 5 端点（generate/create/get 单个与列表/put/delete；invoke 不动）
└── WorkspaceApiController.java      # 新（第 7 个 Controller：tree/file 只读 + 防穿越）
yokeos-web/src/main/java/com/yokeos/web/controller/dto/
├── AgentView.java / GenerateRequest.java / CreateAgentRequest.java / UpdateAgentRequest.java
└── FileNode.java（树节点；若归 controller 包同层则随 WorkspaceApiController 定）

yokeos-web/src/test/java/com/yokeos/web/controller/
├── AgentApiControllerTest.java      # 存量扩展（invoke 零回退 + 5 新端点 + 错误码 + name 白名单）
└── WorkspaceApiControllerTest.java  # 新（tree/file/穿越 400）

yokeos-cli/src/main/java/com/yokeos/cli/YokeosRuntime.java   # 扩：三 Bean + 单线程执行器装配

yokeos-boot/src/main/resources/application.yaml              # 扩：yokeos.agent-generation.{provider,model}
yokeos-boot/src/test/java/com/yokeos/boot/AgentLifecycleIntegrationTest.java   # 新 @Tag("integration")

yokeos-web/src/main/frontend/src/   # App.vue 加两页（Agent 管理 / 工作区），yokeos-admin-ui skill 生成
```

**Structure Decision**: 九模块边界不动：跨模块契约（`AgentLifecycleService` 是 `yokeos-web` Controller 调用的 core 公共类）落 `yokeos-core`（宪法依赖倒置，参照钉版树同位）；web 只做薄 Controller + DTO；装配集中 `YokeosRuntime`（26 节既有先例：boot 组件扫描吸收 cli 的配置类）；配置键落 boot `application.yaml` 但经 `@Value` Environment 绑定读取（与 provider 清单的 SnakeYAML 原文直读并存不悖——agent-generation 键非密钥、无 `${ENV}` 占位，22 节坑不适用）。

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

（空——九条全过，无违规豁免。）
