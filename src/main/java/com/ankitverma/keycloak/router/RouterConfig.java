package com.ankitverma.keycloak.router;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.keycloak.Config;

/**
 * Router settings. All options live under the {@code connectionsJpa} SPI, provider {@code routing}:
 *
 * <pre>
 *   --spi-connections-jpa--routing--datasources=alpha,beta,gamma   named datasources that hold realms
 *   --spi-connections-jpa--routing--header=X-Keycloak-Datasource   request header used when creating a realm
 *   --spi-connections-jpa--routing--rules=alpha:^alpha$;beta:^b.*  realm-name regex rules (first match wins)
 *   --spi-connections-jpa--routing--placement=default              fallback datasource for new realms
 *   --spi-connections-jpa--routing--migration=update               update | validate | none
 * </pre>
 *
 * The datasource key {@value #DEFAULT_KEY} always refers to Keycloak's main database (the "catalog"),
 * which holds the master realm and the realm-to-datasource mapping table.
 */
public final class RouterConfig {

    public static final String SPI_NAME = "connectionsJpa";
    public static final String PROVIDER_ID = "routing";
    public static final String DEFAULT_KEY = "default";
    public static final String DEFAULT_HEADER = "X-Keycloak-Datasource";

    public record PlacementRule(String key, Pattern pattern) {
    }

    record Settings(List<String> tenantKeys, List<String> allKeys, String header, String placement,
                            List<PlacementRule> rules, String migration) {
    }

    private static volatile Settings settings;

    private RouterConfig() {
    }

    /** Re-reads the configuration on next access (used by tests and after config changes). */
    public static void reset() {
        settings = null;
    }

    public static List<String> tenantKeys() {
        return settings().tenantKeys();
    }

    /** {@value #DEFAULT_KEY} followed by every configured tenant datasource. */
    public static List<String> allKeys() {
        return settings().allKeys();
    }

    public static boolean isKnown(String key) {
        return key != null && settings().allKeys().contains(key);
    }

    public static String placementHeader() {
        return settings().header();
    }

    public static String defaultPlacement() {
        return settings().placement();
    }

    public static List<PlacementRule> placementRules() {
        return settings().rules();
    }

    public static String migrationStrategy() {
        return settings().migration();
    }

    private static Settings settings() {
        Settings s = settings;
        if (s == null) {
            s = load(Config.scope(SPI_NAME, PROVIDER_ID));
            settings = s;
        }
        return s;
    }

    static Settings load(Config.Scope scope) {
        List<String> tenants = new ArrayList<>();
        String[] configured = scope.getArray("datasources");
        if (configured != null) {
            for (String value : configured) {
                String key = value.trim();
                if (!key.isEmpty() && !DEFAULT_KEY.equals(key) && !tenants.contains(key)) {
                    tenants.add(key);
                }
            }
        }
        List<String> all = new ArrayList<>();
        all.add(DEFAULT_KEY);
        all.addAll(tenants);

        List<PlacementRule> rules = new ArrayList<>();
        String rawRules = scope.get("rules");
        if (rawRules != null && !rawRules.isBlank()) {
            for (String rule : rawRules.split(";")) {
                String trimmed = rule.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                int idx = trimmed.indexOf(':');
                if (idx <= 0 || idx == trimmed.length() - 1) {
                    throw new IllegalArgumentException("Invalid placement rule '" + trimmed + "', expected <datasource>:<regex>");
                }
                rules.add(new PlacementRule(trimmed.substring(0, idx).trim(), Pattern.compile(trimmed.substring(idx + 1).trim())));
            }
        }

        return new Settings(List.copyOf(tenants), List.copyOf(all),
                scope.get("header", DEFAULT_HEADER),
                scope.get("placement", DEFAULT_KEY),
                List.copyOf(rules),
                scope.get("migration", "update").trim().toLowerCase());
    }
}
