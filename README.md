This is a Kotlin Multiplatform project targeting Android, Web, Server.

* [/app/shared](./app/shared/src) is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
    - [commonMain](./app/shared/src/commonMain/kotlin) is for code that’s common for all targets.
    - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
      For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
      the [iosMain](./app/shared/src/iosMain/kotlin) folder would be the right place for such calls.
      Similarly, if you want to edit the Desktop (JVM) specific part, the [jvmMain](./app/shared/src/jvmMain/kotlin)
      folder is the appropriate location.

* [/core](./core/src) is for the code that will be shared between all targets in the project.
  The most important subfolder is [commonMain](./core/src/commonMain/kotlin). If preferred, you
  can add code to the platform-specific folders here too.

* [/server](./server/src/main/kotlin) is for the Ktor server application.

### Persistence (ArangoDB)

The server persists everything durable (users, rooms, messages, threads, room identities, invite
codes, payments, moderation state, metrics) to **ArangoDB** (DESIGN §12). ArangoDB is **mandatory**:
there is no file/in-memory fallback — the server **fails fast** on startup (both locally and in
deployment) if the database is unreachable. Configure the connection via environment variables; every
variable has a sensible default that matches the local `docker-compose.yml` and the deploy scripts.

| Variable | Default | Purpose |
|----------|---------|---------|
| `ARANGO_HOST` | `localhost` | ArangoDB host |
| `ARANGO_PORT` | `8529` | ArangoDB port |
| `ARANGO_USER` | `root` | Database user |
| `ARANGO_PASSWORD` | _(empty)_ | Database password |
| `ARANGO_DB` | `saigon_social` | Database name (created on startup) |
| `ARANGO_CONNECT_TIMEOUT_SECONDS` | `60` | How long startup waits/retries for ArangoDB to come up |
| `JWT_SECRET` | `dev-secret-change-me` | JWT signing secret (set a real one in prod) |
| `PORT` / `HOST` | `9090` / `0.0.0.0` | Server bind address |

On startup the server creates the database + collections and seeds the opening rooms. Saigon.Social
is **founder-managed** with no open sign-up (DESIGN §2), so the founder account is **not**
pre-created: the very first person to arrive claims it by opening the web client's *Profile → Sign
in* screen, leaving the access-token field **blank**, and pressing *Sign in* (`POST /auth/bootstrap`).
This is only possible until the first member exists — afterwards it is closed forever.

Once signed in, the founder mints access tokens for everyone else from *Profile → Create an access
token* (name the member, pick their tier; `POST /founder/onboard` returns the token to hand over).
Members paste their token into the same *Sign in* screen to chat, edit their profile, and refresh
their per-room identity; an invalid token is rejected with a friendly message at sign-in (the token
is validated against `/profile/me` before it's accepted) rather than a raw error. Without a token
you browse rooms and read public chat as a Seeker.

Your access token is the only credential — there is no separate password — and the web client
**persists it in the browser (`localStorage`)**, so a page reload keeps you signed in instead of
silently logging you out. *Sign out* (Profile → Sign out) clears the stored token. The top-bar
action reads *Sign in* when you're signed out and *Profile* once you're signed in.

The web client talks to the backend at `http://localhost:9091` by default
(`SaigonApi.DEFAULT_BASE_URL`); CORS is enabled on the server so the dev client can reach it.

### Running the apps

Use the run configurations provided by the run widget in your IDE's toolbar. You can also use these commands and
options:

- Android app: `./gradlew :app:androidApp:assembleDebug`
- Start ArangoDB locally first (required): `docker compose up -d arango`
  (exposes `localhost:8529` with auth disabled, matching the server's `ARANGO_*` defaults)
- Server: `PORT=9091 ./gradlew :server:run`
  (override any connection setting via `ARANGO_HOST`, `ARANGO_USER`, `ARANGO_PASSWORD`, … if needed)
  - A freshly started container reports *Started* before ArangoDB is actually accepting connections
    (first boot initialises the `_system` database, ~10–30s). The server **waits and retries** for up
    to `ARANGO_CONNECT_TIMEOUT_SECONDS` (default 60s) before failing, so a plain start just works. To
    block until the DB is ready up front, use `docker compose up -d --wait arango`.
  - Seeing `Cannot reach ArangoDB … Cannot contact any host!`? The container isn't listening yet
    (give it a moment / use `--wait`), or a stale `arango-data` volume was initialised with a
    different password; reset it with `docker compose down -v` and bring it back up.
- Web app:
    - Wasm target (faster, modern browsers): `./gradlew :app:webApp:wasmJsBrowserDevelopmentRun`
    - JS target (slower, supports older browsers): `./gradlew :app:webApp:jsBrowserDevelopmentRun`

### Deployment

First-time host setup (run once per server, over SSH) installs Docker, creates the shared `web`
Docker network, and brings up a Traefik reverse proxy that terminates TLS via Let's Encrypt:

- Backend host (`api.saigon.social`): `./deploy/init-server.sh [user@]host`
- Web host (`saigon.social`): `./deploy/init-web.sh [user@]host`

Both init scripts are idempotent; if the web client and API share a single host, you only need to
run one of them. Override defaults via env vars, e.g. `ACME_EMAIL`, `SSH_HOST`, `CERT_RESOLVER`.

Then two scripts under [`deploy/`](./deploy) build each service's Docker image **on your machine**,
ship it to the target host over SSH (`docker save | ssh docker load`), and run the container **on
that host** — attached to the shared `web` network so the host's Traefik can route to it (this is
what lets Let's Encrypt issue a certificate). No container registry/login is required.

- Server → **api.saigon.social**: `./deploy/deploy-server.sh [user@]host`
  (injects `QWEN_API_KEY` as a runtime env var; pass `JWT_SECRET=…` for a real production secret).
- Web → **saigon.social**: `./deploy/deploy-web.sh [user@]host`
  (bakes `DEFAULT_BASE_URL=https://api.saigon.social` into the bundle at build time, since the web
  client reads it from the compile-time constant `SaigonApi.DEFAULT_BASE_URL`).

The server's fat JAR is compiled **on your machine** with Gradle (`:server:buildFatJar`) and packaged
into a tiny JRE image ([`deploy/server`](./deploy/server)) — we build on the host rather than inside
Docker so each deploy doesn't re-download Gradle and recompile from scratch (which took 10+ minutes).
The web client's Compose/**JS** bundle is compiled **on your machine** with Gradle
(`:app:webApp:jsBrowserDistribution` — we deploy the JS target for broad browser support; the Kotlin
web tooling can't run inside a minimal container and is very slow there), then the static assets are
packaged into a tiny nginx image ([`deploy/web`](./deploy/web)). Both services
are routed by host with Traefik labels. The deploy target defaults to `root@<domain>`; override via
env vars, e.g. `SSH_HOST`, `REGISTRY`, `IMAGE_TAG`, `CERT_RESOLVER`, or set `LOCAL=true` to build and
run on this machine instead (when the app and Traefik share one box):

```bash
SSH_HOST=deploy@api.saigon.social JWT_SECRET=… ./deploy/deploy-server.sh
./deploy/deploy-web.sh                 # ships to root@saigon.social by default
LOCAL=true ./deploy/deploy-server.sh   # build & run here instead of over SSH
```

HTTPS is handled automatically by Traefik (Let's Encrypt) — no certbot needed. To verify that
certificates are being issued and the sites are reachable over TLS, run the read-only checker:

```bash
./deploy/check-tls.sh            # checks saigon.social and api.saigon.social
```

### Running tests

Use the run button in your IDE's editor gutter, or run tests using Gradle tasks:

- Android tests: `./gradlew :app:shared:testAndroidHostTest`
- Server tests: `./gradlew :server:test`
- Web tests:
    - Wasm target: `./gradlew :app:shared:wasmJsTest`
    - JS target: `./gradlew :app:shared:jsTest`

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html),
[Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform/#compose-multiplatform),
[Kotlin/Wasm](https://kotl.in/wasm/)…

We would appreciate your feedback on Compose/Web and Kotlin/Wasm in the public Slack
channel [#compose-web](https://slack-chats.kotlinlang.org/c/compose-web).
If you face any issues, please report them on [YouTrack](https://youtrack.jetbrains.com/newIssue?project=CMP).
