# Quickstart: CLI——YokeOS 的命令行入口（第18节）

**Date**: 2026-09-15 | **Feature**: [spec.md](./spec.md)

 runnable 验证场景——证明本节端到端可用。实现细节见 [tasks.md](./tasks.md)；契约见 [contracts/cli.md](./contracts/cli.md)；表结构见 [data-model.md](./data-model.md)。

## 前置

- JDK 21 + Maven；`DEEPSEEK_API_KEY` 环境变量（仅场景三需要；场景一/二零网络）
- 仓库根（`.yokeos/` 不存在或可新建）

## 场景一：命令面与轻重分流（零网络，秒级）

```bash
mvn clean verify                                   # 预期：九模块全绿（含 16/17 节回归）
mvn -pl yokeos-boot -am package -DskipTests
JAR=$(ls yokeos-boot/target/*.jar | head -1)

java -jar "$JAR" --help                            # 预期：根帮助列出 12 个子命令
java -jar "$JAR" init && java -jar "$JAR" init     # 预期：创建 .yokeos/；二次运行幂等不覆盖
time java -jar "$JAR" profile list                 # 预期：秒级返回（轻命令零 Spring，计时留证据）
java -jar "$JAR" status                            # 预期：工作区/配置/库文件摘要
java -jar "$JAR" session list                      # 预期：库不存在→「暂无会话」不抛异常
java -jar "$JAR" profile create demo && java -jar "$JAR" profile create demo
                                                   # 预期：首次写出 AGENT.md 模板；二次报错不覆盖
java -jar "$JAR" profile delete demo               # 预期：agents/demo 消失、.yokeos/archive/demo 存在
java -jar "$JAR" nonsense                          # 预期：清晰报错 + 退出码非 0、无堆栈
```

## 场景二：会话持久化与跨重启（哑 key 即可，本地 SQLite）

```bash
java -jar "$JAR" provider list                     # 预期：列出 deepseek/kimi 的 name 与 base-url
```

`/context`、`/tools`、多轮历史与重启恢复的端到端走场景三（需引擎真跑）——会话层本身（幂等/隔离/恢复）已由 `SessionManagerTest`/`SessionRepositoryTest` 机器钉死（@TempDir SQLite + 模拟重启，`mvn -pl yokeos-storage -am test` 可单独复跑）。

## 场景三：真模型多轮对话（需真 key，人工项口径）

```bash
export DEEPSEEK_API_KEY=sk-xxx
java -jar "$JAR" chat --profile default            # 预期：启动日志含 "Found N JPA repository interfaces"（N>0）
```

交互内依次验证（需 §11 第 18 节可演示成果）：

1. 问一句需要外部数据的话（如「用 http_get 查一下北京现在气温」）→ 引擎走 思考→调 Tool→观察→续推，回复打印；
2. 继续追问一句 → 共享同一条会话（上下文延续）；
3. `/context` → 打印刚才的对话（含 user/assistant/tool 角色，单条截断）；
4. `/tools` → 打印本会话工具调用记录（http_get、success、耗时毫秒）；
5. `/quit` 退出 → 再次 `chat --profile default` → 上次的对话历史还在（同三元组续会话）；
6. `java -jar "$JAR" session list` → 该会话在列（三模式共享同一套存储的体感证据）。

单条模式冒烟：`java -jar "$JAR" chat --profile default --message "你好"` → 打印一句回复即退出。

## 预期断言汇总（自动化 ↔ 人工对位）

| 验证点 | 承载 |
|---|---|
| 12 命令注册 / --help / 未知命令报错 | `YokeOsCliTest`（自动） |
| 轻命令主路径（create 幂等 / delete 归档 / 库不存在提示） | `ProfileCommandTest` / `SessionListCommandTest` 等（自动） |
| 会话幂等 / 隔离 / id 单点 | `SessionManagerTest` + 全库 grep（自动 + 报告贴证） |
| 跨重启恢复 / 列名对齐 | `SessionRepositoryTest`（自动） |
| /context /tools /quit /EOF /--message | `CliChannelTest`（自动，脚本化驱动） |
| 装配完整性（Found N>0） | `YokeosRuntimeAssemblyTest`（自动，哑 key） |
| 真模型多轮 + 重进历史还在 | 场景三（人工，验收报告「剩余人工项」） |
| 轻命令秒回体感 | `time` 计时输出（半自动，报告留证据） |
