package com.ankitverma.keycloak.router.storage;

import java.util.Set;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.UserProvider;
import org.keycloak.models.UserProviderFactory;
import org.keycloak.models.jpa.JpaUserProvider;

import com.ankitverma.keycloak.router.RouterConfig;

public class RoutingUserProviderFactory implements UserProviderFactory {

    private static final Set<String> FAN_OUT = Set.of("removeExpiredIssuedVerifiableCredentials");

    @Override
    public UserProvider create(KeycloakSession session) {
        return RoutingProxy.create(session, UserProvider.class, JpaUserProvider.class, JpaUserProvider::new, FAN_OUT);
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
