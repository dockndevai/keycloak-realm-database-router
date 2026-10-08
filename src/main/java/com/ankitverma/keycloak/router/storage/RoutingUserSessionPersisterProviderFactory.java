package com.ankitverma.keycloak.router.storage;

import java.util.Set;

import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.jpa.session.JpaUserSessionPersisterProvider;
import org.keycloak.models.session.UserSessionPersisterProvider;
import org.keycloak.models.session.UserSessionPersisterProviderFactory;

import com.ankitverma.keycloak.router.RouterConfig;

/** Routes persisted (offline and online) user sessions. */
public class RoutingUserSessionPersisterProviderFactory implements UserSessionPersisterProviderFactory {

    private static final int DEFAULT_EXPIRATION_BATCH = 512;

    private static final Set<String> FAN_OUT = Set.of("getUserSessionsCount");

    private int expirationBatch = DEFAULT_EXPIRATION_BATCH;

    @Override
    public UserSessionPersisterProvider create(KeycloakSession session) {
        return RoutingProxy.create(session, UserSessionPersisterProvider.class, JpaUserSessionPersisterProvider.class,
                (s, em) -> new JpaUserSessionPersisterProvider(s, em, expirationBatch), FAN_OUT);
    }

    @Override
    public void init(Config.Scope config) {
        Integer configured = config.getInt("expirationBatch");
        if (configured == null) {
            configured = Config.scope("userSessionPersister", "jpa").getInt("expirationBatch");
        }
        expirationBatch = Math.max(1, configured == null ? DEFAULT_EXPIRATION_BATCH : configured);
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
