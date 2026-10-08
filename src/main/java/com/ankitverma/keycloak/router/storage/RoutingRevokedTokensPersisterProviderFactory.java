package com.ankitverma.keycloak.router.storage;

import java.util.Set;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.jpa.session.JpaRevokedTokensPersisterProvider;
import org.keycloak.models.session.RevokedTokenPersisterProvider;
import org.keycloak.models.session.RevokedTokensPersisterProviderFactory;

import com.ankitverma.keycloak.router.RouterConfig;

/** Routes persisted token revocations: writes go to the realm of the request, reads at startup fan out. */
public class RoutingRevokedTokensPersisterProviderFactory implements RevokedTokensPersisterProviderFactory {

    private static final Set<String> FAN_OUT = Set.of("getAllRevokedTokens", "expireTokens");

    @Override
    public RevokedTokenPersisterProvider create(KeycloakSession session) {
        return RoutingProxy.create(session, RevokedTokenPersisterProvider.class, JpaRevokedTokensPersisterProvider.class,
                (s, em) -> new JpaRevokedTokensPersisterProvider(em), FAN_OUT);
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
