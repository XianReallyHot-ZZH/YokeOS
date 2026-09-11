# Quickstart: 验证第 16 节端到端可用

Phase 1 产物。按序执行，每步带预期结果；这是本节「做完怎么验」的可运行版。

## 前置

- JDK 21；Maven（多模块构建）
- 环境变量：`DEEPSEEK_API_KEY` 与 `KIMI_API_KEY`（冒烟步才需要；单测不需要任何真 key）
- 依赖核实（**第一步，H3**）：`mvn -pl yokeos-provider dependency:tree`——确认 Spring AI 1.1.8 的 openai starter 可解析下载（本地 .m2 尚无 1.1.8，首次联网拉取）；失败即停，不降级版本

## 1. 全量门禁（自动化验收主体）

```bash
mvn clean verify
```

预期：BUILD SUCCESS——含 Spotless / P3C / Checkstyle / SpotBugs / PMD 全绿 + 四个单测类通过（`AgentLoaderTest` / `ProviderServiceTest` / `ToolSchemaAdapterTest` / `LlmCallRepositoryTest`；mock ChatModel，不碰网络）。`@Tag("integration")` 的 `ProviderSmokeIntegrationTest` 默认排除。

## 2. 工作区初始化（人工项）

```bash
java -jar yokeos-boot/target/yokeos.jar init   # 或开发态等价入口
ls .yokeos/ && cat .yokeos/USER.md
```

预期：六子目录 + 三 Bootstrap 文件；`USER.md` 为占位模板。再跑一次：内容零变化（幂等）。

## 3. 集成冒烟（人工项，真 key 真调）

```bash
DEEPSEEK_API_KEY=sk-xxx KIMI_API_KEY=sk-yyy \
  mvn -pl yokeos-provider test -Dgroups=integration
```

预期：`ProviderSmokeIntegrationTest` 通过——真调一次目标模型，拿到非空响应，且 `llm_calls` 新增一行 `success=true`（token 三项与耗时有值）。

## 4. 审计落账抽查（人工项）

冒烟后查 `.yokeos/yokeos.db`（sqlite3）：

```bash
sqlite3 .yokeos/yokeos.db "SELECT provider, model, total_tokens, success, error_message, duration_ms FROM llm_calls ORDER BY id DESC LIMIT 5;"
```

预期：冒烟调用的记录在场；字段齐全。失败路径已由 `ProviderServiceTest` 的失败审计回归测试覆盖（`success=false` + 原因）。

## 5. 凭证卫生（人工项）

```bash
grep -rn "sk-" --include="*.java" --include="*.yaml" --include="*.yml" . | grep -v vendors/ | grep -v "\${"
```

预期：零命中（明文 key 不存在于代码与配置）。

## 完成判据

第 1 步全绿 + 第 2~5 步人工项逐项过 = 本节六项证据 DoD 的主体（完整清单见验收报告，届时产出）。
