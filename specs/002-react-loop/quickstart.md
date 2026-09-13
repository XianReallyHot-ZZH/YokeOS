# Quickstart: 验证第 17 节端到端可用

Phase 1 产物。按序执行，每步带预期结果；这是本节「做完怎么验」的可运行版（教学文档第五部分的人工项展开）。

## 前置

- JDK 21；Maven（多模块构建）
- 环境变量：`DEEPSEEK_API_KEY`（仅第 2 步冒烟需要；单测不需要任何真 key，缺 key 冒烟自动跳过）
- 依赖无新拉取风险：Spring AI 1.1.8 已在本地仓库（16 节拉取过；自定义 localRepository `D:\Developer\DeveloperInstall\maven-repo`）；唯一新依赖 jackson-databind 由根 pom jackson-bom 接管版本

## 1. 全量门禁（自动化验收主体）

```bash
mvn clean verify
```

预期：BUILD SUCCESS——含 Spotless / P3C / Checkstyle / SpotBugs / PMD 全绿 + 测试通过：本节七个单测类（`ReActLoopTest` / `PromptBuilderTest` / `ToolExecutorTest` / `AgentServiceTest` / `ContextLoaderTest` / `ToolInvocationRepositoryTest` / `SpringAiProviderServiceTest`，mock `ProviderService` 接口不碰网络）+ **16 节全部测试回归绿**（契约上移零行为变化的门禁——任何 16 节用例变红都说明搬移夹带私货）。`@Tag("integration")` 的 `ReActSmokeIntegrationTest` 默认排除。

## 2. 集成冒烟（人工项，真 key 真 http_get——需 §11 可演示成果）

```bash
DEEPSEEK_API_KEY=sk-xxx mvn -pl yokeos-boot -am test -Dgroups=integration -DexcludedGroups=
```

预期：`ReActSmokeIntegrationTest` 通过——真模型真 `http_get`（open-meteo 无 key 端点）完整走通「思考 → 调 Tool → 观察 → 续推」：强引导提示词下 Agent 自主先调工具、基于天气数据给出非空答复；`tool_invocations` 新增 `success=true` 行、`llm_calls` 有本次调用记录。缺 key 时 `assumeTrue` 跳过（显示 SKIPPED 不失败）。

## 3. 审计落账抽查（人工项）

冒烟后查 SQLite（测试临时库路径见测试输出，或对既有 `.yokeos/yokeos.db`）：

```bash
sqlite3 .yokeos/yokeos.db "SELECT tool_name, success, error_message, duration_ms FROM tool_invocations ORDER BY id DESC LIMIT 5;"
sqlite3 .yokeos/yokeos.db "SELECT provider, model, total_tokens, success FROM llm_calls ORDER BY id DESC LIMIT 5;"
```

预期：`tool_invocations` 有本节写入的 `http_get` 行（成败都可能有，失败行必带 `error_message`）；`llm_calls` 每轮 LLM 调用一行、按同一 `session_id` 关联。坏 JSON / 未注册工具名路径已由 `ToolExecutorTest` 覆盖（`success=false` 留痕不抛异常）。

## 4. 宪法纪律 grep（人工项）

```bash
# 宪法 4：九模块无异步新增
grep -rE "CompletableFuture|reactor|WebFlux" --include="*.java" yokeos-*/src | grep -v vendors/
# 宪法 1/2：core 主源码零 Spring AI 泄漏（契约上移验证口径）
grep -rE "springframework\.ai|com\.yokeos\.provider" yokeos-core/src/main
```

预期：第一条零命中；第二条零命中（Spring AI 类型只存在于 provider 模块）。

## 5. 凭证卫生（人工项）

```bash
grep -rn "sk-" --include="*.java" --include="*.yaml" --include="*.yml" . | grep -v vendors/ | grep -v "\${"
```

预期：零命中（明文 key 不存在于代码与配置）。

## 完成判据

第 1 步全绿 + 第 2~5 步人工项逐项过 = 本节六项证据 DoD 的主体（完整清单见验收报告，届时产出）。教学文档第五部分剩余人工项由第 2 步（可演示成果）与 code review（宪法 1/2 确认）承载。
