package com.ankitverma.keycloak.router.storage;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.storage.federated.UserFederatedStorageProvider;
import org.keycloak.storage.federated.UserFederatedStorageProviderFactory;
import org.keycloak.storage.jpa.JpaUserFederatedStorageProvider;

import com.ankitverma.keycloak.router.RouterConfig;

public class RoutingUserFederatedStorageProviderFactory implements UserFederatedStorageProviderFactory {

    @Override
    public UserFederatedStorageProvider create(KeycloakSession session) {
        return RoutingProxy.create(session, UserFederatedStorageProvider.class, JpaUserFederatedStorageProvider.class, JpaUserFederatedStorageProvider::new);
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
