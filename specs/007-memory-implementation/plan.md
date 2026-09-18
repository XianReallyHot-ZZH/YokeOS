# Implementation Plan: Memory 两层记忆——让 Agent 跨对话记得住用户（第22节）

**Branch**: `specs/007-memory-implementation` | **Date**: 2026-09-18 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/007-memory-implementation/spec.md`

## Summary

第 22 节交付 Memory 实现（spec 冻结自 specs/006 评审 D1~D11）：`MemoryService` 统一门面（接口 + `MemoryScope` 落 yokeos-core——PromptBuilder 消费方在 core、接口留 memory 会成环，16 节 Provider 上移同款）+ `LongTermMemoryStore` 可插拔后端接口与三档实现一次交付（`MarkdownMemoryStore` 默认档两分区文件 / `SqliteMemoryStore` 结构化档 LIMIT+LIKE / `Mem0MemoryStore` 自托管 REST 集成档）+ `InMemoryMemoryStore` 测试基建 + `MemoryServiceImpl` 薄门面（`buildContext` 只出长期记忆，会话历史仍归 PromptBuilder 既有段——坑七）+ `MemoryTools` 两内置 Tool（`@Tool` 经 20 节注解管道注册，落位 memory 模块 = specs/006 裁决二）+ `memory_entries` 手工建表（schema-003）+ `PromptBuilder` 构造器接线（17 节预留注释点名的唯一前序改造点）+ `YokeosRuntime` 装配（`memoryService()` 工厂按 `yokeos.memory.backend` 三选一）。四条行为契约（不缓存 / 核心永不截断 / scope 显式 / 关键词检索）以**契约测试对三档参数化统一跑**钉死；七个坑各配回归点。无新增依赖坐标、无新增审计逻辑。

## Technical Context

**Language/Version**: Java 21（虚拟线程处理并发）

**Primary Dependencies**: Spring Boot 3.5.16；**零新增坐标**——`@Tool`/`MethodToolCallbackProvider` 复用 20 节已引入并 javap 实证的 `spring-ai-model`（BOM 管版本 1.1.8）；Mem0 档 REST 走 `RestClient`（spring-web 既有传递，Framework 6.1+ 同步客户端，宪法 4 合规，写前 javap 核实签名，research D2）；sqlite 档复用 18 节 Spring Data JPA + hibernate-community-dialects 基建；Jackson（REST 请求体序列化，既有）；SLF4J（日志编译期常量）；Boot 属性绑定 `@ConfigurationProperties`（16 节 ProvidersProperties 先例——但 mem0 凭证段不走绑定，见 research D6）

**Storage**: 新表 `memory_entries`（schema-003-memory.sql 手工脚本，列定义逐字技 §9.2，风格照 schema-002；boot `sql.init.schema-locations` 追加）；文件系统 `.yokeos/memory/MEMORY.md`（markdown 档存储体，两分区 header 组织）；配置键 `yokeos.memory.*` 四组（backend / archive-max-chars / archive-max-rows / mem0.base-url+api-key，教学文档拍板①②③）。不走 `ddl-auto=update`（宪法 7）

**Testing**: JUnit 5 + Mockito 单测主体（`@TempDir` 真文件、有状态假 Repository、mock RestClient，不碰网络）：七个测试类逐条对应验收点（教学文档第四部分）——`MemoryStoreContractTest`（参数化遍历三档：markdown @TempDir / sqlite fakeRepo / mem0 用 `InMemoryMemoryStore` 替身；同一套断言：写后立读、截断只裁归档核心一字不少、scope 路由、recall 只搜归档；`@TestInstance(PER_CLASS)` 让 MethodSource 工厂非静态访问实例 TempDir，research D7）、`MarkdownMemoryStoreTest`（两分区解析、截断边界、日期 header、文件不存在视作空）、`MemoryEntryRepositoryTest`（真 SQLite 文件库，18 节口径：手工建表能存能读、归档 LIMIT 取最近 N、LIKE 只命中归档区、`%`/`_` 转义）、`Mem0MemoryStoreTest`（mock RestClient：append 请求体带 scope metadata、recall 转发查询、非 2xx 翻译明确异常）、`MemoryServiceImplTest`（buildContext 不含会话消息、核心完整、转发正确）、`builtin/MemoryToolsTest`（scope 缺省归档/显式核心/非法明确提示、未命中提示语不抛异常）、`PromptBuilderTest` 更新（[2] 位长期记忆出现、历史仍由 [3] 段承载不重复、门面每次组装被调用）。关键回归点：坑一~坑七逐个（spec 验收标准 2）。真模型跨对话演示为人工项（真 key `yokeos chat` 两段会话，教学文档第五部分）。测试方法名英文、`@DisplayName` 保留教学文档中文语义。完成定义 = `mvn clean verify` 九模块全绿（前序零回归）

**Target Platform**: JVM（macOS 开发环境；无平台敏感代码——文件/SQL/REST 全跨平台）

**Project Type**: Maven 多模块 library + cli（本节触四模块：yokeos-core / yokeos-memory / yokeos-storage / yokeos-cli + boot yaml）

**Performance Goals**: 技 §14 口径——每次组装 prompt 读一次长期记忆：md 档小文件 1~2ms、sqlite 档毫秒级查询、mem0 档一次局域网 REST，百级并发可接受；不设本节独立指标

**Constraints**: 宪法 2（`@Tool` 只借 schema 生成，`AnnotatedToolAdapter.call()` 进程内直调、执行发起方永远是 ToolExecutor——20 节已钉管道本节只挂 bean；无 ChatClient、无带 autoconfigure 的 starter）、宪法 4（文件 IO / RestClient / JPA 查询全同步阻塞，零 Reactor/CompletableFuture）、宪法 5（`MemoryTools` 落 memory 模块的裁决二论证，依赖 memory → tool 单向无环）、宪法 7（schema-003 手工脚本；mem0 凭证 `${MEM0_*}` 占位不落明文）；语法禁区——避开 P3C/ASM 解析不了的 Java 18+ 语法形态；测试类名 ≤1 连续大写；日志消息编译期常量（CRLF 门禁），动态内容进异常堆栈

**Scale/Scope**: 长期记忆条目量级——md 档文件几 KB 到几十 KB、sqlite 档记忆量上千、单条内容无硬上限（截断只作用归档区总量）；三档后端进程内单实例（无多副本共享需求的第一阶段口径——多副本部署选 sqlite/mem0 档是存储形态问题不是本节实现问题）

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | 原则 | 本节落点 | 状态 |
|---|------|---------|------|
| 1 | 自实现 ReAct 循环 | 不动循环；Memory 经门面注入 PromptBuilder（组装输入），不碰 ReActLoop/ToolExecutor 执行流 | ✓ |
| 2 | Spring AI 只用两件事 | `MemoryTools` 复用 20 节注解管道（`MethodToolCallbackProvider` 生成 schema + `AnnotatedToolAdapter` 包装直调），执行发起方永远是 ToolExecutor；零 ChatClient 用法；零新增 starter（spring-ai-model 是 20 节既有裸库） | ✓ |
| 3 | Provider 显式映射 | 不涉及（无 ChatModel 交互；既有 providerMap 不动） | ✓ |
| 4 | 同步执行 + 虚拟线程 | 文件 IO（`Files.readString`/write）/ JPA 查询 / `RestClient`（同步客户端）全阻塞式；零 Reactor、零 CompletableFuture | ✓ |
| 5 | Tool 三合一 | **显式论证（specs/006 裁决二）**：宪法 5 管 Tool 基础设施合块（ToolRegistry/MCP/Sandbox/Notify 与通用内置 Tool 留 yokeos-tool）；`MemoryTools` 是能力三的组成部分、随能力落 yokeos-memory，经 `ToolRegistry.registerAnnotated` 与其他内置 Tool 一视同仁；依赖方向 memory → tool 单向无环，不触宪法 5 要防的拆模块场景 | ✓ |
| 6 | Sandbox 接口先行 | Memory 无本节接线的涉外校验：`save_memory` 写 MEMORY.md 的 FILE_WRITE 与 mem0 档的 HTTP_REQUEST 检查位以注释钉死（`sandbox.enforce(new SandboxAction(...))` 调用形态），24 节接线——20 节六件工具同款先例 | ✓ |
| 7 | SQLite + 审计 day one | `memory_entries` 手工建表脚本 schema-003（同 sessions/审计表口径，禁 ddl-auto=update）；`save_memory`/`recall_memory` 经 ToolExecutor 既有路径落 `tool_invocations` 零新增审计逻辑；mem0 凭证 `${MEM0_*}` 占位、缺失清晰报错不静默 | ✓ |
| 8 | 一个目录 = 一个 Agent | 不涉及（MEMORY.md 为全局存储体，技 §5.2 口径；per-Agent 作用域不采纳，教学文档拍板⑤） | ✓ |
| 9 | 结构照抄，瑕疵不继承 | 落位镜像参照（接口上移 core、实现落 memory、builtin 子包、契约测试 PER_CLASS 参数化同构）；钉版树末态的 `readAll(agentName)`/per-Agent 作用域不继承（参照 30 节回填，非 22 节时点形态）；参照契约测试注释里提到但实物不存在的 SqliteMemoryStoreTest 以实物为准（真库行为锚 MemoryEntryRepositoryTest）；坑七（历史重复注入）为本仓 preempt 补防 | ✓ |

Phase 1 设计后复检：无新增违规（见 Complexity Tracking——空）。

## Project Structure

### Documentation (this feature)

```text
specs/007-memory-implementation/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output（零依赖核实 + 六项实现决策 D1~D8）
├── data-model.md        # Phase 1 output（memory_entries 表 + MEMORY.md 格式 + 配置键）
├── quickstart.md        # Phase 1 output（单测/三档切换/真模型跨对话验证）
├── contracts/
│   └── memory.md        # 门面三方法 + 后端三方法与四条行为契约 + 两 Tool 输入输出
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
yokeos-core/src/main/java/com/yokeos/core/
├── memory/
│   ├── MemoryService.java                           # 新增：buildContext(Session)/remember/recall 三方法门面
│   │                                                 #   （跨模块契约放 core——依赖方向论证，16 节同款）
│   └── MemoryScope.java                              # 新增：CORE / ARCHIVAL 枚举
└── agent/
    └── PromptBuilder.java                            # 修改：构造器 + MemoryService 注入；[2] 长期记忆位接线
                                                      #   （system prompt → 长期记忆 → 对话历史；17 节预留注释兑现）

yokeos-core/src/test/java/com/yokeos/core/agent/
└── PromptBuilderTest.java                           # 更新：构造器调用同步；[2] 位注入/历史不重复/每次组装现调三断言组

yokeos-memory/pom.xml                                 # 修改：+ yokeos-tool 依赖（MemoryTools 注册用）

yokeos-memory/src/main/java/com/yokeos/memory/
├── LongTermMemoryStore.java                          # 新增：append(content, scope)/load/recallByKeyword 后端接口
├── MarkdownMemoryStore.java                          # 新增：默认档——.yokeos/memory/MEMORY.md 两分区；截断=归档段
│                                                     #   尾部字符裁剪（archive-max-chars 默认 4000）；检索=行匹配；
│                                                     #   文件不存在视作空；每方法首行 Sandbox 检查位注释（FILE_WRITE，24 节）
├── SqliteMemoryStore.java                            # 新增：结构化档——核心区 WHERE scope='CORE' 全量、归档 LIMIT
│                                                     #   （archive-max-rows 默认 100）、LIKE 通配符转义（research D5）
├── Mem0MemoryStore.java                              # 新增：集成档——RestClient 翻译 add/get/search、scope 落 metadata；
│                                                     #   ${MEM0_*} 占位原文保留、切档使用时解析缺失清晰报错（research D6）；
│                                                     #   检查位注释（HTTP_REQUEST，24 节）
├── InMemoryMemoryStore.java                          # 新增：测试基建（进程内 List、满足四契约、不进 backend 选项）
├── MemoryServiceImpl.java                            # 新增：薄门面——buildContext 委托 load() 只出长期记忆（坑七）；
│                                                     #   remember/recall 转发
├── MemoryProperties.java                             # 新增：yokeos.memory 配置载体（backend/archive-max-chars/
│                                                     #   archive-max-rows；mem0 段占位不经 Boot 绑定解析，D6）
└── builtin/
    └── MemoryTools.java                              # 新增：@Tool save_memory（scope 缺省 archival、非法点名报错）/
                                                      #   recall_memory（未命中提示语不抛异常）；只认门面

yokeos-memory/src/test/java/com/yokeos/memory/
├── MemoryStoreContractTest.java                      # 参数化三档统一契约（四断言组：坑一/二/四/五）
├── MarkdownMemoryStoreTest.java                      # 两分区解析/截断边界/日期 header/空文件/写后 USER.md 不受触碰（坑三）
├── Mem0MemoryStoreTest.java                          # mock RestClient：scope metadata/查询转发/非 2xx 明确异常
├── MemoryServiceImplTest.java                        # buildContext 不含会话消息（坑七）/核心完整/转发
└── builtin/
    └── MemoryToolsTest.java                          # scope 三态/未命中提示语

yokeos-storage/src/main/java/com/yokeos/storage/
├── MemoryEntry.java                                  # 新增：JPA 实体（id/scope/content/createdAt，照 Session 实体风格）
└── MemoryEntryRepository.java                        # 新增：findByScope/findRecentArchival(Pageable)/searchArchival

yokeos-storage/src/main/resources/db/
└── schema-003-memory.sql                             # 新增：memory_entries + idx_memory_scope（CREATE IF NOT EXISTS 幂等）

yokeos-storage/src/test/java/com/yokeos/storage/
└── MemoryEntryRepositoryTest.java                    # 真 SQLite 文件库（18 节口径）：建表存读/归档 LIMIT 最近 N/
                                                      #   LIKE 只命中归档/%_ 转义（坑六）

yokeos-cli/src/main/java/com/yokeos/cli/
└── YokeosRuntime.java                                # 修改：+ memoryService() @Bean（按 yokeos.memory.backend 三选一构造
                                                      #   store 注入 MemoryServiceImpl）；promptBuilder() 增参 MemoryService；
                                                      #   tools() 注册面补 registerAnnotated(memoryTools)

yokeos-boot/src/main/resources/application.yaml       # 修改：+ yokeos.memory 四组配置键；schema-locations 追加 schema-003
```

**Structure Decision**: 九模块既有骨架内落位，本节触 core / memory / storage / cli 四模块 + boot yaml，零根 pom 变更（零新增坐标）。依赖方向：core 不新增依赖（MemoryService 是纯契约）；memory → core（既有）+ memory → tool（**新增**，MemoryTools 注册用）+ memory → storage（既有，SqliteMemoryStore 消费 Repository）——单向无环；cli 装配（YokeosRuntime 既有 @Bean 面加两处）；`@EnableJpaRepositories`/`@EntityScan` 的 basePackages 已覆盖 `com.yokeos.storage`，MemoryEntryRepository 零配置吸收。`core/memory` 包与 `builtin` 子包镜像参照 `io.oryxos.core.memory`/`io.oryxos.memory.builtin`；`PromptBuilder` 构造器扩展是本节唯一前序公共类改造（17 节注释预告），既有测试同节更新，前序零回归是门禁。

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

无违规，无条目（宪法 5 的 MemoryTools 落位为 specs/006 裁决二的显式解读，非违规）。
