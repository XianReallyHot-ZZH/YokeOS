package com.yokeos.core.profile;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Profile 内存索引（技 §8.2）：按 name 快速查找。第 16 节只有「启动扫描」一条注册路径；运行时 register 热加载归第 29
 * 节。同名后到覆盖（目录扫描序），冲突处置语义 29 节定夺。
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
}
