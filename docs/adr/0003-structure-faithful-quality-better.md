# 产品保真度：结构照抄 + 质量优化

模块划分、依赖方向、契约接口设计完全镜像 oryxos：`yokeos-core / boot / cli / channel-cli / memory / provider / storage / tool / web` + `my-agent`（main 上课后演进的 `oryxos-knowledge`、docker、Makefile 第一阶段不建）。已知瑕疵不继承：cli 零测试要补上、git 身份与 commit message 规范化。结构性改良（如 Channel 降维重设计为 SPI）记入 Phase 2 候选清单，第一阶段不做——第一遍学习的价值在于与原版逐处对得上。

## Considered Options

- **严格照抄含瑕疵**——被拒：瑕疵是作者自认的疏漏，不是设计。
- **允许结构改良**——被拒：偏离课程讲授的原设计，对照学习认知负担加重；改良留给有判断力的第二阶段。
