package com.yokeos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/** Aggregated context must load with every module attached, on the foundation configuration. */
@SpringBootTest
class YokeosBootApplicationLoadTest {

  @Autowired private Environment environment;

  @Test
  @DisplayName("Spring 上下文随九模块聚合正常装配")
  void contextLoads() {
    // Reaching here means the aggregated context loaded; pin the application identity too.
    assertEquals("yokeos", environment.getProperty("spring.application.name"));
  }

  @Test
  @DisplayName("虚拟线程开关与端口按地基配置生效")
  void foundationConfigurationIsActive() {
    assertEquals("true", environment.getProperty("spring.threads.virtual.enabled"));
    assertEquals("8080", environment.getProperty("server.port"));
  }
}
