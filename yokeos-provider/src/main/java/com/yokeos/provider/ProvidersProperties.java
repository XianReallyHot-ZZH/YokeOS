package com.yokeos.provider;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 实例级 Provider 清单（application.yaml 的 {@code yokeos.providers}，FR3）：声明这个实例接了哪些 provider、凭证从哪个环境变量读。与
 * AGENT.md frontmatter 的 provider 段分工——全局层管「连接」， Profile 层管「调用参数」。
 */
@ConfigurationProperties("yokeos")
@SuppressFBWarnings(
    value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
    justification = "Spring 宽松绑定需要 setter 与可变容器（@ConfigurationProperties 惯例）；启动校验后只读")
public class ProvidersProperties {

  /** 占位符形态：${ENV_VAR}——凭证只允许这种写法，明文 key 在启动期即被拒（宪法 7 / FR7）。 */
  private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{[^}]+}$");

  /** 内置 mock provider 的保留名（第 27 节）：不连真实端点，无需 api-key / base-url，显式配置时跳过凭证校验。 */
  public static final String MOCK_PROVIDER_NAME = "mock";

  private List<ProviderItem> providers = new ArrayList<>();

  public List<ProviderItem> getProviders() {
    return providers;
  }

  public void setProviders(List<ProviderItem> providers) {
    this.providers = providers;
  }

  /**
   * 启动期校验（FR7）：名字唯一非空、api-key 必须为占位形态、占位的环境变量必须存在。缺失或非法给 清晰报错，不静默失败。env 参数注入便于单测，生产入口走 {@link
   * #validate()}。
   */
  public void validate(Function<String, String> env) {
    Set<String> seen = new HashSet<>();
    for (ProviderItem item : providers) {
      if (item.getName() == null || item.getName().isBlank()) {
        throw new IllegalStateException("yokeos.providers 存在 name 为空的条目");
      }
      if (!seen.add(item.getName())) {
        throw new IllegalStateException("yokeos.providers 名字重复：" + item.getName());
      }
      if (MOCK_PROVIDER_NAME.equals(item.getName())) {
        continue; // 内置 mock（第 27 节）：不连真实端点，api-key / base-url 校验整段跳过
      }
      if (item.getApiKey() == null || !PLACEHOLDER.matcher(item.getApiKey()).matches()) {
        throw new IllegalStateException(
            "provider [%s] 的 api-key 必须写成 ${ENV_VAR} 占位，不允许明文".formatted(item.getName()));
      }
      String var = item.getApiKey().substring(2, item.getApiKey().length() - 1);
      if (env.apply(var) == null) {
        throw new IllegalStateException(
            "provider [%s] 的环境变量 %s 未设置（api-key 占位找不到对应值）".formatted(item.getName(), var));
      }
    }
  }

  /** 生产入口：按进程环境变量校验。 */
  public void validate() {
    validate(System::getenv);
  }

  /** 单个 provider 的连接声明（name 是显式映射的键，宪法 3）。 */
  public static class ProviderItem {

    private String name;

    private String apiKey;

    /** OpenAI 兼容端点（DeepSeek / Kimi 经此接入，research D1）；可选。 */
    private String baseUrl;

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String getApiKey() {
      return apiKey;
    }

    public void setApiKey(String apiKey) {
      this.apiKey = apiKey;
    }

    public String getBaseUrl() {
      return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
    }
  }
}
