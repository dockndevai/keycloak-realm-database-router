package com.ankitverma.keycloak.router.storage;

import org.keycloak.Config;
import org.keycloak.authorization.AuthorizationProvider;
import org.keycloak.authorization.jpa.store.JPAStoreFactory;
import org.keycloak.authorization.store.AuthorizationStoreFactory;
import org.keycloak.authorization.store.StoreFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

import com.ankitverma.keycloak.router.RouterConfig;

/**
 * Routes the authorization-services stores. {@link StoreFactory} methods carry no realm, so the datasource is the
 * realm of the current request, which Keycloak sets before any authorization code runs.
 */
public class RoutingAuthorizationStoreFactory implements AuthorizationStoreFactory {

    @Override
    public StoreFactory create(KeycloakSession session) {
        return RoutingProxy.create(session, StoreFactory.class, JPAStoreFactory.class,
                (s, em) -> new JPAStoreFactory(em, s.getProvider(AuthorizationProvider.class)));
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
