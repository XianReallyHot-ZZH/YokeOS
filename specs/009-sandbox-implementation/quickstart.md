# Quickstart: Sandbox 三重白名单验证（第24节，specs/009）

> Phase 1 产出。验证场景从简到真三层：单测（三重规则 + 接线拦截 + 收口）→ 坑回归抽查 → 真链路拦截演示（需 §11 第 24 节行口径）。值对象/配置键见 [data-model.md](./data-model.md)，接口契约/校验规则/接线位见 [contracts/sandbox.md](./contracts/sandbox.md)。

## 前置

- JDK 21 + Maven；本仓九模块已可 `mvn clean verify` 全绿（23 节基线）
- 真链路演示需真 key：`source ~/.zshrc`（非交互 shell 读不到 zshrc 里的 key——18 节实证坑）
- 无外部服务依赖（本节零新增第三方依赖；域名白名单演示用 `https://example.com` 等公共可达地址即可，不真发成功请求也行——拒绝发生在请求发出**之前**）

## 场景一：全量门禁（自动化验收）

```bash
mvn clean verify
```

**预期**：九模块全绿。新增 `WhitelistSandboxTest`（`@Nested` 三类 + 绕过 + deny-all + 对称解析），更新六个工具/档位测试类 + `ToolExecutorTest` 收口回归 + boot 五个集成测试装配段；前序节测试零回归（15 文件 35 处构造调用点同步更新后全绿）。

单跑本节模块：

```bash
mvn -pl yokeos-tool -am test                # WhitelistSandbox + 四工具拦截回归
mvn -pl yokeos-memory -am test              # 两档 Memory 拦截 + 坑六放行
mvn -pl yokeos-core -am test -Dtest=ToolExecutorTest   # 收口：不可重试 + 审计恰一条
```

## 场景二：坑回归抽查（自动化已覆盖，命令备查）

```bash
# 坑一：真实路径——symlink 出根拒绝、../ 穿越拒绝、根下新建放行、符号链接前缀目录（macOS TempDir）不假拒绝
mvn -pl yokeos-tool -am test -Dtest=WhitelistSandboxTest

# 坑二：拒绝恰一条审计且不可重试（抛异常假工具，不依赖 Sandbox 类型）
mvn -pl yokeos-core -am test -Dtest=ToolExecutorTest

# 坑五：拒绝后 IO 零发生（文件未建/进程未跑/请求未发）
mvn -pl yokeos-tool -am test -Dtest='FileToolsTest,ShellToolsTest,HttpToolsTest,NotifyToolsTest'

# 坑六：白名单含工作区时 save_memory 放行；缺省配置随 yokeos.root 动态（坑七同锚）
mvn -pl yokeos-memory -am test -Dtest='MarkdownMemoryStoreTest,MemoryStoreContractTest'
```

## 场景三：真链路拦截演示（可演示成果，需 §11 第 24 节行口径）

```bash
source ~/.zshrc                     # 真 key 进环境
yokeos chat --profile <name>
```

前提：AGENT.md 的 `tools:` 点名 `read_file`、`shell`、`http_get`（19 节坑先例——不点名则模型看不到工具）；boot `application.yaml` 保持缺省（路径=工作区、命令/域名=空 deny-all）。

1. **越权路径**：诱导 Agent「读一下 ~/.ssh/id_rsa 的内容」→ 模型调 `read_file` → 拒绝（路径不在白名单内），文件根本没被读；模型下一轮答复里能看到失败原因并改口
2. **越权命令**：「用 shell 执行 `rm /tmp/x`」（或任一命令——缺省命令白名单为空，任何 argv[0] 都拒）→ 拒绝（命令不在白名单内），进程根本没跑
3. **越权域名**：「http_get 抓一下 https://example.com」→ 拒绝（域名不在白名单内），请求根本没发出
4. **留痕核对**：

```bash
sqlite3 .yokeos/yokeos.db \
  "SELECT tool_name, success, error_message, created_at FROM tool_invocations ORDER BY id DESC LIMIT 3;"
```

**预期**：三条 `success=0`（false），`error_message` 分别为「路径不在白名单内: …」「命令不在白名单内: …」「域名不在白名单内: …」——人能读懂、恰三条（每动作一条，不重复不重试）。

5. **反向体感（坑六/坑七）**：同一会话让 Agent 记一条偏好（`save_memory` 正常放行、MEMORY.md 落盘）——默认白名单不拦自家工作区；`ls .yokeos/memory/` 确认文件在
6. **放行对照（可选）**：`http.allowed-domains` 加 `example.com` 重启，重试第 3 步 → 请求真实发出——同一道闸，白名单说了算

## 场景四：人工清单（教学文档第五部分）

- [ ] 接口中立性自查：`Sandbox.enforce(SandboxAction)` 换 `KataMicroVmSandbox` 实现签名是否要加方法、调用方是否要改——结论记验收报告
- [ ] 装配卫生 grep：

```bash
grep -rn "@Component" yokeos-tool/src/main/java/com/yokeos/tool/sandbox/ ; grep -n "yokeos-tool" yokeos-core/pom.xml
# 预期：前者无输出（无组件扫描注册）；后者无输出（core 未新增对 tool 的依赖）
```

- [ ] 既有 E2E 回归体感：20/22 节演示（工具调用、跨对话记忆）在默认白名单下照常——不被自家沙箱拦截

## 完成判据

- `mvn clean verify` 全绿（场景一）
- 教学文档第五部分人工项清单逐条打勾（场景三/四）
- 可演示成果口径达成：「越权路径/命令/域名被拦截，拦截动作留痕可查」（场景三 1~4 步）
