package social.saigon.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileEditorHasChangesTest {

    private val baseline = ProfileResponse(
        id = "u1",
        role = Role.SHARER,
        displayName = "Alex",
        interests = listOf("food", "music"),
        introduction = "Hello",
        skills = listOf(Skill(emoji = "🎸", name = "Guitar", level = 3)),
        intents = listOf("Friends", "Learning"),
        languages = listOf("en", "vi"),
        offlineSchedule = listOf("weekday evenings"),
    )

    @Test
    fun unchangedFormIsClean() {
        assertFalse(
            profileEditorHasChanges(
                baseline = baseline,
                interests = baseline.interests,
                introduction = baseline.introduction,
                skills = baseline.skills,
                intents = baseline.intents,
                languages = baseline.languages,
                offlineSchedule = baseline.offlineSchedule,
            )
        )
    }

    @Test
    fun reorderedIntentsAndLanguagesAreClean() {
        assertFalse(
            profileEditorHasChanges(
                baseline = baseline,
                interests = baseline.interests,
                introduction = baseline.introduction,
                skills = baseline.skills,
                intents = baseline.intents.reversed(),
                languages = baseline.languages.reversed(),
                offlineSchedule = baseline.offlineSchedule,
            )
        )
    }

    @Test
    fun introductionEditIsDirty() {
        assertTrue(
            profileEditorHasChanges(
                baseline = baseline,
                interests = baseline.interests,
                introduction = "Updated intro",
                skills = baseline.skills,
                intents = baseline.intents,
                languages = baseline.languages,
                offlineSchedule = baseline.offlineSchedule,
            )
        )
    }

    @Test
    fun skillEditIsDirty() {
        assertTrue(
            profileEditorHasChanges(
                baseline = baseline,
                interests = baseline.interests,
                introduction = baseline.introduction,
                skills = listOf(Skill(emoji = "🎸", name = "Guitar", level = 5)),
                intents = baseline.intents,
                languages = baseline.languages,
                offlineSchedule = baseline.offlineSchedule,
            )
        )
    }

    @Test
    fun interestEditIsDirty() {
        assertTrue(
            profileEditorHasChanges(
                baseline = baseline,
                interests = listOf("food"),
                introduction = baseline.introduction,
                skills = baseline.skills,
                intents = baseline.intents,
                languages = baseline.languages,
                offlineSchedule = baseline.offlineSchedule,
            )
        )
    }
}
