package com.yokeos.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 骨架期冒烟测试：让 verify 门禁从第一天起就在本模块真实运转。 */
class ToolPackageSanityTest {

  @Test
  @DisplayName("模块冒烟：包名与测试基础设施可用")
  void packageFollowsGroupIdConvention() {
    assertEquals("com.yokeos.tool", getClass().getPackageName());
  }
}
