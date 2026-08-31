package com.yokeos.channel.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Skeleton-period smoke test: keeps the verify gate exercised in this module from day one. */
class ChannelCliPackageSanityTest {

  @Test
  @DisplayName("模块冒烟：包名与测试基础设施可用")
  void packageFollowsGroupIdConvention() {
    assertEquals("com.yokeos.channel.cli", getClass().getPackageName());
  }
}
