package com.ankitverma.keycloak.router.storage;

import jakarta.persistence.EntityManager;

import org.keycloak.events.jpa.JpaEventStoreProvider;
import org.keycloak.models.KeycloakSession;

/** Exposes the protected admin-event clean-up so the router can fan it out per datasource. */
public class RoutableJpaEventStoreProvider extends JpaEventStoreProvider {

    public RoutableJpaEventStoreProvider(KeycloakSession session, EntityManager em) {
        super(session, em);
    }

    @Override
    public void clearExpiredAdminEvents() {
        super.clearExpiredAdminEvents();
    }
}
