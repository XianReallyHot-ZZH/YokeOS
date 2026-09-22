package com.yokeos.web.controller;

import com.yokeos.core.profile.Profile;
import com.yokeos.core.profile.ProfileRegistry;
import com.yokeos.web.common.ApiResponse;
import com.yokeos.web.controller.dto.InfoView;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统状态（第 26 节）：健康检查 + 运行信息。providers 取「已加载 Profile 引用到的 Provider 名单」——已配置口径（拍板④）， 不做 live 探活：探活会真调
 * API 花钱、把外部可用性变成自家状态页的可用性（research D5）。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
    justification =
        "第一阶段 web API 无认证是设计决定（内网部署假设，需求 §5.10「第一阶段不做」）；认证（API Key + JWT）列扩展阶段。"
            + "EI 两项：profileRegistry 是注入的单例服务，构造共享引用正是意图（25 节 AgentScheduler 同款；"
            + "29 节 ProfileRegistry 增设 remove 运行时原语后 SpotBugs 可变性判定升级，随门禁连锁显式落档）")
@RestController
@RequestMapping("/api/v1")
public class SystemApiController {

  private static final String PRODUCT = "yokeos";

  private final ProfileRegistry profileRegistry;

  /** 注册表注入（已配置口径的数据源）。 */
  public SystemApiController(ProfileRegistry profileRegistry) {
    this.profileRegistry = profileRegistry;
  }

  /** 健康检查：进程活着即 ok。 */
  @GetMapping("/health")
  public ApiResponse<Map<String, String>> health() {
    return ApiResponse.ok(Map.of("status", "ok"));
  }

  /** 运行信息：产品、版本（fat JAR manifest，测试环境回退 dev）、已配置 providers 去重排序。 */
  @GetMapping("/info")
  public ApiResponse<InfoView> info() {
    List<String> providers =
        profileRegistry.all().stream()
            .map(Profile::provider)
            .filter(p -> p != null && p.name() != null)
            .map(Profile.ProviderConfig::name)
            .distinct()
            .sorted()
            .toList();
    String version = getClass().getPackage().getImplementationVersion();
    return ApiResponse.ok(new InfoView(PRODUCT, version == null ? "dev" : version, providers));
  }
}
