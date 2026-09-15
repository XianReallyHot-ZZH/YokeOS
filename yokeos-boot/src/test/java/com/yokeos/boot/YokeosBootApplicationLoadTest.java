package com.yokeos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/**
 * 聚合后的上下文必须带着全部模块装载成功，并跑在地基配置上。
 *
 * <p>第 18 节起本测试顺带验证「双入口一套配置」：YokeosBootApplication 组件扫描吸收 YokeosRuntime 的显式装配（providerMap 启动即
 * 校验占位环境变量——surefire 配哑值，断言语义不变：上下文能起）。
 */
@SpringBootTest
class YokeosBootApplicationLoadTest {

  @Autowired private Environment environment;

  @Test
  @DisplayName("Spring 上下文随九模块聚合正常装配")
  void contextLoads() {
    // 能跑到这里说明聚合上下文已装载；顺带钉住应用标识。
    assertEquals("yokeos", environment.getProperty("spring.application.name"));
  }

  @Test
  @DisplayName("虚拟线程开关与端口按地基配置生效")
  void foundationConfigurationIsActive() {
    assertEquals("true", environment.getProperty("spring.threads.virtual.enabled"));
    assertEquals("8080", environment.getProperty("server.port"));
  }
}
