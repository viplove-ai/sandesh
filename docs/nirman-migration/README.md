# Changes Sandesh needs in Nirman

Three, and the footprint being this small is the argument for the whole approach.

## 1. The contract views

Copy `V43__chat_contract_views.sql` into
`nirman/backend/src/main/resources/db/migration/` and restart the Nirman backend. Renumber if
V43 is taken. Additive — nothing existing changes.

Then set the role's password out of band (it is not in the migration, because the migration is
committed):

```sql
ALTER ROLE chat_reader WITH PASSWORD '<the value you put in NIRMAN_DB_PASSWORD>';
```

## 2. The CORS origin

In `nirman/backend/src/main/resources/application-prod.yml` (or the Fly secret backing
`CORS_ALLOWED_ORIGINS`), append the Sandesh origin:

```
https://sandesh.fly.dev
```

Sandesh's login screen posts to Nirman's `/api/v1/auth/login` from that origin. Without this
every sign-in fails at the preflight with no useful message in the UI.

Config only. `SecurityConfig` already reads the list from configuration and already sets
`allowCredentials(true)`.

## 3. A test that the views still resolve

So a future refactor of `users` or `user_site_assignments` fails in Nirman's own CI rather than
in Sandesh's production. Assert both views resolve and return the expected column names.

---

## Not a change, but check it

`JWT_SECRET` must be byte-identical in both services. Sandesh verifies the tokens Nirman signs;
a different secret there means every request is refused, and the symptom — a 401 on a token that
looks perfectly good — is genuinely hard to read.
