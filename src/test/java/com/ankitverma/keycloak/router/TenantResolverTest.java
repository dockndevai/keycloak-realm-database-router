package com.ankitverma.keycloak.router;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.events.Event;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TenantResolverTest {

    private KeycloakSession session;
    private KeycloakContext context;
    private final Map<String, Object> attributes = new HashMap<>();

    @BeforeEach
    void setUp() {
        RealmRouter.clearCache();
        RealmRouter.cache("alpha-id", "alpha", "alpha");
        RealmRouter.cache("beta-id", "beta", "beta");
        RealmRouter.cache("master-id", "master", RouterConfig.DEFAULT_KEY);

        session = mock(KeycloakSession.class);
        context = mock(KeycloakContext.class);
        when(session.getContext()).thenReturn(context);
        when(session.getAttribute(anyString())).thenAnswer(inv -> attributes.get(inv.<String>getArgument(0)));
    }

    @AfterEach
    void tearDown() {
        RealmRouter.clearCache();
    }

    private static RealmModel realm(String id) {
        RealmModel realm = mock(RealmModel.class);
        when(realm.getId()).thenReturn(id);
        return realm;
    }

    @Test
    void realmArgumentWins() {
        assertEquals("alpha", TenantResolver.resolve(session, new Object[]{"x", realm("alpha-id")}));
        assertEquals(RouterConfig.DEFAULT_KEY, TenantResolver.resolve(session, new Object[]{realm("master-id")}));
    }

    @Test
    void clientRoleAndEventCarryTheirRealm() {
        RealmModel beta = realm("beta-id");
        RealmModel alpha = realm("alpha-id");
        ClientModel client = mock(ClientModel.class);
        when(client.getRealm()).thenReturn(beta);
        assertEquals("beta", TenantResolver.resolve(session, new Object[]{client}));

        RoleModel clientRole = mock(RoleModel.class);
        when(clientRole.getContainer()).thenReturn(client);
        assertEquals("beta", TenantResolver.resolve(session, new Object[]{clientRole}));

        RoleModel realmRole = mock(RoleModel.class);
        when(realmRole.getContainer()).thenReturn(alpha);
        assertEquals("alpha", TenantResolver.resolve(session, new Object[]{realmRole}));

        Event event = new Event();
        event.setRealmId("alpha-id");
        assertEquals("alpha", TenantResolver.resolve(session, new Object[]{event}));
    }

    @Test
    void userWithoutRealmFallsBackToContext() {
        UserModel user = mock(UserModel.class);
        assertNull(TenantResolver.fromArgument(session, user));

        RealmModel beta = realm("beta-id");
        when(context.getRealm()).thenReturn(beta);
        assertEquals("beta", TenantResolver.resolve(session, new Object[]{user}));
    }

    @Test
    void explicitTenantContextBeatsRequestRealm() {
        RealmModel beta = realm("beta-id");
        when(context.getRealm()).thenReturn(beta);
        attributes.put(TenantContext.ATTRIBUTE, "alpha");
        assertEquals("alpha", TenantResolver.resolve(session, new Object[]{"no realm here"}));
        assertEquals("alpha", TenantResolver.resolve(session, null));
    }

    @Test
    void nothingKnownMeansCatalog() {
        assertEquals(RouterConfig.DEFAULT_KEY, TenantResolver.resolve(session, new Object[]{42}));
    }
}
