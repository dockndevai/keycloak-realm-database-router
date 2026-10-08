package com.ankitverma.keycloak.router.storage;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RoleProvider;
import org.keycloak.models.RoleProviderFactory;
import org.keycloak.models.jpa.JpaRealmProvider;

import com.ankitverma.keycloak.router.RouterConfig;

public class RoutingRoleProviderFactory implements RoleProviderFactory {

    @Override
    public RoleProvider create(KeycloakSession session) {
        return RoutingProxy.create(session, RoleProvider.class, JpaRealmProvider.class, (s, em) -> new JpaRealmProvider(s, em, null, null));
    }

    @Override
    public void init(Config.Scope config) {
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
