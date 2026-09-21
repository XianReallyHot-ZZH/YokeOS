# 验收报告：Web Service 与管理台第一版（第26节 / specs/011-web-service）

**日期**：2026-09-21 · **分支**：`specs/011-web-service` · **DoD**：教学文档第五部分 + 六项证据（AI 编程指南 §5）

## 证据 1 · `mvn clean verify` 九模块全绿

终态门禁（**不带 `-Dfrontend.skip=true`**，含前端构建与全部静态门禁 Spotless / P3C / Checkstyle / SpotBugs / PMD / OWASP，BugInstance=0）：

```text
Tests run: 62（core） + 14 + 39 + 76 + 38 + 10 + 32（web） + 32 + 2 = 305，Failures 0，Errors 0
BUILD SUCCESS · Total time: 02:26 min
```

集成冒烟（显式触发，含前序节全部 E2E 真跑）：`mvn -pl yokeos-boot -am test -Dgroups=integration -DexcludedGroups=` → **15/15 绿**（WebSmoke 7 + ReAct/ToolSystem/Sandbox/Notify/Memory/Scheduler/McpSmoke/Load 共 8）。

## 证据 2 · 教学文档 harness 映射对号

| 测试类（教学文档四） | 实测结果 | 关键回归锚 |
|---|---|---|
| `SessionApiControllerTest` | 11/11 | process 恰一次（Controller 薄）、三路 404、32KB/100 条钳制、?status 过滤 |
| `AgentApiControllerTest` | 4/4 | 坑三（先查注册表 404 非 503）、两次 invoke 会话不同（research D3） |
| `ProfileApiControllerTest` | 2/2 | 投影字段、provider 段缺失不炸 |
| `ToolApiControllerTest` | 1/1 | 注册表全量投影 |
| `MemoryApiControllerTest` | 1/1 | readAll 全文原样 |
| `SystemApiControllerTest` | 3/3 | health ok、providers 去重排序（拍板④）、空注册表不炸 |
| `GlobalExceptionHandlerTest`（扩） | 8/8 | 三新映射 + Spring AI 故障族 503 + 500 不泄漏 |
| `JpaSessionManagerTest`（扩）+ `InMemorySessionManagerTest`（新） | 10/10 + 3/3 | 倒序上限、归档置状态、归档后幂等返回（research D4）、列表不反序列化 messages_json |
| `MemoryServiceImplTest`（扩）+ `MarkdownMemoryStoreTest`（扩） | +2 | readAll 原文 vs load 截断对照 |
| `WebSmokeIntegrationTest`（integration） | 7/7 | 坑一两半边（回落 + API 不被劫持）、坑二（两档 Cache-Control）、api-docs 覆盖（analyze M1）、CORS（L1） |

坑↔回归：坑一→WebSmoke 两断言 ✓ · 坑二→Cache-Control 断言 ✓ · 坑三→AgentApiControllerTest ✓ · 坑四→切片/冒烟分层 ✓ · 坑五→504 映射 ✓ · 坑六→全量构建本报告证据 1 ✓。

## 证据 3 · 「本节交付物」存在性核对

代码/测试/配置/skill/教学文档逐项 ls 全 OK（报告撰写时逐条核对：6 Controller、8 DTO、4 异常类、`WebConfig`、前端工程六件、`SessionSummary`、三处契约扩展、`.claude/skills/yokeos-admin-ui/SKILL.md`、`docs/class/026-web-service.md` + 三张 SVG 配图）；前端产物 `yokeos-web/target/classes/static/admin/index.html` 在包内。**表**：无新表、无表结构变更（`sessions` 既有列启用，宪法 7 ✓）。文档同步：技 §13 行 26 分节口径、技 §7.2 与需求 §5.10 与 CLAUDE.md 端点 18→19 已落。

## 证据 4 · 前序节测试回归绿

终态 `mvn clean verify` 含九模块全部既有测试（305 全绿）；集成合跑 15/15（前序节 8 个 E2E 真跑通过，含真 key 链路）。25 节 Scheduler E2E 补 `@AfterAll` 属性还原后同批全绿。

## 证据 5 · H4 七条全局不变量自查

1. Spring AI 自动执行/eager 装配：本节零 Spring AI 触点；冒烟真上下文起得来即证（无 autoconfigure.exclude，research D9）✓
2. 三元组拼接单点 `SessionIds`：Web/invoke 入口只传三元组零拼接（grep 无第二拼接点；`SessionApiController`/`AgentApiController` 均调 `getOrCreate`）✓
3. 审计两表 day one：Web 链路真跑落账实证（`llm_calls` 成功 3 条 + 失败 1 条 `success=0`）✓
4. session 拼接按 18 节判：`web:demo:weather` 实测 ✓；CLI 会话 `cli:*` 与 Web/invoke 同库（人工项实证三 channel 并存）✓
5. 新触发入口按 17 节判：invoke 复用 `AgentService.process` 同一入口，切片断言恰调一次 ✓
6. Sandbox：本节无新执行路径；HTTP 入站不属四 ActionType 管辖，出站仍走既有链 ✓
7. 宪法 4 同步 + 虚拟线程：无异步栈（唯一 CompletableFuture 零出现）；504 仅口径占位（research D10）；并发冒烟虚拟线程承载实证 ✓

## 证据 6 · 人工项当场跑完

真 serve（18080，25 节坑③ classpath 形态）+ 真 key 全链路：

- [x] 建会话 `web:demo:weather` → 发消息真模型回复（「今天偏凉，建议穿薄外套…」）→ 查历史（2 条）→ 列表 → 归档 → invoke×2 → 未知名 404
- [x] 审计两表有账：`llm_calls success=1` ×3；invoke 一次性会话 ×2、web ×1
- [x] 两入口共享存储：`yokeos chat --message` 落 cli 会话，REST 列表同时见 cli/invoke/web 三 channel
- [x] 断 Provider 503：错 key 重启发消息 → `503`（Provider 故障族映射实证；失败亦落账 `success=0`）
- [x] `/admin` 链：入口 302 → `index.html` 200 `<title>YokeOS 管理台</title>`、SPA 子路由回落 200、API 未命中 JSON 404 不被劫持
- [x] Swagger：`/v3/api-docs` 200 + `/swagger-ui/index.html` 200
- [x] 并发：20 并发 GET 全 200；**8 并发真 invoke 全 200（1.7s）**——并暴露并修复生产源并发缺陷（见偏差 ③）
- [x] 凭证卫生：`grep -rn "sk-"` 仅 16 节负向测试假字面量命中，零真实凭证
- [x] 不带 skip 全量构建（证据 1）
- [x] 浏览器真人目检五页（2026-09-21 用户确认：五页渲染/等宽与状态圆点/F5 刷新子路由/窄屏收导航/零写入口——全部通过）
- 口径说明：教学文档「如 200 并发 invoke」按费用降为 8 真调 + 20 GET——并发缺陷已被 5 并发暴露且修复后 8 并发验证，量级足够。

## 实施偏差（全部有据）

1. **springdoc 2.6.0 → 2.8.13**（根 pom + CLAUDE.md 技术栈表 + 技 §1.2 + 教学文档技术栈行）：2.6.0 与 Spring FW 6.2 二进制不兼容（`NoSuchMethodError`，WebSmoke 实证）；**宪法文件 `.specify/memory/constitution.md` 技术栈节的 2.6.0 字面量按治理规则未动，留用户 PATCH**（版本字面量修正，非原则变更）。
2. **YokeosRuntime toolRegistry Bean 重构**（plan 原说 cli 零改动）：装配层原以 `Map<String,YokeTool> tools` 为唯一暴露形态、registry 是方法内临时对象——controller 注入 `ToolRegistry` 无 Bean。改为 registry 提为 Bean、`tools` Map 由其派生；`PromptBuilder`/`ToolExecutor` 既有消费零变化（research D6 意图的落地形态）。
3. **生产数据源单连接池 + 关 OSIV**（`application.yaml`，文档链未预见的实证产物）：sqlite-jdbc busy handler 对写冲突不可靠（三形态探针全立即 BUSY）——`maximum-pool-size=1` + `open-in-view=false`，8 并发 invoke 全 200。已回填 CLAUDE.md 陷阱表。
4. **`/admin` 入口 302 到真实文件路径**（参照为 forward）：forward 视图依赖 ViewResolver 链，MockMvc 与无模板环境下产出空响应；资源处理器对空路径不走 resolver。落为 `/admin`、`/admin/` 双 302 → `/admin/index.html`（纯资源命中，两种环境皆稳）。
5. **GlobalExceptionHandler 增 Spring AI 故障族映射**（计划内 503 口径的实证补全）：错 key 401 抛 `NonTransientAiException` 直穿兜底 500，显式映射 503；yokeos-web pom 显式补 `spring-ai-retry` 依赖（传递件显式化）。
6. **25 节 Scheduler 测试补 `@AfterAll` 属性还原**：系统属性跨测试类污染致合跑 SQLITE_CANTOPEN（前序测试文件补丁，雷拆除）。
7. **CLAUDE.md 技术栈表 springdoc 版本与陷阱表 +5 条**（宪法 7 节纪律收尾回填）。

## 结论

六项证据齐备、九模块 305 测试全绿（另集成 15/15）、11 端点 + 管理台五页真跑通；需求 §11 行 26 可演示成果达成：**REST 端点完整可用，管理台只读观察五页上线**。剩余人工项 0 条（浏览器目检已由用户确认通过）——commit / 合流 / push 由人决定。
