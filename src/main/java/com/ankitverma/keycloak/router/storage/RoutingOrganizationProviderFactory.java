package com.ankitverma.keycloak.router.storage;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.organization.OrganizationProvider;
import org.keycloak.organization.OrganizationProviderFactory;
import org.keycloak.organization.jpa.JpaOrganizationProvider;

import com.ankitverma.keycloak.router.RouterConfig;

/**
 * Routes organizations. Keycloak's Infinispan organization cache looks up the {@code jpa} provider by id and
 * therefore cannot sit in front of this provider; select this one as the default instead
 * ({@code --spi-organization--provider=routing}).
 */
public class RoutingOrganizationProviderFactory implements OrganizationProviderFactory {

    @Override
    public OrganizationProvider create(KeycloakSession session) {
        return RoutingProxy.create(session, OrganizationProvider.class, JpaOrganizationProvider.class, (s, em) -> new JpaOrganizationProvider(s));
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
