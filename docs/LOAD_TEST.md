# Load test: three realms, three databases, in parallel

Date: 2026-10-09. Keycloak 26.8.0 with the realm-database-router, PgBouncer 1.26 (transaction pooling),
PostgreSQL 16. Everything runs in Docker Desktop on one Apple M2 Pro (10 CPUs, 10 GB RAM for Docker);
Keycloak JVM `-Xmx2g`. k6 runs inside the same Docker network, one scenario per realm, all realms hammered
at the same time. Each realm has 100 users with password `password`; every tenant realm lives in its own
PostgreSQL database behind PgBouncer.

Script: [load/login.js](../load/login.js). Raw summaries: [load/results](../load/results).

## Password grant (a real login: user lookup, Argon2 password verification, token issuance, event + session persisted)

| VUs per realm | realm | logins/s | p50 ms | p95 ms | p99 ms | success |
|---|---|---|---|---|---|---|
| 20 | alpha | 75.2 | 264 | 401 | 585 | 100 % |
| 20 | beta | 74.5 | 265 | 403 | 553 | 100 % |
| 20 | gamma | 74.7 | 265 | 398 | 540 | 100 % |
| **20** | **total (60 VUs)** | **224.4** | 264 | 400 | 557 | 100 % |
| 50 | alpha | 93.6 | 540 | 617 | 673 | 100 % |
| 50 | beta | 93.8 | 540 | 617 | 673 | 100 % |
| 50 | gamma | 93.9 | 540 | 617 | 672 | 100 % |
| **50** | **total (150 VUs)** | **281.3** | 540 | 617 | 673 | 100 % |

Going from 60 to 150 concurrent users raised throughput by 25 % while doubling latency: the server is at its
CPU ceiling. The ceiling is Argon2 password hashing (Keycloak's default, roughly 30 ms of CPU per login), not
the databases. **Maximum sustained login rate on this box: about 280 logins/s, ~94 per realm, evenly spread.**

## Client credentials grant (token issuance without password hashing: client lookup, service-account user, token signing, session persisted)

| VUs per realm | realm | tokens/s | p50 ms | p95 ms | p99 ms | success |
|---|---|---|---|---|---|---|
| 20 | alpha | 1092.8 | 16.9 | 32.2 | 44.8 | 100 % |
| 20 | beta | 1086.5 | 16.9 | 32.3 | 44.5 | 100 % |
| 20 | gamma | 1092.7 | 16.9 | 32.1 | 44.7 | 100 % |
| **20** | **total (60 VUs)** | **3272** | 16.9 | 32.2 | 44.7 | 100 % |
| 50 | alpha | 1122.7 | 42.9 | 59.6 | 73.1 | 100 % |
| 50 | beta | 1122.7 | 42.9 | 59.8 | 73.7 | 100 % |
| 50 | gamma | 1124.1 | 42.9 | 59.4 | 73.4 | 100 % |
| **50** | **total (150 VUs)** | **3370** | 42.9 | 59.7 | 73.4 | 100 % |

The plateau is already reached at 60 VUs: **about 3,300 tokens/s in total, ~1,100 per realm**, each token
writing a persisted session row into the realm's own database through PgBouncer. Latency scaled linearly with
concurrency above that point, which again points at CPU (token signing, JSON, Hibernate) rather than at the
databases: PostgreSQL stayed at a few percent CPU.

## What the numbers say about the router

* All three realms get the same share of throughput and the same latency: routing adds no per-realm skew.
* Zero errors across ~430,000 requests; no Keycloak warnings or errors in the server log during the runs.
* Every event and session row produced by the load landed in the realm's own database (see the row counts
  printed by `scripts/setup.sh` and in the README).
* The router's own cost per request is one map lookup for the realm and, for tenant realms, one extra JDBC
  connection borrow from the tenant pool. It does not show up in the latency profile; the password-grant and
  client-credentials numbers are in line with a stock single-database Keycloak on the same hardware, where
  Argon2 hashing dominates logins.

## Reproduce

```bash
docker compose -f docker/compose.yaml up -d --build && ./scripts/setup.sh
VUS_PER_REALM=20 DURATION=60s GRANT=password           docker compose -f docker/compose.yaml --profile load run --rm k6
VUS_PER_REALM=20 DURATION=60s GRANT=client_credentials docker compose -f docker/compose.yaml --profile load run --rm k6
VUS_PER_REALM=50 DURATION=60s GRANT=password           docker compose -f docker/compose.yaml --profile load run --rm k6
VUS_PER_REALM=50 DURATION=60s GRANT=client_credentials docker compose -f docker/compose.yaml --profile load run --rm k6
```

To test more realms, add a datasource (`--db-kind-<name>` in the Dockerfile, URL/credentials in
`compose.yaml`, a database in `docker/postgres/init.sql`, an entry in `docker/pgbouncer/pgbouncer.ini`) and
list it in `REALMS`.
