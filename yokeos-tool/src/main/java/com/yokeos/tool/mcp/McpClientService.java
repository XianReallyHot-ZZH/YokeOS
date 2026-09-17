package com.yokeos.tool.mcp;

import com.yokeos.tool.ToolRegistry;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP server 的连接维护与工具注册（技 §6.4）：启动时连接全部配置的 server，tools/list 逐个包装成 {@link
 * com.yokeos.core.tool.YokeTool} 注册进 ToolRegistry——ReAct 循环由此对工具来源无感知。
 *
 * <p>双层容错（specs/005 research D5 + clarify B）：server 级——连接/初始化/列工具失败只 WARN 跳过该 server 全部工具（外部依赖的可用性
 * 不是自己的可用性，一个外部进程的生死不能决定底座起不起得来）；工具级——单个工具注册失败（含与已注册重名）只 WARN 跳过该件、同 server 其余工具照常注册（不连坐）。
 */
public class McpClientService {

  private static final Logger LOG = LoggerFactory.getLogger(McpClientService.class);

  /** MCP 请求超时（对端挂起不拖死启动与 ReAct 循环，research D6）。 */
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

  /** 第一阶段唯一支持的 transport（SSE/HTTP 归扩展阶段）。 */
  private static final String TRANSPORT_STDIO = "stdio";

  private final McpConfigLoader configLoader;

  /** 连接工厂构造注入（测试替身；生产默认 stdio 子进程）。 */
  private final Function<McpServerConfig, McpSyncClient> clientFactory;

  /** 生产构造：默认 stdio 子进程连接工厂。 */
  public McpClientService(McpConfigLoader configLoader) {
    this(configLoader, McpClientService::connectStdio);
  }

  /**
   * @param clientFactory 测试注入替身（不起真子进程）。
   */
  public McpClientService(
      McpConfigLoader configLoader, Function<McpServerConfig, McpSyncClient> clientFactory) {
    this.configLoader = configLoader;
    this.clientFactory = clientFactory;
  }

  /** 连接全部配置 server 并注册其工具（任何失败只 WARN，绝不抛出——启动路径）。 */
  public void connectAll(ToolRegistry registry) {
    List<McpServerConfig> configs = configLoader.load();
    for (McpServerConfig config : configs) {
      if (!TRANSPORT_STDIO.equals(config.transport())) {
        // 消息编译期常量，server 名与 transport 进异常消息（CRLF 门禁——19 节 AgentLoader 同款形态）
        LOG.warn(
            "MCP server 的 transport 第一阶段不支持，跳过（支持: stdio，名字见异常消息）",
            new IllegalArgumentException(
                "name=" + config.name() + ", transport=" + config.transport()));
        continue;
      }
      try {
        McpSyncClient client = clientFactory.apply(config);
        client.initialize();
        client
            .listTools()
            .tools()
            .forEach(
                tool -> {
                  try {
                    registry.register(new McpToolAdapter(client, tool));
                  } catch (RuntimeException e) {
                    // 工具级容错：单件失败（含重名）不连坐同 server 其余工具（clarify B）
                    LOG.warn(
                        "MCP 工具注册失败，跳过该件（工具名与原因见异常消息）",
                        new IllegalStateException(
                            "tool=" + tool.name() + ", cause=" + e.getMessage(), e));
                  }
                });
      } catch (RuntimeException e) {
        // server 级容错：失联只 WARN，YokeOS 照常起（坑二）；原因与上下文全在异常链
        LOG.warn("MCP server 连接失败，跳过它的工具（原因见异常链）", e);
      }
    }
  }

  /**
   * 生产默认连接：stdio 子进程（command 按空白拆 argv——含空格参数不可表达，凭证走 env 段，research D7）。
   *
   * <p>1.1.1 的 StdioClientTransport 构造器必须显式给 JSON 映射器（research D1）——此处是 jackson3 传递件（{@code
   * tools.jackson}）在 YokeOS 的唯一消费点：JSON-RPC 序列化收在 SDK transport 层内，业务代码零接触。
   */
  private static McpSyncClient connectStdio(McpServerConfig config) {
    String[] parts = config.command().trim().split("\\s+");
    ServerParameters params =
        ServerParameters.builder(parts[0])
            .args(Arrays.copyOfRange(parts, 1, parts.length))
            .env(config.env())
            .build();
    return McpClient.sync(
            new StdioClientTransport(
                params,
                new io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper(
                    tools.jackson.databind.json.JsonMapper.builder().build())))
        .requestTimeout(REQUEST_TIMEOUT)
        .build();
  }
}
