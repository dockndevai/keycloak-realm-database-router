package com.ankitverma.keycloak.router.jpa;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;

import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.connections.jpa.support.EntityManagerProxy;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelException;
import org.keycloak.transaction.JtaTransactionManagerLookup;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.jboss.logging.Logger;

import com.ankitverma.keycloak.router.RealmRouter;
import com.ankitverma.keycloak.router.RouterConfig;
import com.ankitverma.keycloak.router.TenantContext;
import com.ankitverma.keycloak.router.TenantDataSources;
import com.ankitverma.keycloak.router.TenantResolver;

/**
 * Hands out one {@link EntityManager} per datasource for the life of a {@link KeycloakSession}.
 * <ul>
 *   <li>{@link RouterConfig#DEFAULT_KEY}: Keycloak's own entity manager (catalog database).</li>
 *   <li>tenant keys: a Hibernate session from the shared EntityManagerFactory, opened on a lazily acquired
 *       connection from the tenant's Agroal pool. Agroal enlists that connection in the request's JTA
 *       transaction, so the tenant commits together with the rest of the Keycloak transaction.</li>
 * </ul>
 * Each tenant session registers a JTA synchronization (after Hibernate's own) that closes the session and
 * returns the connection before the transaction completes, mirroring Hibernate's release-before-completion
 * handling of the catalog connection.
 */
public class RoutingJpaConnectionProvider implements JpaConnectionProvider {

    private static final Logger LOG = Logger.getLogger(RoutingJpaConnectionProvider.class);

    private static final class TenantEntityManager {
        final String key;
        final EntityManager entityManager;
        final LazyConnection connection;
        boolean released;

        TenantEntityManager(String key, EntityManager entityManager, LazyConnection connection) {
            this.key = key;
            this.entityManager = entityManager;
            this.connection = connection;
        }
    }

    private final KeycloakSession session;
    private final Map<String, TenantEntityManager> tenants = new LinkedHashMap<>();

    public RoutingJpaConnectionProvider(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public EntityManager getEntityManager() {
        String key = TenantContext.current(session);
        if (key == null) {
            key = TenantResolver.fromContext(session);
        }
        return entityManager(key);
    }

    public EntityManager entityManager(String key) {
        if (RouterConfig.DEFAULT_KEY.equals(key)) {
            return RealmRouter.catalogEntityManager(session);
        }
        TenantEntityManager tenant = tenants.get(key);
        if (tenant == null) {
            tenant = open(key);
            tenants.put(key, tenant);
        }
        return tenant.entityManager;
    }

    /** Datasource keys that have an open entity manager in this session. */
    public Set<String> openTenants() {
        return tenants.keySet();
    }

    private TenantEntityManager open(String key) {
        if (!RouterConfig.isKnown(key)) {
            throw new ModelException("Unknown datasource '" + key + "'. Configured datasources: " + RouterConfig.allKeys());
        }
        LazyConnection connection = new LazyConnection(key);
        Connection proxy = connection.proxy();
        SessionFactory sessionFactory = TenantDataSources.entityManagerFactory().unwrap(SessionFactory.class);
        Session hibernateSession = sessionFactory.withOptions()
                .connection(proxy)
                .autoJoinTransactions(true)
                .openSession();
        hibernateSession.setFlushMode(FlushModeType.AUTO);
        EntityManager em = EntityManagerProxy.create(session, hibernateSession, true);
        TenantEntityManager tenant = new TenantEntityManager(key, em, connection);
        registerSynchronization(tenant);
        LOG.tracef("Opened entity manager for datasource '%s'", key);
        return tenant;
    }

    private void registerSynchronization(TenantEntityManager tenant) {
        Transaction transaction = activeJtaTransaction();
        if (transaction == null) {
            // No JTA transaction (for example a startup session): released when the provider closes.
            return;
        }
        try {
            transaction.registerSynchronization(new Synchronization() {
                @Override
                public void beforeCompletion() {
                    release(tenant, true);
                }

                @Override
                public void afterCompletion(int status) {
                    release(tenant, false);
                }
            });
        } catch (Exception e) {
            throw new ModelException("Failed to register transaction synchronization for datasource '" + tenant.key + "'", e);
        }
    }

    private Transaction activeJtaTransaction() {
        JtaTransactionManagerLookup lookup = session.getProvider(JtaTransactionManagerLookup.class);
        TransactionManager tm = lookup == null ? null : lookup.getTransactionManager();
        if (tm == null) {
            return null;
        }
        try {
            Transaction transaction = tm.getTransaction();
            return transaction != null && transaction.getStatus() == Status.STATUS_ACTIVE ? transaction : null;
        } catch (SystemException e) {
            LOG.warn("Unable to determine the JTA transaction status", e);
            return null;
        }
    }

    /** Flushes (when committing) and closes the tenant session, then returns its connection to the pool. */
    private void release(TenantEntityManager tenant, boolean flush) {
        if (tenant.released) {
            return;
        }
        tenant.released = true;
        tenants.remove(tenant.key, tenant);
        try {
            if (flush && tenant.entityManager.isOpen()) {
                tenant.entityManager.flush();
            }
        } finally {
            try {
                if (tenant.entityManager.isOpen()) {
                    tenant.entityManager.close();
                }
            } catch (RuntimeException e) {
                LOG.warnf(e, "Failed to close entity manager for datasource '%s'", tenant.key);
            }
            try {
                tenant.connection.close();
            } catch (Exception e) {
                LOG.warnf(e, "Failed to return connection of datasource '%s' to the pool", tenant.key);
            }
        }
    }

    @Override
    public void close() {
        for (TenantEntityManager tenant : new ArrayList<>(tenants.values())) {
            release(tenant, false);
        }
        tenants.clear();
    }
}
