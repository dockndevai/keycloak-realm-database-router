package com.ankitverma.keycloak.router.storage;

import org.keycloak.Config;
import org.keycloak.models.IdentityProviderStorageProvider;
import org.keycloak.models.IdentityProviderStorageProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.jpa.JpaIdentityProviderStorageProvider;

import com.ankitverma.keycloak.router.RouterConfig;

/**
 * Routes identity providers. Keycloak's Infinispan IdP cache looks up the {@code jpa} provider by id and therefore
 * cannot sit in front of this provider; select this one as the default instead
 * ({@code --spi-identity-provider-storage--provider=routing}).
 */
public class RoutingIdentityProviderStorageProviderFactory implements IdentityProviderStorageProviderFactory<IdentityProviderStorageProvider> {

    @Override
    public IdentityProviderStorageProvider create(KeycloakSession session) {
        return RoutingProxy.create(session, IdentityProviderStorageProvider.class, JpaIdentityProviderStorageProvider.class,
                (s, em) -> new JpaIdentityProviderStorageProvider(s));
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
