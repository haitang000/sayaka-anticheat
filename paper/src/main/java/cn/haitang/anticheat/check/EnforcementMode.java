package cn.haitang.anticheat.check;

import java.util.Locale;

/** Maximum action a check may take for its evidence. */
public enum EnforcementMode {
    ALERT,
    MITIGATE,
    PUNISH;

    public boolean allowsMitigation() {
        return this == MITIGATE || this == PUNISH;
    }

    public boolean allowsPunishment() {
        return this == PUNISH;
    }

    public static EnforcementMode parse(String value, EnforcementMode fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
