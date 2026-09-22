package com.yokeos.web.controller;

import com.yokeos.core.profile.Profile;
import com.yokeos.core.profile.ProfileRegistry;
import com.yokeos.web.common.ApiResponse;
import com.yokeos.web.controller.dto.ProfileView;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 只读：列出已加载的 Profile 投影（第 26 节）——运行时注册归 29 节，本节只有启动扫描这一来源。 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
    justification =
        "第一阶段 web API 无认证是设计决定（内网部署假设，需求 §5.10「第一阶段不做」）；认证（API Key + JWT）列扩展阶段。"
            + "EI 两项：profileRegistry 是注入的单例服务，构造共享引用正是意图（25 节 AgentScheduler 同款；"
            + "29 节 ProfileRegistry 增设 remove 运行时原语后 SpotBugs 可变性判定升级，随门禁连锁显式落档）")
@RestController
@RequestMapping("/api/v1/profiles")
public class ProfileApiController {

  private final ProfileRegistry profileRegistry;

  /** 注册表注入（共享单例，构造注入即意图）。 */
  public ProfileApiController(ProfileRegistry profileRegistry) {
    this.profileRegistry = profileRegistry;
  }

  /** 全量投影：provider 段缺失时 view 内为 null，不炸（26 节 harness 口径）。 */
  @GetMapping
  public ApiResponse<List<ProfileView>> list() {
    return ApiResponse.ok(
        profileRegistry.all().stream().map(ProfileApiController::toView).toList());
  }

  private static ProfileView toView(Profile p) {
    Profile.ProviderConfig provider = p.provider();
    return new ProfileView(
        p.name(),
        p.description(),
        provider == null ? null : provider.name(),
        provider == null ? null : provider.model(),
        p.tools());
  }
}
