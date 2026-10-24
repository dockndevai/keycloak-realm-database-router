package com.ankitverma.keycloak.router.storage;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;

import com.ankitverma.keycloak.router.RealmRouter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RealmNameSyncProxyTest {

    private KeycloakSession session;
    private Query query;

    @BeforeEach
    void setUp() {
        RealmRouter.clearCache();

        query = mock(Query.class);
        when(query.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(query);
        EntityManager entityManager = mock(EntityManager.class);
        when(entityManager.createNativeQuery(org.mockito.ArgumentMatchers.anyString())).thenReturn(query);
        JpaConnectionProvider provider = mock(JpaConnectionProvider.class);
        when(provider.getEntityManager()).thenReturn(entityManager);

        session = mock(KeycloakSession.class);
        when(session.getProvider(JpaConnectionProvider.class, RealmRouter.CATALOG_CONNECTION_PROVIDER)).thenReturn(provider);
    }

    @AfterEach
    void tearDown() {
        RealmRouter.clearCache();
    }

    @Test
    void renamingTheWrappedRealmUpdatesTheCatalogAndTheCache() {
        RealmModel delegate = mock(RealmModel.class);
        when(delegate.getId()).thenReturn("realm-id");
        when(delegate.getName()).thenReturn("old-name");

        RealmModel wrapped = RealmNameSyncProxy.wrap(delegate, session, "tenant-a");
        wrapped.setName("new-name");

        verify(delegate).setName("new-name");
        verify(query).executeUpdate();
        assertEquals("tenant-a", RealmRouter.snapshotByName().get("new-name"));
        assertNull(RealmRouter.snapshotByName().get("old-name"));
    }

    @Test
    void settingTheSameNameDoesNotTouchTheCatalog() {
        RealmModel delegate = mock(RealmModel.class);
        when(delegate.getId()).thenReturn("realm-id");
        when(delegate.getName()).thenReturn("same-name");

        RealmModel wrapped = RealmNameSyncProxy.wrap(delegate, session, "tenant-a");
        wrapped.setName("same-name");

        verify(delegate).setName("same-name");
        verify(query, org.mockito.Mockito.never()).executeUpdate();
    }

    @Test
    void methodsOtherThanSetNameAreDelegatedUnchanged() {
        RealmModel delegate = mock(RealmModel.class);
        when(delegate.getId()).thenReturn("realm-id");
        when(delegate.getName()).thenReturn("a-name");

        RealmModel wrapped = RealmNameSyncProxy.wrap(delegate, session, "tenant-a");

        assertEquals("realm-id", wrapped.getId());
        assertEquals("a-name", wrapped.getName());
    }

    @Test
    void wrappingNullReturnsNull() {
        assertNull(RealmNameSyncProxy.wrap(null, session, "tenant-a"));
    }
}
