package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nuvio.app.features.screensaver.SCREENSAVER_DIM_DELAY_OPTIONS_MINUTES
import com.nuvio.app.features.screensaver.SCREENSAVER_PLAYBACK_DIM_DELAY_OPTIONS_MINUTES
import com.nuvio.app.features.screensaver.SCREENSAVER_PLAYBACK_SHUTDOWN_DELAY_OPTIONS_MINUTES
import com.nuvio.app.features.screensaver.SCREENSAVER_SHUTDOWN_DELAY_OPTIONS_MINUTES
import com.nuvio.app.features.screensaver.ScreensaverSettings
import com.nuvio.app.features.screensaver.ScreensaverSettingsRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_screensaver_dim_amount
import nuvio.composeapp.generated.resources.settings_screensaver_dim_amount_description
import nuvio.composeapp.generated.resources.settings_screensaver_duration_hours
import nuvio.composeapp.generated.resources.settings_screensaver_duration_hours_minutes
import nuvio.composeapp.generated.resources.settings_screensaver_duration_minutes
import nuvio.composeapp.generated.resources.settings_screensaver_duration_one_hour
import nuvio.composeapp.generated.resources.settings_screensaver_duration_one_minute_testing
import nuvio.composeapp.generated.resources.settings_screensaver_during_playback
import nuvio.composeapp.generated.resources.settings_screensaver_during_playback_description
import nuvio.composeapp.generated.resources.settings_screensaver_enable
import nuvio.composeapp.generated.resources.settings_screensaver_enable_description
import nuvio.composeapp.generated.resources.settings_screensaver_idle_time
import nuvio.composeapp.generated.resources.settings_screensaver_idle_time_description
import nuvio.composeapp.generated.resources.settings_screensaver_playback_idle_time
import nuvio.composeapp.generated.resources.settings_screensaver_playback_idle_time_description
import nuvio.composeapp.generated.resources.settings_screensaver_playback_shutdown_idle_time
import nuvio.composeapp.generated.resources.settings_screensaver_playback_shutdown_idle_time_description
import nuvio.composeapp.generated.resources.settings_screensaver_section_shutdown
import nuvio.composeapp.generated.resources.settings_screensaver_section_title
import nuvio.composeapp.generated.resources.settings_screensaver_shutdown_enable
import nuvio.composeapp.generated.resources.settings_screensaver_shutdown_enable_description
import nuvio.composeapp.generated.resources.settings_screensaver_shutdown_idle_time
import nuvio.composeapp.generated.resources.settings_screensaver_shutdown_idle_time_description
import org.jetbrains.compose.resources.stringResource

/**
 * Screensaver: dim after idling, optionally shut the PC down after idling longer. The settings
 * are shared; the timer and the shade themselves are desktop-only (`DesktopScreensaver`).
 */
internal fun LazyListScope.screensaverSettingsContent(
    isTablet: Boolean,
    settings: ScreensaverSettings,
) {
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_screensaver_section_title),
            isTablet = isTablet,
        ) {
            SettingsGroup(
                isTablet = isTablet,
                modifier = Modifier.settingsSearchAnchors(
                    "screensaver-enable",
                    "screensaver-dim-delay",
                    "screensaver-dim-amount",
                    "screensaver-during-playback",
                    "screensaver-playback-dim-delay",
                ),
            ) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_screensaver_enable),
                    description = stringResource(Res.string.settings_screensaver_enable_description),
                    checked = settings.enabled,
                    isTablet = isTablet,
                    onCheckedChange = ScreensaverSettingsRepository::setEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsDropdownChoiceRow(
                    title = stringResource(Res.string.settings_screensaver_idle_time),
                    description = stringResource(Res.string.settings_screensaver_idle_time_description),
                    options = screensaverDelayOptions(SCREENSAVER_DIM_DELAY_OPTIONS_MINUTES, settings.dimDelayMinutes),
                    selectedValue = settings.dimDelayMinutes,
                    enabled = settings.enabled,
                    isTablet = isTablet,
                    onSelected = ScreensaverSettingsRepository::setDimDelayMinutes,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSliderRow(
                    title = stringResource(Res.string.settings_screensaver_dim_amount),
                    description = stringResource(Res.string.settings_screensaver_dim_amount_description),
                    value = settings.dimPercent,
                    valueText = "${settings.dimPercent}%",
                    valueTextForValue = { "$it%" },
                    valueRange = 10..100,
                    step = 5,
                    enabled = settings.enabled,
                    isTablet = isTablet,
                    onValueChange = ScreensaverSettingsRepository::setDimPercent,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_screensaver_during_playback),
                    description = stringResource(Res.string.settings_screensaver_during_playback_description),
                    checked = settings.activeDuringPlayback,
                    isTablet = isTablet,
                    onCheckedChange = ScreensaverSettingsRepository::setActiveDuringPlayback,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsDropdownChoiceRow(
                    title = stringResource(Res.string.settings_screensaver_playback_idle_time),
                    description = stringResource(Res.string.settings_screensaver_playback_idle_time_description),
                    options = screensaverDelayOptions(SCREENSAVER_PLAYBACK_DIM_DELAY_OPTIONS_MINUTES, settings.playbackDimDelayMinutes),
                    selectedValue = settings.playbackDimDelayMinutes,
                    enabled = settings.enabled && settings.activeDuringPlayback,
                    isTablet = isTablet,
                    onSelected = ScreensaverSettingsRepository::setPlaybackDimDelayMinutes,
                )
            }
        }
    }

    if (platformOffersScreensaverShutdown) {
        item {
            SettingsSection(
                title = stringResource(Res.string.settings_screensaver_section_shutdown),
                isTablet = isTablet,
            ) {
                SettingsGroup(
                    isTablet = isTablet,
                    modifier = Modifier.settingsSearchAnchors(
                        "screensaver-shutdown-enable",
                        "screensaver-shutdown-delay",
                        "screensaver-playback-shutdown-delay",
                    ),
                ) {
                    SettingsSwitchRow(
                        title = stringResource(Res.string.settings_screensaver_shutdown_enable),
                        description = stringResource(Res.string.settings_screensaver_shutdown_enable_description),
                        checked = settings.shutdownEnabled,
                        isTablet = isTablet,
                        onCheckedChange = ScreensaverSettingsRepository::setShutdownEnabled,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsDropdownChoiceRow(
                        title = stringResource(Res.string.settings_screensaver_shutdown_idle_time),
                        description = stringResource(Res.string.settings_screensaver_shutdown_idle_time_description),
                        options = screensaverDelayOptions(SCREENSAVER_SHUTDOWN_DELAY_OPTIONS_MINUTES, settings.shutdownDelayMinutes),
                        selectedValue = settings.shutdownDelayMinutes,
                        enabled = settings.shutdownEnabled,
                        isTablet = isTablet,
                        onSelected = ScreensaverSettingsRepository::setShutdownDelayMinutes,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsDropdownChoiceRow(
                        title = stringResource(Res.string.settings_screensaver_playback_shutdown_idle_time),
                        description = stringResource(Res.string.settings_screensaver_playback_shutdown_idle_time_description),
                        options = screensaverDelayOptions(SCREENSAVER_PLAYBACK_SHUTDOWN_DELAY_OPTIONS_MINUTES, settings.playbackShutdownDelayMinutes),
                        selectedValue = settings.playbackShutdownDelayMinutes,
                        enabled = settings.shutdownEnabled && settings.activeDuringPlayback,
                        isTablet = isTablet,
                        onSelected = ScreensaverSettingsRepository::setPlaybackShutdownDelayMinutes,
                    )
                }
            }
        }
    }
}

/**
 * The offered delays plus whatever is currently stored, so a value from a list that has since
 * changed (the testing entry, once it goes) still shows its own label instead of a blank.
 */
@Composable
private fun screensaverDelayOptions(offered: List<Int>, selected: Int): List<SettingsChoiceOption<Int>> =
    (offered + selected).distinct().sorted().map { minutes ->
        SettingsChoiceOption(value = minutes, label = screensaverDurationLabel(minutes))
    }

@Composable
private fun screensaverDurationLabel(minutes: Int): String = when {
    minutes == 1 -> stringResource(Res.string.settings_screensaver_duration_one_minute_testing)
    minutes < 60 -> stringResource(Res.string.settings_screensaver_duration_minutes, minutes)
    minutes == 60 -> stringResource(Res.string.settings_screensaver_duration_one_hour)
    minutes % 60 == 0 -> stringResource(Res.string.settings_screensaver_duration_hours, minutes / 60)
    else -> stringResource(Res.string.settings_screensaver_duration_hours_minutes, minutes / 60, minutes % 60)
}
