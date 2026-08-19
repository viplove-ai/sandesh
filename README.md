# Sandesh

A messenger for the people already on a [Nirman](https://github.com/viplove-ai/saas-contractor)
site. Same credentials, same projects, same sites, same faces. It carries text, photographs and
documents between them, notifies them when something arrives, and keeps a record only of the
part that is company business.

The full design — and the reasoning behind every decision below — is in
[`docs/PLAN.md`](docs/PLAN.md). This README is how to run it.

> **Status: Weeks 1–4 of the pilot** (`docs/PLAN.md` §24), plus announcements and moderation.
> Login, conversations, text, photographs, Web Push with a notification health screen, the
> install gate, an org-wide announcements channel, and mute/block/report with an audit trail.
>
> Push stays switched off until `VAPID_PUBLIC_KEY` and `VAPID_PRIVATE_KEY` are set — blank keys
> disable it rather than failing at boot, because the pilot has to be able to run before
> somebody has generated a pair. Generate one with `npx web-push generate-vapid-keys`.

## What it is, in four lines

- **No user table.** Nirman issues the tokens; Sandesh verifies them with the same secret.
- **No chat history table.** An undelivered message waits in `outbox` and is deleted the moment
  the recipient's device says it has committed it.
- **No conversation table.** Membership is derived from Nirman's site assignments on every read.
- **No new infrastructure.** A database and a bucket, on the Postgres and MinIO already running.

## Running it locally

You need Java 21, Node 22, and either Nirman's stack already up or Docker for the one here.

```bash
cp .env.example .env
```

Set `JWT_SECRET` to **exactly** the value Nirman uses. Then apply the contract views to Nirman's
database — see [`docs/nirman-migration/`](docs/nirman-migration/) — and:

```bash
docker compose up -d
```

```bash
cd backend && ./mvnw spring-boot:run
```

```bash
cd frontend && npm install && npm run dev
```

The app is on <http://localhost:5174> — 5174 rather than 5173, so Nirman and Sandesh run side by
side. Sign in with any active Nirman username and password.

### If `./mvnw` cannot find a JDK

```bash
export JAVA_HOME=/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
```

## Checks

Everything CI runs, runnable locally:

```bash
cd backend && ./mvnw -B verify
```

```bash
cd frontend && npm run lint && npm run typecheck && npm test && npm run build
```

## Layout

```
backend/src/main/java/in/sandesh/
  security/      verifies Nirman's tokens — parse only, never issue
  directory/     the only code that touches Nirman's database, through two read-only views
  conversation/  membership, derived on every call and stored nowhere
  message/       the outbox, the SSE stream, send and acknowledge
  media/         presigned upload and download; bytes never pass through this service
frontend/src/
  offline/db.ts  Dexie — on a delivered message this is the only copy that exists
  shared/stream.ts  the held connection, with its own reconnect and jitter
```

## Deploying

CD is authored and runs on `main`, but **the Fly apps and their tokens have to be created by a
person** — they are credentials, and neither CI nor an assistant can mint them. Until they
exist the deploy workflow fails fast with a message saying so rather than half-deploying.

```bash
fly apps create sandesh-api
```

```bash
fly apps create sandesh
```

### The database and its role

Same Neon project as Nirman — one Postgres server, separate databases (`docs/PLAN.md` §18).
Run against any database in the project:

```sql
CREATE ROLE sandesh LOGIN PASSWORD '<pick one>';
```

```sql
GRANT sandesh TO CURRENT_USER;
```

```sql
CREATE DATABASE sandesh OWNER sandesh;
```

The role first, then the database it owns. Owning it is all the grant Flyway needs.

The middle line is not optional and the error without it is opaque —
`must be able to SET ROLE "sandesh"`. Since PostgreSQL 16, creating a database owned by
*another* role requires membership in that role, and a managed Postgres does not hand you the
superuser that would skip the check. Membership can be revoked afterwards; the ownership stays.
It grants nothing new in any case, because the role that created `sandesh` can `ALTER ROLE` it
regardless.

If the database already exists (created before the role did), reassign rather than re-create —
the `GRANT` above is what makes this permitted:

```sql
ALTER DATABASE sandesh OWNER TO sandesh;
```

Then connect **to the `sandesh` database** and confirm both owners, because owning a database is
not the same as owning its `public` schema, and Flyway needs `CREATE` there or the backend dies
at boot with `permission denied for schema public`:

```sql
SELECT current_database(), (SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = current_database()) AS db_owner, (SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname = 'public') AS schema_owner;
```

`db_owner` must be `sandesh`. `schema_owner` may be `sandesh` or `pg_database_owner` — the
latter tracks whoever owns the database and is fine. If it is anything else:

```sql
ALTER SCHEMA public OWNER TO sandesh;
```

A dedicated role rather than reusing `neondb_owner`, and the reason is this repository: it is
public, and Sandesh is a second service holding a second set of secrets. If those leak, they
should reach a messenger's spool and not the payroll sitting on the same server.

`chat_reader` is a third role again, created by the Nirman migration and given a password out of
band — see `docs/nirman-migration/`.

### Secrets

Never in `fly.toml` — this repo is public:

```bash
flyctl storage create --app sandesh-api --name sandesh-media
```

```bash
flyctl secrets set --app sandesh-api DATABASE_URL='jdbc:postgresql://<neon-host>/sandesh?sslmode=require' DB_USER='sandesh' DB_PASSWORD='<the one you just picked>' NIRMAN_DATABASE_URL='jdbc:postgresql://<neon-host>/nirman?sslmode=require' NIRMAN_DB_USER='chat_reader' NIRMAN_DB_PASSWORD='...' JWT_SECRET='<the same value Nirman uses>' STORAGE_ACCESS_KEY='tid_...' STORAGE_SECRET_KEY='tsec_...' STORAGE_BUCKET='sandesh-media' CORS_ALLOWED_ORIGINS='https://sandesh.fly.dev'
```

Production takes the two connection strings **whole**, because Neon's require `sslmode` — the
`DB_HOST`/`DB_PORT` form in `.env.example` is for local development only. Take Neon's **direct**
endpoint, not the `-pooler` one: Hikari already holds a pool, and stacking it on PgBouncer in
transaction mode breaks prepared statements.

Create a deploy token per app and add each to this repo's Actions secrets as
`FLY_API_TOKEN_BACKEND` and `FLY_API_TOKEN_FRONTEND`:

```bash
fly tokens create deploy --app sandesh-api
```

Finally, add `https://sandesh.fly.dev` to Nirman's `CORS_ALLOWED_ORIGINS` — without it every
sign-in fails at the preflight.

## What is deliberately not here yet

Documents, retention (§15 — gated on counsel, not on engineering), the action channel (§16), the
device eviction ladder (§22), read receipts and typing. Each is cut on purpose and the reasoning
is in `docs/PLAN.md` §24 — the pilot exists to answer two questions, and everything above only
helps answer them at scale.

## Turning notifications on

```bash
npx web-push generate-vapid-keys
```

```bash
flyctl secrets set --app sandesh-api VAPID_PUBLIC_KEY='B...' VAPID_PRIVATE_KEY='...' VAPID_SUBJECT='mailto:you@example.com'
```

The public key is served to the browser by `/api/v1/push/health`, so it is not duplicated into
the frontend build and rotating it does not need a redeploy of the web app.
