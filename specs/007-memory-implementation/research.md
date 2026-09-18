# Research: Memory 两层记忆（第22节，specs/007）

> Phase 0 产出。本节设计已由 specs/006 评审冻结（D1~D11 + O1~O5 落定），research 范围收窄为：零新增依赖的核实、既有基建的复用口径、四处实现决策。外部 Mem0 REST 契约按教学文档拍板④以 mock 定契约（软门禁⑤的化解：本地无真实实例、不强猜字段、真实例人工可选）。

## D1：零新增依赖坐标（核实计划）

**Decision**: 本节零新增 Maven 坐标、零根 pom 变更。

**Rationale**: `@Tool`/`@ToolParam`/`MethodToolCallbackProvider` 复用 20 节已引入并 javap 实证的 `spring-ai-model`（BOM 管 1.1.8）；`RestClient` 是 spring-web 的既有传递（Boot 3.5.16 → Framework 6.2，RestClient 自 6.1 起可用）；SQLite/JPA/Pageable 复用 18 节基建；Jackson 既有。唯一 pom 变更是 yokeos-memory 补 `yokeos-tool` 模块依赖（模块间依赖非坐标）。

**Alternatives**: 引入 Mem0 官方/非官方 Java SDK——拒绝（无官方 SDK，第三方 SDK 引入外部依赖与版本风险，且 REST 集成是评审 D4 定稿形态）。

**实施动作**: `mvn -pl yokeos-memory -am dependency:resolve` 核实（H3 写前纪律）；`javap -p org.springframework.web.client.RestClient` 核实方法签名（本地仓库 jar）。

## D2：RestClient 同步形态（宪法 4 核实点）

**Decision**: Mem0 档用 `RestClient.create()` 或 `RestClient.builder().baseUrl(...)` 构造，`post().uri(...).body(...).retrieve().toEntity(...)` 同步调用。

**Rationale**: RestClient 是 Framework 6.1+ 的**同步** HTTP 客户端（ WebClient 的同步对应物），无 Reactor 类型进入业务代码——宪法 4 合规。参照钉版树同款用法。

**Alternatives**: JDK `HttpClient.send`（17 节 HttpTools 口径）——可行但手工拼 JSON/解析响应样板多；WebClient——违反宪法 4（Reactor）。选 RestClient：序列化/反序列化经 Jackson 自动化，代码更薄。

## D3：Mem0 REST 契约（mock 定契约口径）

**Decision**: 按参照课件口径翻译三方法——`append` → `POST /v1/memories/`（body 含 messages、user_id、metadata.scope）；`load` → `GET /v1/memories/`（metadata 过滤，核心全量 + 归档取最近）；`recallByKeyword` → `POST /v1/memories/search/`。**本仓不依赖这些字段的真实性**：`Mem0MemoryStoreTest` mock RestClient 断言「发出的请求带 scope、查询被转发」，真实字段以部署的 Mem0 版本为准（换实例只改 store 内的常量，契约测试用 InMemoryMemoryStore 替身不受影响）。

**Rationale**: 本地核实不到真实 Mem0 实例（软门禁⑤）；mock 定契约让「协议转换职责」可测，真实连通留人工可选项（教学文档拍板④、评审 O5）。

**Alternatives**: 联网查 Mem0 OpenAPI spec 抄字段——拒绝（版本口径不明，抄了也测不了；参照课件同款口径已够定契约形状）。

## D4：sqlite 档 LIMIT 与排序形态

**Decision**: 归档查询 `findRecentArchival` 用 Spring Data `Pageable`（`PageRequest.of(0, N, Sort.by(Sort.Direction.DESC, "createdAt"))`）承载 `LIMIT N`；核心查询 `findByScope("CORE")` 无分页全量。

**Rationale**: LIMIT 只出现在归档查询上——契约二靠查询结构保证（核心查询物理上没有 LIMIT 子句）；createdAt 排序承载「保留最近」语义。参照钉版树用 `Pageable` 同款。

**Alternatives**: `@Query("... LIMIT :n")` 原生 SQL——拒绝（Hibernate 方言可移植性差，Pageable 是 Spring Data 正路）。

## D5：LIKE 通配符转义

**Decision**: `searchArchival` 的关键词先转义（`\` → `\\`、`%` → `\%`、`_` → `\_`），再拼 `%关键词%`，JPQL 用 `LIKE ... ESCAPE '\'`。

**Rationale**: 契约四是「关键词做字面量包含匹配」——`%`/`_` 是用户关键词的一部分时不得放大成通配符（spec Edge Case 点名）。SQLite 的 LIKE 默认不区分 ASCII 大小写，与 md 档 `String.contains`（区分大小写）存在已知微差——接受（关键词检索第一阶段口径，两档各自一致即可，不对齐）。

**Alternatives**: 内存过滤（全取归档行再 contains）——拒绝（记忆量上千后全量取回违背 LIMIT 档的定位）。

## D6：mem0 凭证占位的读取策略（16 节 provider 先例适用）

**Decision**: `yokeos.memory.backend`/`archive-max-chars`/`archive-max-rows` 三个普通键走 Boot 属性绑定（`MemoryProperties`，16 节 ProvidersProperties 先例）；**mem0 段不走绑定**——`base-url`/`api-key` 值为 `${MEM0_BASE_URL}`/`${MEM0_API_KEY}` 占位，Boot 绑定会提前解析占位、环境变量缺失时启动即失败，违背「缺省空不阻断启动、仅切到该档使用时报错」（FR9）。mem0 段与 16 节 provider 清单同款：占位原样保留，`Mem0MemoryStore` 构造/使用时解析，缺失给含变量名的清晰报错。

**Rationale**: YokeosRuntime 已有先例注释——「Boot 属性绑定会提前解析 ${ENV} 占位，与占位形态校验冲突，故不走绑定」；20 节 McpConfigLoader 的 `${ENV}` 占位缺失保留 + 使用时解析同款。

**Alternatives**: mem0 段也走绑定 + `@DefaultValue("")`——拒绝（占位符字符串经绑定后语义丢失，「缺 env 报错」发生在错误的时机）。

## D7：契约测试参数化形态（参照实证）

**Decision**: `@TestInstance(TestInstance.Lifecycle.PER_CLASS)` + 非静态 `@MethodSource` 工厂——工厂方法访问实例级 `@TempDir` 构造 markdown 档；三档各以 `Supplier<LongTermMemoryStore>` 工厂暴露（每断言新建实例，避免档间状态串扰）；sqlite 档用背靠内存 List 的有状态假 Repository（只 stub 用到的三个方法），真库行为由 `MemoryEntryRepositoryTest` 单独锚。

**Rationale**: 参照钉版树 `MemoryStoreContractTest` 同款结构（PER_CLASS 解决 MethodSource 静态限制 + TempDir 实例注入的组合）；契约测试不起 Spring 容器，秒级跑完。

**Alternatives**: `@ParameterizedTest @ValueSource` + 内部 switch——拒绝（类型不安全）；契约测试挂真库——拒绝（慢、且真库行为已有专属测试类）。

## D8：MemoryEntry 时间戳与实体风格（18 节先例对齐）

**Decision**: `createdAt` 类型与列名照 18 节 `Session`/`LlmCall` 实体先例（`LocalDateTime` + `created_at` 列映射），插入时 `LocalDateTime.now()`；schema-003 的 DDL 风格逐字照 schema-002（头注释注明列定义出处技 §9.2 + 宪法 7）。

**Rationale**: 同库同口径，避免两套时间戳风格；`yokeos.db.dir` 测试指向机制（18 节 busy_timeout 先例）直接复用。

**Alternatives**: `Instant` + UTC——可行但与既有三表不一致，统一性优先。

## 汇总：NEEDS CLARIFICATION 清零

Technical Context 无 NEEDS CLARIFICATION 残留——本节设计依据已冻结（specs/006），开放事项 O1~O5 已由教学文档拍板①~④落定，实现决策 D1~D8 全部有既有先例或参照实证背书。
