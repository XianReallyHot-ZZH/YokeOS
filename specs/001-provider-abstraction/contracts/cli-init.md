# Contract: `yokeos init` 命令（第16节）

Phase 1 产物。本节唯一 CLI 命令契约。

## 用法

```bash
yokeos init          # 在当前目录初始化 .yokeos/ 工作区
```

## 行为契约

| 项 | 契约 |
|----|------|
| 产物 | `.yokeos/` 六子目录：`agents/` `skills/` `output/` `memory/` `sessions/` `logs/` |
| Bootstrap | 三个文件 `AGENTS.md` / `SOUL.md` / `USER.md`——最小占位模板（一级标题 + 一行用途说明 + 填写提示），不预填演示内容（clarify Q2） |
| 幂等 | 已存在的目录与文件**一律不覆盖**；二次运行正常退出 |
| Spring | 不启动 Spring 上下文，纯文件操作（宪法 4 / 性能考量：CLI 冷启动） |
| 退出码 | 成功 0；目标路径不可写等 IO 失败非 0 + 清晰错误信息 |
| 输出 | 结构化日志（禁 System.out 直写，走日志框架） |

## 验收锚点

- 二次运行前后 `.yokeos/` 内容零变化（幂等抽查，人工项）。
- 六目录 + 三文件齐全且三文件均为占位模板形态。
