package com.yokeos.web;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * 切片测试共享引导（第 26 节）：{@code @WebMvcTest} 会从测试包向上搜 {@code @SpringBootConfiguration}， 而 yokeos-web
 * 是库模块没有主类（主类在 boot/cli）——没有本类，切片起不来。 刻意不带 {@code @ComponentScan}：slice 的类型过滤器不作用于显式扫描， 全量扫描会把未被测
 * Controller 的依赖拖进上下文（AgentApiController 缺 ProfileRegistry 即炸）。 各测试用 {@code @Import} 显式登记被测
 * Controller 与 GlobalExceptionHandler（信封断言依赖异常转译在场）。
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class WebSliceTestBoot {}
