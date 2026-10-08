package com.ankitverma.keycloak.router.storage;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.keycloak.Config;
import org.keycloak.models.ClientProvider;
import org.keycloak.models.ClientProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.jpa.JpaRealmProvider;
import org.keycloak.protocol.saml.SamlConfigAttributes;

import com.ankitverma.keycloak.router.RouterConfig;

public class RoutingClientProviderFactory implements ClientProviderFactory {

    /** Same list as Keycloak's JpaClientProviderFactory. */
    private static final List<String> REQUIRED_SEARCHABLE_ATTRIBUTES = Arrays.asList(
            "saml_idp_initiated_sso_url_name",
            SamlConfigAttributes.SAML_ARTIFACT_BINDING_IDENTIFIER,
            "jwt.credential.issuer",
            "jwt.credential.sub");

    private Set<String> clientSearchableAttributes;

    @Override
    public ClientProvider create(KeycloakSession session) {
        return RoutingProxy.create(session, ClientProvider.class, JpaRealmProvider.class,
                (s, em) -> new JpaRealmProvider(s, em, clientSearchableAttributes, null));
    }

    @Override
    public void init(Config.Scope config) {
        Set<String> attributes = new HashSet<>(REQUIRED_SEARCHABLE_ATTRIBUTES);
        String[] configured = config.getArray("searchableAttributes");
        if (configured == null) {
            // Honour the stock provider's configuration so existing deployments keep their settings.
            configured = Config.scope("client", "jpa").getArray("searchableAttributes");
        }
        if (configured != null) {
            attributes.addAll(Arrays.asList(configured));
        }
        clientSearchableAttributes = Set.copyOf(attributes);
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
