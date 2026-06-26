Perfect. Let's refine this into a lean, buildable document. You've stripped away the cruft and made it *yours*.

---

# Saigon.Social – Product Requirements Document (PRD)

**Version:** 2.0  
**Status:** Ready for Development  
**Launch City:** Ho Chi Minh City (Saigon), Vietnam  
**Core Promise:** *A digital room for the city. Real people, real conversations, zero pressure.*

---

## 1. Product Vision

**Saigon.Social is a private, city-based social space where verified individuals connect through conversation.**

It is not a dating app. It is not a marketplace. It is not a social network with profiles and followers. It is a *room* – a place where you show up as yourself (anonymously by name, but authentically by interest), talk to whoever is there, and leave a trace of how you made others feel.

**Tagline (internal):** *People on demand. For Saigon.*

---

## 2. Core Principles

| Principle | Description |
|-----------|-------------|
| **Trust-first** | All members are onboarded by the founder personally. No open sign-ups. No phone OTP. |
| **One city** | Saigon only. The app is *place-based*, not global. |
| **Identity fluidity** | Names and avatars change per room, per user action. No fixed identity. |
| **No algorithms** | No matching. No "for you." No curation. Just a list of who's in each room. |
| **Plain text only** | No images, no voice notes, no video. Text preserves focus and reduces friction. |
| **Privacy by default** | No chat history stored. No read receipts. No "last seen." Chats vanish when closed. |
| **Economic transparency** | One-time fee to join as Sharer. Optional Supporter fee for 1-on-1 availability. No cut of any transactions between users. |

---

## 3. User Types

| Type | Description | Cost | Capabilities |
|------|-------------|------|--------------|
| **Seeker** | Temporary visitor. Can browse rooms and view public chat. Cannot chat. | Free | JWT or device ID. No persistence. |
| **Sharer** | Verified member. Can participate in rooms and public chat. | 100,000 VND (one-time) | Full room access, public chat, private chat, emoji reactions, identity control. |
| **Supporter** | Sharer + pays to be available for 1-on-1 chats. | 50,000 VND / 3 months | All Sharer capabilities + appears as "available" for private chats. |
| **Founder** | You. | N/A | Full admin: onboarding, moderation, room creation, user management. |

**Onboarding:** Founder personally verifies and adds each Sharer/Supporter. No automated sign-up.

---

## 4. User Profile (Persistent)

This data is **stable** and tied to the real person behind the room-specific identities.

| Field | Required? | Notes |
|-------|-----------|-------|
| Display name (real) | Yes | For founder's reference only. Not shown publicly. |
| Interests (tags) | Yes | Max 5. Free-text, auto-suggested (e.g., `philosophy`, `parenting`, `startups`, `guitar`, `badminton`). |
| Intent (badge) | Yes | Select one primary: `💬 Just Chatting` / `🧠 Deep Talk` / `🫂 Heavy Topics` / `🎯 Open to Collab` / `🎲 Surprise Me` / `☕ Casual` |
| Languages | Yes | Select all that apply (e.g., `vi`, `en`, `vi/en`, `ko`, `zh`). |
| Offline schedule | Optional | Set daily/weekly availability windows. Example: Mon–Fri 7–9 PM, Sat 10 AM–12 PM. |
| Emoji stats (private) | Auto-generated | Aggregated from received reactions. Visible only to the user. Not public. |

---

## 5. Identity – Per Room, User-Controlled

**Avatar and name are unique per room for each user.**

| Behavior | Description |
|----------|-------------|
| **Default** | When a user joins a room for the first time, Qwen AI generates a random name and avatar. |
| **Change** | User clicks a button: *"Refresh my name & avatar in this room"* – generates new ones instantly. |
| **Per room** | The identity is independent per room. Your name in `☕ Coffee Chat` is different from your name in `🧠 Deep Talks`. |
| **No time-based reset** | The user controls when they change. No forced resets. |

**Why:** This gives users agency over their identity while preserving anonymity. You can be "Minh" in one room and "Linh" in another, and change both whenever you feel like a fresh start.

**Generation:** Qwen AI generates human-sounding names (e.g., "Minh," "Linh," "Alex") and abstract sketch-style avatars.

---

## 6. Rooms

**Rooms are the primary social unit.** They are public chat spaces where anyone can see who's inside.

| Field | Description |
|-------|-------------|
| **Name** | Single string, no `#` prefix. Example: `☕ Coffee Chat`, `🧠 Deep Talks`, `🌱 Language Exchange` |
| **Emoji** | Each room has a primary emoji. Displayed before the name. |
| **Atmosphere** | Different rooms have different vibes. Set by founder. No "types." |

**Room behavior:**
- Anyone (Seeker, Sharer, Supporter) can *view* any room and see public chat.
- Only **Sharers and Supporters** can send messages in public chat.
- **Seekers** are read-only. They can browse, watch, and decide if they want to pay the one-time fee to join.
- You can tap any person's name in the room to start a **private chat** (if they are a Supporter – see Section 7).
- Intents and languages are displayed next to each person's name in the room list.

**Room list view:**
> `☕ Coffee Chat` – 12 people  
> `🌱 Language Exchange (EN/VI)` – 8 people  
> `🧠 Deep Talks` – 4 people  
> `🚀 Startup Talk` – 3 people

---

## 7. Private Chat (One-on-One)

**Only Supporters are available for private chats.** Sharers cannot be privately messaged.

- Initiated by tapping a Supporter's name in a room.
- Opens a plain-text chat window.
- No typing indicators.
- No read receipts.
- No "last seen."
- Chat history is **not stored** after the chat ends.
- Either party can end the chat at any time. No warning, no "are you sure?"

**Why Supporters only:** This creates a clear value proposition for the 50K/3-month fee. If you want to be *available for deep one-on-one conversations*, you pay to support the platform. It's not about gatekeeping—it's about sustainability.

---

## 8. Post-Chat Experience – The Final Emoji

When **either person ends** a private chat, both parties see a **shared emoji selection screen**.

**How it works:**
1. Person A ends the chat.
2. Both A and B see the same screen: *"One last emoji to sum it up?"*
3. Each selects an emoji privately.
4. Once both have selected, the two emojis are revealed to both, displayed side by side beautifully.

**Emoji options:**
> `😊` · `💡` · `😂` · `🙏` · `🤝` · `❤️` · `✨` · `🔥`

**Reveal screen:**
```
  You sent:  🙏
  They sent: 😊
  
  [Close]
```

**No follow-up nudges.** No "would you like to chat again?" The emojis are the closing note. If both want to talk again, they'll find each other in a room.

**Emoji stats:** Each emoji received is added to the user's private tally. Visible only to them.

---

## 9. Message Threads (The TikTok-Style Comment Sheet)

> *"Each message has its own comment thread (like TikTok videos, a bottom sheet with extra chit-chats or followups)."*

**How it works in public chat:**

1. Any message in a room can be tapped.
2. A bottom sheet slides up, showing a threaded conversation attached to that message.
3. Anyone in the room (Sharer/Supporter) can reply to the thread.
4. Threads are nested (replies to replies), but displayed linearly in the bottom sheet.
5. Threads are ephemeral – they disappear when the room empties or after 24 hours of inactivity.

**Why:** This allows conversations to branch without cluttering the main chat feed. It also encourages *depth* – you can have a side conversation about one specific point without derailing the room.

**Visual:**
```
Main room feed:
  [Minh] Anyone know a good pho place in D1?
    ↳ 4 replies (tap to open)
  [Linh] I'm thinking about switching careers...

Tapping the first message opens bottom sheet:
  ┌─────────────────────────────┐
  │ [Minh] Anyone know a good   │
  │ pho place in D1?            │
  │                             │
  │ [Alex] Pho Hung at 241 Nguyễn│
  │ Trãi is solid              │
  │   [Linh] +1 to that        │
  │   [Minh] thanks!           │
  │ [Sam] Try Pho Hoa on Pasteur│
  │                             │
  │ [Type your reply...]        │
  └─────────────────────────────┘
```

---

## 10. Moderation & Safety

| Rule | Implementation |
|------|----------------|
| **No harassment** | Report button on every profile and chat. Two reports = permanent ban. No appeals. |
| **No unsolicited sales** | If a user mentions their services without being asked, founder issues a warning. Second warning = ban. |
| **No bots** | Founder personally verifies each Sharer/Supporter. No automated sign-up. |
| **No screenshots** | Technical prevention: disable screenshots on mobile (if using KMP for native apps later). |

---

## 11. Economics

| Tier | Price | Capabilities |
|------|-------|--------------|
| **Seeker** | 0 VND | Browse rooms, view public chat, read threads. Cannot send messages. |
| **Sharer** | 100,000 VND (one-time) | Full room access. Public chat. Thread replies. Cannot be privately messaged. |
| **Supporter** | 50,000 VND / 3 months | All Sharer features + Available for 1-on-1 private chats. |

**Revenue model:**
- One-time join fee covers basic server costs.
- Supporter subscriptions provide ongoing revenue for maintenance and growth.
- No cut of any transactions between users. That's between them.

---

## 12. Technical Requirements

| Component | Technology |
|-----------|------------|
| **Platform** | Web-first. Compose Web for UI. |
| **Mobile (future)** | Kotlin Multiplatform (KMP) for iOS/Android if needed. |
| **Backend** | Ktor (Kotlin). |
| **Real-time** | WebSockets for live chat. |
| **Database** | ArangoDB (multi-model: documents + graphs). |
| **Auth** | Founder-managed. No OTP. No open sign-up. |
| **Identity generation** | Qwen AI API for names and avatars (per room, per user). |
| **Push notifications** | None. Web-only. |

**Data model notes:**
- Users (Sharer/Supporter profiles)
- Rooms (name + emoji + creator)
- Messages (room chat + thread relationships)
- Threads (parent message ID, nested replies)
- Emojis (private tally per user, not public)
- No chat history stored (private chats are ephemeral)

---

## 13. The Flow – A User Journey

**Day 1 – Seeker:**
- Opens Saigon.Social.
- Sees room list. Enters `☕ Coffee Chat`.
- Watches public chat. Reads threads.
- Likes the vibe. Decides to join.

**Day 2 – Sharer:**
- Pays 100K VND. Founder verifies.
- Enters `☕ Coffee Chat` for the first time. Qwen AI generates: *"Minh"* + abstract avatar.
- Sends a message. Gets replies.
- Also enters `🧠 Deep Talks`. Gets a different name: *"Linh"* + different avatar.
- Decides to become a Supporter.

**Day 3 – Supporter:**
- Pays 50K VND / 3 months.
- Now appears with a small indicator: *"Available for chat"* in any room.
- Someone taps their name. Private chat opens.
- Conversation flows. Ends naturally.
- Both share final emojis: `🙏` and `😊`.

**Day 7 – Identity refresh:**
- In `☕ Coffee Chat`, clicks *"Refresh my name & avatar"*.
- Becomes *"Alex"* with a new avatar. Fresh start.

---

## 14. Success Metrics (Internal Only)

| Metric | Why |
|--------|-----|
| Seeker → Sharer conversion | Is the value clear? |
| Sharer → Supporter conversion | Is 1-on-1 chat valued? |
| Daily Active Users (DAU) | Is the room alive? |
| Average chat duration | Are conversations meaningful? |
| Emoji selection rate | Are people leaving a positive trace? |
| Report rate | Is the community healthy? (Should be <1%) |

---

## Final Notes

This is lean. This is human. This is *Saigon*.

You're not building a unicorn. You're building a *park bench* for the city.

**Your stack is solid:**
- Compose Web + KMP = write once, deploy everywhere
- Ktor = Kotlin-native backend
- ArangoDB = flexible enough for messages + rooms + threads
- Qwen AI = identity generation on demand

**Ready to build. Let me know when you want:**
- API spec (endpoints + WebSocket events)
- Database schema (ArangoDB collections + indices)
- UI mockup prompts (for yourself or a designer)

Otherwise, go get 'em. Saigon's waiting.
