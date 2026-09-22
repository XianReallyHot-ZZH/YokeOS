# Quickstart: 动态管理（第30节）

> Phase 1 产物。可运行验证场景——证明「一句话生成草稿 → 预览 → 创建 → 编辑 → 删除，全程免重启」端到端成立。契约细节见 [contracts/java-api.md](contracts/java-api.md)。

## 前置

```bash
# 1. 全量构建（本节验收锚：至少一次不带 frontend.skip——管理台两页产物真实进包）
mvn clean install -DskipTests

# 2. 准备工作区与配置（真 key 集成冒烟才需要；无 key 场景见下）
export DEEPSEEK_API_KEY=<真 key>
mkdir -p /tmp/yoke-demo-030 && cd /tmp/yoke-demo-030
java -cp "<boot classes 前置的 classpath>" com.yokeos.cli.YokeOsCli init   # 或复制既有工作区
```

`application.yaml` 生成配置键（缺失不阻断启动，generate 调用时才 503）：

```yaml
yokeos:
  agent-generation:
    provider: deepseek
    model: deepseek-chat
```

## 场景 A · 自动化 harness（机器判，`mvn test` 全绿即过）

```bash
# 单测主体（core 编排时序/回滚 + web 切片错误码/穿越）
mvn -pl yokeos-core,yokeos-web -am test

# 集成冒烟（真上下文免重启闭环 + 真丢目录 Watcher 拾取 + generate 真模型 assumeTrue）
mvn -pl yokeos-boot -am test -Dgroups=integration -DexcludedGroups=

# 全量门禁（九模块三层门禁 + 前端构建）
mvn clean verify
```

预期：全绿；`AgentLifecycleIntegrationTest` 断言 create 后不重启 GET 列表即见、DELETE 后 `.yokeos/archive/` 目录实证、cp 目录进 agents/ 轮询几秒内列表出现。

## 场景 B · 免重启闭环（真 serve，人工项主链路）

```bash
# 起 serve（25 节坑③形态：boot classes 前置 + mvn install 刷新 m2 + kimi 哑值）
java -cp "..." com.yokeos.cli.YokeOsCli serve --port 8080 &

# ① create → 不重启立即可见
curl -s -X POST localhost:8080/api/v1/agents -H 'Content-Type: application/json' \
  -d '{"name":"ping-agent","agentMarkdown":"<合法定义全文>"}'
curl -s localhost:8080/api/v1/agents | grep ping-agent        # 预期：命中

# ② PUT 覆写 → 即时生效
curl -s -X PUT localhost:8080/api/v1/agents/ping-agent -H 'Content-Type: application/json' \
  -d '{"agentMarkdown":"<新定义>"}'

# ③ invoke 一次（26 节端点回归）
curl -s -X POST localhost:8080/api/v1/agents/ping-agent/invoke -H 'Content-Type: application/json' \
  -d '{"content":"你好"}'

# ④ DELETE → 归档实证
curl -s -X DELETE localhost:8080/api/v1/agents/ping-agent
ls .yokeos/archive/                                            # 预期：ping-agent 在
curl -s localhost:8080/api/v1/agents/ping-agent                # 预期：404
```

## 场景 C · 丢目录即上线（Watcher 真拾取）

```bash
# serve 运行中，直接拷一个合法 Agent 目录（不走 API）
cp -r <某合法 Agent 目录> .yokeos/agents/dropped-agent/
sleep 5 && curl -s localhost:8080/api/v1/agents | grep dropped-agent   # 预期：命中（几秒内）

# 手工删对称
rm -rf .yokeos/agents/dropped-agent
sleep 5 && curl -s localhost:8080/api/v1/agents | grep -c dropped-agent || echo "已下线"  # 预期：0 命中
```

## 场景 D · 一句话生成（真 key；无 key 时该场景跳过不失败）

```bash
curl -s -X POST localhost:8080/api/v1/agents/generate -H 'Content-Type: application/json' \
  -d '{"sentence":"每天早上九点查北京天气，把穿搭建议发到团队群"}'
# 预期：200，data.agentMarkdown 是可解析的 AGENT.md 草稿；
#       .yokeos/agents/ 无新目录（不落盘）、GET /agents 无新增（不注册）；
#       sqlite3 .yokeos/yokeos.db "select session_id from llm_calls order by id desc limit 1"
#       预期前缀 agent-generation。

# 配置缺失路径：删掉 yokeos.agent-generation.provider 重启 → 端点 503 且消息含配置方法；进程正常起。
```

## 场景 E · 防目录穿越

```bash
curl -s -o /dev/null -w '%{http_code}' 'localhost:8080/api/v1/workspace/file?path=../../etc/passwd'
# 预期：400（normalize 后越出 .yokeos/）
curl -s -o /dev/null -w '%{http_code}' 'localhost:8080/api/v1/workspace/file?path=/etc/passwd'
# 预期：400
curl -s 'localhost:8080/api/v1/workspace/file?path=agents/<name>/AGENT.md'   # 预期：200 全文
```

## 场景 F · 管理台（浏览器）

`http://localhost:8080/admin` → Agent 管理页走「一句话新建 → 预览改 → 创建 → 列表 → 编辑 → 删除」；工作区页钻进 Agent 目录看 AGENT.md。错误提示（如 503 配置缺失）显示后端 message。

## 凭证卫生

```bash
grep -rn "sk-" yokeos-core/src yokeos-web/src yokeos-cli/src yokeos-boot/src --include='*.java' --include='*.yaml'
# 预期：零命中
```
