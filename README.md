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

The server persists everything durable (users, rooms, messages, threads, room identities, payments,
moderation state, metrics) to **ArangoDB** (DESIGN §12). Configure the connection via environment
variables; when `ARANGO_HOST` is unset the server falls back to in-memory storage (used by tests).

| Variable | Default | Purpose |
|----------|---------|---------|
| `ARANGO_HOST` | _(unset → in-memory)_ | ArangoDB host |
| `ARANGO_PORT` | `8529` | ArangoDB port |
| `ARANGO_USER` | `root` | Database user |
| `ARANGO_PASSWORD` | _(empty)_ | Database password |
| `ARANGO_DB` | `saigon_social` | Database name (created on startup) |
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
- Server (with ArangoDB): `ARANGO_HOST=localhost ARANGO_USER=root ARANGO_PASSWORD=… PORT=9091 ./gradlew :server:run`
- Server (in-memory, no DB): `./gradlew :server:run`
- Web app:
    - Wasm target (faster, modern browsers): `./gradlew :app:webApp:wasmJsBrowserDevelopmentRun`
    - JS target (slower, supports older browsers): `./gradlew :app:webApp:jsBrowserDevelopmentRun`

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
