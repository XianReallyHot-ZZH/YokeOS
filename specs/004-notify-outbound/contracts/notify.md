# Contract: Notify——渠道适配接口与 notify 工具（第19节）

本文件钉死两份契约：出站渠道适配接口（对实现方）与 `notify` 内置 Tool（对 LLM 与调用链）。

## 一、`NotifyChannelAdapter`（渠道适配契约，yokeos-tool）

```java
public interface NotifyChannelAdapter {
    void send(NotifyTarget target, String content);
}
```

| 约束 | 内容 |
|------|------|
| 中立性 | 接口、`NotifyTarget` 及其 Javadoc MUST NOT 出现任何具体渠道特有词（wecom/feishu/dingtalk/slack…）——grep 守点（验收⑧） |
| 实现集 | 第一阶段唯一实现 `WebhookNotifyAdapter`（`channelType="webhook"`）；扩展阶段按 `channelType` 新增实现类，不改本签名（接口中立性自查） |
| 成功语义 | `send` 正常返回 = 已送达（对端 2xx）；**void 签名意味着没有「部分成功」** |
| 失败语义 | 任何失败 MUST 以 RuntimeException 上抛（`WebhookNotifyAdapter`：缺 url 抛 `IllegalArgumentException`；连接失败/超时/非 2xx 包 `UncheckedIOException`，research D1）——绝不静默吞掉（FR2，坑一） |
| 禁止 | 实现内不得自行重试（research D6——重复推群风险）、不得自行写审计（归 ToolExecutor 既有路径） |

## 二、`notify` 内置 Tool（对 LLM 的调用契约）

**身份**：`getName()="notify"`；`getDescription()`="把一条消息推送到当前 Agent 配置好的通知渠道"。

**入参 schema**（`getInputSchema()`，JSON Schema）：

```json
{
  "type": "object",
  "properties": {
    "content": {"type": "string", "description": "要推送的内容"},
    "channel": {"type": "string", "description": "渠道名；缺省用第一个配置的渠道"}
  },
  "required": ["content"]
}
```

**渠道解析规则**（FR3，拍板①）：

| `channel` 实参 | 行为 |
|----------------|------|
| 缺省 / null / 空白 / 字面量 `default` | 取 `notifyChannels` 第一个 |
| 与某条 `NotifyChannelConfig.name` 精确相等 | 命中该条 |
| 无任何匹配 | 失败点名该名字，**不回退默认**（避免消息发错地方） |

**失败目录**（全部 `ToolResult.error(msg, retryable=false)`，零请求、明确点名）：

| 场景 | 报错要点 |
|------|---------|
| `content` 缺失/空白 | 点名缺必填参数 content |
| 当前无 Agent 上下文（`ProfileContext.current()` 为 null） | 点名无 Agent 上下文 |
| `notifyChannels` 为空 | 点名「Profile {name} 未配置 notify_channels，无处可推」 |
| 渠道名未命中 | 点名该名字 + 不回退说明 |
| 渠道 type 无对应实现 | 点名该 type + 已装配类型集 |
| config 缺 url（进入 Adapter 后） | `IllegalArgumentException` 点名缺 url（异常路径，经 ToolExecutor 转失败） |
| 对端非 2xx / 连接失败 / 超时 | `UncheckedIOException` 上抛（异常路径，经 ToolExecutor 转失败 + 审计 success=false） |

**成功**：`ToolResult.ok("已推送")`。

**沙箱检查位**（FR5，24 节接线）：`send` 之前 MUST 过 `Sandbox.enforce(new SandboxAction(HTTP_REQUEST, url))`，与 `http_post` 共享 `http.allowed_domains`；本节仅检查位注释，24 节接线后补「enforce 先于 send」InOrder 回归于 `NotifyToolsTest`。

## 三、frontmatter `notify.channels` 声明契约（对 Agent 定义者）

```yaml
notify:
  channels:
    - name: ops-group            # 必填语义：channel 参数按它匹配；Agent 内建议唯一
      type: webhook              # 可省（缺省 webhook）；第一阶段支持集 = {"webhook"}
      config:
        url: ${TEAM_WEBHOOK_URL} # webhook 档必填；${ENV_VAR} 占位从环境变量解析，不落明文
```

| 规则 | 内容 |
|------|------|
| type 三态 | 显式支持值照收 / 缺省补 `webhook` / 不支持值记错误日志**剔除该条**、Agent 照常加载（拍板②） |
| 凭证 | webhook URL 即凭证——不进 system prompt、不进 tool schema、不进日志、不进 git |
| 生效 | 随启动扫描派生（重启生效）；运行时注册归 29 节 |
| payload | 第一阶段统一发 `{"content": ...}`（Discord 天然兼容；企微/飞书/钉钉需专用格式，扩展阶段 Adapter 适配——教学文档坑三） |

## 四、CLI 对话注册契约（FR6）

`YokeosRuntime.tools()` 的可用工具 Map 注册 `"notify"` 条目——`yokeos chat` 对话中 LLM 可见可调；`profile list`/`tool list` 等命令随之自然可见（18 节既有命令零改动）。
