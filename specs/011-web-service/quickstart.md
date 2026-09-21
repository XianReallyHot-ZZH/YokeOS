# Quickstart: Web Service 与管理台第一版（第26节）

Phase 1 产物。端到端验证指南——自动化兜底 + 手动链路；契约细节见 [contracts/rest-api.md](./contracts/rest-api.md)。

## 前置

- 前序节就绪：`.yokeos/` 工作区有至少一个可用 Agent（如 demo 的 weather）；`DEEPSEEK_API_KEY` 在环境（真模型链路用）。
- 本机可联网（首跑构建要下载 Node v20.18.0 与 npm 依赖，耗时属预期）。

## 一、全量构建（含前端，坑六验收锚）

```bash
mvn clean verify          # 不带 frontend.skip——管理台产物必须真实进包
```

预期：九模块全绿；`yokeos-web/target/classes/static/admin/index.html` 存在（产物落位证据）。快速纯 Java 迭代可用 `mvn clean verify -Dfrontend.skip=true`（日常逃生门，验收不以它为准）。

## 二、自动化验收（不依赖模型）

```bash
mvn -pl yokeos-web -am test                     # 切片单测 + 存储层单测
mvn -pl yokeos-core -am test                    # SessionManager/SessionSummary 扩展
mvn -pl yokeos-memory -am test                  # readAll 三档
mvn -pl yokeos-storage -am test                 # listRecent/archive
mvn -pl yokeos-boot -am test -Dgroups=integration -DexcludedGroups=   # WebSmoke 冒烟（真上下文）
```

预期：全绿。WebSmoke 覆盖：五 GET 端点可达、`/admin/` 200 HTML、`/admin/不存在` 回落 index.html、`/api/v1/不存在` JSON 404。

## 三、手动链路（真 key，serve 常驻）

启动（IDE 起 `YokeosRuntime` 或按 25 节验证过的 classpath 形态；端口 8080）：

```bash
curl -s -X POST localhost:8080/api/v1/sessions -H 'Content-Type: application/json' \
     -d '{"profile":"weather","userId":"demo"}'          # → data.sessionId
curl -s -X POST localhost:8080/api/v1/sessions/<id>/messages -H 'Content-Type: application/json' \
     -d '{"content":"今天北京天气怎么样"}'                 # → data.reply（真模型）；审计两表各多行
curl -s localhost:8080/api/v1/sessions/<id>               # → 历史含上轮
curl -s "localhost:8080/api/v1/sessions?status=active"    # → 列表含本会话
curl -s -X DELETE localhost:8080/api/v1/sessions/<id>     # → {"archived":true}
curl -s -X POST localhost:8080/api/v1/agents/weather/invoke -H 'Content-Type: application/json' \
     -d '{"content":"上海呢"}'                             # → 无状态一次性调用
curl -s localhost:8080/api/v1/profiles                    # 五 GET 收尾
curl -s localhost:8080/api/v1/memory
curl -s localhost:8080/api/v1/tools
curl -s localhost:8080/api/v1/health && curl -s localhost:8080/api/v1/info
```

预期逐条对照 contracts/rest-api.md 的 data 形态；错误注入抽查：`POST` 缺 profile → 400；不存在会话 → 404；不存在 Agent invoke → 404。

## 四、管理台与文档（人工项）

- 浏览器 `http://localhost:8080/admin`：五页渲染真实数据、零写入口、三态占位、窄屏收导航；`/admin/sessions` 子路由刷新不 404。
- `http://localhost:8080/swagger-ui.html`：11 端点齐全可试。
- 两入口共享存储：`yokeos chat`（cli channel）聊过的会话出现在 `GET /api/v1/sessions` 列表。
- 故障注入：改错 key 重启后发消息 → 503（真实故障路径，切片测不了）。
- 凭证卫生：`grep -rn "sk-" --include="*.java" --include="*.yaml" --include="*.js"` 零命中。

## 预期总结果

`mvn clean verify` 全绿 + 上述链路全通 = 需求 §11 行 26 可演示成果达成：**REST 端点完整可用，管理台只读观察五页上线**。
