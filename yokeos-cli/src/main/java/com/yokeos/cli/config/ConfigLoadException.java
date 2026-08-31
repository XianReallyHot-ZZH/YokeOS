package com.yokeos.cli.config;

/**
 * Configuration is missing or invalid at load time. Carries every offending key in its message so a
 * broken setup is fixable in one pass — configuration problems fail loudly at startup, never
 * silently (docs/TechnicalSolution.md §8.8).
 */
public class ConfigLoadException extends RuntimeException {

  /** Creates the exception with a message naming every offending configuration key. */
  public ConfigLoadException(String message) {
    super(message);
  }
}
