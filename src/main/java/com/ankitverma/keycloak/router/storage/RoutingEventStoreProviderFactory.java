package com.ankitverma.keycloak.router.storage;

import java.util.Set;

import org.keycloak.Config;
import org.keycloak.events.EventStoreProvider;
import org.keycloak.events.EventStoreProviderFactory;
import org.keycloak.events.jpa.JpaEventStoreProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.InvalidationHandler;
import org.keycloak.storage.datastore.PeriodicEventInvalidation;

import com.ankitverma.keycloak.router.RouterConfig;

/**
 * Routes login and admin events. Events carry the realm id; queries use the realm of the current request;
 * global clean-ups fan out to every datasource.
 * <p>
 * Selected with {@code --spi-events-store--provider=routing}, which makes Keycloak load only this factory; the
 * stock factory's periodic admin-event clean-up (which casts the default event store to its own class) is
 * replicated here as a fan-out over every datasource.
 */
public class RoutingEventStoreProviderFactory implements EventStoreProviderFactory, InvalidationHandler {

    private static final Set<String> FAN_OUT = Set.of("clear", "clearAdmin", "clearExpiredEvents");

    @Override
    public EventStoreProvider create(KeycloakSession session) {
        return RoutingProxy.create(session, EventStoreProvider.class, RoutableJpaEventStoreProvider.class, RoutableJpaEventStoreProvider::new, FAN_OUT);
    }

    @Override
    public void invalidate(KeycloakSession session, InvalidableObjectType type, Object... params) {
        if (type == PeriodicEventInvalidation.JPA_EVENT_STORE) {
            RoutingProxy<EventStoreProvider> proxy = RoutingProxy.handlerOf(session.getProvider(EventStoreProvider.class));
            if (proxy == null) {
                return;
            }
            for (String key : RouterConfig.allKeys()) {
                proxy.on(key, delegate -> {
                    ((RoutableJpaEventStoreProvider) delegate).clearExpiredAdminEvents();
                    return null;
                });
            }
        }
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
