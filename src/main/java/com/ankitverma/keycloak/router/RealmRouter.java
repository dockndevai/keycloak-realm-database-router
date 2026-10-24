package com.ankitverma.keycloak.router;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import jakarta.persistence.EntityManager;

import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSession;

import org.jboss.logging.Logger;

/**
 * Maps realms to datasource keys. The authoritative copy is the {@value #TABLE} table in the catalog database;
 * an in-memory cache in front of it is warmed at startup and filled on demand.
 * <p>
 * A realm without a row lives in the catalog ({@link RouterConfig#DEFAULT_KEY}); that includes {@code master}.
 */
public final class RealmRouter {

    private static final Logger LOG = Logger.getLogger(RealmRouter.class);

    public static final String TABLE = "REALM_DATASOURCE";
    /** Provider id of Keycloak's own JPA connection provider in the Quarkus distribution. */
    public static final String CATALOG_CONNECTION_PROVIDER = "quarkus";

    private static final ConcurrentMap<String, String> BY_ID = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, String> BY_NAME = new ConcurrentHashMap<>();

    /** Result of a lookup: the key and whether it was answered from the in-memory cache. */
    public record Resolution(String key, boolean cached) {
    }

    private RealmRouter() {
    }

    public static String keyForRealmId(KeycloakSession session, String realmId) {
        return resolveById(session, realmId).key();
    }

    public static Resolution resolveById(KeycloakSession session, String realmId) {
        if (realmId == null) {
            return new Resolution(RouterConfig.DEFAULT_KEY, true);
        }
        String key = BY_ID.get(realmId);
        if (key != null) {
            return new Resolution(key, true);
        }
        return new Resolution(lookup(session, "REALM_ID", realmId), false);
    }

    public static Resolution resolveByName(KeycloakSession session, String realmName) {
        if (realmName == null) {
            return new Resolution(RouterConfig.DEFAULT_KEY, true);
        }
        String key = BY_NAME.get(realmName);
        if (key != null) {
            return new Resolution(key, true);
        }
        return new Resolution(lookup(session, "REALM_NAME", realmName), false);
    }

    /** Drops the cached entry and re-reads the catalog. Used to self-heal after another node changed the mapping. */
    public static String refreshById(KeycloakSession session, String realmId) {
        BY_ID.remove(realmId);
        return lookup(session, "REALM_ID", realmId);
    }

    public static String refreshByName(KeycloakSession session, String realmName) {
        BY_NAME.remove(realmName);
        return lookup(session, "REALM_NAME", realmName);
    }

    private static String lookup(KeycloakSession session, String column, String value) {
        EntityManager em = catalogEntityManager(session);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT REALM_ID, REALM_NAME, DATASOURCE_KEY FROM " + TABLE + " WHERE " + column + " = :v")
                .setParameter("v", value)
                .getResultList();
        if (rows.isEmpty()) {
            // Negative cache: realms without a row live in the catalog database.
            if ("REALM_ID".equals(column)) {
                BY_ID.put(value, RouterConfig.DEFAULT_KEY);
            } else {
                BY_NAME.put(value, RouterConfig.DEFAULT_KEY);
            }
            return RouterConfig.DEFAULT_KEY;
        }
        Object[] row = rows.get(0);
        String key = String.valueOf(row[2]);
        cache(String.valueOf(row[0]), String.valueOf(row[1]), key);
        return key;
    }

    public static void register(KeycloakSession session, String realmId, String realmName, String key) {
        EntityManager em = catalogEntityManager(session);
        em.createNativeQuery("INSERT INTO " + TABLE + " (REALM_ID, REALM_NAME, DATASOURCE_KEY, CREATED_AT) VALUES (:id, :name, :key, :ts)")
                .setParameter("id", realmId)
                .setParameter("name", realmName)
                .setParameter("key", key)
                .setParameter("ts", System.currentTimeMillis())
                .executeUpdate();
        cache(realmId, realmName, key);
        LOG.infof("Realm '%s' (%s) placed on datasource '%s'", realmName, realmId, key);
    }

    /** Keeps the catalog row and the {@code BY_NAME} cache in sync with a realm rename. */
    public static void renameRealm(KeycloakSession session, String realmId, String oldName, String newName, String key) {
        EntityManager em = catalogEntityManager(session);
        em.createNativeQuery("UPDATE " + TABLE + " SET REALM_NAME = :newName WHERE REALM_ID = :id")
                .setParameter("newName", newName)
                .setParameter("id", realmId)
                .executeUpdate();
        forgetName(oldName);
        cache(realmId, newName, key);
        LOG.infof("Realm %s renamed from '%s' to '%s' on datasource '%s'", realmId, oldName, newName, key);
    }

    public static void unregister(KeycloakSession session, String realmId) {
        EntityManager em = catalogEntityManager(session);
        em.createNativeQuery("DELETE FROM " + TABLE + " WHERE REALM_ID = :id")
                .setParameter("id", realmId)
                .executeUpdate();
        String key = BY_ID.remove(realmId);
        BY_NAME.values().removeIf(v -> false); // no-op, kept for symmetry
        BY_NAME.entrySet().removeIf(e -> {
            // We do not know the name here; drop every name entry pointing at a realm id we no longer track
            // by re-validating lazily on the next lookup instead. Only the id entry is removed eagerly.
            return false;
        });
        LOG.infof("Realm %s removed from datasource '%s'", realmId, key);
    }

    /** Removes a cached name entry (called when a realm is deleted or renamed). */
    public static void forgetName(String realmName) {
        if (realmName != null) {
            BY_NAME.remove(realmName);
        }
    }

    public static void cache(String realmId, String realmName, String key) {
        BY_ID.put(realmId, key);
        if (realmName != null) {
            BY_NAME.put(realmName, key);
        }
    }

    /** Loads every mapping from the catalog using a plain JDBC connection (startup). */
    public static void warm(Connection connection) throws SQLException {
        int count = 0;
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT REALM_ID, REALM_NAME, DATASOURCE_KEY FROM " + TABLE)) {
            while (rs.next()) {
                cache(rs.getString(1), rs.getString(2), rs.getString(3));
                count++;
            }
        }
        LOG.infof("Realm router warmed with %d mapping(s)", count);
    }

    /** @return realm name to datasource key for every cached realm (including catalog realms). */
    public static Map<String, String> snapshotByName() {
        return new TreeMap<>(BY_NAME);
    }

    public static void clearCache() {
        BY_ID.clear();
        BY_NAME.clear();
    }

    /** Keycloak's own (catalog) entity manager for the session. */
    public static EntityManager catalogEntityManager(KeycloakSession session) {
        JpaConnectionProvider provider = session.getProvider(JpaConnectionProvider.class, CATALOG_CONNECTION_PROVIDER);
        if (provider == null) {
            throw new IllegalStateException("Keycloak's '" + CATALOG_CONNECTION_PROVIDER + "' JPA connection provider is not available");
        }
        return provider.getEntityManager();
    }
}
