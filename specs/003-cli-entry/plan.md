# Implementation Plan: CLI——YokeOS 的命令行入口（第18节）

**Branch**: `specs/003-cli-entry` | **Date**: 2026-09-15 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/003-cli-entry/spec.md`

## Summary

第 18 节交付 CLI 整节：`YokeOsCli` 根命令挂 12 个子命令（init 16 节已有接入、serve/gateway 启动骨架、其余轻命令零 Spring 直达），命令按「要不要跑引擎」轻重分流；重命令经 `YokeosRuntime`（yokeos-cli 自身的装配面：显式 `@EnableJpaRepositories`/`@EntityScan` 指 storage——参照坑四的正面解法 + `@Bean` 全链显式装配照 17 节冒烟配方转录）。会话层一并落地（17 节预告改造点）：`SessionManager` 补全 getOrCreate/get/save、`Session` 恢复构造器、`JpaSessionManager`（`session_id` = `channel:user:agent` 全库唯一拼接点，拍板③）、`sessions` 表（schema-002，`agent_name` 列按技 §9.2 字面量，拍板②）。交互面 YokeOS 特有三件：`/context`（查会话上下文）、`/tools`（经新 core 只读口 `ToolInvocationReader` 查审计表）、`--message` 单条模式（需 §5.12）。fat JAR mainClass 切 `YokeOsCli`；运行配置唯一归 boot。验收 harness 11 测试类（宪法 9：参照 CLI 零测试瑕疵不继承，Found N>0 目检升格机器断言）。

## Technical Context

**Language/Version**: Java 21（虚拟线程处理并发）

**Primary Dependencies**: Spring Boot 3.5.16——yokeos-cli 补显式 `spring-boot-starter`（research D7 实证：当前仅 test 域经 starter-test 传递，main 域起不了上下文）；snakeyaml 2.4（core 传递，provider list 直读 yaml 直接使用处补显式）；sqlite-jdbc 3.53.2.1 + spring-boot-starter-data-jpa（storage 传递，session list 纯 JDBC / storage 内 JPA 用）；jackson-databind 2.21.5（core 显式已有，storage JpaSessionManager 直接使用处补显式）；Picocli 4.7.6（已锁定）；**本节零新增 Spring AI 写法**——classpath 无 spring-ai autoconfigure（16 节裸 `spring-ai-openai` 方案，provider pom 已核），引 Boot 自家 starter 不触发宪法 2 坑

**Storage**: 新表 `sessions`（`db/schema-002-sessions.sql`：9 列 + idx_sessions_agent，手工脚本幂等 CREATE IF NOT EXISTS，boot yaml `schema-locations` 登记）；既有 `tool_invocations` 起读（/tools 数据源，17 节 `findBySessionId` 复用）

**Testing**: 11 测试类对号（教学文档第四部分）——storage 三件（`SessionManagerTest` / `SessionRepositoryTest` / `JpaToolInvocationReaderTest`，@DataJpaTest + @TempDir SQLite 文件库 + 测试内执行 schema-002，16/17 节模式）；channel-cli 一件（`CliChannelTest`，mock AgentService + 注入 Reader/PrintStream 脚本化驱动）；cli 七件（`YokeOsCliTest` / `StatusCommandTest` / `ProfileCommandTest` / `ProviderListCommandTest` / `ToolListCommandTest` / `SessionListCommandTest` / `YokeosRuntimeAssemblyTest`——哑 key + @TempDir SQLite 起装配面上下文，本地无网络不算 integration，与 `YokeosBootApplicationLoadTest` 同款定位）。测试方法名英文、`@DisplayName` 保留中文语义。完成定义 = `mvn clean verify` 九模块全绿（16/17 节全部测试保持绿——17 节预告改造点的回归门禁）

**Target Platform**: JVM（Windows + macOS 双平台开发环境）

**Project Type**: Maven 多模块 library（本节触五模块：yokeos-core / yokeos-storage / yokeos-channel-cli / yokeos-cli / yokeos-boot〔配置与 mainClass，测试补哑 key〕）

**Performance Goals**: 轻命令冷执行秒级（SC-004，脚本计时留证据）；其余承需求 §8 全局口径，本节不设独立指标；同步阻塞 + 虚拟线程（宪法 4）

**Constraints**: 宪法 4（CLI 循环同步阻塞读行；session list 同步 JDBC；无 CompletableFuture/reactor/线程池）；宪法 7（schema-002 手工脚本；两审计表经装配面照写；无 ddl-auto）；**模块依赖方向硬约束**——yokeos-cli 不依赖 yokeos-boot（boot 聚合全部九模块，反向即循环依赖），装配面落 cli 自身（research D4）；语法禁区——避开 P3C/ASM 解析不了的 Java 18+ 语法形态、测试类名 ≤1 连续大写、日志消息编译期常量；依赖已核实（research D7，plan 期完成不留实现期悬念）

**Scale/Scope**: 12 命令单入口；会话量个位数起步（需求 §8 上限 100 并发 Session 承接全局口径）；`messages_json` 整体序列化（单会话 ~50KB 量级，技 §14）

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | 原则 | 本节落点 | 状态 |
|---|------|---------|------|
| 1 | 自实现 ReAct 循环 | 本节不碰循环——CLI 薄壳零 Agent 逻辑（FR2），装配面原样复用 17 节 `ReActLoop`；code review 断言 CliChannel 无循环/模型/工具调用 | ✓ |
| 2 | Spring AI 只用两件事 | 零新增 Spring AI 写法面；classpath 无 spring-ai autoconfigure（裸依赖已核），引 Boot 自家 starter 不触发 eager 坑；`ToolExecutor` 唯一执行路径不动；装配全 `@Bean` 显式构造（不依赖任何自动装配的模型 Bean） | ✓ |
| 3 | Provider 显式映射 | `YokeosRuntime` 的 providerMap `@Bean` 复用 16 节显式构造（`Map<String, ChatModel>`），不引类型扫描 | ✓ |
| 4 | 同步执行 + 虚拟线程 | 交互循环同步阻塞读行；轻命令同步文件/只读 JDBC；无 CompletableFuture / reactor / WebFlux / 手工线程池 | ✓ |
| 5 | Tool 三合一 | 本节不碰 yokeos-tool（`HttpGetTool` 原样装配进 ToolExecutor 的工具 Map；ToolRegistry 归 20 节） | ✓ |
| 6 | Sandbox 接口先行 | 本节不建 Sandbox；ToolExecutor 白名单注释位不动（24 节接线） | ✓ |
| 7 | SQLite + 审计 day one | `sessions` 手工建表脚本 schema-002（禁 ddl-auto）；两审计表经装配面照写——`JpaLlmCallAuditor`/`JpaToolInvocationAuditor` 装进运行链；`/tools` 只读复用审计表不另建表 | ✓ |
| 8 | 一个目录 = 一个 Agent | profile 命令组操作 `.yokeos/agents/<name>/AGENT.md`（目录 = Agent，不涉任何 Tool 注册）；create 模板即 AGENT.md；delete 归档不物理删（对齐技 §11.3） | ✓ |
| 9 | 结构照抄，瑕疵不继承 | 落位镜像参照第 18 节（CliChannel→channel-cli、12 命令→cli、Session JPA→storage）；参照「CLI 入口零测试」→ 本仓 11 测试类覆盖入口；参照留人工的「Found N>0」目检 → `YokeosRuntimeAssemblyTest` 机器断言；参照「双 application.yml 冲突」→ 配置唯一归 boot 直接吸收 | ✓ |

Phase 1 设计后复检：无新增违规（见 Complexity Tracking——空）。范围口径（CLI 整节 12 命令）超出技 §13 第 18 节行的字面清单，依据 specs/001 明文预告 + 参照口径 + 用户拍板①，验收报告记实施口径说明。

## Project Structure

### Documentation (this feature)

```text
specs/003-cli-entry/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command output)
├── data-model.md        # Phase 1 output (/speckit-plan command output)
├── quickstart.md        # Phase 1 output (/speckit-plan command output)
├── contracts/
│   └── cli.md           # Phase 1 output：命令面（用户契约）+ SessionManager/CliChannel/只读口契约 + YokeosRuntime 装配面
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
yokeos-core/src/main/java/com/yokeos/core/
├── session/
│   ├── SessionManager.java          # 补全三方法：getOrCreate/get/save（17 节预告改造点）
│   ├── Session.java                 # 加恢复构造器 (sessionId, profileName, List<Message> restored)；append 三兄弟不动
│   └── InMemorySessionManager.java  # 随动实现（getOrCreate/get；保留作测试与轻量场景）
└── audit/
    ├── ToolInvocationRecord.java    # record：toolName/inputJson/success/errorMessage/durationMs/createdAt
    └── ToolInvocationReader.java    # 只读契约：List<ToolInvocationRecord> findBySession(String sessionId)

yokeos-storage/src/main/java/com/yokeos/storage/
├── Session.java                     # JPA 实体 @Table("sessions")（与 core 领域对象同名不同包，参照先例）
├── SessionRepository.java           # JpaRepository<Session, String>
├── JpaSessionManager.java           # implements core SessionManager；sessionId() 全库唯一拼接点
├── JpaToolInvocationReader.java     # ToolInvocationReader 实现（包既有 repository）
└── resources/db/schema-002-sessions.sql

yokeos-channel-cli/src/main/java/com/yokeos/channel/cli/
└── CliChannel.java                  # run(profileName, userId) 委托可注入 IO 重载；/quit /context /tools --message

yokeos-cli/src/main/java/com/yokeos/cli/
├── YokeOsCli.java                   # 根命令：注册 12 子命令；main = System.exit(execute(args))
├── YokeosRuntime.java               # 重命令装配面：@SpringBootApplication + 显式 JPA 注解 + @Bean 全链
├── InitCommand.java                 # 16 节既有（接入根命令，不改动）
└── command/
    ├── ChatCommand.java             # --profile/--message；起 YokeosRuntime → CliChannel
    ├── ServeCommand.java            # 启动骨架：上下文常驻 + keepAlive；--port 透传
    ├── GatewayCommand.java          # 启动骨架：上下文常驻 + keepAlive
    ├── StatusCommand.java           # 轻：工作区/配置/库文件存在性
    ├── ProfileCommand.java          # 轻：嵌套 list/create/show/delete（目录操作，delete 归档式）
    ├── ProviderListCommand.java     # 轻：SnakeYAML 直读 application.yaml 的 yokeos.providers
    ├── ToolListCommand.java         # 轻：当前真实就绪内置工具 + 20 节注明
    └── SessionListCommand.java      # 轻：纯只读 JDBC 查 sessions；库不存在→暂无会话

yokeos-boot/
├── pom.xml                          # spring-boot-maven-plugin mainClass → com.yokeos.cli.YokeOsCli
├── src/main/resources/application.yaml   # schema-locations 追加 schema-002
└── src/test/java/com/yokeos/boot/YokeosBootApplicationLoadTest.java  # 补哑 key 测试属性（断言语义不变）
```

**Structure Decision**: 九模块既有骨架内落位，镜像参照第 18 节。依赖方向合规不新增环：core 零新外部依赖；storage → core（实现会话/只读契约）；channel-cli → core（既有）；cli → core/channel-cli/provider/storage/tool/memory（既有）+ 新增 Boot starter；boot 聚合不变。17 节 `SessionManager`（仅 save）以接口**扩展**方式补全——前向预告的兑现，`InMemorySessionManager` 随动，17 节既有测试零改动。`YokeosBootApplication`（boot 主类）保留：组件扫描 `com.yokeos` 时自然吸收 `YokeosRuntime` 的显式装配与 JPA 注解（两个入口一套配置，26 节 serve 直接受益）——其上下文加载测试因全链 Bean 进入而需补哑 key（research D4）。

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

无违规，无条目。
