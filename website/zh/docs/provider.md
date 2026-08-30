# Provider 路由（能力一：对接 LLM）

***一句话定位：Provider 抽象统一对接主流大模型——Agent 不感知具体调的是哪家，运行时切换零代码改动，支持本地推理。***

## 它解决什么

没有这层抽象时，业务方每换一家模型供应商就要改一遍调用代码、处理一遍各家协议差异。YokeOS 把 LLM 调用的复杂度收敛到一个 Provider 接口后面：主流模型（DeepSeek、通义、Kimi、智谱、混元、豆包、Anthropic、OpenAI 等）由 Spring AI Alibaba 的现成 connector 接入，YokeOS 把它们包装成统一的 Provider，不重复造轮子。**基于这个能力**：同一个底座上，简单任务走便宜模型、复杂任务走强模型；接企业自有的本地推理服务（Ollama、vLLM），数据完全不出企业；多 Provider 并存，做一份报告可以让规划用便宜模型、综合用强模型。

## 怎么工作

**薄包装，不做零层也不做厚层。** 直接用框架的 `ChatClient`/`ChatModel`（零层）调用链最短，但多 Provider 映射、token 统计落库、Provider 状态查询这些横切关注点会散落各处；在之上加厚层（重定义一套通信协议）则重复框架已做好的协议转换。取中间：`ProviderService` 做一层薄包装——转发调用、持有映射、记录审计，不碰协议。

**显式映射，不靠类型扫描。** 配多个 Provider 时容器里会有多个同类型的 `ChatModel` Bean，仅靠「扫描容器里所有 Bean」无法可靠区分哪个是 deepseek、哪个是 kimi。YokeOS 维护一份显式的 **provider name → ChatModel 映射**：每个 Provider 声明唯一的 provider name，`ProviderService` 按名字建立映射表，Agent 通过名字引用。

![Provider 架构：ReAct 循环 → ProviderService → 显式映射的 ChatModel → 各家 LLM API](/images/docs-provider.svg)

**每次调用落审计。** ReAct 循环调 LLM 时传入 Profile 和 Prompt，`ProviderService` 选对应的底层模型完成调用，并把每次调用的 token 使用量与耗时写入 `llm_calls` 表——成本透明的基础版从第一阶段就有。

**配置与密钥。** Provider 的 API key 走 `${ENV_VAR}` 占位从环境变量解析，不明文写进配置；配置加载时做必填项与格式校验，缺失或非法时给清晰报错，不静默失败。

## 目标用法示例

```yaml
# AGENT.md frontmatter — 换供应商零代码改动
provider:
  name: deepseek          # 换成 qwen / kimi / ollama，只改这一行
  model: deepseek-chat
  api_key: ${DEEPSEEK_API_KEY}
```

多 Provider 并存时，不同 Agent 绑不同 Provider；同一个 Agent 的不同任务也可以在扩展阶段按策略路由（见边界）。

## 第一阶段边界

- **不做 fallback 和 hedge racing**：Provider 故障时直接报错给 Agent；failover 链路、circuit breaker 放扩展阶段
- 成本透明只做基础版（token 使用量、Provider、模型落审计表）；完整的成本聚合与 Web 看板放扩展阶段
- 一句话生成 Agent 草稿所用系统默认 Provider 走独立配置键，未配置时不静默回退——调用时返回明确错误
