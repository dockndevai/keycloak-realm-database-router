package com.ankitverma.keycloak.router;

import java.sql.Connection;
import java.sql.SQLException;

import jakarta.enterprise.inject.Instance;
import jakarta.persistence.EntityManagerFactory;

import org.keycloak.models.ModelException;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.arc.Arc;

/**
 * Access to the named Agroal datasources Keycloak builds from {@code --db-kind-<name>} / {@code --db-url-full-<name>}
 * and to the single Hibernate {@link EntityManagerFactory} of Keycloak's persistence unit.
 */
public final class TenantDataSources {

    private static volatile EntityManagerFactory entityManagerFactory;

    private TenantDataSources() {
    }

    public static AgroalDataSource dataSource(String key) {
        Instance<AgroalDataSource> instance = Arc.container().select(AgroalDataSource.class, new DataSource.DataSourceLiteral(key));
        if (!instance.isResolvable()) {
            throw new IllegalStateException("Datasource '" + key + "' is not configured. Build the server with"
                    + " --db-kind-" + key + "=<vendor> and start it with --db-url-full-" + key + "=<jdbc-url>.");
        }
        return instance.get();
    }

    /**
     * Borrows a connection from the tenant pool. When a JTA transaction is active on the calling thread Agroal
     * enlists the connection in it, so the tenant commits together with the rest of the Keycloak transaction.
     */
    public static Connection connection(String key) {
        try {
            return dataSource(key).getConnection();
        } catch (SQLException e) {
            throw new ModelException("Unable to obtain a JDBC connection for datasource '" + key + "'", e);
        }
    }

    public static EntityManagerFactory entityManagerFactory() {
        EntityManagerFactory emf = entityManagerFactory;
        if (emf == null) {
            Instance<EntityManagerFactory> instance = Arc.container().select(EntityManagerFactory.class);
            if (!instance.isResolvable()) {
                throw new IllegalStateException("Failed to resolve Keycloak's default EntityManagerFactory");
            }
            emf = instance.get();
            entityManagerFactory = emf;
        }
        return emf;
    }
}
