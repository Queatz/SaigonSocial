# Founder Member Management — Findings & Suggestions

**Question raised:** Does the Founder have a way to (1) upgrade/downgrade members between
Sharer and Supporter, and (2) see a list of all members?

**Short answer (original):** **No on both counts.** Neither capability existed; the data and
building blocks were present, but no API route or UI surface exposed them to the Founder.

> **Status: ✅ Implemented.** Both capabilities now exist (see [§5 Resolution](#5-resolution)):
> a Founder-only `GET /founder/members` directory and a `PATCH /founder/members/{id}/role` tier
> control (comp upgrade / downgrade / soft removal), each audited, with a Members panel on the
> Founder's Profile screen.

---

## 1. Current State (what the code actually does)

### Roles & tiers
`Role` (`server/.../domain/Models.kt`) has `SEEKER → SHARER → SUPPORTER → FOUNDER`. A member's tier
is carried on the `User` record (`role`, `sharerPaidAt`, `supporterUntil`).

### How a member's tier changes today
Tier transitions are **payment-driven and self-serve**, not Founder-driven:

| Transition | Trigger | Code |
|------------|---------|------|
| Seeker → Sharer | One-time Sharer fee settled | `EconomicsService.paySharerFee()` |
| Sharer → Supporter | Supporter subscription purchased/renewed | `EconomicsService.paySupporterSubscription()` |
| Supporter → Sharer | **Only implicitly**, when the subscription *expires* (`effectiveRole`) | — |

Key constraints found in `EconomicsService`:
- `paySharerFee` comment: *"A paid member is at least a Sharer; never downgrade an existing Supporter."*
- There is **no method that demotes** a member (e.g. revoke Supporter, demote Sharer → Seeker, ban
  via role change). Expiry is the only path back down, and it is passive.

### Founder onboarding
`POST /founder/onboard` (`routes/Routes.kt`) lets the Founder create a member at a chosen
`role` (Sharer or Supporter) and settles the corresponding fee. This sets a tier **at creation
time only** — there is no endpoint to change it afterward.

### Members list
- The repository layer **does** support it: `UserRepository.all()` exists and is used internally
  (bootstrap check, tests).
- It is **never exposed** via an HTTP route. The only Founder-only read endpoints are
  `GET /moderation/users/{id}` (single user's moderation state) and `GET /metrics` (aggregates).
- There is **no `GET /founder/members`** (or equivalent), and the client (`Api.kt` / `SaigonApp.kt`)
  has no members-list screen.

---

## 2. Gaps

1. **No Founder-initiated upgrade.** The Founder cannot promote an existing Sharer to Supporter
   without going through the payment flow (and `paySupporterSubscription` is keyed to a payment,
   not an admin action).
2. **No downgrade at all.** Nothing can move Supporter → Sharer (other than waiting for expiry) or
   Sharer → Seeker. This is a real operational gap (e.g. refunds, comps, mistakes, abuse).
3. **No member directory.** The Founder, who *personally* onboards everyone (DESIGN §2), has no way
   to see who they've onboarded, each member's tier, payment status, or subscription expiry.

---

## 3. Suggestions

### A. Members List (read) — recommended first, low risk
- **Route:** `GET /founder/members` (Founder-only, gated by `requireFounder`).
- **Returns:** for each `User`: id, displayName, `role`, `sharerPaidAt`, `supporterUntil`, derived
  `effectiveRole`, and current `EconomicStatus`. Reuse `UserRepository.all()`.
- **Client:** a "Members" section on the Founder's Profile screen listing members with their tier
  and an "expires" badge for Supporters.

### B. Upgrade / Downgrade (write)
- **Route:** `PATCH /founder/members/{id}/role` with body `{ "role": "SUPPORTER" }` (Founder-only).
- **Server:** add an admin method on `EconomicsService` (e.g. `setTier(userId, role, by = founderId)`)
  that:
  - **Upgrade to Supporter:** sets `supporterUntil` to `now + 3 months` (a comped period) and
    records a metric/audit event, *without* requiring a `Payment` — distinct from
    `paySupporterSubscription`.
  - **Downgrade to Sharer:** clears `supporterUntil` (or sets it in the past) so `effectiveRole`
    resolves to Sharer.
  - **Demote to Seeker:** clears `sharerPaidAt` / sets role to Seeker (useful for soft removal).
  - Refuses to change the `FOUNDER`'s own role.
- **Token note:** because the JWT carries the role, a tier change should invalidate or refresh the
  affected member's token (they'll re-authenticate, or issue a fresh token). Document this so a
  downgraded Supporter can't keep "available for 1-on-1" until their old token expires.
- **Client:** per-member up/down controls in the Members list, with a confirmation dialog.

### C. Audit / metrics
- Record every Founder-initiated tier change as a `MetricEvent` (who, whom, from→to, when) so the
  Founder-only `/metrics` view reflects manual adjustments, consistent with the existing
  moderation/warning audit pattern.

---

## 4. Recommendation

Ship **A (members list)** first — it's read-only, reuses `users.all()`, and immediately answers
"who are my members?". Then add **B (tier PATCH)** with the no-payment comp/downgrade semantics and
token refresh. Both fit the existing Founder-only route + `EconomicsService` patterns and require no
schema changes (the `User` fields already exist).

---

## 5. Resolution

All three suggestions are implemented (no schema changes — the `User` fields already existed).

### A. Members List — done
- **Route:** `GET /founder/members` (`routes/Routes.kt`), gated by `requireFounder`.
- **Returns:** a `MemberView` per non-Seeker `User`: `id`, `displayName`, `role`, derived
  `effectiveRole`, `sharerPaidAt`, `supporterUntil`, and the full `EconomicStatus`. Reuses
  `UserRepository.all()`.
- **Client:** a **Members** section on the Founder's Profile screen (`FounderMembersSection` in
  `SaigonApp.kt`), via `SaigonApi.members()`.

### B. Upgrade / Downgrade — done
- **Route:** `PATCH /founder/members/{id}/role` with body `{ "role": "SUPPORTER" }` (Founder-only).
- **Server:** `EconomicsService.setTier(userId, role)` records **no `Payment`** (distinct from
  `paySupporterSubscription`):
  - **Supporter:** comps a fresh `Pricing.SUPPORTER_PERIOD_MILLIS` period (settling the Sharer fee
    if needed).
  - **Sharer:** clears `supporterUntil` so `effectiveRole` resolves to Sharer.
  - **Seeker:** clears `sharerPaidAt` + `supporterUntil` (soft removal).
  - Refuses to change the `FOUNDER`'s own role or to assign `FOUNDER`.
- **Token note:** the response carries a fresh member token reflecting the new role (null for a
  Seeker demotion), so the member re-authenticates rather than riding their old role-bearing JWT.
- **Client:** per-member tier controls in the Members list, each behind a confirmation dialog;
  the minted token is surfaced for the Founder to hand over (`SaigonApi.setMemberRole()`).

### C. Audit / metrics — done
- Each change records a `MetricType.TIER_CHANGE` event; the Founder-only `/metrics` snapshot
  exposes the running total as `founderTierChanges`.

### Tests
- `server/.../MemberManagementTest.kt`: `setTier` semantics (comp/downgrade/demote, no payment,
  audited, founder-self refusal) and the HTTP surface (founder-only listing, tier PATCH + fresh
  token, founder-self rejection).
