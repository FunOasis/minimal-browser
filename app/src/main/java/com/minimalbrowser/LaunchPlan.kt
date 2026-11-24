package com.minimalbrowser

/**
 * What MainActivity.onCreate should do with the tab list, given:
 *   - a possible in-memory saved state (config change / same-process
 *     recreate),
 *   - a possible on-disk session snapshot (cold start after process
 *     death, reboot, or swipe-from-recents),
 *   - a possible launcher-shortcut URL,
 *   - a possible external ACTION_VIEW URL.
 *
 * This was previously inline in onCreate() as a sequence of `when`
 * expressions over five local variables, which made the decision hard
 * to exercise without standing up a whole Activity. Extracted here as
 * a pure function of plain Kotlin values (no Android types), it can be
 * unit tested directly -- see LaunchPlanTest.kt -- for every
 * restore/shortcut/external-URL combination MainActivity can see on
 * startup, closing a gap this project previously had no coverage for
 * at all ("Intent handling" was one of the explicitly untested areas).
 */
internal sealed class LaunchPlan {

    /** A saved or on-disk session exists: restore it. */
    data class Restore(
        val urls: List<String>,
        val activeIndex: Int,
        val jsFlags: List<Boolean>?,
        val postAction: PostRestoreAction
    ) : LaunchPlan()

    /** No session to restore: open exactly one tab. */
    data class CreateSingle(val url: String?) : LaunchPlan()
}

/** What to do to the restored tab set, once restore() has run. */
internal sealed class PostRestoreAction {
    object None : PostRestoreAction()
    data class LoadShortcut(val url: String) : PostRestoreAction()
    data class OpenExternalTab(val url: String) : PostRestoreAction()
}

/**
 * Decide what MainActivity.onCreate should do.
 *
 * Precedence (unchanged from the inline version this replaces):
 *   1. A non-empty `savedUrls` (Activity-recreation Bundle) always
 *      wins over the on-disk snapshot -- it is strictly fresher.
 *   2. Otherwise the on-disk snapshot (`diskUrls`), if any.
 *   3. If neither exists, a shortcut URL opens as the sole tab;
 *      otherwise an external URL does; otherwise a bare home tab.
 *
 * When a session IS being restored, a shortcut URL loads into the
 * restored active tab (replacing its content) while an external URL
 * opens as an additional new tab -- so an externally shared link never
 * clobbers the session the user had open, matching how Chrome handles
 * a link tapped while it already has tabs open.
 */
internal fun resolveLaunchPlan(
    savedUrls: List<String>?,
    savedActive: Int,
    savedJs: List<Boolean>?,
    diskUrls: List<String>?,
    diskActive: Int,
    shortcutUrl: String?,
    externalUrl: String?
): LaunchPlan {
    val restoreUrls: List<String>? = when {
        !savedUrls.isNullOrEmpty() -> savedUrls
        diskUrls != null -> diskUrls
        else -> null
    }
    val restoreActiveIndex: Int = when {
        !savedUrls.isNullOrEmpty() -> savedActive
        diskUrls != null -> diskActive
        else -> 0
    }

    if (restoreUrls != null) {
        val post = when {
            !shortcutUrl.isNullOrBlank() -> PostRestoreAction.LoadShortcut(shortcutUrl)
            externalUrl != null -> PostRestoreAction.OpenExternalTab(externalUrl)
            else -> PostRestoreAction.None
        }
        return LaunchPlan.Restore(restoreUrls, restoreActiveIndex, savedJs, post)
    }

    val singleUrl = when {
        !shortcutUrl.isNullOrBlank() -> shortcutUrl
        externalUrl != null -> externalUrl
        else -> null
    }
    return LaunchPlan.CreateSingle(singleUrl)
}
