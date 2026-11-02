package com.nuvio.app.core.i18n

import org.jetbrains.compose.resources.StringResource

/**
 * The string a platform shows in place of [resource], for text that only describes another platform.
 * What has no wording of its own comes back as it is, so Windows and macOS see no change.
 */
internal expect fun platformString(resource: StringResource): StringResource
