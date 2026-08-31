package com.yokeos.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Single Spring entry point that boots the whole YokeOS runtime. Component scanning covers every
 * {@code com.yokeos} package so capabilities register from their own modules
 * (docs/TechnicalSolution.md §10).
 */
@SpringBootApplication(scanBasePackages = "com.yokeos")
public class YokeosBootApplication {

  /** Delegates to Spring Boot's runner; the fat JAR's {@code Main-Class} points here for now. */
  public static void main(String[] args) {
    SpringApplication.run(YokeosBootApplication.class, args);
  }
}
