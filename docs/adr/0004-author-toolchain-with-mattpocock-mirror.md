# 过程工具链：作者原装 Spec Kit + harness 复刻，mattpocock 作对照学习层

「复刻作者用 AI 编程的过程」以作者原装工具链落地：在 YokeOS 安装 GitHub Spec Kit（`.specify/` + specify→clarify→plan→tasks→implement），并复刻其自研 harness（`oryxos-lesson-dev` 式节级编排、H0~H6 门禁、课型分流）。理由：课程讲义、工具、节号一一对应，遇到问题课程内容直接可查。

mattpocock 技能不开主流程，降为**对照学习层**：作者工具链 ↔ mattpocock 的映射表沉淀在 `docs/methodology/`，每节验收报告含「方法论对照」节；自有 SDD v2.1 方法论作为反检轴，每节实践后反检迭代。课级工作流走作者形态（`specs/NNN-*/` + `class-N` 分支 + merge 回 main），不逐节开 GitHub Issues；Constitution 内容不预先锁定，立项阶段写 `AiProgrammingGuide.md` 时以作者 constitution v1.1.0 八原则与 CLAUDE.md 为底本适配。

## Considered Options

- **mattpocock 技能为主流程**（grill-with-docs → to-spec → to-tickets → implement）——被拒：流程形态偏离课程，「复刻作者过程」要自己做翻译，工具链本身就是教材的一部分。
- **混合**（Spec Kit 主体 + mattpocock 环节增强）——被拒：两套门禁叠加增加每节流程成本，对照价值靠映射表已可获取。
