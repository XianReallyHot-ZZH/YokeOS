# 验收报告：Memory 两层记忆（第 22 节，specs/007-memory-implementation）

> 课型：代码课。spec：[spec.md](./spec.md) · plan：[plan.md](./plan.md) · tasks：26/26 ✅ · 设计依据：specs/006-memory-review 定稿 D1~D11。
> 日期：2026-09-18 · 分支：`specs/007-memory-implementation`。

## 一、六项证据 DoD

### 1. `mvn clean verify` 九模块全绿

```
[INFO] BUILD SUCCESS（exit 0）
YokeOS / Core / Provider / Storage / Tool / Memory / CLI Channel / Web / CLI / Boot 全 SUCCESS（10 reactor 单元）
总测试 222（21 节基线 184 + 本节新增 38），Failures 0 / Errors 0
```

门禁全过：Spotless + P3C（PMD）+ Checkstyle + SpotBugs/FSB。相对基线 +38 测试（契约 12 + markdown 6 + mem0 4 + 门面 4 + 工具 5 + repo 4 + PromptBuilder 新增 3）。

### 2. 教学文档 harness 映射逐类对号

| 测试类 | 教学文档锚点 | 结果 |
|---|---|---|
| `MemoryStoreContractTest`（12：4 方法 × 3 档参数） | 四条行为契约对三档统一跑——「接口不变、实现随便换」的自动化保障 | ✅ |
| `MarkdownMemoryStoreTest`（6） | 两分区解析 / 截断边界（超裁/恰等不裁）/ 日期 header / 空文件态 / 坑三锚法 | ✅ |
| `MemoryEntryRepositoryTest`（4，真 SQLite 文件库） | 坑六手工建表存读 / LIMIT 取最近 N / LIKE 只命中归档 / `%`_` 转义 | ✅ |
| `Mem0MemoryStoreTest`（4，mock RestClient） | append 带 scope metadata / search 转发 / 非 2xx 明确异常 / 配置缺失点名报错 | ✅ |
| `MemoryServiceImplTest`（4） | 坑七（buildContext 不含会话消息）/ 核心完整 / 转发正确 | ✅ |
| `builtin/MemoryToolsTest`（5） | 坑四 scope 三态 / 未命中提示语不抛异常 / 命中拼接 | ✅ |
| `PromptBuilderTest`（10 = 既有 7 + 新增 3） | [2] 位注入位置 / 坑七历史不重复 / 每次组装现调（坑二链路收口） | ✅ |

**坑 ↔ 回归点逐个过**：

| 坑 | 回归断言 | 结果 |
|---|---|---|
| 坑一 膨胀不截断 | 契约测试「截断只裁归档区」（500 条灌满，最早被裁、核心一字不少）+ markdown 恰等阈值不裁 | ✅ |
| 坑二 缓存/预载 | 契约测试「写入后立刻可读」+ PromptBuilderTest「每次组装都现读长期记忆」 | ✅ |
| 坑三 USER.md 被写 | MarkdownMemoryStoreTest「记忆写入路径碰不到用户初始设定文件」（写入后 @TempDir 仅 MEMORY.md 一个文件）+ 收尾 grep（memory main 零 USER.md 引用，4 处命中均为 `USER_ID` 常量） | ✅ |
| 坑四 scope 系统猜 | MemoryToolsTest 三态（缺省 ARCHIVAL / 显式 CORE / 非法点名不落区）+ 契约测试 scope 路由 | ✅ |
| 坑五 检索越界核心区 | 契约测试「检索只作用归档区」+「核心区的词检索不到」 | ✅ |
| 坑六 ddl-auto 建表 | MemoryEntryRepositoryTest 走 schema-003 手工脚本（真库存读 + 列可查） | ✅ |
| 坑七 历史重复注入 | MemoryServiceImplTest「门面上下文只含长期记忆」+ PromptBuilderTest「会话历史只由历史段承载」（消息恰好出现一次） | ✅ |

### 3. 「本节交付物」逐项存在性核对

代码组 13 文件（core/memory 两件 + memory 模块八件 + storage 三件）ls 全 OK；测试组 7 类全 OK；配置组：`yokeos.memory` 四组键 + schema-locations 追加 schema-003 在 application.yaml；表：`schema-003-memory.sql`（memory_entries + idx_memory_scope，CREATE IF NOT EXISTS 幂等）。证据见 T025 执行记录（OK × 20）。

### 4. 前序节测试回归绿

全仓 `mvn clean verify` 222 全绿（16~21 节用例零回归）；四阶段门禁（T011/T015/T023）均在前序全绿后推进。`YokeosRuntimeAssemblyTest` 注册面断言更新为 ≥9（7 既有 + 记忆两件点名）。

### 5. H4 七条全局不变量逐条自查

| # | 不变量 | 自查 |
|---|---|---|
| 1 | 宪法 2：无自动 tool 执行 | `MemoryTools` 走 20 节 `registerAnnotated` 管道（schema 生成），执行发起方 ToolExecutor；grep `internalToolExecutionEnabled(true)\|\.tools(` 4 处命中均为假阳性（`profile.tools()` 字段访问 / MCP schema getter / 注释），无 ChatClient 工具注册链 ✅ |
| 2 | 宪法 4：零异步 | `import reactor` / `CompletableFuture` main 零命中；文件 IO / JPA / RestClient 全同步 ✅ |
| 3 | 宪法 5：Tool 基础设施合块 | `MemoryTools` 落 yokeos-memory = specs/006 裁决二显式论证（依赖 memory→tool 单向无环）；ToolRegistry 本体不动 ✅ |
| 4 | 宪法 7：手工建表 | schema-003 手工脚本（boot `sql.init` 幂等执行，`ddl-auto: none` 不变）；凭证 `${MEM0_*}` 占位零明文（`sk-` 零命中）✅ |
| 5 | 新触发入口 session 判定 | 不涉及（本节无新入口；PromptBuilder 是既有三入口共用组装点）✅ |
| 6 | Sandbox 留位 | 检查位注释 3 处（MarkdownMemoryStore FILE_WRITE / Mem0MemoryStore HTTP_REQUEST / SqliteMemoryStore 无涉外说明），24 节接线 ✅ |
| 7 | 依赖方向 | core 零新依赖；memory → {core, tool(新), storage} 单向无环；跨模块契约（MemoryService/MemoryScope）在 core ✅ |

### 6. 人工项当场跑完 + 剩余清单

**当场跑完**：真模型 E2E `ToolSystemEndToEndIntegrationTest`（22 节接线后真 key 全链路，1/1 绿，22.4s 真调用——`source ~/.zshrc` 后跑，18 节坑纪律）。

**剩余人工项**（2 条，需用户真实交互，非阻塞）：

- [ ] 跨对话记住偏好的用户体感演示（quickstart 场景三）：`yokeos chat` 会话一让 Agent 调 save_memory，新会话问相关问题验证答复体现偏好——自动化已锚同链路（PromptBuilder 现读注入 + markdown 落盘），此项是可演示成果的体感确认
- [ ] markdown↔sqlite 两档切换体感（quickstart 场景二）；mem0 档真实例可选（需自托管 server，缺位不阻塞验收——O5 口径）

## 二、分批说明（留后续节）

- Sandbox 拦截用例：检查位注释已钉（3 处），接线归 24 节（20 节同款先例）。
- `GET /api/v1/memory` 查询端点：归 26 节；本节未加 `readAll` 类方法（钉版树末态不采纳，教学文档拍板⑤）。
- 记忆压缩 / 自动抽取 / 向量：扩展阶段（评审 D11）。

## 三、实施偏差

| # | 偏差 | 理由 |
|---|---|---|
| 1 | 装配落位：`YokeosRuntime.memoryService()` @Bean（cli）+ `MemoryProperties`（memory 模块），未设独立 `MemoryModule` 装配类 | 本仓 16~20 节装配统一在 YokeosRuntime @Bean 面（plan 已锁定口径）；配置载体仍落 yokeos-memory |
| 2 | `MemoryProperties` 不走 `@ConfigurationProperties` 绑定，全文 classpath yaml 原文读取（SnakeYAML） | mem0 段 `${MEM0_*}` 占位会被 Boot 绑定提前解析（env 缺失启动即炸，违 FR9）；三普通键顺势同路径，配置来源单一——16 节 provider 原文读取策略的泛化（research D6） |
| 3 | yokeos-memory pom 增 `org.springframework:spring-web`（既有坐标的模块级新增）+ `spotbugs-annotations`（provided，core 同款） | RestClient 所在包；非新外部坐标（软门禁⑥记理由：research D2 已 javap 核实） |
| 4 | `MemoryEntryRepository` 用 `findByScope(String, Pageable)` 单方法承载核心全量（unpaged）与归档 LIMIT，未单设 `findRecentArchival` | 两语义同一查询形态，参数区分（plan 接口签名微调，行为不变） |
| 5 | `RestClientResponseException.getUri()` 不可用（spring-web 6.1.14 无此方法），错误翻译用 `getMessage()` | 版本差异，断言不受影响（含状态码） |

## 四、门禁拦下了什么、怎么修的（过程证据）

1. **Checkstyle `MissingJavadocMethod`**：构造器缺 javadoc → 补（MemoryServiceImpl）。
2. **Checkstyle `OverloadMethodsDeclarationOrder` / `VariableDeclarationUsageDistance`**：测试 helper 重载被字段隔开、变量声明使用距离 4 行 → 重排（PromptBuilderTest）。
3. **Checkstyle `LineLength`**：@Query 字符串 103 字符 → 字符串拼接折行（MemoryEntryRepository）。
4. **Checkstyle `Indentation`（switch 表达式）**：YokeosRuntime 选档 switch 缩进风格不被 google_checks 接受 → 改 if-else 链（等价更稳）。
5. **JPA 列名**：`createdAt` 未映射蛇形（本仓实体惯例是显式 @Column）→ 补 `@Column(name = "created_at")`（MemoryEntry）。
6. **PMD `UndefineMagicConstantRule` × 6**：`"\n"`、`"results"`、档名 `"markdown"/"sqlite"/"mem0"` 判魔法值 → 提常量（NEWLINE / RESULTS_FIELD / MemoryProperties.BACKEND_*）。
7. **SpotBugs × 3**：`NP_NULL_ON_SOME_PATH`（Path.getParent() 可空）→ null 防御；`CT_CONSTRUCTOR_THROW`（Mem0 构造校验抛异常）与 `EI_EXPOSE_REP2`（MemoryServiceImpl 存引用）→ 类级 @SuppressFBWarnings + javadoc 理由（设计本体/装配层单例，PromptBuilder 先例）。
8. **spring-web 传递不可见于 main**：测试 classpath 有、main 没有 → pom 显式补依赖（见偏差 3）。
9. **RestClient 深链 mock NPE**：逐环 thenReturn stub 在链上未命中返回 null → 改 `mock(X.class, Mockito.RETURNS_SELF)` 标准方案（已回填 CLAUDE.md 陷阱表）。
10. **boot 四个既有集成测试**：PromptBuilder 构造器扩参后编译红 → 统一注入 `memoryService()` helper（InMemory 轻量档，这些测试不测记忆）。

全部修实现不改规则、不删断言。

## 五、方法论对照

- **语料先行**：教学文档 022 起草→用户拍板①~⑥→specify 素材（specs/006 6.1 预填直取）。
- **接口墙兑现**：三档后端一次交付 + 契约测试参数化统一跑（12 断言）——「换后端只改 `yokeos.memory.backend` 一行，上层与契约测试改动行数为 0」由测试结构保证。
- **TDD 配对**：六对测试→实现（T005→T006、T007→T008、T009→T010、T012→T013、T016→T017、T020→T021），阶段门禁四次。
- **H3 写前核实**：RestClient javap 签名核实（research D2 落实）；「getUri() 不存在」「构造器签名差异」两处版本代差当场暴露当场修。
- **钉版树末态分辨**：`readAll(agentName)`/per-Agent 作用域不采纳（参照 30 节回填）；契约测试注释与实物不符处以实物为准。

## 六、验证命令（可复制）

```bash
# 全量门禁（预期：10 reactor 单元 SUCCESS，222 测试全绿）
mvn clean verify

# 本节测试（预期：memory 25 + storage repo 4 + core PromptBuilder 10 全绿）
mvn -pl yokeos-memory -am test
mvn -pl yokeos-storage -am test -Dtest=MemoryEntryRepositoryTest

# 真模型 E2E（预期：1/1 绿；真 key 需 source ~/.zshrc）
source ~/.zshrc && mvn -pl yokeos-boot -am test -Dgroups=integration -DexcludedGroups= -Dtest=ToolSystemEndToEndIntegrationTest

# 宪法专项（预期：reactor/CompletableFuture 零；internalToolExecutionEnabled(true) 零）
grep -rn 'import reactor\|CompletableFuture' yokeos-*/src/main --include='*.java' | wc -l

# 检查位（预期：memory 模块 3 处形态一致）
grep -rn 'Sandbox 检查位' yokeos-memory/src/main --include='*.java'
```

**可演示成果口径**（需 §11 第 22 节行）：「Agent 跨对话记住用户偏好并在后续对话用到」——链路自动化已全锚（写入 → 落盘/落库 → 下次组装现读注入 → 答复引用），用户体感演示见剩余人工项 ①（quickstart 场景三步骤）。
