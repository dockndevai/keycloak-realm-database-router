package com.ankitverma.keycloak.router.storage;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.keycloak.Config;
import org.keycloak.models.GroupProvider;
import org.keycloak.models.GroupProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.jpa.JpaRealmProvider;

import com.ankitverma.keycloak.router.RouterConfig;

public class RoutingGroupProviderFactory implements GroupProviderFactory {

    private Set<String> groupSearchableAttributes;

    @Override
    public GroupProvider create(KeycloakSession session) {
        return RoutingProxy.create(session, GroupProvider.class, JpaRealmProvider.class,
                (s, em) -> new JpaRealmProvider(s, em, null, groupSearchableAttributes));
    }

    @Override
    public void init(Config.Scope config) {
        String[] configured = config.getArray("searchableAttributes");
        if (configured == null) {
            configured = Config.scope("group", "jpa").getArray("searchableAttributes");
        }
        groupSearchableAttributes = configured == null ? Set.of() : new HashSet<>(Arrays.asList(configured));
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
    }

    @Override
    public String getId() {
        return RouterConfig.PROVIDER_ID;
    }

    @Override
    public int order() {
        return 1000;
    }
}
