# Passwords for Sign-In — Findings & Proposal

**Question raised:** Today the access token is the *only* credential. After a member (Founder
included) enters their access token, can we let them **set a password** and sign back in with it
later? This doc captures the current behavior, the gap, and a concrete, codebase-aligned design for
adding passwords.

---

## 1. Current State (what the code actually does)

### The access token is the sole credential
- The Founder claims their account with a **blank token** (`POST /auth/bootstrap`), and every other
  member is onboarded by the Founder, who hands them a token (`POST /founder/onboard`). In both
  cases the server returns an `OnboardResponse` containing a signed **JWT** minted by
  `TokenService.issueMemberToken(userId, role)` (`server/.../auth/Auth.kt`).
- That JWT *is* the credential. There is no username/password anywhere in `AuthConfig`,
  `TokenService`, or the `User` model (`server/.../domain/Models.kt` has no password field).

### How "staying signed in" works today
- On the web the token is persisted in `localStorage` via `TokenStore` (commonMain `expect`, with
  `js`/`wasmJs` actuals); `SaigonApp` restores it on launch and re-saves on change, so a reload no
  longer logs you out.
- At sign-in, a pasted token is validated against `/profile/me` before being accepted, so an
  invalid/expired token shows a friendly inline error instead of a broken profile.

### Why this is a gap
- **Member-side token lifetime:** member JWTs expire after `memberTtlMillis` (default **30 days**,
  `AuthConfig`). Once expired, the *exact same token string* no longer authenticates — there is no
  in-app way to renew it. The user must get a fresh token from the Founder.
- **Founder lockout:** `POST /auth/bootstrap` only succeeds when **no member account exists**. After
  the Founder claims their account, a blank token can never re-create it. If the Founder loses their
  token (or it expires after 30 days), there is **no in-app recovery path** — the only workaround is
  operational (delete the Founder `User` row in ArangoDB and re-bootstrap). This is documented as a
  real gap.
- **New-device sign-in:** moving to a new browser/device means re-pasting a long opaque token. A
  password the user *chooses and remembers* is a far better re-entry credential than a copied JWT.

A password solves all three: it's a stable, user-chosen, renewable credential that survives token
expiry and device changes — for the Founder and every other member alike.

---

## 2. Proposed Design — "set a password after entering your token"

The token stays the **bootstrap/onboarding** credential (how you first get in). The password becomes
the **durable re-entry** credential you set once you're in, then use to sign in again later.

### A. Data model
Add password fields to `User` (server `domain/Models.kt`). **Never store plaintext** — store a salted
hash only:

```kotlin
data class User(
    // …existing fields…
    /** Argon2/bcrypt hash of the chosen password; null until the member sets one. */
    val passwordHash: String? = null,
    /** Unix epoch millis the password was last set/changed (for audit + "rotate password" UX). */
    val passwordSetAt: Long? = null,
)
```

- Applies to **all account types that have a persisted `User`** — Founder, Supporter, Sharer.
- **Seekers are excluded:** they are non-persistent, device-bound, read-only (DESIGN §3), so there is
  no `User` row to attach a password to.
- Use a vetted KDF (Argon2id preferred, bcrypt acceptable). Add the dependency in
  `gradle/libs.versions.toml` and `server/build.gradle.kts`; keep all hashing server-side.

### B. Setting / changing a password (authenticated)
- **Route:** `POST /profile/password` (or `PATCH`), gated by the existing `AUTH_JWT` auth (the caller
  must already be signed in with a valid token).
- **Body:** `{ "newPassword": "…", "currentPassword": "…"? }` — `currentPassword` required only when
  one is already set (change vs. first set).
- **Server:** validate strength (min length, etc.), hash, store `passwordHash` + `passwordSetAt`.
- **Client:** a "Set a password" / "Change password" section on the **Profile** screen, shown right
  after the user signs in with their token. This is the literal flow the question asks for — *enter
  the access token, then set a password*.

### C. Signing in with a password (unauthenticated)
- **Route:** `POST /auth/login` with `{ "identifier": "…", "password": "…" }`.
- **Identifier:** the `User` model currently has only `id` + `displayName` (no email/username). Two
  options:
  1. **Recommended:** add a unique, login-only **handle** (or email) field set at onboarding, used
     purely as the password's username. Keeps `displayName` free-text and private as today.
  2. **Interim:** allow login by `userId` + password (works immediately, but a UUID is poor UX).
- **Server:** look up the user, verify the password against `passwordHash` (constant-time), and on
  success mint a **fresh** member JWT via `issueMemberToken` — i.e. the password flow *re-issues* a
  token, neatly solving the 30-day expiry problem.
- **Client:** add a "Sign in with password" option alongside the existing "paste token" field on the
  Sign-in screen. Persist the returned token via `TokenStore` exactly as today.

### D. Founder specifics
- The Founder sets a password the same way (Profile → Set a password) right after the one-time
  blank-token bootstrap. From then on they sign back in with **handle + password**, removing the
  "lost the only token = locked out forever" risk.
- Optionally seed an **env-configured founder recovery** (e.g. `FOUNDER_RECOVERY_SECRET`) so a lost
  password can be reset operationally without direct DB surgery. (Same Founder-only,
  env-config pattern noted in `MEMBER_MANAGEMENT.md`.)

### E. Security notes
- Hash with a strong KDF + per-user salt; never log or return the hash.
- Rate-limit `/auth/login` and return a generic "invalid credentials" message (no user enumeration).
- Keep validating the resulting token against `/profile/me` on the client, as today.
- Consider invalidating older tokens on password change (e.g. a `tokenVersion` claim) if you want a
  password change to forcibly sign out other sessions.

---

## 3. Recommendation / Phasing

1. **Phase 1 — set + login (core ask):** add `passwordHash`/`passwordSetAt` to `User`,
   `POST /profile/password` (authenticated set/change), and `POST /auth/login` returning a fresh JWT;
   add a login-only handle for a usable identifier. Wire the Profile "Set a password" section and a
   "Sign in with password" option in `SaigonApp`.
2. **Phase 2 — Founder recovery:** env-configured recovery secret / reset path so a lost Founder
   credential is no longer a permanent lockout.
3. **Phase 3 — hardening:** rate limiting, `tokenVersion` invalidation on password change, password
   strength + "last changed" UX.

This keeps the token as the onboarding mechanism (no change to how the Founder bootstraps or how
members are onboarded) while giving **every persisted account type** a durable, user-chosen way to
sign back in — directly addressing the lockout and 30-day-expiry gaps.
