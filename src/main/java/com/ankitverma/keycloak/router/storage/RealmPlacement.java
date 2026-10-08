package com.ankitverma.keycloak.router.storage;

import org.keycloak.http.HttpRequest;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelException;

import org.jboss.logging.Logger;

import com.ankitverma.keycloak.router.RouterConfig;

/**
 * Chooses the datasource for a realm that is being created:
 * request header, then realm-name rules, then the configured default.
 */
public final class RealmPlacement {

    private static final Logger LOG = Logger.getLogger(RealmPlacement.class);

    private RealmPlacement() {
    }

    public static String choose(KeycloakSession session, String realmName) {
        String key = fromHeader(session);
        String source = "header " + RouterConfig.placementHeader();
        if (key == null) {
            key = fromRules(realmName);
            source = "name rule";
        }
        if (key == null) {
            key = RouterConfig.defaultPlacement();
            source = "default placement";
        }
        if (!RouterConfig.isKnown(key)) {
            throw new ModelException("Cannot create realm '" + realmName + "': datasource '" + key + "' (from " + source
                    + ") is not one of " + RouterConfig.allKeys());
        }
        LOG.debugf("Realm '%s' will be placed on datasource '%s' (%s)", realmName, key, source);
        return key;
    }

    static String fromHeader(KeycloakSession session) {
        try {
            KeycloakContext context = session.getContext();
            HttpRequest request = context == null ? null : context.getHttpRequest();
            if (request == null || request.getHttpHeaders() == null) {
                return null;
            }
            String value = request.getHttpHeaders().getHeaderString(RouterConfig.placementHeader());
            return value == null || value.isBlank() ? null : value.trim();
        } catch (RuntimeException e) {
            // No HTTP request in this session (import, CLI, scheduled task).
            return null;
        }
    }

    static String fromRules(String realmName) {
        if (realmName == null) {
            return null;
        }
        for (RouterConfig.PlacementRule rule : RouterConfig.placementRules()) {
            if (rule.pattern().matcher(realmName).matches()) {
                return rule.key();
            }
        }
        return null;
    }
}
