package com.yokeos.cli.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Foundation config loader (docs/TechnicalSolution.md §8.8): resolves {@code ${ENV_VAR}}
 * placeholders against environment variables and validates required keys up front. The sections
 * that actually consume provider/MCP credentials call this from the startup path; the class lives
 * in {@code yokeos-cli} because it is the process entry module.
 */
public final class ConfigLoader {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

  /** Resolves every {@code ${ENV_VAR}} placeholder against the real process environment. */
  public Map<String, String> resolve(Map<String, String> raw) {
    return resolve(raw, System.getenv()::get);
  }

  /**
   * Resolves {@code ${ENV_VAR}} placeholders using the given environment lookup. Reports all
   * unresolved placeholders in one error — the missing-variable list is fixable in one pass.
   */
  public Map<String, String> resolve(Map<String, String> raw, EnvLookup env) {
    Map<String, String> resolved = new LinkedHashMap<>();
    List<String> missing = new ArrayList<>();
    for (Map.Entry<String, String> entry : raw.entrySet()) {
      resolved.put(entry.getKey(), replace(entry.getValue(), entry.getKey(), env, missing));
    }
    if (!missing.isEmpty()) {
      throw new ConfigLoadException(
          "Missing environment variables for configuration: " + String.join(", ", missing));
    }
    return resolved;
  }

  /**
   * Validates that required keys exist and are non-blank after placeholder resolution. Blank counts
   * as missing: an empty value is an invalid credential, not a present one.
   */
  public void requireKeys(Map<String, String> resolved, Collection<String> required) {
    List<String> missing = new ArrayList<>();
    for (String key : required) {
      String value = resolved.get(key);
      if (value == null || value.isBlank()) {
        missing.add(key);
      }
    }
    if (!missing.isEmpty()) {
      throw new ConfigLoadException(
          "Missing required configuration keys: " + String.join(", ", missing));
    }
  }

  private String replace(String value, String key, EnvLookup env, List<String> missing) {
    if (value == null) {
      return null;
    }
    Matcher matcher = PLACEHOLDER.matcher(value);
    StringBuilder result = new StringBuilder();
    while (matcher.find()) {
      String variable = matcher.group(1).trim();
      String replacement = env.get(variable);
      if (replacement == null) {
        missing.add(variable + " (needed by " + key + ")");
        replacement = "";
      }
      matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(result);
    return result.toString();
  }

  /** Environment lookup seam; production passes {@link System#getenv}, tests pass a fixed map. */
  @FunctionalInterface
  public interface EnvLookup {

    /** Returns the environment value for the variable, or {@code null} when it is unset. */
    String get(String variable);
  }
}
