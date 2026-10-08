package com.ankitverma.keycloak.router;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.keycloak.Config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RouterConfigTest {

    private static Config.Scope scope(Map<String, String> values) {
        Config.Scope scope = mock(Config.Scope.class);
        when(scope.get(anyString())).thenAnswer(inv -> values.get(inv.<String>getArgument(0)));
        when(scope.get(anyString(), anyString())).thenAnswer(inv -> values.getOrDefault(inv.getArgument(0), inv.getArgument(1)));
        when(scope.getArray(anyString())).thenAnswer(inv -> {
            String v = values.get(inv.<String>getArgument(0));
            return v == null ? null : v.split("\\s*,\\s*");
        });
        return scope;
    }

    @Test
    void parsesDatasourcesAndRules() {
        var settings = RouterConfig.load(scope(Map.of(
                "datasources", " alpha, beta ,default,alpha",
                "rules", "alpha:^alpha.*;beta:^b.*; ",
                "migration", "Validate")));

        assertEquals(java.util.List.of("alpha", "beta"), settings.tenantKeys());
        assertEquals(java.util.List.of("default", "alpha", "beta"), settings.allKeys());
        assertEquals(2, settings.rules().size());
        assertEquals("alpha", settings.rules().get(0).key());
        assertTrue(settings.rules().get(1).pattern().matcher("beta").matches());
        assertEquals("validate", settings.migration());
        assertEquals(RouterConfig.DEFAULT_HEADER, settings.header());
        assertEquals(RouterConfig.DEFAULT_KEY, settings.placement());
    }

    @Test
    void defaultsWhenNothingConfigured() {
        var settings = RouterConfig.load(scope(Map.of()));
        assertTrue(settings.tenantKeys().isEmpty());
        assertEquals(java.util.List.of("default"), settings.allKeys());
        assertEquals("update", settings.migration());
    }

    @Test
    void rejectsMalformedRule() {
        assertThrows(IllegalArgumentException.class, () -> RouterConfig.load(scope(Map.of("rules", "alpha"))));
        assertThrows(IllegalArgumentException.class, () -> RouterConfig.load(scope(Map.of("rules", ":^x$"))));
        assertEquals(1, RouterConfig.load(scope(Map.of("rules", "x:^x$"))).rules().size());
        var s = scope(Map.of("rules", "x:^x$"));
        when(s.get(eq("header"), anyString())).thenReturn("X-Custom");
        assertEquals("X-Custom", RouterConfig.load(s).header());
    }
}
