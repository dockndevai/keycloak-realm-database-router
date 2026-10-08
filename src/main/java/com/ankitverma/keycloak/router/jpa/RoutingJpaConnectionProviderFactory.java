package com.ankitverma.keycloak.router.jpa;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import org.keycloak.Config;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.connections.jpa.JpaConnectionProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;

import org.eclipse.microprofile.config.ConfigProvider;
import org.hibernate.internal.SessionFactoryImpl;
import org.jboss.logging.Logger;

import com.ankitverma.keycloak.router.RouterConfig;
import com.ankitverma.keycloak.router.TenantDataSources;

/**
 * Default provider for the {@code connectionsJpa} SPI. Select it with
 * {@code --spi-connections-jpa--provider-default=routing} ({@code provider-default}, not {@code provider}: Keycloak's
 * own {@code quarkus} factory must stay loaded because it owns the catalog database and its migration).
 * <p>
 * Plain JDBC connections (used by Keycloak for locking and schema migration) always go to the catalog database,
 * exactly like the stock provider. Entity managers are routed per datasource by {@link RoutingJpaConnectionProvider}.
 */
public class RoutingJpaConnectionProviderFactory implements JpaConnectionProviderFactory {

    private static final Logger LOG = Logger.getLogger(RoutingJpaConnectionProviderFactory.class);

    public static final String ID = RouterConfig.PROVIDER_ID;

    @Override
    public JpaConnectionProvider create(KeycloakSession session) {
        return new RoutingJpaConnectionProvider(session);
    }

    @Override
    public Connection getConnection() {
        SessionFactoryImpl sessionFactoryImpl = TenantDataSources.entityManagerFactory().unwrap(SessionFactoryImpl.class);
        try {
            return sessionFactoryImpl.getJdbcServices().getBootstrapJdbcConnectionAccess().obtainConnection();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to obtain JDBC connection", e);
        }
    }

    @Override
    public String getSchema() {
        return ConfigProvider.getConfig().getOptionalValue("kc.db-schema", String.class).orElse(null);
    }

    @Override
    public void init(Config.Scope config) {
        RouterConfig.reset();
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        List<String> tenants = RouterConfig.tenantKeys();
        LOG.infof("Realm database router active. Catalog: '%s'. Tenant datasources: %s. Placement header: %s. Rules: %d",
                RouterConfig.DEFAULT_KEY, tenants, RouterConfig.placementHeader(), RouterConfig.placementRules().size());
        TenantSchemaMigrator migrator = new TenantSchemaMigrator(factory, this);
        // The mapping table must exist before the first realm lookup, which happens before Keycloak finishes booting.
        migrator.ensureCatalogTable();
        // Tenant schemas must exist before Keycloak's model migration lists the realms (it fans out over tenants).
        migrator.migrateTenants();
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

    @Override
    public List<ProviderConfigProperty> getConfigMetadata() {
        return ProviderConfigurationBuilder.create()
                .property().name("datasources").type("string")
                .helpText("Comma separated names of the additional datasources (db-kind-<name>) that may hold realms.").add()
                .property().name("header").type("string").defaultValue(RouterConfig.DEFAULT_HEADER)
                .helpText("HTTP header read when a realm is created through the admin API to pick its datasource.").add()
                .property().name("rules").type("string")
                .helpText("Semicolon separated <datasource>:<regex> rules matched against the realm name when no header is present.").add()
                .property().name("placement").type("string").defaultValue(RouterConfig.DEFAULT_KEY)
                .helpText("Datasource used for new realms that match no header and no rule.").add()
                .property().name("migration").type("string").defaultValue("update").options("update", "validate", "none")
                .helpText("Schema strategy applied to every tenant datasource at startup.").add()
                .build();
    }
}
