package com.ankitverma.keycloak.router.jpa;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.keycloak.connections.jpa.JpaConnectionProviderFactory;
import org.keycloak.connections.jpa.updater.JpaUpdaterProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import com.ankitverma.keycloak.router.RealmRouter;
import com.ankitverma.keycloak.router.RouterConfig;
import com.ankitverma.keycloak.router.TenantDataSources;

/**
 * Creates the catalog mapping table and runs Keycloak's own Liquibase changelog against every tenant database,
 * so each tenant carries the complete Keycloak schema and follows Keycloak upgrades automatically.
 * <p>
 * This runs from the connection provider's {@code postInit}, i.e. before Keycloak's model migration lists the
 * realms. Concurrent nodes are serialized by Liquibase's own {@code DATABASECHANGELOGLOCK} table in each tenant
 * database; Keycloak's {@code DBLOCK} table cannot be used here because the catalog may not be migrated yet.
 */
public class TenantSchemaMigrator {

    private static final Logger LOG = Logger.getLogger(TenantSchemaMigrator.class);

    /** Session attribute understood by Keycloak's Quarkus Liquibase updater. */
    static final String VERIFY_AND_RUN_MASTER_CHANGELOG = "VERIFY_AND_RUN_MASTER_CHANGELOG";

    private static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS " + RealmRouter.TABLE + " ("
            + "REALM_ID VARCHAR(36) NOT NULL PRIMARY KEY, "
            + "REALM_NAME VARCHAR(255) NOT NULL, "
            + "DATASOURCE_KEY VARCHAR(64) NOT NULL, "
            + "CREATED_AT BIGINT NOT NULL, "
            + "CONSTRAINT UK_REALM_DATASOURCE_NAME UNIQUE (REALM_NAME))";

    private final KeycloakSessionFactory sessionFactory;
    private final JpaConnectionProviderFactory catalogConnections;

    public TenantSchemaMigrator(KeycloakSessionFactory sessionFactory, JpaConnectionProviderFactory catalogConnections) {
        this.sessionFactory = sessionFactory;
        this.catalogConnections = catalogConnections;
    }

    public void ensureCatalogTable() {
        try (Connection connection = catalogConnections.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            try (Statement statement = connection.createStatement()) {
                statement.execute(CREATE_TABLE);
            }
            if (!autoCommit) {
                connection.commit();
            }
            RealmRouter.warm(connection);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to prepare the " + RealmRouter.TABLE + " table in the catalog database", e);
        }
    }

    public void migrateTenants() {
        String strategy = RouterConfig.migrationStrategy();
        if ("none".equals(strategy)) {
            LOG.info("Tenant schema migration disabled (migration=none)");
            return;
        }
        for (String key : RouterConfig.tenantKeys()) {
            migrate(key, strategy);
        }
    }

    private void migrate(String key, String strategy) {
        String schema = schemaFor(key);
        long start = System.currentTimeMillis();
        try (KeycloakSession session = sessionFactory.create();
             Connection connection = TenantDataSources.connection(key)) {
            session.setAttribute(VERIFY_AND_RUN_MASTER_CHANGELOG, Boolean.TRUE);
            JpaUpdaterProvider updater = session.getProvider(JpaUpdaterProvider.class);
            JpaUpdaterProvider.Status status = updater.validate(connection, schema);
            switch (status) {
                case VALID -> LOG.infof("Tenant datasource '%s': schema is up to date", key);
                case EMPTY, OUTDATED -> {
                    if ("validate".equals(strategy)) {
                        throw new IllegalStateException("Tenant datasource '" + key + "' schema is " + status
                                + " and migration=validate forbids changing it");
                    }
                    LOG.infof("Tenant datasource '%s': schema is %s, running Keycloak changelog", key, status);
                    updater.update(connection, schema);
                    LOG.infof("Tenant datasource '%s': schema migrated in %d ms", key, System.currentTimeMillis() - start);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to migrate tenant datasource '" + key + "'", e);
        }
    }

    private static String schemaFor(String key) {
        return ConfigProvider.getConfig().getOptionalValue("kc.db-schema-" + key, String.class).orElse(null);
    }
}
