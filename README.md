# Keycloak Realm Database Router

A Keycloak 26.8 provider that stores **each realm in its own database**. Users, credentials, clients, roles,
groups, events, persisted sessions, organizations, identity providers and authorization data of a realm live in
the database the realm was placed on. Nothing is filtered by a `REALM_ID` column across tenants: a tenant
database contains only that tenant's rows.

```
                     ┌───────────────────────────────────────────────┐
  /realms/alpha ───▶ │ Keycloak 26.8 + realm-database-router         │
  /realms/beta  ───▶ │   realm ──▶ datasource key ──▶ EntityManager  │
  /admin/...    ───▶ │   (catalog table + in-memory cache)           │
                     └──────┬──────────┬──────────┬──────────┬───────┘
                            │          │          │          │
                        ┌───▼───┐  ┌───▼───┐  ┌───▼───┐  ┌───▼────┐
                        │catalog│  │ alpha │  │ beta  │  │ gamma  │   named Agroal datasources
                        └───┬───┘  └───┬───┘  └───┬───┘  └───┬────┘   (db-kind-<name>, db-url-full-<name>)
                            └──────────┴────┬─────┴──────────┘
                                        ┌───▼──────┐
                                        │ PgBouncer │ transaction pooling
                                        └───┬──────┘
                                        ┌───▼──────┐
                                        │PostgreSQL│ keycloak, kc_alpha, kc_beta, kc_gamma
                                        └──────────┘
```

* **Catalog** = Keycloak's normal database (`--db-url`). It holds the `master` realm, any realm that is not
  explicitly placed, and the `REALM_DATASOURCE` mapping table.
* **Tenant datasources** = Keycloak's own *additional datasources* feature (`--db-kind-<name>` at build time,
  `--db-url-full-<name>` / `--db-username-<name>` / `--db-password-<name>` / `--db-pool-*-<name>` at runtime).
  Each tenant database carries the complete Keycloak schema; the router runs Keycloak's Liquibase changelog
  against every tenant at startup, so Keycloak upgrades migrate all tenants.
* **Placement** is decided once, when a realm is created: `X-Keycloak-Datasource` request header, then
  realm-name regex rules, then the configured default. It is recorded in the catalog and never changes.

## How it works

| Piece | Role |
|---|---|
| `RoutingJpaConnectionProviderFactory` (`connectionsJpa` / `routing`) | Default JPA connection provider. Hands out Keycloak's entity manager for the catalog and, per tenant, a Hibernate session from the **same** `EntityManagerFactory` opened on a connection from the tenant's Agroal pool. Agroal enlists that connection in the request's JTA transaction. |
| `RoutingProxy` + one `Routing*ProviderFactory` per storage SPI | A dynamic proxy in front of each storage SPI (`realm`, `user`, `client`, `client-scope`, `group`, `role`, `user-federated-storage`, `authorization-persister`, `events-store`, `organization`, `identity-provider-storage`, `user-session-persister`, `revoked-token-persister`). Every call is resolved to a datasource from its realm-bearing argument (or the request's realm), then delegated to the **stock Keycloak JPA provider** created for that datasource. No Keycloak storage code is forked. |
| `RealmRouter` | realm id / name → datasource key, backed by the `REALM_DATASOURCE` table with an in-memory cache and self-healing on stale entries. |
| `TenantSchemaMigrator` | Creates the mapping table and migrates each tenant database with Keycloak's `JpaUpdaterProvider`. |
| `GET /realms/master/realm-router` | Read-only status (master `admin` role): datasources and realm placement. |

Multi-realm operations (`GET /admin/realms`, expired-event clean-up, session counts) fan out over all datasources
and merge the results; a failing tenant is logged and skipped so one database outage does not take the others down.

## Configuration

Build time (`kc.sh build`, see [docker/keycloak/Dockerfile](docker/keycloak/Dockerfile)):

```
--db=postgres
--db-kind-alpha=postgres --db-kind-beta=postgres --db-kind-gamma=postgres
--transaction-xa-enabled-alpha=false --transaction-xa-enabled-beta=false --transaction-xa-enabled-gamma=false
--spi-connections-jpa--provider-default=routing      # provider-default: Keycloak's own factory must stay loaded
--spi-realm--provider=routing --spi-user--provider=routing --spi-client--provider=routing
--spi-client-scope--provider=routing --spi-group--provider=routing --spi-role--provider=routing
--spi-user-federated-storage--provider=routing --spi-authorization-persister--provider=routing
--spi-events-store--provider=routing --spi-organization--provider=routing
--spi-identity-provider-storage--provider=routing --spi-user-session-persister--provider=routing
--spi-revoked-token-persister--provider=routing
```

`conf/quarkus.properties` must contain `quarkus.transaction-manager.unsafe-multiple-last-resources=allow`
(see *Transactions*).

Runtime:

| Option | Default | Meaning |
|---|---|---|
| `--spi-connections-jpa--routing--datasources` | *(none)* | Comma separated tenant datasource names |
| `--spi-connections-jpa--routing--header` | `X-Keycloak-Datasource` | Header read on realm creation |
| `--spi-connections-jpa--routing--rules` | *(none)* | `<datasource>:<regex>;...` matched against the realm name |
| `--spi-connections-jpa--routing--placement` | `default` | Datasource for realms matching nothing (`default` = catalog) |
| `--spi-connections-jpa--routing--migration` | `update` | `update`, `validate` or `none` for tenant schemas |

Environment variable form: `KC_SPI_CONNECTIONS_JPA__ROUTING__DATASOURCES=alpha,beta,gamma`.

Create a realm on a given database:

```bash
curl -X POST http://localhost:8080/admin/realms \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -H "X-Keycloak-Datasource: alpha" \
  -d '{"realm":"alpha","enabled":true}'
```

## Running the demo stack

Requirements: JDK 21, Maven, Docker.

```bash
mvn -q -DskipTests package                       # builds target/keycloak-realm-database-router.jar
docker compose -f docker/compose.yaml up -d --build
./scripts/setup.sh                               # realms alpha/beta/gamma, 100 users each, verification
```

`setup.sh` prints the router status, the row counts per database and a login per realm. Admin console:
http://localhost:8080 (admin / admin).

Load test (k6 runs inside the compose network):

```bash
VUS_PER_REALM=20 DURATION=60s GRANT=password docker compose -f docker/compose.yaml --profile load run --rm k6
VUS_PER_REALM=20 DURATION=60s GRANT=client_credentials docker compose -f docker/compose.yaml --profile load run --rm k6
```

Summaries are written to `load/results/`.

## Verification results (2026-10-09, Keycloak 26.8.0)

After `setup.sh` and a few admin operations:

| database | realms | users | clients | credentials |
|---|---|---|---|---|
| keycloak (catalog) | master | 1 | 9 | 1 |
| kc_alpha | alpha | 101 | 8 | 100 |
| kc_beta | beta | 101 | 8 | 100 |
| kc_gamma | gamma | 101 | 8 | 100 |

Also verified: login events and persisted sessions land only in the realm's database; deleting a realm role
cleans up its group mappings inside the tenant; a realm created without placement goes to the catalog and is
deleted cleanly; an unknown datasource is rejected with HTTP 400; master-realm admin requests that manage a
tenant realm commit across both databases.

## Load test results

See [docs/LOAD_TEST.md](docs/LOAD_TEST.md).

## Transactions

Catalog and tenant pools are plain (non-XA) PostgreSQL datasources. A request that writes to two databases
(realm creation writes the realm to the tenant and the `<realm>-realm` client to master; the admin API
authenticates against master) enlists two "last resources" in the Narayana transaction. With
`unsafe-multiple-last-resources=allow` Narayana commits them **in order, not atomically**: a crash between the
two commits can leave one side committed. This is the same guarantee Keycloak gives today between its database
and Infinispan. Realm creation and deletion are ordered so the catalog mapping row is written last, and a stale
mapping self-heals on the next lookup. XA (`--transaction-xa-enabled-<name>=true` plus
`max_prepared_transactions` on PostgreSQL) is possible but not compatible with PgBouncer transaction pooling.

## Limitations

* All databases must be the same vendor (one Hibernate dialect). Tested with PostgreSQL only.
* The tenant list is fixed at build time (`--db-kind-<name>`); adding a database needs a rebuild.
* `organization` and `identity-provider-storage` bypass Keycloak's Infinispan caches, because those caches
  look up the `jpa` provider by id. Lookups for those two types hit the database.
* Scheduled clean-ups that Keycloak runs without a realm (expired login failures, authentication sessions and
  single-use objects **when those are configured to use JPA**) only clean the catalog. The default Infinispan
  providers for those SPIs are unaffected.
* Cluster: the realm mapping is read from the catalog on every cache miss, so new realms are visible on all nodes;
  a node keeps a stale entry for a deleted realm until the next miss on that name.
* Pinned to Keycloak 26.8.0: the router instantiates Keycloak's JPA providers directly, so a Keycloak upgrade is
  a compile-time check, not a runtime surprise.
