package social.saigon.ui

/**
 * Reusable role-badge rendering (issue §7). A member's role is shown as a small emoji appended
 * after their name, everywhere a name is displayed:
 *  - Supporter → 🌟 (star)
 *  - Founder   → 👑 (crown)
 *  - everyone else → no badge
 *
 * Keep all badge logic here (a single source of truth) rather than scattering the emoji throughout
 * the screens, so the rule stays consistent across chat rooms, direct chats, threads, user lists,
 * search results, and the service screens.
 */

/** The badge emoji for [role], or an empty string when the role carries none. */
fun roleBadge(role: Role?): String = when (role) {
    Role.SUPPORTER -> "🌟"
    Role.FOUNDER -> "👑"
    else -> ""
}

/**
 * [name] with its role badge appended (e.g. "Minh 🌟"). When the role carries no badge, or the
 * name is blank, the name is returned unchanged.
 */
fun nameWithBadge(name: String, role: Role?): String {
    val badge = roleBadge(role)
    return if (badge.isEmpty() || name.isBlank()) name else "$name $badge"
}
