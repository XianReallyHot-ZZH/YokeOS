# Quickstart: Memory 两层记忆验证（第22节，specs/007）

> Phase 1 产出。验证场景从简到真三层：单测（契约 + 各档）→ 三档切换体感 → 真模型跨对话演示。表结构见 [data-model.md](./data-model.md)，行为契约见 [contracts/memory.md](./contracts/memory.md)。

## 前置

- JDK 21 + Maven；本仓九模块已可 `mvn clean verify` 全绿（21 节基线）
- 真模型演示需真 key：`source ~/.zshrc`（非交互 shell 读不到 zshrc 里的 key——18 节实证坑）
- mem0 档真实例为**可选项**（需自托管 Mem0 server）；无实例时 `Mem0MemoryStoreTest`（mock）与契约测试替身已覆盖行为面

## 场景一：全量门禁（自动化验收）

```bash
mvn clean verify
```

**预期**：九模块全绿。新增测试类七个（`MemoryStoreContractTest` 参数化三档 ×4 断言组、`MarkdownMemoryStoreTest`、`MemoryEntryRepositoryTest`、`Mem0MemoryStoreTest`、`MemoryServiceImplTest`、`builtin/MemoryToolsTest`、`PromptBuilderTest` 更新），前序节测试零回归。

单跑本节模块：

```bash
mvn -pl yokeos-memory -am test          # 门面/后端/工具/契约
mvn -pl yokeos-storage -am test         # memory_entries 真库
```

## 场景二：三档切换体感（markdown ↔ sqlite）

1. 工作区就绪：`yokeos init`（已初始化则跳过）
2. 默认档（markdown）跑一段对话（见场景三步骤），期间说一句值得记的话让 Agent 调 `save_memory`
3. 查看记忆落盘：`cat .yokeos/memory/MEMORY.md`——两分区 header + 带日期条目
4. 改配置 `yokeos.memory.backend: sqlite`（boot application.yaml），重启后再跑一段对话
5. 查看落库：`sqlite3 .yokeos/yokeos.db 'SELECT scope, content, created_at FROM memory_entries ORDER BY id DESC LIMIT 5;'`
6. 同一 Agent 同类交互，两次体感一致（写入 → 下一轮可用）——「墙」的人工证据

**预期**：markdown 档产物在文件、sqlite 档产物在表；`tool_invocations` 里两档的 `save_memory` 调用都有留痕。

## 场景三：真模型跨对话演示（可演示成果，需 §11 第 22 节行口径）

```bash
source ~/.zshrc   # 真 key 进环境
yokeos chat --profile <name>
```

1. **会话一**：「记住：我们项目用 Java 21，部署在 K8s」——观察模型主动调 `save_memory`（AGENT.md 的 `tools:` 需点名该工具），工具返回「已记住」
2. **验证写入**：`cat .yokeos/memory/MEMORY.md`（或 sqlite 档查表）——条目在核心/归档分区、带日期
3. **新会话**（换 user 标识或重开 CLI 实例，确保是新 Session）：「我们项目的技术栈是什么？」
4. **预期**：答复直接体现记住的偏好（Java 21 / K8s），不再要求用户重复解释——长期记忆跨 Session 生效；`GET sessions` 侧会话二的历史里能看到记忆已注入的痕迹（prompt 组装含 [2] 长期记忆位）
5. **检索路径**（可选）：新会话里问「上次讨论过什么存储选型」——观察模型调 `recall_memory`

## 场景四：坑回归抽查（自动化已覆盖，命令备查）

```bash
# 坑六：schema-003 手工建表真库可存可读
mvn -pl yokeos-storage -am test -Dtest=MemoryEntryRepositoryTest

# 坑一/二/四/五：四条契约对三档统一
mvn -pl yokeos-memory -am test -Dtest=MemoryStoreContractTest

# 坑七：buildContext 不含会话消息、prompt 组装历史不重复
mvn -pl yokeos-memory -am test -Dtest=MemoryServiceImplTest
mvn -pl yokeos-core -am test -Dtest=PromptBuilderTest

# 凭证卫生：明文 key 零出现
grep -rn "sk-" --include='*.java' --include='*.yaml' yokeos-*/src | grep -v test || echo "clean"
```

## 完成判据

- `mvn clean verify` 全绿（场景一）
- 教学文档第五部分人工项清单逐条打勾（场景二/三 + USER.md 只读 code review + 凭证卫生）
- 可演示成果口径达成：「Agent 跨对话记住用户偏好并在后续对话用到」（场景三）
