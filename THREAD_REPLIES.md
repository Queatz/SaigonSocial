# Thread Replies Documentation

This document describes the architecture, data models, backend API, WebSocket notification system, and frontend user interface for the **Thread Replies** feature in Saigon Social.

---

## 1. Feature Overview

The "Thread replies" feature provides users with a central inbox on the home screen to track discussions they are involved in. Similar to "Direct chats", it shows up as a card underneath the "Direct chats" button on the home screen if there is any active thread that:
1. The user started (i.e., authored the parent message).
2. The user has participated in (i.e., replied to).

To keep the workspace focused and tidy, users can **"Dismiss"** a thread reply. Dismissing a thread hides it from their active list **until another reply is posted to that thread**, at which point it resurfaces automatically.

---

## 2. Architecture & Data Models

The feature uses a hybrid state model combining a REST API for state restoration on login/reload and real-time WebSocket notifications for instant updates.

### A. Core Models

#### `ThreadSummary` (Domain & Client Mirror)
Represents a summary of an active thread returned to the client:
```kotlin
@Serializable
data class ThreadSummary(
    val roomId: String,
    val roomName: String,
    val roomEmoji: String,
    val parentMessageId: String,
    val parentMessageText: String,
    val parentMessageAuthorName: String,
    val replyCount: Int,
    val lastReplyText: String? = null,
    val lastReplyAuthorName: String? = null,
    val lastReplyAt: Long? = null,
)
```

#### `Outgoing.MessageReplyReceived` (WebSocket Payload)
Pushed through the always-on notifications socket (`/ws`) to alert participants of a thread in real-time:
```kotlin
@Serializable
data class MessageReplyReceived(
    val roomId: String,
    val roomName: String,
    val roomEmoji: String,
    val parentMessageId: String,
    val parentMessageText: String,
    val parentMessageAuthorName: String,
    val replyId: String,
    val replyText: String,
    val replyAuthorName: String,
    val replyCreatedAt: Long,
    val replyCount: Int,
) : Outgoing
```

---

## 3. Backend Endpoints & Routing

### A. State Retrieval (`GET /profile/me/threads`)
* **Access:** Authenticated Members (`SHARER`, `SUPPORTER`, `FOUNDER`).
* **Behavior:** Scans all active threads. Selects those started or participate in by the caller. Replaces blanks with room-scoped names/avatars.
* **Filtering:** Explicitly filters out threads that the user has **dismissed**.
* **Ordering:** Sorted descending by `lastReplyAt`, bubbling the most recently updated threads to the top.

### B. Thread Dismissal (`POST /profile/me/threads/{parentMessageId}/dismiss`)
* **Access:** Authenticated Members.
* **Behavior:** Registers that the current user has dismissed the specified thread. It adds the `(userId, parentMessageId)` to a thread-safe repository lookup. This removes the thread from the user's active threads list.

### C. Reset Dismissal on New Activity
When any member replies to a thread (processed in the WebSocket handler for `Incoming.Reply`):
1. The new reply is saved in `ThreadRepository`.
2. The repository automatically **invalidates/clears any existing dismissals** for that `parentMessageId`.
3. An `Outgoing.MessageReplyReceived` notification is dispatched to all participants (starter and previous repliers) except the replier themselves, ensuring the thread instantly resurfaces on their home screens.

---

## 4. Ephemerality & Persistence Policies

* **Thread Lifecycle:** Threads are ephemeral. They are cleared completely when a room empties or after 24 hours of inactivity (via `ThreadService.sweepInactive()`).
* **Dismissal Lifecycle:** Thread dismissals are tied to the lifetime of the thread.
  - When a thread is swept/cleared via `ThreadRepository.clear()`, its associated dismissals are purged.
  - When a new reply is posted, the dismissals for that thread are cleared.
  - Dismissals are stored durably in the ArangoDB `ThreadDismissals` collection to survive server restarts (in production), while in-memory repositories use a thread-safe registry.

---

## 5. Client-Side (UI) Experience

### A. Room List Entry Point
On the room list (home screen), below "Direct chats", an entry card is displayed if `threadReplyCount > 0`.
- It shows the label **"Thread replies"** with an indicator of active threads (e.g., `3 active`).

### B. Thread Replies Screen
A dedicated screen that lists all active thread summaries:
- **Card Content:** Displays room emoji and name, the original thread parent message, the author and content of the last reply, and a comment balloon count (e.g., `💬 4`).
- **Deep-linking (Tapping Card):** Tapping anywhere on the thread card uses the application deep-linking router to open the room with the specific `ThreadSheet` already open, taking the user directly to the conversation.
- **Dismiss Button:** Each card features a **"Dismiss"** text button. Tapping it calls the `dismissThread(parentMessageId)` API and immediately animates the thread out of the active list.
