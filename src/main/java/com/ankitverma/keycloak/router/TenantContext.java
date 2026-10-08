package com.ankitverma.keycloak.router;

import java.util.function.Supplier;

import org.keycloak.models.KeycloakSession;

/**
 * The datasource key that storage calls on the current {@link KeycloakSession} should be served from.
 * It is a session attribute so nested provider calls made by a routed delegate stay on the same database.
 */
public final class TenantContext {

    public static final String ATTRIBUTE = "realm-router.datasource";

    private TenantContext() {
    }

    /** @return the explicitly selected datasource key, or {@code null} when none was selected. */
    public static String current(KeycloakSession session) {
        Object value = session.getAttribute(ATTRIBUTE);
        return value == null ? null : value.toString();
    }

    public static <T> T with(KeycloakSession session, String key, Supplier<T> body) {
        Object previous = session.getAttribute(ATTRIBUTE);
        session.setAttribute(ATTRIBUTE, key);
        try {
            return body.get();
        } finally {
            restore(session, previous);
        }
    }

    public static void run(KeycloakSession session, String key, Runnable body) {
        with(session, key, () -> {
            body.run();
            return null;
        });
    }

    static void restore(KeycloakSession session, Object previous) {
        if (previous == null) {
            session.removeAttribute(ATTRIBUTE);
        } else {
            session.setAttribute(ATTRIBUTE, previous);
        }
    }
}
