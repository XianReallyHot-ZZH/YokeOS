package com.yokeos.core.profile;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Profile 内存索引（技 §8.2）：按 name 快速查找。
 *
 * <p>29 节定夺（兑现 16 节 javadoc 遗留决议）：① register/remove/exists 是<strong>运行时方法</strong>而非仅启动期 方法——启动扫描与
 * 30 节运行期新增（API 创建）走同一段派生与注册代码（AgentLoader.deriveProfile 同源校验，技 §11.2「目录就位即上线」）； ②
 * 同名冲突语义维持<strong>后到覆盖</strong>——覆盖即 30 节 PUT 更新的机制基础，完整冲突策略（拒绝/版本化）放扩展阶段。
 */
public final class ProfileRegistry {

  private final Map<String, Profile> profiles = new ConcurrentHashMap<>();

  /** 注册（覆盖同名）。 */
  public void register(Profile profile) {
    profiles.put(profile.name(), profile);
  }

  /** 按名查找。 */
  public Optional<Profile> get(String name) {
    return Optional.ofNullable(profiles.get(name));
  }

  /** 全部已注册 Profile（快照）。 */
  public Collection<Profile> all() {
    return List.copyOf(profiles.values());
  }

  /** 是否已注册（29 节运行时原语）。 */
  public boolean exists(String name) {
    return profiles.containsKey(name);
  }

  /** 移除：存在返回 true、重复 remove 返回 false（幂等）——30 节 DELETE 消费。 */
  public boolean remove(String name) {
    return profiles.remove(name) != null;
  }
}
