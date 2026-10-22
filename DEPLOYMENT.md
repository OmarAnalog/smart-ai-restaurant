# Deployment

The root Dockerfile builds React, copies its output into Spring Boot's static resources, and packages a Java 21 application. Deploy **one application container and PostgreSQL**. Both UI and API use the same HTTPS origin; the Vite development server is not used in production.

## Container configuration

Build from the repository root:

```bash
docker build -t table-talk .
```

The build context uses an allowlist: `.env`, local toolchains, photographs and existing build outputs are excluded. API secrets are supplied at runtime, never as build arguments. The final container runs as a non-root user.

Set these variables on the hosting service:

| Variable | Required value |
| --- | --- |
| `GEMINI_API_KEY` | Your API key, entered in the host's secret/environment settings |
| `GEMINI_MODEL` | A model your project can successfully call; current default is `gemini-3.5-flash-lite` |
| `DATABASE_URL` | **JDBC** connection URL, for example `jdbc:postgresql://internal-host:5432/restaurant` |
| `DATABASE_USERNAME` | Database username |
| `DATABASE_PASSWORD` | Database password, configured as a secret |
| `PORT` | Host-provided listening port; defaults to 8080 |

A `postgres://...` or `postgresql://...` connection string is not directly a JDBC URL. Use the provider's host, port and database name to form the JDBC URL and configure credentials separately. Follow the database provider's TLS requirements; append `?sslmode=require` if required for that connection.

Use a persistent managed PostgreSQL database in the same region/private network as the application. Do not store PostgreSQL data in an ephemeral web-service filesystem. Flyway runs the V1-V3 migrations on startup. A fresh deployment starts with new users and ten servings per menu item; local data is not automatically uploaded.

Health-check path: `/api/health`. It verifies a database connection and returns only `{"status":"UP"}`. It does not call Gemini, consume model quota or certify AI availability. Database failures return a non-success response through existing error handling.

## Verification before sharing

1. Verify `/` serves the React page and `/api/health` responds successfully through the public HTTPS URL.
2. Verify `/api/menu` returns the 16 menu items.
3. Use a fresh username and test a real chat with the deployed Gemini project. Confirm that the configured model responds successfully and the project has available quota.
4. Verify persistent preferences across logout/login and a new conversation, then place one order and confirm history.
5. Restart the application service and verify preferences, history and stock persist.
6. Ensure the service/database remain available throughout evaluation. Confirm the host's current plan limits, suspension behavior, expiry and charges before provisioning.
7. Verify the public environment settings contain no local `localhost` database address. Never place the Gemini key in `VITE_*` variables or frontend assets.

The default identity mechanism is a shared username-only demo. Anyone who knows a username can select it. Tell reviewers to use demonstration data and keep platform secrets server-side.

## Release and rollback

Build/deploy from a fixed source revision. Let the first startup finish migrations before judging the health check. For this small demo, deploy one application instance and avoid overlapping incompatible database migrations. Keep a database backup before schema changes; an application rollback does not automatically undo migrations.

Configure the hosting account, database and application secrets before starting the deployment.

## Selected hosting path: GitHub and Railway

Source repository: `https://github.com/OmarAnalog/smart-ai-restaurant`. Publish only source, tests and documentation; `.gitignore` excludes environment files, toolchains and local build artifacts.

1. Publish the source repository to your GitHub account.
2. In Railway, create a project and add PostgreSQL with persistent storage. Keep it private to the project.
3. Add a service from the GitHub repository. Keep its root at the monorepo root, where Railway can detect the Dockerfile.
4. Set these app-service variable references, using the actual database service name in place of `Postgres` if different:

   ```text
   DATABASE_URL=jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}
   DATABASE_USERNAME=${{Postgres.PGUSER}}
   DATABASE_PASSWORD=${{Postgres.PGPASSWORD}}
   ```

5. Enter `GEMINI_API_KEY` as a service secret and select the generation model. Do not copy the local `.env` into the repository or the Docker image.
6. Set the deployment health-check path to `/api/health`, allow 120 seconds for startup/migrations, and keep one application replica initially. Use the Dockerfile's start command and the host-provided `PORT`.
7. Generate a Railway public domain for the application service; do not expose PostgreSQL publicly for normal app connectivity.
8. Complete the verification steps above before sharing the URL with reviewers. Confirm the account's plan and usage costs before provisioning.

Use Railway's current dashboard or CLI for service configuration. Do not introduce a new `railway.json`/`railway.toml`: Railway's current documentation deprecates that deployment-config mechanism for new services. A small app does not require a separate infrastructure-as-code project.

Official references: [Dockerfiles](https://docs.railway.com/builds/dockerfiles), [PostgreSQL connections](https://docs.railway.com/databases/postgresql), [CLI](https://docs.railway.com/cli), [configuration changes](https://docs.railway.com/config-as-code).
