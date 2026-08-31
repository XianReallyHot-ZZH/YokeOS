package com.yokeos.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 启动整个 YokeOS 运行时的唯一 Spring 入口。组件扫描覆盖所有 {@code com.yokeos} 包，各能力从 自己的模块注册（docs/TechnicalSolution.md
 * §10）。
 */
@SpringBootApplication(scanBasePackages = "com.yokeos")
public class YokeosBootApplication {

  /** 委托给 Spring Boot 启动器；fat JAR 的 {@code Main-Class} 暂指向这里。 */
  public static void main(String[] args) {
    SpringApplication.run(YokeosBootApplication.class, args);
  }
}
