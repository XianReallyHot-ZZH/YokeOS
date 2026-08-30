# ReAct 循环（能力二：Agent 的大脑）

***一句话定位：Agent 的推理引擎，自己实现不套外部框架——LLM 思考是否调工具、调哪个，底座执行后回填结果，LLM 再决定下一步，循环行为完全可控。***

## 它解决什么

裸模型只会生成文本；Agent 要完成多步骤任务，需要一个驱动「思考 → 行动 → 观察」循环的引擎。把这个引擎交给外部框架，得到的是便利与黑盒的绑定：循环行为不可定制、工具执行不可控、出了问题无从查起。YokeOS 的选择是**自己实现**——核心循环约数十行 Java，完整掌握 Agent 工作机制，保留未来定制循环行为的空间。**基于这个能力**：Agent 能自主决定何时调用哪个工具，不需要业务方写死流程；多步骤任务一次对话内连续完成（先读文件、再分析、再调 API、再生成报告）；复杂业务流程不需要预先编排，Agent 在运行时动态决定执行路径。

## 怎么工作

### 算法

1. 接到用户消息，追加到 Session 对话历史
2. 组装 Prompt（system prompt + Bootstrap + Skill + 长期记忆 + 对话历史 + 可用 Tool 列表）
3. 调用 LLM Provider 获取响应
4. 响应**没有** Tool 调用 → 返回最终响应
5. **有** Tool 调用 → 底座执行 Tool，把结果作为 tool 消息追加到对话历史
6. 回到步骤 2 继续循环
7. 达到最大迭代次数（默认 10 次，可在 Agent 定义里覆盖）强制结束

![ReAct 循环：Reason → Act → Observe，循环直到无工具调用或达到最大轮数](/images/docs-react-loop.svg)

### 三个模块

**`ReActLoop`**——核心循环引擎。输入 Session 和用户消息，输出最终响应；内部维护迭代次数，调 `ProviderService` 调 LLM，调 `ToolExecutor` 执行工具，把每轮响应和工具结果累积到 Session 对话历史。

**`PromptBuilder`**——按固定顺序组装每轮 Prompt：

1. system prompt（`AGENT.md` 正文 + Bootstrap 文件 + 引用到的 Skill 正文；末尾附当前日期时间——LLM 自己不知道今天几号，定时场景的「今天」全靠这一行）
2. Memory 注入（会话历史 + 长期记忆）
3. 对话历史（按 `max_history_turns` 截断）
4. 当前 Agent 可用的 Tool 列表（Function Calling 格式）

**`ToolExecutor`**——执行 LLM 返回的 Tool 调用请求：从 `ToolRegistry` 找到对应 Tool，做 Sandbox 检查，执行，把结果包装成 `ToolResult` 返回，并写入 `tool_invocations` 审计表。失败按指数退避重试，默认最多三次。

**`AgentService`**——三种触发源共用的统一入口：把当前 Profile 放进请求上下文、跑完循环、持久化 Session、清理上下文。

### 协议适配的边界

LLM 各家协议差异（OpenAI tools、Anthropic tools、Gemini function declarations）由 Spring AI 的格式转换吸收；但**框架自带的自动 tool 执行被明确禁用**——工具的实际调度和执行完全由 `ReActLoop` + `ToolExecutor` 控制，否则会出现 tool 被调两次的问题。

## 目标用法示例

```
你：检查一下 nginx 最近的错误日志，有问题就推送到团队群
    → 第 1 轮：LLM 决定调 shell（tail 错误日志）→ 白名单校验 → 执行 → 回填
    → 第 2 轮：LLM 看到 502 错误，决定调 notify 推送摘要 → 白名单校验 → 执行 → 回填
    → 第 3 轮：无更多工具调用，返回最终响应
```

一次对话，多步任务，全程留痕：每轮 LLM 调用落 `llm_calls`，每次工具调用落 `tool_invocations`。

## 第一阶段边界

- Tool 调用不并行（一次响应里多个 Tool 调用按顺序执行）
- 不做 Agent 间任务委托、不做流式响应（SSE）
- 上下文超限用简单截断（保留 system prompt 和最近 N 轮），总结压缩放扩展阶段
