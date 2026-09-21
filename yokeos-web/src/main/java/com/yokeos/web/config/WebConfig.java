package com.yokeos.web.config;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * 管理台（Vue SPA）静态托管（第 26 节）：{@code /admin/**} 映射到打进包的 {@code classpath:/static/admin/}， 未命中的前端路径回落
 * {@code index.html}（SPA 前端路由，刷新子路由不 404）；{@code /api/v1/**} 由 Controller 处理，不受回落影响。
 *
 * <p>缓存两档（坑二）：带内容 hash 的 assets（{@code assets/index-<hash>.js}）文件名即指纹，immutable 长缓存 365 天；
 * index.html 无 hash 必须 no-cache 每次向服务端校验——否则前端重建后浏览器用旧壳指向已删除的旧 bundle，表现为「只有某个页签能用」。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "PERMISSIVE_CORS",
    justification = "第一阶段 CORS 全开是文档拍板（技 §7.4：内网调试便利，FR-012），扩展阶段收敛白名单——不是疏漏")
@Configuration
public class WebConfig implements WebMvcConfigurer {

  private static final String INDEX = "index.html";

  private static final String SLASH = "/";

  private static final long STATIC_CACHE_DAYS = 365;

  @Override
  public void addViewControllers(ViewControllerRegistry registry) {
    // 两种入口形态都重定向到真实文件路径：资源处理器对空路径（/admin/）不走 resolver，
    // forward 视图又依赖 ViewResolver 链（MockMvc 与无模板环境下产出空响应）——302 到 /admin/index.html 是两条路都稳的形态
    registry.addRedirectViewController("/admin", "/admin/index.html");
    registry.addRedirectViewController("/admin/", "/admin/index.html");
  }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    // hash 命名的资源：比 /admin/** 更具体，优先命中；内容变则文件名变，可放心 immutable 长缓存
    registry
        .addResourceHandler("/admin/assets/**")
        .addResourceLocations("classpath:/static/admin/assets/")
        .setCacheControl(
            CacheControl.maxAge(STATIC_CACHE_DAYS, TimeUnit.DAYS).cachePublic().immutable());
    // index.html 与 SPA 回落：无 hash 必须 no-cache 强制校验，否则重建后旧壳指向已删 bundle
    registry
        .addResourceHandler("/admin/**")
        .addResourceLocations("classpath:/static/admin/")
        .setCacheControl(CacheControl.noCache())
        .resourceChain(true)
        .addResolver(
            new PathResourceResolver() {
              @Override
              protected Resource getResource(String resourcePath, Resource location)
                  throws IOException {
                // 空路径（/admin/ 入口）或目录一律回落 index.html——否则会把目录当资源返，404
                if (resourcePath.isEmpty() || resourcePath.endsWith(SLASH)) {
                  return location.createRelative(INDEX);
                }
                Resource requested = location.createRelative(resourcePath);
                // 命中真实静态文件就返它；否则回落 index.html（SPA 路由兜底——/api/v1 不在本 pattern 下，天然不受影响）
                return requested.exists() && requested.isReadable()
                    ? requested
                    : location.createRelative(INDEX);
              }
            });
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    // 第一阶段全开（技 §7.4：调试便利），扩展阶段收敛白名单——allowedOrigins("*") 回字面 *（patterns 会回显 origin）
    registry.addMapping("/**").allowedOrigins("*").allowedMethods("*").allowedHeaders("*");
  }
}
