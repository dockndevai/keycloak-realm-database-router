# Keycloak Realm Database Router — Design & Implementation Plan

Status: IMPLEMENTED on 2026-10-09 (Keycloak 26.8.0). See README.md for the delivered design and docs/LOAD_TEST.md for results.
Original plan below; deviations: Phase 0 showed Keycloak 26.8 wires provider persistence units into Quarkus, so the
shared-EntityManagerFactory approach (3.2) was used; schema migration runs in postInit rather than on PostMigrationEvent;
placement uses a request header plus name rules instead of a realm attribute.
Target: Keycloak 26.7.x (Quarkus distribution), PostgreSQL first

## 1. Goal

Hard data isolation per realm: every realm's data (realm config, clients, users, groups, roles,
credentials, events, authorization data, organizations, persisted sessions) lives in a
**database chosen per realm**, not in one shared schema filtered by `REALM_ID`.

Non-goals (v1):
- Different DB vendors per tenant (all tenant DBs must be the same vendor as the catalog DB).
- Moving a realm between databases online (handled by export/import tooling, offline).
- Replacing Infinispan caches / session storage (they stay as-is; they are already keyed by realm).

## 2. Why this is harder than it looks

Keycloak has **no multi-datasource storage abstraction**. All JPA storage providers obtain one
`EntityManager` per request from the `connectionsJpa` SPI, and entity adapters (`RealmAdapter`,
`UserAdapter`, `ClientAdapter`, …) capture that `EntityManager` at construction time. Any design
therefore has to satisfy three constraints at once:

1. A single request routinely touches **two** databases: the admin API authenticates against
   `master`, then operates on a target realm; realm creation writes the realm (tenant DB) and the
   `<realm>-realm` client in `master` (catalog DB).
2. Realm lookup by name/id happens **before** anyone knows which DB holds the realm.
3. Every provider instance is cached per `KeycloakSession`; a provider created for tenant A must
   never be reused for tenant B.

## 3. Architecture

```
                  ┌──────────────────────────────────────────────────────────────┐
 request ──────▶  │ KeycloakSession                                               │
                  │   TenantContext (session attribute: current datasource key)   │
                  │                                                               │
                  │   RoutingRealmProvider ──┐                                    │
                  │   RoutingUserProvider ───┤  resolve tenant from RealmModel    │
                  │   RoutingClientProvider ─┤  (or catalog for name/id lookups)  │
                  │   … one per storage SPI ─┘        │                           │
                  │                                   ▼                           │
                  │   RoutingJpaConnectionProvider  tenant key ──▶ EntityManager  │
                  │     per-tenant EM map, per-tenant KeycloakTransaction         │
                  └───────────────┬──────────────────────┬────────────────────────┘
                                  │                      │
                     ┌────────────▼──────────┐   ┌───────▼───────────────────────┐
                     │ Catalog DB (default   │   │ Tenant DBs (named datasources) │
                     │ Keycloak --db-*)      │   │  tenant-a, tenant-b, …         │
                     │  master realm         │   │  full Keycloak schema each     │
                     │  REALM_DATASOURCE map │   │  one or many realms each       │
                     │  unplaced realms      │   └────────────────────────────────┘
                     └───────────────────────┘
```

### 3.1 Databases and roles

| Database | Holds | Configured by |
|---|---|---|
| Catalog (= Keycloak default DB) | `master` realm, routing table `REALM_DATASOURCE`, any realm with no explicit placement | standard `--db`, `--db-url`, … |
| Tenant DB `<key>` | full Keycloak schema; realms placed on it | Keycloak named datasources: `--db-kind-<key>`, `--db-url-full-<key>`, `--db-username-<key>`, `--db-password-<key>`, `--db-pool-*-<key>` |

Using Keycloak's own named-datasource options (GA since 26.4) gives us Agroal pools, metrics,
health and vault/env credential handling for free. v1 tenant set is therefore static at build
time (`db-kind-<key>` is a build-time option). A dynamic registry (pools created at runtime from
rows in the catalog) is a planned extension behind the same `TenantDataSourceRegistry` interface.

### 3.2 EntityManager per tenant without a second persistence unit

Keep the single Quarkus-managed `EntityManagerFactory` (Keycloak's persistence unit, with all
entity mappings and build-time enhancement intact). For a tenant, open a Hibernate session on a
connection borrowed from the tenant pool:

```java
Connection c = registry.dataSource(tenantKey).getConnection();
EntityManager em = emf.unwrap(SessionFactory.class)
        .withOptions().connection(c).openSession();
```

The connection is held for the life of the `KeycloakSession` (same as Keycloak's default
behavior) and closed when the session closes. This is the single most important spike item
(see §6, Phase 0): it must be verified against the exact Keycloak/Hibernate/Quarkus versions,
including flush/commit ordering with Keycloak's JTA wrapper.

Fallback if the spike fails: bootstrap one Hibernate `SessionFactory` per tenant from
`keycloak-model-jpa`'s `persistence.xml` the way `DefaultJpaConnectionProviderFactory`
(the non-Quarkus factory) still does. Heavier (N metamodels in memory) but proven.

### 3.3 Routing primitives

- **`TenantContext`**: a `KeycloakSession` attribute holding the current datasource key.
  Default = catalog. Set by routing providers right before they create or call a tenant
  delegate; restored afterwards (try/finally).
- **`RoutingJpaConnectionProviderFactory`** (SPI `connectionsJpa`, id `routing`):
  `getEntityManager()` returns the EM for the current `TenantContext`, creating it lazily and
  enlisting a per-tenant `JpaKeycloakTransaction` in the `KeycloakTransactionManager`. Closes
  all tenant EMs and returns connections on session close.
- **`RealmRouter`**: in-memory map `realmName → key`, `realmId → key`, backed by catalog table
  `REALM_DATASOURCE(REALM_ID, REALM_NAME, DATASOURCE_KEY, CREATED_AT)`. Misses fall through to
  the catalog DB (covers `master` and unplaced realms). Cluster-wide invalidation through the
  Infinispan `work` cache, the same mechanism Keycloak uses for its own realm cache.

### 3.4 Routing storage providers

One thin provider per JPA-backed storage SPI, registered with id `routing-jpa` and selected via
`--spi-<spi>-provider=routing-jpa`. Each wrapper:

1. Derives the tenant key from the `RealmModel` argument (`router.keyFor(realm.getId())`).
   Lookups that have no realm yet (`getRealmByName`, `getRealm(id)`) consult the router first.
2. Sets `TenantContext`, obtains (or lazily constructs and caches, keyed by tenant) the
   **stock Keycloak JPA provider** for that tenant, delegates, restores context.

Stock delegates are real Keycloak classes constructed with the tenant EM, so entity adapters
capture the correct `EntityManager`. No Keycloak storage code is forked.

SPIs to wrap (the full list is confirmed in Phase 0 by grepping Keycloak for
`JpaConnectionProvider.class` consumers):

| SPI (`--spi-…`) | Stock delegate |
|---|---|
| `realm` | `JpaRealmProvider` |
| `user` | `JpaUserProvider` |
| `client` | `JpaRealmProvider` (also implements ClientProvider) |
| `clientScope`, `group`, `role` | `JpaRealmProvider` |
| `userFederatedStorage` | `JpaUserFederatedStorageProvider` |
| `authorizationPersister` | `JPAAuthorizationStoreFactory` / `JPAStoreFactory` |
| `eventsStore` | `JpaEventStoreProvider` |
| `organization` | `JpaOrganizationProvider` |
| `identityProviderStorage` | `JpaIdentityProviderStorageProvider` |
| `userSessionPersister` | `JpaUserSessionPersisterProvider` |
| `revokedTokenPersister` / single-use persistence | `JpaRevokedTokenPersisterProvider` |
| `dblock`, `connectionsJpaUpdater` | run per datasource (see §3.6) |

Fan-out methods (no realm argument, e.g. `getRealmsStream()`, `removeExpiredEvents`,
persister bulk loads at startup) iterate over all registered datasources and concatenate.

### 3.5 Realm placement

- Placement is decided **once, at creation**, and is immutable.
- Source of the decision, in order: realm attribute `datasource` in the `RealmRepresentation`
  (works for admin API, admin console, `--import-realm`, and `kc.sh import`); otherwise the
  configured default (`--spi-realm-routing-jpa-default-datasource`, default = catalog).
- Unknown key → creation fails fast with a clear error (no silent fallback to the shared DB).
- `createRealm` writes the realm rows on the tenant, then the catalog row; `removeRealm`
  removes the catalog row last. Compensating cleanup on failure (§3.7).

### 3.6 Schema management per tenant DB

Every tenant DB carries the full Keycloak schema (Liquibase changelog). At startup, for each
registered datasource, the router runs Keycloak's own `JpaUpdaterProvider` under that DB's
`DBLockProvider`, honoring the standard `--spi-connections-jpa-quarkus-migration-strategy`
semantics (`update` / `validate` / `manual`). Keycloak version upgrades therefore migrate all
tenant DBs the same way the main DB is migrated today.

### 3.7 Transactions

v1 uses **ordered, best-effort commit**: each tenant EM has its own `KeycloakTransaction`
enlisted in the `KeycloakTransactionManager`; they commit in enlistment order. This is exactly
the guarantee Keycloak already gives between JPA and Infinispan (no XA across them).

Where it matters (multi-DB writes) the code orders writes so the catalog is the last writer and
adds compensation:

| Operation | DBs | Ordering / compensation |
|---|---|---|
| create realm | tenant, catalog, master | tenant realm → master `<realm>-realm` client → catalog row; on failure after tenant commit: delete realm rows from tenant |
| delete realm | tenant, master, catalog | catalog row removed last; a dangling catalog row pointing at a missing realm is self-healed on next lookup |
| everything else | one DB | nothing special |

Option for later: enlist tenant connections in the Narayana JTA transaction (Agroal
`transactions=xa`) for true 2PC. Deferred because XA costs and vendor support vary.

### 3.8 Caching, clustering, failure isolation

- Infinispan `realms` / `users` caches are keyed by realm id / user id (UUIDs) and realm name;
  names are globally unique (enforced by the catalog), so the cache layer stays untouched.
- `RealmRouter` invalidations broadcast through the `work` cache; new nodes warm the router
  from the catalog table at startup.
- A tenant DB outage makes **only that tenant's realms** fail (503 from the routing providers);
  `master`, the admin API for other realms, and other tenants keep working. Health endpoint
  `/health/ready` gains one check per datasource.

## 4. Configuration (proposed)

```
# catalog = ordinary Keycloak DB
--db=postgres --db-url=jdbc:postgresql://catalog/keycloak --db-username=… --db-password=…

# tenants (build-time kind, runtime connection settings)
--db-kind-tenant-a=postgres --db-url-full-tenant-a=jdbc:postgresql://a/keycloak --db-username-tenant-a=… --db-password-tenant-a=…
--db-kind-tenant-b=postgres --db-url-full-tenant-b=jdbc:postgresql://b/keycloak …

# switch storage to the router
--spi-connections-jpa-provider=routing
--spi-realm-provider=routing-jpa --spi-user-provider=routing-jpa --spi-client-provider=routing-jpa
… (one per SPI in §3.4; a documented block, or a single env var the module expands)

# router options
--spi-realm-routing-jpa-datasources=tenant-a,tenant-b
--spi-realm-routing-jpa-default-datasource=catalog
--spi-realm-routing-jpa-migration-strategy=update
```

## 5. Repository layout

```
keycloak-realm-database-router/
  pom.xml                       Java 21, Keycloak BOM 26.7.x (provided scope), Maven
  src/main/java/…/router/
    catalog/      RealmRouter, RealmDatasourceEntity (catalog table), cluster invalidation
    datasource/   TenantDataSourceRegistry (+ named-datasource impl, dynamic impl later)
    jpa/          RoutingJpaConnectionProviderFactory, TenantContext, TenantEntityManagers,
                  per-tenant schema migration runner
    storage/      Routing* providers, one file per SPI
    admin/        placement validation (realm attribute), health checks, REST endpoint
                  GET /admin/realms/{realm}/datasource (read-only, which DB a realm lives in)
  src/main/resources/META-INF/services/…      SPI registrations
  src/main/resources/META-INF/jpa-changelog-router.xml   Liquibase for REALM_DATASOURCE
  src/test/java/…   unit tests + Testcontainers integration tests (3 PostgreSQL containers)
  docker/           compose file: 3 Postgres + Keycloak with the provider mounted
  docs/             config reference, operations runbook (add tenant, move realm, upgrade)
```

## 6. Phases and acceptance criteria

### Phase 0 — Spike (1–2 days) — de-risk before writing the real thing
- Pin Keycloak version; pull its sources at that tag.
- Grep for every consumer of `JpaConnectionProvider` to finalize the SPI list in §3.4.
- Prove `withOptions().connection(conn)` works inside Keycloak's Quarkus/JTA session wrapper:
  write a throwaway `connectionsJpa` provider that routes **all** traffic to a second DB and
  run a login + admin flow against it.
- Confirm a named datasource can be declared without a persistence unit (docs are ambiguous).
- Record findings in `docs/spike.md`. Go/no-go on §3.2 vs. its fallback.

### Phase 1 — Core routing
- `TenantDataSourceRegistry`, `RoutingJpaConnectionProviderFactory`, `TenantContext`,
  per-tenant transaction enlistment, startup schema migration per tenant DB.
- Catalog table + `RealmRouter` + cluster invalidation.
- Acceptance: with `master` on the catalog and one realm forced onto tenant-a, login, token
  refresh, logout and account console work; `psql` shows the realm's rows only in tenant-a.

### Phase 2 — Routing storage providers
- Implement all wrappers from §3.4, including fan-out methods and provider event listeners
  (realm/user removal hooks the stock factories register).
- Acceptance: Keycloak's admin console works end to end on routed realms (users, groups,
  roles, clients, scopes, identity providers, organizations, authorization services, events),
  offline tokens survive restart (persisted sessions routed), realm export/import round-trips.

### Phase 3 — Placement and lifecycle
- Realm attribute `datasource`, default placement, create/delete ordering and compensation,
  `--import-realm` support, read-only admin endpoint, health checks per datasource.
- Acceptance: create realm via API with and without attribute → lands in the right DB;
  unknown key rejected; delete realm cleans both DBs; realm creation failure leaves no orphans.

### Phase 4 — Hardening
- Failure isolation tests (kill tenant-a container: tenant-b and master unaffected).
- Multi-node test (2 Keycloak nodes, router invalidation).
- Load test comparison against vanilla Keycloak on the same hardware (target: < 5 % overhead
  on login throughput; the router adds one map lookup per call and one extra connection per
  request only when two DBs are touched).
- Upgrade rehearsal: 26.7.x → next patch with migration-strategy=update on all tenant DBs.

### Phase 5 — Packaging and docs
- `providers/*.jar` + `kc.sh build` instructions, Docker image example, config reference,
  runbooks: add a tenant DB, move a realm (export → create with new attribute → import →
  delete old), back up/restore per tenant, upgrade.

## 7. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Coupling to Keycloak internal classes (`JpaRealmProvider` constructors etc.) | Pin exact version; provided-scope compile against it so upgrades fail at build time, not at runtime; Phase 4 upgrade rehearsal. |
| A code path reaches the EM without going through a routed provider | Phase 0 grep makes the list exhaustive; `RoutingJpaConnectionProvider` logs a warning when called with no `TenantContext` and a non-master realm in `KeycloakContext`. |
| Partial commits across DBs | Ordered commit + compensation (§3.7); XA as a documented upgrade path. |
| Static tenant list requires rebuild to add a DB | v1 accepted; dynamic registry is the planned Phase 6. |
| Same-vendor requirement | Documented constraint; the fallback design in §3.2 would lift it if ever needed. |
| Operational blast radius: N databases to back up, migrate and monitor | Runbooks + per-datasource health/metrics; schema migration automated in-process. |

## 8. Alternatives considered

- **Column filter (`REALM_ID`)**: what Keycloak does today; rejected by requirement.
- **One Keycloak deployment per tenant**: strongest isolation and zero custom code, but N
  control planes, N admin consoles, N upgrade cycles. Right answer below ~5 tenants or when
  tenants also need separate Keycloak versions/config. This plan is for the case where one
  control plane with per-tenant data stores is wanted.
- **Schema-per-tenant in one DB** (`currentSchema` per datasource): supported by this design
  with no code change (a datasource is just a JDBC URL), and a cheap middle ground.
- **Hibernate native multi-tenancy (`MultiTenantConnectionProvider`)**: conceptually the same
  as §3.2 but needs Quarkus build-time configuration of Keycloak's persistence unit, which
  Keycloak's own build step owns. Rejected as too fragile.

## 9. Open decisions (need your input)

1. Keycloak version to pin (proposal: latest 26.7.x at kick-off).
2. Database vendor (proposal: PostgreSQL only for v1).
3. Static tenant list (named datasources, rebuild to add) is acceptable for v1? If tenants must
   be onboarded at runtime, Phase 6 (dynamic Agroal registry) moves up.
4. Placement signal: realm attribute `datasource` (proposal) vs. naming convention
   (`<tenant>-<realm>`) vs. HTTP header on create.
5. Is ordered best-effort commit acceptable for realm create/delete, or is XA required from
   day one?
