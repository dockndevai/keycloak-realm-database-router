# Build context: repository root (see docker/compose.yaml)
FROM quay.io/keycloak/keycloak:26.8.0 AS builder

# Router provider + Quarkus override that allows several non-XA datasources in one JTA transaction.
COPY target/keycloak-realm-database-router.jar /opt/keycloak/providers/keycloak-realm-database-router.jar
COPY docker/keycloak/quarkus.properties /opt/keycloak/conf/quarkus.properties

# Build-time options: database vendors (catalog + one named datasource per tenant database) and the
# provider defaults that switch Keycloak's storage SPIs over to the router.
RUN /opt/keycloak/bin/kc.sh build \
    --db=postgres \
    --db-kind-alpha=postgres \
    --db-kind-beta=postgres \
    --db-kind-gamma=postgres \
    --transaction-xa-enabled-alpha=false \
    --transaction-xa-enabled-beta=false \
    --transaction-xa-enabled-gamma=false \
    --health-enabled=true \
    --metrics-enabled=true \
    --spi-connections-jpa--provider-default=routing \
    --spi-realm--provider=routing \
    --spi-user--provider=routing \
    --spi-client--provider=routing \
    --spi-client-scope--provider=routing \
    --spi-group--provider=routing \
    --spi-role--provider=routing \
    --spi-user-federated-storage--provider=routing \
    --spi-authorization-persister--provider=routing \
    --spi-events-store--provider=routing \
    --spi-organization--provider=routing \
    --spi-identity-provider-storage--provider=routing \
    --spi-user-session-persister--provider=routing \
    --spi-revoked-token-persister--provider=routing

FROM quay.io/keycloak/keycloak:26.8.0
COPY --from=builder /opt/keycloak/ /opt/keycloak/
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start", "--optimized"]
