# Quickstart: Tool 体系与 MCP（第20节）验证指南

从零到验证本节特性可用的最短路径。契约细节见 [contracts/tool-registry.md](./contracts/tool-registry.md) 与 [contracts/mcp.md](./contracts/mcp.md)。

## 前置

- JDK 21、Maven、`DEEPSEEK_API_KEY` 在环境（集成冒烟用；source `~/.zshrc` 或显式注入）
- Node/npx 可选（真 MCP server 冒烟用；缺失时集成测试 assumeTrue 跳过）

## 场景 1：全量门禁（自动化验收主体）

```bash
mvn clean verify
```

预期：九模块全绿（19 节基线 + 本节新增——七个 tool 测试类、AgentLoaderTest 补用例、cli 侧断言更新），前序零回归。`-Dgroups=integration -DexcludedGroups=` 另行显式触发集成冒烟。

## 场景 2：tool list 查真实注册面

```bash
java -jar yokeos-boot/target/yokeos-boot-*.jar init   # fat JAR 在 boot（实测：cli 是普通 jar）
java -jar yokeos-boot/target/yokeos-boot-*.jar tool list
```

预期：输出恰好七件（read_file / write_file / list_dir / shell / http_get / http_post / notify）——名与描述来自真实工具实例（非手写清单）；尾注「MCP 工具随 chat/serve 启动注册，此处不列」；重复 `init` 零覆盖（含 `.yokeos/mcp_servers.yaml` 模板）。

## 场景 3：配置一个真 MCP server 并让模型调用（可演示成果主口径）

```bash
# 1. 编辑 .yokeos/mcp_servers.yaml（npx everything server 无需 key）：
#    servers:
#      - name: everything
#        transport: stdio
#        command: npx -y @modelcontextprotocol/server-everything
# 2. AGENT.md 的 tools 清单加入该 server 暴露的工具名
# 3. 启动对话
source ~/.zshrc && mvn -pl yokeos-boot -am package -DskipTests -q
java -jar yokeos-boot/target/yokeos-boot-*.jar chat
```

预期：启动日志先出现 MCP server 连接与工具注册（或失联 WARN 不阻断）；对话里让 Agent 调用该工具——答复引用工具产出，`tool_invocations` 查得到该调用（`session list` / SQLite 直查）。

## 场景 4：坏 server 不拖垮启动（失联隔离人工抽查）

在 `mcp_servers.yaml` 追加一条 `command: definitely-not-exists-cmd`，重跑 `chat`。

预期：启动照常进入对话；日志出现 WARN 点名坏 server；好 server 的工具仍可用。

## 场景 5：集成冒烟（可选，CI 默认排除）

```bash
mvn -pl yokeos-boot -am test -Dgroups=integration -DexcludedGroups= -Dtest=ToolMcpSmokeIntegrationTest
```

预期：真 stdio echo server 的工具进注册面、真模型点名调用成功、审计落表；npx 或 key 缺失时整类 assumeTrue 跳过（非失败）。

## 人工核对清单（验收报告「剩余人工项」）

- [ ] 场景 3 真跑一次（可演示成果：内置全量 + 接入外部 MCP server）
- [ ] 检查位抽查：`grep -rn "Sandbox 检查位" yokeos-tool/src/main` 命中 FileTools×3 / ShellTools×1 / HttpTools×2（NotifyTools 19 节既有），形态一致
- [ ] 点名 WARN 抽查：`tools:` 写未注册名，启动日志 WARN 点名
- [ ] 凭证卫生：`grep -rn "sk-\|api_key:" --include="*.yaml" --include="*.md" .yokeos/ docs/ yokeos-*/src` 零明文命中
- [ ] 宪法 2 grep：`grep -rn "internalToolExecutionEnabled(true)\|\.tools(" yokeos-*/src/main` 零命中（自动执行关闭延续）
