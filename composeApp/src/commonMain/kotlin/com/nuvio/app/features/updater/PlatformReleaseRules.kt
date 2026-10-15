package com.nuvio.app.features.updater

import androidx.compose.runtime.Composable

/**
 * Release rules for a build that is not distributed from the upstream repository.
 *
 * Null (see [platformReleaseRules]) keeps the upstream behaviour exactly: the UmbraProjects
 * repository, its branch guard, the stable/nightly channels and the portable ZIP asset. A platform
 * whose builds are published elsewhere supplies all of these instead, so neither set of rules has
 * to know about the other.
 */
internal interface PlatformReleaseRules {
    /** `owner/repo` whose GitHub releases this build updates from. */
    val repository: String

    /** Project page linked from the update dialog. */
    val projectUrl: String

    /** Whether the repository publishes a nightly channel the user may switch to. */
    val nightlyChannelAvailable: Boolean

    /** Whether [release] is a build the given channel distributes. */
    fun matchesChannel(release: GitHubReleaseDto, channel: UpdateChannel): Boolean

    /** The one asset this install can update from; may throw with a platform-specific reason. */
    fun selectUpdateAsset(release: GitHubReleaseDto): GitHubAssetDto?
}

internal expect val platformReleaseRules: PlatformReleaseRules?

/** Whether the update-channel picker offers nightlies on this build. */
internal val nightlyChannelSelectable: Boolean
    get() = platformReleaseRules?.nightlyChannelAvailable ?: true

internal fun GitHubReleaseDto.matchesPlatformChannel(channel: UpdateChannel): Boolean =
    platformReleaseRules?.matchesChannel(this, channel) ?: matchesChannel(channel)

internal fun selectPlatformUpdateAsset(release: GitHubReleaseDto): GitHubAssetDto? {
    val rules = platformReleaseRules ?: return selectBestPortableUpdateAsset(release.assets)
    return rules.selectUpdateAsset(release)
}

/**
 * Platform wording and action for the update dialog. Every null field keeps the shared default, so
 * a platform that returns no prompt at all renders the upstream dialog unchanged.
 */
internal data class PlatformUpdatePrompt(
    val message: String? = null,
    /** Shown selectable under the message, e.g. a terminal command. */
    val detail: String? = null,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null,
)

/**
 * The platform's prompt for [update] (null when nothing is on offer), given the path the last
 * download produced and whether one is still running.
 */
@Composable
internal expect fun platformUpdatePrompt(
    update: AppUpdate?,
    downloadedPath: String?,
    isDownloading: Boolean,
): PlatformUpdatePrompt?
