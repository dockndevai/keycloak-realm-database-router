package com.ankitverma.keycloak.router.storage;

import java.util.Map;
import java.util.Set;

import org.keycloak.Config;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RealmProvider;
import org.keycloak.models.RealmProviderFactory;
import org.keycloak.models.RoleContainerModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.jpa.JpaRealmProvider;
import org.keycloak.provider.ProviderEvent;
import org.keycloak.provider.ProviderEventListener;

import org.jboss.logging.Logger;

import com.ankitverma.keycloak.router.RealmRouter;
import com.ankitverma.keycloak.router.RouterConfig;

/**
 * Routes the {@code realm} SPI. Realm creation decides the datasource ({@link RealmPlacement}) and records it in
 * the catalog; lookups consult the catalog mapping; listing fans out over every datasource.
 * <p>
 * Selected with {@code --spi-realm--provider=routing}, which makes Keycloak load only this factory for the SPI.
 * The stock {@code jpa} factory's role-removal listener (which casts the default realm provider to its own class)
 * is therefore not registered; this factory replicates it.
 */
public class RoutingRealmProviderFactory implements RealmProviderFactory, ProviderEventListener {

    private static final Logger LOG = Logger.getLogger(RoutingRealmProviderFactory.class);

    public static final String ID = RouterConfig.PROVIDER_ID;

    private static final Set<String> FAN_OUT = Set.of(
            "getRealmsStream", "getRealmsWithProviderTypeStream", "removeExpiredClientInitialAccess");

    private static final Map<String, RoutingProxy.MethodOverride<RealmProvider>> OVERRIDES = Map.of(
            "createRealm", RoutingRealmProviderFactory::createRealm,
            "getRealm", RoutingRealmProviderFactory::getRealm,
            "getRealmByName", RoutingRealmProviderFactory::getRealmByName,
            "removeRealm", RoutingRealmProviderFactory::removeRealm);

    @Override
    public RealmProvider create(KeycloakSession session) {
        return RoutingProxy.create(session, RealmProvider.class, JpaRealmProvider.class,
                (s, em) -> new JpaRealmProvider(s, em, null, null), FAN_OUT, OVERRIDES);
    }

    private static Object createRealm(RoutingProxy<RealmProvider> proxy, java.lang.reflect.Method method, Object[] args) {
        KeycloakSession session = proxy.session();
        String id = args.length == 2 ? (String) args[0] : null;
        String name = (String) args[args.length - 1];
        String key = RealmPlacement.choose(session, name);
        RealmModel realm = proxy.on(key, delegate -> id == null ? delegate.createRealm(name) : delegate.createRealm(id, name));
        RealmRouter.register(session, realm.getId(), name, key);
        return RealmNameSyncProxy.wrap(realm, session, key);
    }

    private static Object getRealm(RoutingProxy<RealmProvider> proxy, java.lang.reflect.Method method, Object[] args) {
        KeycloakSession session = proxy.session();
        String id = (String) args[0];
        RealmRouter.Resolution resolution = RealmRouter.resolveById(session, id);
        String key = resolution.key();
        RealmModel realm = proxy.on(key, delegate -> delegate.getRealm(id));
        if (realm == null && resolution.cached()) {
            // The cached mapping may be stale (realm moved or created on another node): re-read the catalog once.
            String fresh = RealmRouter.refreshById(session, id);
            if (!fresh.equals(resolution.key())) {
                realm = proxy.on(fresh, delegate -> delegate.getRealm(id));
                key = fresh;
            }
        }
        return RealmNameSyncProxy.wrap(realm, session, key);
    }

    private static Object getRealmByName(RoutingProxy<RealmProvider> proxy, java.lang.reflect.Method method, Object[] args) {
        KeycloakSession session = proxy.session();
        String name = (String) args[0];
        RealmRouter.Resolution resolution = RealmRouter.resolveByName(session, name);
        String key = resolution.key();
        RealmModel realm = proxy.on(key, delegate -> delegate.getRealmByName(name));
        if (realm == null && resolution.cached()) {
            String fresh = RealmRouter.refreshByName(session, name);
            if (!fresh.equals(resolution.key())) {
                realm = proxy.on(fresh, delegate -> delegate.getRealmByName(name));
                key = fresh;
            }
        }
        return RealmNameSyncProxy.wrap(realm, session, key);
    }

    private static Object removeRealm(RoutingProxy<RealmProvider> proxy, java.lang.reflect.Method method, Object[] args) {
        KeycloakSession session = proxy.session();
        String id = (String) args[0];
        String key = RealmRouter.keyForRealmId(session, id);
        String name = proxy.on(key, delegate -> {
            RealmModel realm = delegate.getRealm(id);
            return realm == null ? null : realm.getName();
        });
        boolean removed = proxy.on(key, delegate -> delegate.removeRealm(id));
        if (removed) {
            RealmRouter.unregister(session, id);
            RealmRouter.forgetName(name);
        }
        return removed;
    }

    @Override
    public void init(Config.Scope config) {
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        factory.register(this);
    }

    @Override
    public void onEvent(ProviderEvent event) {
        if (event instanceof RoleContainerModel.RoleRemovedEvent removed) {
            RoleModel role = removed.getRole();
            RoleContainerModel container = role.getContainer();
            RealmModel realm;
            if (container instanceof RealmModel r) {
                realm = r;
            } else if (container instanceof ClientModel c) {
                realm = c.getRealm();
            } else {
                return;
            }
            KeycloakSession session = removed.getKeycloakSession();
            RoutingProxy<RealmProvider> proxy = RoutingProxy.handlerOf(session.getProvider(RealmProvider.class));
            if (proxy == null) {
                LOG.warn("Default realm provider is not the routing provider; skipping role pre-removal cleanup");
                return;
            }
            String key = RealmRouter.keyForRealmId(session, realm.getId());
            proxy.on(key, delegate -> {
                ((JpaRealmProvider) delegate).preRemove(realm, role);
                return null;
            });
        }
    }

    @Override
    public void close() {
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public int order() {
        return 1000;
    }
}
