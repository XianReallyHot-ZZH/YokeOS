package com.yokeos.tool;

import com.yokeos.core.tool.YokeTool;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

/**
 * 统一注册表：所有来源（内置 @Tool、方式三 @Tool、MCP、直接实现）汇成一个 {@link YokeTool} 注册面（技 §6.6）。 ReAct 链路只认注册面（{@link
 * com.yokeos.core.agent.ToolExecutor} 与 PromptBuilder 消费 {@link #asMap()}），对工具来源无感知—— 这是第 20 节整个
 * Tool 体系的地基。
 *
 * <p>两条注册路径：{@link #register}（直接实现 / MCP adapter）与 {@link #registerAnnotated}（@Tool 注解 Bean——schema
 * 由 Spring AI 生成，宪法 2 允许的第二件事）。重名拒绝：静默覆盖会让两个来源打架且无从查起（坑六）。
 *
 * <p>注册面为启动时快照（LinkedHashMap 插入序稳定），进程内只读——无热注册，按需加载归扩展阶段。
 */
public class ToolRegistry {

  private final Map<String, YokeTool> tools = new LinkedHashMap<>();

  /** 直接实现 / MCP adapter 的注册路径。重名抛 {@link IllegalStateException} 点名。 */
  public void register(YokeTool tool) {
    String name = tool.getName();
    if (tools.containsKey(name)) {
      throw new IllegalStateException("工具重名，拒绝注册: " + name);
    }
    tools.put(name, tool);
  }

  /**
   * 注解 Bean 的注册路径（{@code @Tool} 注解方法）：内置工具与业务方方式三共用（技 §6.5「写法完全一样」）。 schema 经
   * MethodToolCallbackProvider 自动生成（1.1.8 正路——M6 时代的 ToolCallbacks.from 已不存在，specs/005 research
   * D2）。
   */
  public void registerAnnotated(Object bean) {
    for (ToolCallback callback :
        MethodToolCallbackProvider.builder().toolObjects(bean).build().getToolCallbacks()) {
      register(new AnnotatedToolAdapter(callback));
    }
  }

  /** 是否已注册该名字。 */
  public boolean contains(String name) {
    return tools.containsKey(name);
  }

  /** 按名查找。 */
  public Optional<YokeTool> get(String name) {
    return Optional.ofNullable(tools.get(name));
  }

  /** 全部已注册工具（不可变，插入序）。 */
  public Collection<YokeTool> all() {
    return List.copyOf(tools.values());
  }

  /** 供 ToolExecutor / PromptBuilder 消费的既有 Map 形态（不可变快照）。 */
  public Map<String, YokeTool> asMap() {
    return Map.copyOf(tools);
  }

  /** 按 Profile.tools 过滤：结果恰好等于声明∩注册面（声明顺序；未知名跳过——注册校验 WARN 归 AgentLoader）。 */
  public List<YokeTool> filterByNames(List<String> names) {
    List<YokeTool> filtered = new ArrayList<>();
    for (String name : names) {
      YokeTool tool = tools.get(name);
      if (tool != null) {
        filtered.add(tool);
      }
    }
    return filtered;
  }
}
