package com.yokeos.cli.config;

/** 配置缺失或非法时抛出。message 里点名每一个问题键，坏掉的配置一轮就能改全——配置问题在启动时 大声失败，绝不静默（docs/TechnicalSolution.md §8.8）。 */
public class ConfigLoadException extends RuntimeException {

  /** 以点名全部问题配置键的消息创建异常。 */
  public ConfigLoadException(String message) {
    super(message);
  }
}
