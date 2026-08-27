# 技术栈与 OryxOS main 完全同款

与参照库 `origin/main` 逐项对齐：JDK 21、Spring Boot 3.5.16（parent）、Spring AI 1.1.8、Maven 多模块、Vue3 单文件管理台、frontend-maven-plugin（Node 20.18）；groupId 换为 `com.yokeos`。理由：逐节对照时依赖行为完全一致，作者 lock 过的坑直接继承；版本微调省不了什么，却制造对照噪音。

## Considered Options

- **换栈**（TypeScript/Go/Rust）换取额外学习收益——被拒：「复刻功能与实现」是明示目标，换栈使逐节对照失效。
- **同 major 但用更新 patch**——被拒：同款 rationale，无差别更新只增加 diff 噪音。
