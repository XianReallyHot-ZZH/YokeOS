package com.yokeos.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yokeos.core.profile.Profile;
import com.yokeos.core.profile.Profile.Identity;
import com.yokeos.core.profile.Profile.ProviderConfig;
import com.yokeos.core.profile.Profile.Settings;
import com.yokeos.core.profile.ProfileRegistry;
import com.yokeos.core.session.InMemorySessionManager;
import com.yokeos.core.session.Session;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AgentService 编排 harness（docs/class/017-react-loop.md 第四部分）：ProfileContext 生命周期（处理期间可取、 抛异常
 * finally 也清——坑四：单请求测试永远不报错、并发复用线程才串号，必须显式钉死）、正常保存 / 异常不保存、 Profile 不存在点名报错。
 */
class AgentServiceTest {

  private final ReActLoop reActLoop = mock(ReActLoop.class);
  private final ProfileRegistry registry = mock(ProfileRegistry.class);
  private final InMemorySessionManager sessionManager = new InMemorySessionManager();
  private final AgentService service = new AgentService(registry, reActLoop, sessionManager);

  private static final Profile PROFILE =
      new Profile(
          "ops-agent",
          "运维助手",
          new Identity("运维小欧", "你是运维助手"),
          new ProviderConfig("deepseek", "test-model", 0.7),
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          List.of(),
          Settings.DEFAULT);

  @AfterEach
  void cleanUp() {
    ProfileContext.clear(); // 防测试间串号
  }

  @Test
  @DisplayName("处理期间_ProfileContext可取到当前Profile")
  void profileContextAvailableDuringProcess() {
    when(registry.get("ops-agent")).thenReturn(Optional.of(PROFILE));
    when(reActLoop.run(any(), anyString(), eq(PROFILE)))
        .thenAnswer(
            inv -> {
              assertSame(PROFILE, ProfileContext.current(), "循环执行期间可取当前 Agent 声明");
              return "ok";
            });
    Session session = new Session("s-1", "ops-agent");

    String reply = service.process(session, "你好");

    assertEquals("ok", reply);
  }

  @Test
  @DisplayName("处理中抛异常_ProfileContext也必须被清掉")
  void processThrowsExceptionProfileContextClearedInFinally() {
    when(registry.get("ops-agent")).thenReturn(Optional.of(PROFILE));
    when(reActLoop.run(any(), anyString(), eq(PROFILE))).thenThrow(new RuntimeException("boom"));
    Session session = new Session("s-1", "ops-agent");

    assertThrows(RuntimeException.class, () -> service.process(session, "hi"));

    // 坑四回归：finally 没清，下一个复用此线程的请求会拿到别人的 Profile
    assertNull(ProfileContext.current());
  }

  @Test
  @DisplayName("正常结束_Session被保存且返回循环结果")
  void normalPathSessionSavedReturnsLoopResult() {
    when(registry.get("ops-agent")).thenReturn(Optional.of(PROFILE));
    when(reActLoop.run(any(), anyString(), eq(PROFILE))).thenReturn("最终答复");
    Session session = new Session("s-1", "ops-agent");

    String reply = service.process(session, "查天气");

    assertEquals("最终答复", reply);
    assertSame(session, sessionManager.get("s-1"), "累积完的历史已保存");
  }

  @Test
  @DisplayName("异常路径_不保存Session")
  void exceptionPathSessionNotSaved() {
    when(registry.get("ops-agent")).thenReturn(Optional.of(PROFILE));
    when(reActLoop.run(any(), anyString(), eq(PROFILE))).thenThrow(new RuntimeException("boom"));
    Session session = new Session("s-1", "ops-agent");

    assertThrows(RuntimeException.class, () -> service.process(session, "hi"));

    assertNull(sessionManager.get("s-1"), "异常路径不保存半截会话");
  }

  @Test
  @DisplayName("Profile不存在_点名报错含名字")
  void profileMissingThrowsWithName() {
    when(registry.get("ghost")).thenReturn(Optional.empty());
    Session session = new Session("s-1", "ghost");

    var ex = assertThrows(IllegalStateException.class, () -> service.process(session, "hi"));

    assertTrue(ex.getMessage().contains("ghost"), "报错必须指出是哪个 Profile 不存在");
    verify(reActLoop, org.mockito.Mockito.never()).run(any(), anyString(), any());
  }
}
