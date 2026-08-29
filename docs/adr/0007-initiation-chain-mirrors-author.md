# 立项文档链对齐作者立项期：产品定位第二篇

经参照库核实（ADR 0006 之后复查）：作者的"愿景与路线图"内容在立项期住在 `oryxos.md`（项目定位）的"## 愿景"与"## 路线图"两节里，立项期 `docs/` 没有独立的 VisionAndRoadmap.md；独立的 `VisionAndRoadmap.md`（"第一阶段总结与未来规划"）是课后演化产物（2026-07-25 入 main），是复盘文档。据此把立项文档链修订为：**业界调研 → 产品定位 → 需求分析 → 技术方案 → AI 编程指南**——五篇与参照库立项期 docs（oryxos.md / IndustryResearch / DemandAnalysis / TechnicalSolution / AiProgrammingGuide）一一对应，逐篇对照能力是本仓最高使命（ADR 0001），立项链为此让路。"愿景与路线图"归位为第一阶段结束后的阶段总结（ADR 0001 DoD 第 4 条，对应课程第 32 节），不是立项文档。CONTEXT.md 词汇与 ADR 0001/0005/0006 的相关表述已同步修订。

## Considered Options

- **维持既定链（愿景与路线图独立成篇、产品定位后置）**——被拒：与作者立项形态偏离一档，逐篇对照性减弱；且独立的愿景路线图在立项期没有原型可对照，其原型（演化版 VisionAndRoadmap）是复盘形态，立项期无"第一阶段做成了什么"可写。
- **缩到课件四篇（调研/需求/方案/AI 编程指南）**——被拒：丢掉 oryxos.md 这个对照物；产品定位承担的锚点与边界内容需要落点。

## Consequences

- 后续 skill 套件第二篇为 product-positioning（对应 oryxos.md），不再规划 vision-roadmap。
- 第一阶段结束时的"阶段总结与未来规划"才是演化版 VisionAndRoadmap 的对应物，届时可逐节对照它。
