package dev.silicium.compat;

import dev.silicium.SiliciumConstants;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Reads companion optimization mod configuration files (such as {@code config/lithium.properties})
 * at Mixin-load time so Silicium can cooperate intelligently: if a user explicitly disables a
 * specific Lithium rule (e.g. {@code mixin.math.sine_lut=false}), Silicium automatically steps in
 * and activates its own module for that subsystem.
 */
public final class CompanionConfigReader {
	private final Map<String, Boolean> lithiumRules;

	public CompanionConfigReader(Map<String, Boolean> lithiumRules) {
		this.lithiumRules = Collections.unmodifiableMap(new HashMap<>(lithiumRules));
	}

	public static CompanionConfigReader empty() {
		return new CompanionConfigReader(Map.of());
	}

	public static CompanionConfigReader fromConfigDir(Path configDir) {
		if (configDir == null) {
			return empty();
		}
		Path lithiumProps = configDir.resolve("lithium.properties");
		if (!Files.isRegularFile(lithiumProps)) {
			return empty();
		}
		Properties props = new Properties();
		try (Reader reader = Files.newBufferedReader(lithiumProps, StandardCharsets.UTF_8)) {
			props.load(reader);
		} catch (IOException e) {
			SiliciumConstants.LOGGER.debug("Could not read lithium.properties: {}", e.getMessage());
			return empty();
		}

		Map<String, Boolean> parsed = new HashMap<>();
		for (String key : props.stringPropertyNames()) {
			String raw = props.getProperty(key);
			if (raw == null) {
				continue;
			}
			String trimmed = raw.trim();
			if ("true".equalsIgnoreCase(trimmed)) {
				parsed.put(key.trim(), Boolean.TRUE);
			} else if ("false".equalsIgnoreCase(trimmed)) {
				parsed.put(key.trim(), Boolean.FALSE);
			}
		}
		return new CompanionConfigReader(parsed);
	}

	/**
	 * Returns {@code true} if the specified Lithium mixin rule (e.g. {@code "mixin.math.sine_lut"})
	 * or any of its parent hierarchy segments (e.g. {@code "mixin.math"} or {@code "mixin"}) is
	 * explicitly disabled ({@code false}) in {@code config/lithium.properties}.
	 */
	public boolean isLithiumRuleDisabled(String ruleKey) {
		if (ruleKey == null || ruleKey.isEmpty() || this.lithiumRules.isEmpty()) {
			return false;
		}
		String current = ruleKey;
		while (true) {
			Boolean setting = this.lithiumRules.get(current);
			if (Boolean.FALSE.equals(setting)) {
				return true;
			}
			int dot = current.lastIndexOf('.');
			if (dot <= 0) {
				break;
			}
			current = current.substring(0, dot);
		}
		return false;
	}
}
