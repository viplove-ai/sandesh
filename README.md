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
- **No new infrastructure.** A schema and a bucket, on the Postgres and MinIO already running.

## Running it locally

You need Java 21, Node 22, and Nirman's Postgres. Sandesh's tables live in a `sandesh` schema
inside Nirman's database and its directory reads two views in `public` on the same connection,
so there is no standalone database worth pointing at — the compose file here is for MinIO.

```bash
cp .env.example .env
```

Set `JWT_SECRET` to **exactly** the value Nirman uses and point `DATABASE_URL` at Nirman's local
database. Then apply the contract views to it — see
[`docs/nirman-migration/`](docs/nirman-migration/) — and:

```bash
docker compose up -d minio minio-init
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

### The database and its schema

Same database as Nirman, with a `sandesh` schema inside it (`docs/PLAN.md` §18). There is
nothing to create by hand: Flyway makes the schema on first boot and keeps its history table
inside it, so Sandesh's migrations never see Nirman's and Nirman's never see these.

What it needs is a role holding `CREATE` on Nirman's database, which is why the deployment
reuses Nirman's own credentials rather than minting a second pair. Copy them machine to machine
rather than by hand — Fly will not show you a secret's value, and `JWT_SECRET` must be
byte-identical to Nirman's or every request is refused with no useful error:

```bash
flyctl ssh console -a nirman-constructions-api -C printenv | tr -d '\r' | grep -E '^(DATABASE_URL|DB_USER|DB_PASSWORD|JWT_SECRET)=' | flyctl secrets import --app sandesh-api --stage
```

Compare `flyctl secrets list` across the two apps afterwards: the digests match when the values
do, which is the only confirmation available that the secret arrived intact.

An earlier design gave Sandesh its own database, plus a read-only `chat_reader` role for
Nirman's views. What it bought was a real boundary and what it cost was four credentials kept in
step across two systems — each one a way to fail at boot with an error naming the wrong thing.
The price of the change is worth stating plainly rather than discovering: shared vacuum, shared
backups, shared restore, and a chat table that can now fill the disk Nirman is using.

<details>
<summary>The old two-database setup, kept for reference</summary>

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

</details>

### Scale the backend back to one machine

Fly creates a second machine for high availability on an app's first deploy, and for this
backend that is wrong. Presence lives in the JVM — `StreamRegistry` holds the open streams of
the instance it is running in, and a second instance holds streams the first cannot deliver to.
`fly.toml` says `min_machines_running = 1`; the first deploy overrides it anyway:

```bash
fly scale count 1 --app sandesh-api
```

The frontend is static nginx and can keep both.

### Secrets

Never in `fly.toml` — this repo is public. The database credentials arrive with the import
above; these are Sandesh's own:

```bash
flyctl storage create --app sandesh-api --name sandesh-media
```

```bash
flyctl secrets set --app sandesh-api STORAGE_ACCESS_KEY='tid_...' STORAGE_SECRET_KEY='tsec_...' STORAGE_BUCKET='sandesh-media' CORS_ALLOWED_ORIGINS='https://sandesh.fly.dev'
```

`flyctl storage create` sets the same two keys under its own `AWS_*` names. Sandesh reads
`STORAGE_ACCESS_KEY` and `STORAGE_SECRET_KEY`, so copy the values across and unset the `AWS_*`
ones rather than leaving two copies of one credential on the app. A destroyed bucket's name is
held by Tigris for some minutes afterwards, so recreating one under the same name fails until
it is released.

Production takes `DATABASE_URL` **whole**, because Neon's carries `sslmode`. Take Neon's
**direct** endpoint, not the `-pooler` one: Hikari already holds a pool, and stacking it on
PgBouncer in transaction mode breaks prepared statements.

Create a deploy token per app and add each to this repo's Actions secrets as
`FLY_API_TOKEN_BACKEND` and `FLY_API_TOKEN_FRONTEND`:

```bash
fly tokens create deploy --app sandesh-api
```

Finally, Nirman's `CORS_ALLOWED_ORIGINS` has to name `https://sandesh.fly.dev` — without it
every sign-in fails at the preflight.

## Retention is built and switched off

`app.retention.enabled` defaults to **false**, and that default is a decision rather than
caution. Keeping the work channels needs a stated purpose, a stated window and language in the
employment contract *before* the first retained message exists — none of which is engineering's
to supply, and shipping it enabled would make the legal position a consequence of a deploy.

When counsel has signed off:

```bash
flyctl secrets set --app sandesh-api RETENTION_ENABLED=true RETENTION_WINDOW_DAYS=1095
```

Three years is a placeholder; the real number follows your contracts' arbitration window.
Direct messages are never retained by any setting — refused in the service and again by a check
constraint, because it is the one thing that must not be got wrong.

## What is deliberately not here yet

The action channel (§16), read receipts and typing, and the export gate's approval flow — the
tables exist, the two-approver workflow does not. Each is cut on purpose and the reasoning
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
