# Saigon.Social – Build Task Lists

Derived from `DESIGN.md` (PRD v2.0). Tasks are grouped by **feature/domain** and presented in a **recommended build order**. Check items off (`[x]`) as you complete them.

> **Recommended phase order:** Foundation → Identity & Profiles → Rooms & Public Chat → Message Threads → Private Chat → Post-Chat Emoji → Moderation & Safety → Economics & Onboarding → Frontend/UX → Observability & Metrics → Launch Hardening.

---

## Phase 0 — Foundation & Infrastructure
*Domain: Backend platform, persistence, real-time transport. Build this first; everything depends on it.*

- [x] Stand up Ktor backend skeleton (routing, config, logging via logback).
- [x] Provision and connect ArangoDB (multi-model: documents + graphs). _`repository/ArangoPersistence.kt` connects via the official Java driver (`connectArango`), creates the database + all collections on startup, and backs every durable repository (`Users`/`Rooms`/`Messages`/`Threads`/`RoomIdentities`/`Reports`/`Warnings`/`ModerationFlags`/`Payments`/`Metrics`) by serializing domain types to `RawJson`. Wired in `main()` via `ARANGO_HOST`/`ARANGO_*` env (in-memory fallback when unset, used by tests). `seedInitialData` seeds the founder + opening rooms._
- [x] Define base data model collections: `Users`, `Rooms`, `Messages`, `Threads`, `Emojis`. _Modeled in `domain/Models.kt` + `repository/Repositories.kt` (`ArangoSchema`)._
- [x] Establish WebSocket infrastructure for live chat (connection lifecycle, heartbeat, reconnect). _`realtime/ChatHub.kt` + `/ws` endpoint; ping/timeout configured._
- [x] Implement founder-managed auth (no OTP, no open sign-up); session/JWT issuance. _`auth/Auth.kt`, `/founder/onboard`._
- [x] Add Seeker access via JWT or device ID (no persistence). _`/auth/seeker`, device-bound token, no user record._
- [x] Set up Qwen AI API client/integration scaffolding (names + avatars). _`ai/QwenClient.kt` (`IdentityGenerator` seam + local fallback)._
- [x] Define ephemeral-data policy: no chat history stored for private chats. _Private chats are non-persistent by design; `ThreadRepository.clearForRoom` supports ephemeral threads._

---

## Phase 1 — User Types & Profiles
*Domain: Identity persistence, roles, profile data. (DESIGN §3, §4)*

- [x] Implement role model: Seeker, Sharer, Supporter, Founder with capability gates. _`domain/Role` gates + explicit `CapabilityMatrix` (`domain/Profile.kt`)._
- [x] Persistent profile fields: real display name (founder-only), interests (max 5, free-text + auto-suggest). _`ProfileService.update` validates; `displayName` stays founder-managed; `/profile/interests/suggest`._
- [x] Intent badge selection (single primary from defined set). _`Intent` enum; set via `ProfileUpdate.intent`._
- [x] Languages multi-select (vi, en, vi/en, ko, zh, etc.). _`Languages.supported` + validation in `ProfileService`._
- [x] Optional offline schedule (daily/weekly availability windows). _`User.offlineSchedule`, editable via `ProfileUpdate`._
- [x] Private emoji stats aggregation (visible only to the user). _`ProfileService.recordReceivedEmoji`/`emojiStats`; `/profile/me/emoji-stats`._
- [x] Enforce capability matrix per role (who can chat, who is messageable). _`CapabilityMatrix` + `Role.can`; routes reject Seekers via `requireMember`._

---

## Phase 2 — Per-Room Identity
*Domain: Anonymous, room-scoped identity generation. (DESIGN §5)*

- [x] Generate random name + avatar on first room join via Qwen AI. _`IdentityService.getOrCreate` generates via `IdentityGenerator` on first join; `GET /rooms/{roomId}/identity`._
- [x] Store identity scoped per (user, room) — independent across rooms. _`RoomIdentity` keyed per (user, room) in `RoomIdentityRepository`._
- [x] "Refresh my name & avatar in this room" action (instant regeneration). _`IdentityService.refresh`; `POST /rooms/{roomId}/identity/refresh`._
- [x] Ensure no time-based/forced resets — user-controlled only. _`getOrCreate` is idempotent/stable; identity only changes on explicit refresh._
- [x] Abstract sketch-style avatar generation pipeline. _`IdentityGenerator` seam (`LocalIdentityGenerator`/`QwenIdentityGenerator`) emits abstract sketch-style avatar URLs._

---

## Phase 3 — Rooms & Public Chat
*Domain: Core social unit. (DESIGN §6)*

- [x] Room model: name (no `#` prefix), primary emoji, atmosphere, founder-created. _`Room` model + `RoomService.create` validates (no `#`, required emoji)._
- [x] Founder-only room creation/management. _`POST /rooms` gated by `Role.isAdmin`; `RoomService` owns persistence._
- [x] Room list view with live occupant counts. _`GET /rooms` → `RoomSummary` (name/emoji/atmosphere + live `chatHub.occupants`)._
- [x] Open viewing for all roles (Seeker/Sharer/Supporter) — read public chat. _Unauthenticated `GET /rooms/{roomId}/messages`._
- [x] Restrict message sending to Sharers & Supporters (Seekers read-only). _WS `Say` gated by `session.role.canChatPublicly`; token-bound role via `TokenService.resolve`._
- [x] Display intents + languages next to each person in the room list. _`GET /rooms/{roomId}/occupants` → `OccupantView` (identity + intent + languages)._
- [x] Real-time public chat message broadcast over WebSockets. _`ChatHub.broadcast` fans `Outgoing.Said` (with room-scoped author name) to room members._
- [x] Plain-text-only enforcement (no images/voice/video). _`PlainText.isValid` rejects blanks, control chars, and overlong messages on the `Say` path._

---

## Phase 4 — Message Threads (TikTok-style Comment Sheet)
*Domain: Branching ephemeral conversations. (DESIGN §9)*

- [x] Tappable messages open a bottom-sheet thread view. _`Outgoing.Said` now carries `messageId`; `GET /rooms/{roomId}/messages/{messageId}/thread` returns the sheet contents (backend support for the bottom-sheet UI in Phase 9)._
- [x] Thread data model: parent message ID + nested replies. _`ThreadReply` (`parentMessageId` + optional `replyToId`); `ThreadRepository`/`ThreadService`._
- [x] Allow Sharers/Supporters to reply within a thread. _WS `Incoming.Reply` gated by `session.role.canChatPublicly`; Seekers rejected. Plain-text enforced._
- [x] Nested replies displayed linearly in the sheet. _`ThreadService.thread` returns replies sorted by `createdAt`; `replyToId` preserves nesting._
- [x] Reply-count indicator on main feed messages (e.g., "↳ 4 replies"). _`FeedMessage.replyCount` on `GET /rooms/{roomId}/messages`; live count in `Outgoing.Replied`._
- [x] Ephemeral threads: clear on room empty or after 24h inactivity. _`ThreadService.clearForRoom` on room-empty (Leave + disconnect via `ChatHub.unregister`); `sweepInactive` clears threads idle > 24h._

---

## Phase 5 — Private Chat (One-on-One)
*Domain: Supporter-only direct conversations. (DESIGN §7)*

- [x] Restrict private chat availability to Supporters only. _`PrivateChatService.start` requires the target to hold `BE_PRIVATELY_MESSAGED` (Supporter/Founder) and an unexpired subscription._
- [x] Initiate private chat by tapping a Supporter's name in a room. _WS `Incoming.StartPrivateChat(roomId, targetUserId)`; broadcasts `Outgoing.PrivateChatStarted` (room-scoped peer name) to both parties._
- [x] Plain-text chat window — no typing indicators, no read receipts, no "last seen." _`Incoming.SayPrivate` → `Outgoing.PrivateMessageSaid`; plain-text enforced, no presence/receipt signals emitted._
- [x] Do not persist chat history after the chat ends. _`InMemoryPrivateChatRepository.end` drops the session and all messages; chats are non-persistent by design._
- [x] Either party can end the chat instantly (no confirmation prompt). _`Incoming.EndPrivateChat`; `PrivateChatService.end` lets any participant close it and notifies both via `Outgoing.PrivateChatEnded`._
- [x] "Available for chat" indicator for active Supporters in rooms. _`OccupantView.available` via `PrivateChatService.isAvailable` on `GET /rooms/{roomId}/occupants`._

---

## Phase 6 — Post-Chat Final Emoji
*Domain: Closing ritual + private stats. (DESIGN §8)*

- [x] Trigger shared emoji-selection screen when either party ends the chat. _`Incoming.EndPrivateChat` opens a `FinalEmojiSession` (`FinalEmojiService.open`) and pushes `Outgoing.FinalEmojiPrompt` (with `EmojiOptions.all`) to both parties._
- [x] Both users select an emoji privately from the defined option set. _WS `Incoming.SelectFinalEmoji`; `FinalEmojiService.select` validates participant + `EmojiOptions`, and selections stay hidden (no signal emitted) while waiting on the peer._
- [x] Reveal both emojis side-by-side once both have selected. _On completion `select` returns per-user `FinalEmojiReveal` (`youSent`/`theySent`); the server sends each party a personalized `Outgoing.FinalEmojiRevealed`._
- [x] No follow-up nudges after reveal. _The session is dropped on reveal (`FinalEmojiRepository.remove`); no further frames are sent and re-selection is rejected._
- [x] Add each received emoji to the user's private tally. _Each participant is credited with the peer's emoji via `ProfileService.recordReceivedEmoji` (DESIGN §8)._

---

## Phase 7 — Moderation & Safety
*Domain: Community health & abuse prevention. (DESIGN §10)*

- [x] Report button on every profile and chat. _`POST /moderation/report` (any member) with `ReportTarget.PROFILE`/`CHAT`; `ModerationService.report` records the report (`moderation/ModerationService.kt`)._
- [x] Two-reports = permanent ban rule (no appeals). _Reports counted by **distinct reporter**; reaching `ModerationPolicy.REPORTS_TO_BAN` (2) permanently bans the subject. Bans are silenced on the WS chat (`Say`/`Reply`/`StartPrivateChat`/`SayPrivate`)._
- [x] Unsolicited-sales warning workflow (warning → second warning → ban). _`POST /moderation/warn` (founder-only); `ModerationService.warn` escalates warning → second warning → ban (`WARNINGS_TO_BAN`)._
- [x] Founder-only manual verification (no bots / no automated sign-up). _`POST /moderation/verify` + `GET /moderation/status/{userId}` gated by `requireFounder` (403 via `ForbiddenException`); `ModerationService.verify` sets the verified flag._
- [ ] Screenshot prevention on native (deferred to KMP mobile phase).

---

## Phase 8 — Economics & Onboarding
*Domain: Payments, tiers, founder onboarding. (DESIGN §3, §11)*

- [x] Sharer one-time fee flow (100,000 VND). _`EconomicsService.paySharerFee` records a `Payment` and sets `User.sharerPaidAt`; `POST /economics/sharer-fee` (amount validated against `Pricing.SHARER_ONE_TIME_VND`)._
- [x] Supporter subscription flow (50,000 VND / 3 months) with expiry handling. _`EconomicsService.paySupporterSubscription` sets `supporterUntil = now + Pricing.SUPPORTER_PERIOD_MILLIS` (90d), renewals stack from the current expiry; expiry handled via `effectiveRole` (expired Supporter → Sharer) and `PrivateChatService.available`. `POST /economics/supporter-subscription` returns a fresh token carrying the upgraded role._
- [x] Founder onboarding/verification workflow to add Sharers/Supporters. _`POST /founder/onboard` (founder-only) creates the account and settles its tier: every onboarded member has paid the Sharer fee and an onboarded Supporter starts a real subscription period. Manual verification remains `POST /moderation/verify` (Phase 7)._
- [x] Tier capability enforcement tied to payment status. _`EconomicStatus.effectiveRole`/`capabilities` derive from payment status: unpaid → Seeker-level, expired Supporter → Sharer; surfaced via `GET /economics/me`._
- [x] No platform cut on user-to-user transactions (no marketplace logic). _`Pricing.PLATFORM_CUT_BPS == 0`; the only money recorded is the platform fees above — there is no marketplace/transaction ledger. Exposed on `GET /economics/pricing`._

---

## Phase 9 — Frontend / UX (Compose Web)
*Domain: Web-first client. (DESIGN §12)*

- [x] Compose Web app shell + navigation. _`ui/SaigonApp.kt`: themed shell + top bar + a `Screen` navigation stack; every screen is wired to the live backend via `ui/Api.kt` (`SaigonApi`, HTTP + WebSocket) — there is no `SampleData`. Compiles for `js` and `wasmJs`._
- [x] Room list screen. _`RoomListScreen` lists rooms (emoji/name/atmosphere + live occupant count) fetched from `GET /rooms` via the live `SaigonApi`._
- [x] Room/public chat screen with occupant list (intents + languages). _`RoomChatScreen` + `OccupantList`: message feed with a send box and a side panel showing each person's intent, languages, and "available" indicator._
- [x] Thread bottom-sheet UI. _`ThreadSheet`: scrim + bottom-anchored sheet opened by tapping a message; shows the parent + linear replies and a reply box (DESIGN §9)._
- [x] Private chat window UI. _`PrivateChatScreen`: plain-text bubbles, no presence/receipts, with an instant "End chat" action (DESIGN §7)._
- [x] Final-emoji selection + reveal screens. _`FinalEmojiScreen`: private emoji grid selection, then side-by-side "You sent / They sent" reveal with a Close, no nudges (DESIGN §8)._
- [x] Profile setup/edit screens (interests, intent, languages, schedule). _`ProfileScreen`: interests field, single-select intent, multi-select languages, and offline-schedule field (DESIGN §4)._
- [x] Identity refresh button per room. _`IdentityBar` "🔄 Refresh name & avatar" cycles the per-room identity (DESIGN §5)._
- [ ] (Future) Kotlin Multiplatform iOS/Android targets.

---

## Phase 10 — Success Metrics & Observability
*Domain: Internal analytics. (DESIGN §14)*

- [x] Track Seeker → Sharer conversion. _`EconomicsService.paySharerFee` emits `MetricType.SHARER_CONVERSION` on first settlement (renewals/already-paid excluded); surfaced as `seekerToSharerConversions` in `MetricsService.snapshot()`._
- [x] Track Sharer → Supporter conversion. _`EconomicsService.paySupporterSubscription` emits `MetricType.SUPPORTER_CONVERSION` on first promotion only (renewals excluded); `sharerToSupporterConversions`._
- [x] Track Daily Active Users (DAU). _`MetricType.ACTIVE` recorded on room join + public message (`handleIncoming`); `MetricsService` counts distinct members in the trailing 24h window._
- [x] Track average chat duration. _`MetricType.CHAT_ENDED` carries the chat's duration (now − `createdAt`) on `EndPrivateChat`; averaged in `averageChatDurationMillis`/`Seconds`._
- [x] Track emoji selection rate. _`FINAL_EMOJI_PROMPTED` (per participant on chat end) vs. `FINAL_EMOJI_SELECTED` (on `SelectFinalEmoji`); `emojiSelectionRate = selected ÷ prompted`._
- [x] Track report rate (target < 1%). _`ModerationService.report` emits `REPORT_FILED`; `reportRate = reports ÷ distinct active members`. Founder-only snapshot at `GET /metrics`; events are content-free (DESIGN §14)._

---

## Phase 11 — Launch Hardening
*Domain: Cross-cutting non-functional requirements.*

- [x] Verify privacy guarantees end-to-end (no history, no receipts, no last-seen). _`Phase11Test` asserts private-chat history is dropped on end and the `Outgoing` protocol exposes no receipt/typing/last-seen frames; codified in `LaunchConfig` (`STORES_CHAT_HISTORY`/`READ_RECEIPTS`/`LAST_SEEN`/`TYPING_INDICATORS` = false)._
- [x] Load/concurrency test WebSocket rooms and threads. _`Phase11Test` drives 500 concurrent `ChatHub` joins/leaves (exact occupant counts) and 500 concurrent thread replies (no lost writes). Hardened `InMemoryMessage`/`ThreadRepository` to `CopyOnWriteArrayList` + atomic `computeIfAbsent`._
- [x] Confirm no push notifications path exists (web-only). _`LaunchConfig.PUSH_NOTIFICATIONS = false`; no push routes/dependencies exist. Surfaced publicly via `GET /launch` and asserted in `Phase11Test`._
- [x] Security review of founder auth and payment flows. _`Phase11Test` confirms founder-only routes (rooms/warn/verify/metrics) reject non-founders (403/400), payment endpoints require auth (401), and tampered amounts are rejected (400)._
- [x] Saigon-only / single-city scoping verified. _`LaunchConfig.SINGLE_CITY = true`, `LAUNCH_CITY = "Ho Chi Minh City (Saigon)"`; there is no multi-city concept. Exposed on `GET /launch` and asserted in `Phase11Test`._
