# Community post: one database per realm, without forking Keycloak

Suggested title: **Hard data isolation per realm (one PostgreSQL database per realm) with a routing SPI on Keycloak 26.8 — lessons and numbers**

Suitable for: GitHub Discussions in keycloak/keycloak (category "Show and tell" or "Ideas"), CNCF Slack #keycloak, keycloak-user mailing list. The Slack version is the first three paragraphs plus the link.

---

Hi all,

I want to share something we built and the reasoning behind it, in case it helps others or someone sees a better way.

**The requirement.** We run Keycloak as a shared identity service for several customers, one realm each. A compliance review asked for a guarantee that each customer's identity data (users, credentials, sessions, audit events) lives in its own database, with its own backups and its own blast radius. Keycloak's schema separates realms by a `REALM_ID` column, and that was not accepted as "isolation": a single query, migration or restore still touches everyone.

**What we did not want.** One Keycloak deployment per customer would have satisfied the auditors, but it multiplies control planes, upgrade cycles and admin consoles, and our application layer (and the people operating it) wanted to keep talking to one Keycloak with one admin API.

**What we ended up with.** A provider jar that routes storage per realm. Keycloak 26.4+ already supports additional named datasources (`--db-kind-<name>`, `--db-url-full-<name>`), so each tenant database is just one of those. The router:

- replaces the default `connectionsJpa` provider (`--spi-connections-jpa--provider-default=routing`) and hands out, per request, Keycloak's own entity manager for the catalog DB plus one Hibernate session per tenant DB, opened from the same `EntityManagerFactory` on a connection borrowed from the tenant's Agroal pool (JTA-enlisted, so it commits with the request);
- puts a thin dynamic proxy in front of each storage SPI (`realm`, `user`, `client`, `client-scope`, `group`, `role`, `user-federated-storage`, `authorization-persister`, `events-store`, `organization`, `identity-provider-storage`, `user-session-persister`, `revoked-token-persister`). Each call is resolved to a datasource from its realm argument (or the request realm) and delegated to the **stock** JPA provider created for that datasource. No Keycloak code is forked;
- decides a realm's database once, at creation (request header, then name rules, then a default), stores it in a small mapping table in the catalog DB, and runs Keycloak's Liquibase changelog against every tenant DB at startup so upgrades migrate all tenants.

**Numbers** (3 realms on 3 databases behind PgBouncer, k6 inside the same Docker network, one Apple M2 Pro): about 280 password logins/s total with Argon2 verification, about 3,300 client-credentials tokens/s, both with 100% success and an exactly even split across realms. CPU (hashing, signing) was the ceiling; PostgreSQL stayed at a few percent. Counting rows afterwards: catalog holds only `master`, each tenant DB holds only its realm's users, credentials, ~147k events and ~14k persisted sessions.

**Things that cost us a failed startup each, in case they save someone time:**

1. `--spi-<spi>--provider=X` makes Keycloak load *only* factory X for that SPI. For `connections-jpa` you need `--provider-default`, otherwise Keycloak's own factory (which migrates the main DB) disappears.
2. Additional datasources default to XA. PostgreSQL behind PgBouncer transaction pooling cannot `PREPARE TRANSACTION`, so we set `--transaction-xa-enabled-<name>=false` and `quarkus.transaction-manager.unsafe-multiple-last-resources=allow` (commits across DBs are then ordered, not atomic, which is the same guarantee Keycloak already gives between the DB and Infinispan).
3. A Hibernate session opened on a user-supplied connection must register its JTA synchronization *before* Agroal enlists the connection (we acquire the connection lazily through a proxy), and the connection must be returned in a synchronization that runs after Hibernate's. Releasing it in Keycloak's prepare phase breaks the async-commit hook that runs at `beforeCompletion`.
4. Tenant schemas have to exist before Keycloak's model migration lists realms, so the migration runs in the connection factory's `postInit`.
5. The Infinispan organization and IdP caches look up the `jpa` provider by id, so those two SPIs are served by the router directly (no cache). Keycloak also casts the user provider to `UserCredentialStore`, so the proxies implement every interface of the stock class.

**Known limits:** one DB vendor for all tenants, tenant list fixed at build time, pinned to 26.8.0 (an upgrade is a compile-time check).

Repository with the provider, a Docker Compose stack (PostgreSQL, PgBouncer, Keycloak, k6), a setup/verification script and the full load-test write-up: https://github.com/dockndevai/keycloak-realm-database-router

Questions I would value input on:

- Is there a supported way to put a Keycloak persistence unit (all built-in entities) on a named datasource, so the per-tenant Hibernate session trick is not needed?
- Would a `DATABASE`-style multi-tenancy hook in the `connectionsJpa` SPI be something the project would consider?

Thanks for reading, and for Keycloak.
