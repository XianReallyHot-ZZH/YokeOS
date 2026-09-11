package com.yokeos.provider;

/**
 * Profile 引用的 provider 名在实例清单中不存在（FR3）：报错必须含缺失名字，绝不静默改用他家。 与加载期防线（AgentLoader 记错误日志跳过该
 * Agent）构成双防线——本异常是运行期兜底。
 */
public class ProviderNotFoundException extends RuntimeException {

  /** 报错消息含缺失名字与两处排查位置（全局清单 / frontmatter）。 */
  public ProviderNotFoundException(String providerName) {
    super(
        "未在实例 provider 清单中找到名字："
            + providerName
            + "（检查 application.yaml 的 yokeos.providers 与 AGENT.md frontmatter 的 provider.name）");
  }
}
