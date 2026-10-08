package com.ankitverma.keycloak.router.storage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.provider.Provider;

import com.ankitverma.keycloak.router.RealmRouter;
import com.ankitverma.keycloak.router.TenantContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoutingProxyTest {

    /** A tiny storage SPI for the test. */
    public interface ThingProvider extends Provider {
        String describe(RealmModel realm);

        int count();

        Stream<String> all();

        String contextual();
    }

    /** Marker interface the delegate implements in addition to the SPI (like UserCredentialStore). */
    public interface Extra {
        String extra();
    }

    static final class ThingDelegate implements ThingProvider, Extra {
        static final List<String> closed = new ArrayList<>();
        final String key;
        final KeycloakSession session;

        ThingDelegate(String key, KeycloakSession session) {
            this.key = key;
            this.session = session;
        }

        @Override
        public String describe(RealmModel realm) {
            return key + ":" + realm.getId() + ":" + TenantContext.current(session);
        }

        @Override
        public int count() {
            return key.length();
        }

        @Override
        public Stream<String> all() {
            return Stream.of(key + "-1", key + "-2");
        }

        @Override
        public String contextual() {
            return TenantContext.current(session);
        }

        @Override
        public String extra() {
            return "extra-" + key;
        }

        @Override
        public void close() {
            closed.add(key);
        }
    }

    private KeycloakSession session;
    private final Map<String, Object> attributes = new HashMap<>();

    @BeforeEach
    void setUp() {
        RealmRouter.clearCache();
        RealmRouter.cache("alpha-id", "alpha", "alpha");
        RealmRouter.cache("beta-id", "beta", "beta");
        ThingDelegate.closed.clear();

        session = mock(KeycloakSession.class);
        when(session.getContext()).thenReturn(mock(KeycloakContext.class));
        when(session.getAttribute(anyString())).thenAnswer(inv -> attributes.get(inv.<String>getArgument(0)));
        doAnswer(inv -> attributes.put(inv.getArgument(0), inv.getArgument(1))).when(session).setAttribute(anyString(), any());
        doAnswer(inv -> attributes.remove(inv.<String>getArgument(0))).when(session).removeAttribute(anyString());
        JpaConnectionProvider connections = mock(JpaConnectionProvider.class);
        when(session.getProvider(JpaConnectionProvider.class)).thenReturn(connections);
        System.setProperty("realm-router.test", "true");
    }

    @AfterEach
    void tearDown() {
        RealmRouter.clearCache();
    }

    private ThingProvider proxy(Set<String> fanOut) {
        return RoutingProxy.create(session, ThingProvider.class, ThingDelegate.class,
                (s, em) -> new ThingDelegate(TenantContext.current(s), s), fanOut);
    }

    private static RealmModel realm(String id) {
        RealmModel realm = mock(RealmModel.class);
        when(realm.getId()).thenReturn(id);
        return realm;
    }

    @Test
    void routesByRealmAndSetsTenantContextDuringTheCall() {
        ThingProvider p = proxy(Set.of());
        assertEquals("alpha:alpha-id:alpha", p.describe(realm("alpha-id")));
        assertEquals("beta:beta-id:beta", p.describe(realm("beta-id")));
        assertNull(TenantContext.current(session), "context must be restored after the call");
    }

    @Test
    void implementsEveryInterfaceOfTheDelegate() {
        ThingProvider p = proxy(Set.of());
        assertInstanceOf(Extra.class, p);
        assertEquals("extra-default", ((Extra) p).extra());
    }

    @Test
    void closeClosesEveryDelegate() {
        ThingProvider p = proxy(Set.of());
        p.describe(realm("alpha-id"));
        p.describe(realm("beta-id"));
        p.close();
        assertEquals(List.of("alpha", "beta"), ThingDelegate.closed);
    }

    @Test
    void nestedContextIsKeptForCallsWithoutRealm() {
        ThingProvider p = proxy(Set.of());
        String seen = TenantContext.with(session, "beta", p::contextual);
        assertEquals("beta", seen);
    }

    @Test
    void fanOutMergesStreamsAndSums() {
        com.ankitverma.keycloak.router.RouterConfig.reset();
        // Without server configuration only the catalog exists; add tenants through the cache-independent config
        // by faking the configured keys.
        ThingProvider p = proxy(Set.of("count", "all"));
        // RouterConfig.allKeys() is [default] in a unit test: fan-out still works with a single datasource.
        assertEquals("default".length(), p.count());
        assertEquals(List.of("default-1", "default-2"), p.all().toList());
        assertTrue(RoutingProxy.handlerOf(p) != null);
        assertNull(RoutingProxy.handlerOf("not a proxy"));
    }
}
