@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.homeassistant.AmbianceMoment
import com.nuvio.tv.core.homeassistant.AmbianceStatus
import com.nuvio.tv.core.homeassistant.AmbianceTimeMode
import com.nuvio.tv.core.homeassistant.HaApiFailure
import com.nuvio.tv.core.homeassistant.HaLight
import com.nuvio.tv.core.homeassistant.LightAction
import com.nuvio.tv.core.homeassistant.LightActionType
import com.nuvio.tv.core.homeassistant.LightCapabilities
import com.nuvio.tv.core.homeassistant.LightColor
import com.nuvio.tv.core.homeassistant.LightSetup
import com.nuvio.tv.core.homeassistant.NowPlayingActivity
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** Sub-pages of Settings › Integrations › Home Assistant. */
internal sealed interface HaPage {
    data object Main : HaPage
    data object Lights : HaPage
    data object Ambiance : HaPage
    data class Light(val entityId: String) : HaPage
}

/** A page of the Home Assistant settings: header, then a scrolling card that takes the first focus. */
@Composable
private fun HaPageScaffold(
    title: String,
    subtitle: String,
    content: androidx.compose.foundation.lazy.LazyListScope.(FocusRequester) -> Unit
) {
    val firstFocus = remember { FocusRequester() }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsDetailHeader(title = title, subtitle = subtitle)
        SettingsGroupCard(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val listState = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) { content(firstFocus) }
                SettingsVerticalScrollIndicators(state = listState)
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
}

// ---------------------------------------------------------------- Lights list

@Composable
internal fun HaLightsPage(viewModel: HomeAssistantSettingsViewModel) {
    val state by viewModel.lights.collectAsStateWithLifecycle()
    val ambiance by viewModel.ambiance.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadLights() }
    val noRoom = stringResource(R.string.ha_lights_no_room)

    HaPageScaffold(
        title = stringResource(R.string.ha_lights_title),
        subtitle = stringResource(R.string.ha_lights_subtitle)
    ) { firstFocus ->
        when (val s = state) {
            HaLightsState.Loading -> item(key = "loading") {
                SettingsActionRow(
                    title = stringResource(R.string.ha_lights_loading),
                    subtitle = null,
                    onClick = {},
                    modifier = Modifier.focusRequester(firstFocus)
                )
            }
            is HaLightsState.Failed -> item(key = "failed") {
                SettingsActionRow(
                    title = apiFailureText(s.reason),
                    subtitle = stringResource(R.string.ha_lights_retry),
                    onClick = { viewModel.loadLights(force = true) },
                    modifier = Modifier.focusRequester(firstFocus)
                )
            }
            is HaLightsState.Loaded -> {
                if (s.lights.isEmpty()) item(key = "none") {
                    SettingsActionRow(
                        title = stringResource(R.string.ha_lights_none),
                        subtitle = null,
                        onClick = {},
                        modifier = Modifier.focusRequester(firstFocus)
                    )
                }
                var first = true
                s.lights.groupBy { it.room }.forEach { (room, lights) ->
                    item(key = "room_${room ?: "-"}") { SettingsSectionLabel(text = room ?: noRoom) }
                    lights.forEach { light ->
                        val focus = first
                        first = false
                        item(key = light.entityId) {
                            SettingsToggleRow(
                                title = light.name,
                                subtitle = lightSubtitle(light),
                                checked = ambiance.lights.any { it.entityId == light.entityId },
                                onToggle = { viewModel.toggleLight(light) },
                                modifier = if (focus) Modifier.focusRequester(firstFocus) else Modifier
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun lightSubtitle(light: HaLight): String {
    val parts = mutableListOf(capabilitiesText(light.capabilities))
    if (light.members.isNotEmpty()) parts += stringResource(R.string.ha_light_group, light.members.size)
    if (!light.available) parts += stringResource(R.string.ha_light_unavailable)
    return parts.joinToString(" · ")
}

@Composable
private fun capabilitiesText(caps: LightCapabilities): String = when {
    caps.colors -> stringResource(R.string.ha_cap_colors)
    caps.whites -> stringResource(R.string.ha_cap_whites)
    caps.brightness -> stringResource(R.string.ha_cap_brightness)
    else -> stringResource(R.string.ha_cap_onoff)
}

@Composable
internal fun apiFailureText(reason: HaApiFailure): String = when (reason) {
    HaApiFailure.NOT_CONFIGURED -> stringResource(R.string.ha_api_failure_not_configured)
    HaApiFailure.UNREACHABLE -> stringResource(R.string.ha_api_failure_unreachable)
    HaApiFailure.BAD_CREDENTIALS -> stringResource(R.string.ha_api_failure_bad_credentials)
    HaApiFailure.TWO_FACTOR -> stringResource(R.string.ha_api_failure_two_factor)
    HaApiFailure.OTHER -> stringResource(R.string.ha_api_failure_other)
}

// ---------------------------------------------------------------- Ambiance

private enum class AmbianceDialog { DAYS, TIME_MODE, START, END }

@Composable
internal fun HaAmbiancePage(
    viewModel: HomeAssistantSettingsViewModel,
    onOpenLights: () -> Unit,
    onOpenLight: (String) -> Unit
) {
    val ambiance by viewModel.ambiance.collectAsStateWithLifecycle()
    val status by viewModel.ambianceStatus.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<AmbianceDialog?>(null) }
    val kinds = listOf(
        NowPlayingActivity.MOVIE to stringResource(R.string.ha_kind_movie),
        NowPlayingActivity.EPISODE to stringResource(R.string.ha_kind_episode),
        NowPlayingActivity.LIVE to stringResource(R.string.ha_kind_live),
        NowPlayingActivity.CATCHUP to stringResource(R.string.ha_kind_catchup)
    )
    val summaries = ambiance.lights.associate { it.entityId to lightSummary(it) }
    val daysText = daysSummary(ambiance.days)
    val timeModeText = timeModeText(ambiance.timeMode)

    HaPageScaffold(
        title = stringResource(R.string.ha_amb_title),
        subtitle = stringResource(R.string.ha_amb_subtitle)
    ) { firstFocus ->
        item(key = "enabled") {
            SettingsToggleRow(
                title = stringResource(R.string.ha_amb_enable_title),
                subtitle = stringResource(R.string.ha_amb_enable_subtitle),
                checked = ambiance.enabled,
                onToggle = { viewModel.setAmbianceEnabled(!ambiance.enabled) },
                modifier = Modifier.padding(top = NuvioTheme.spacing.xxs).focusRequester(firstFocus)
            )
        }
        item(key = "status") {
            SettingsActionRow(
                title = stringResource(R.string.ha_amb_status_title),
                subtitle = null,
                value = ambianceStatusText(status),
                valueColor = if (status is AmbianceStatus.Failed) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.Primary,
                onClick = {}
            )
        }

        item(key = "when") { SettingsSectionLabel(text = stringResource(R.string.ha_amb_when)) }
        kinds.forEach { (kind, label) ->
            item(key = "kind_${kind.haValue}") {
                SettingsToggleRow(
                    title = label,
                    subtitle = null,
                    checked = kind.haValue in ambiance.kinds,
                    onToggle = { viewModel.toggleKind(kind.haValue) }
                )
            }
        }
        item(key = "days") {
            SettingsActionRow(
                title = stringResource(R.string.ha_amb_days_title),
                subtitle = null,
                value = daysText,
                onClick = { dialog = AmbianceDialog.DAYS }
            )
        }
        item(key = "time_mode") {
            SettingsActionRow(
                title = stringResource(R.string.ha_amb_time_title),
                subtitle = stringResource(R.string.ha_amb_time_subtitle),
                value = timeModeText,
                onClick = { dialog = AmbianceDialog.TIME_MODE }
            )
        }
        if (ambiance.timeMode == AmbianceTimeMode.RANGE) {
            item(key = "start") {
                SettingsActionRow(
                    title = stringResource(R.string.ha_amb_start_title),
                    subtitle = null,
                    value = minuteText(ambiance.startMinute),
                    onClick = { dialog = AmbianceDialog.START }
                )
            }
            item(key = "end") {
                SettingsActionRow(
                    title = stringResource(R.string.ha_amb_end_title),
                    subtitle = null,
                    value = minuteText(ambiance.endMinute),
                    onClick = { dialog = AmbianceDialog.END }
                )
            }
        }

        item(key = "lights_label") { SettingsSectionLabel(text = stringResource(R.string.ha_amb_lights_section)) }
        item(key = "choose") {
            SettingsActionRow(
                title = stringResource(R.string.ha_amb_choose_lights),
                subtitle = if (ambiance.lights.isEmpty()) stringResource(R.string.ha_amb_no_lights) else null,
                value = stringResource(R.string.ha_lights_value, ambiance.lights.size),
                onClick = onOpenLights
            )
        }
        ambiance.lights.forEach { light ->
            item(key = "light_${light.entityId}") {
                SettingsActionRow(
                    title = light.name,
                    subtitle = summaries[light.entityId],
                    onClick = { onOpenLight(light.entityId) }
                )
            }
        }

        if (ambiance.lights.isNotEmpty()) {
            item(key = "try_label") {
                SettingsSectionLabel(
                    text = stringResource(R.string.ha_amb_try_section),
                    description = stringResource(R.string.ha_amb_try_subtitle)
                )
            }
            item(key = "try_start") {
                SettingsActionRow(
                    title = stringResource(R.string.ha_amb_try_start),
                    subtitle = null,
                    onClick = { viewModel.preview(AmbianceMoment.START) }
                )
            }
            item(key = "try_pause") {
                SettingsActionRow(
                    title = stringResource(R.string.ha_amb_try_pause),
                    subtitle = null,
                    onClick = { viewModel.preview(AmbianceMoment.PAUSE) }
                )
            }
        }
    }

    when (dialog) {
        null -> Unit
        AmbianceDialog.DAYS -> HaChipsDialog(
            title = stringResource(R.string.ha_amb_days_title),
            options = (1..7).map { it to DayOfWeek.of(it).getDisplayName(TextStyle.FULL, Locale.getDefault()).replaceFirstChar { c -> c.titlecase() } },
            isSelected = { it in ambiance.days },
            multi = true,
            onPick = { viewModel.toggleDay(it) },
            onDismiss = { dialog = null }
        )
        AmbianceDialog.TIME_MODE -> HaChipsDialog(
            title = stringResource(R.string.ha_amb_time_title),
            options = listOf(
                AmbianceTimeMode.ALWAYS to stringResource(R.string.ha_time_always),
                AmbianceTimeMode.NIGHT to stringResource(R.string.ha_time_night),
                AmbianceTimeMode.RANGE to stringResource(R.string.ha_time_range)
            ),
            isSelected = { it == ambiance.timeMode },
            onPick = { viewModel.setTimeMode(it) },
            onDismiss = { dialog = null }
        )
        AmbianceDialog.START, AmbianceDialog.END -> {
            val isStart = dialog == AmbianceDialog.START
            val current = if (isStart) ambiance.startMinute else ambiance.endMinute
            HaChipsDialog(
                title = stringResource(if (isStart) R.string.ha_amb_start_title else R.string.ha_amb_end_title),
                options = (0 until 48).map { it * 30 to minuteText(it * 30) },
                isSelected = { it == current },
                onPick = { if (isStart) viewModel.setStartMinute(it) else viewModel.setEndMinute(it) },
                onDismiss = { dialog = null },
                width = 900
            )
        }
    }
}

@Composable
private fun ambianceStatusText(status: AmbianceStatus): String = when (status) {
    AmbianceStatus.Idle -> stringResource(R.string.ha_amb_status_idle)
    AmbianceStatus.Previewing -> stringResource(R.string.ha_amb_status_preview)
    is AmbianceStatus.Active -> stringResource(
        if (status.moment == AmbianceMoment.PAUSE) R.string.ha_amb_status_pause else R.string.ha_amb_status_start
    )
    is AmbianceStatus.Failed -> apiFailureText(status.reason)
}

@Composable
private fun daysSummary(days: Set<Int>): String = when (days) {
    (1..7).toSet() -> stringResource(R.string.ha_amb_days_all)
    (1..5).toSet() -> stringResource(R.string.ha_amb_days_weekdays)
    setOf(6, 7) -> stringResource(R.string.ha_amb_days_weekend)
    emptySet<Int>() -> stringResource(R.string.ha_amb_days_none)
    else -> days.sorted().joinToString(", ") { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
}

@Composable
private fun timeModeText(mode: AmbianceTimeMode): String = when (mode) {
    AmbianceTimeMode.ALWAYS -> stringResource(R.string.ha_time_always)
    AmbianceTimeMode.NIGHT -> stringResource(R.string.ha_time_night)
    AmbianceTimeMode.RANGE -> stringResource(R.string.ha_time_range)
}

private fun minuteText(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

@Composable
private fun lightSummary(light: LightSetup): String = AmbianceMoment.entries.map { moment ->
    stringResource(R.string.ha_summary_item, momentText(moment), actionText(light.action(moment)))
}.joinToString(" · ")

@Composable
private fun momentText(moment: AmbianceMoment): String = stringResource(
    when (moment) {
        AmbianceMoment.START -> R.string.ha_moment_start
        AmbianceMoment.PAUSE -> R.string.ha_moment_pause
        AmbianceMoment.END -> R.string.ha_moment_end
    }
)

@Composable
private fun actionTypeText(type: LightActionType): String = stringResource(
    when (type) {
        LightActionType.NOTHING -> R.string.ha_action_nothing
        LightActionType.OFF -> R.string.ha_action_off
        LightActionType.ON -> R.string.ha_action_on
        LightActionType.RESTORE -> R.string.ha_action_restore
    }
)

/** "éteinte", "allumée, 40 %, blanc chaud", "comme avant"… */
@Composable
private fun actionText(action: LightAction): String {
    if (action.type != LightActionType.ON) return actionTypeText(action.type)
    val parts = mutableListOf(actionTypeText(action.type), "${action.brightness} %")
    LightColor.of(action.color)?.let { parts += colorText(it) }
    return parts.joinToString(", ")
}

@Composable
private fun colorText(color: LightColor): String = stringResource(
    when (color) {
        LightColor.VERY_WARM -> R.string.ha_color_very_warm
        LightColor.WARM -> R.string.ha_color_warm
        LightColor.NEUTRAL -> R.string.ha_color_neutral
        LightColor.COLD -> R.string.ha_color_cold
        LightColor.AMBER -> R.string.ha_color_amber
        LightColor.ORANGE -> R.string.ha_color_orange
        LightColor.RED -> R.string.ha_color_red
        LightColor.PINK -> R.string.ha_color_pink
        LightColor.PURPLE -> R.string.ha_color_purple
        LightColor.BLUE -> R.string.ha_color_blue
        LightColor.CYAN -> R.string.ha_color_cyan
        LightColor.GREEN -> R.string.ha_color_green
    }
)

// ---------------------------------------------------------------- One light

private sealed interface LightDialog {
    val moment: AmbianceMoment
    data class Action(override val moment: AmbianceMoment) : LightDialog
    data class Brightness(override val moment: AmbianceMoment) : LightDialog
    data class Color(override val moment: AmbianceMoment) : LightDialog
}

@Composable
internal fun HaLightPage(viewModel: HomeAssistantSettingsViewModel, entityId: String) {
    val ambiance by viewModel.ambiance.collectAsStateWithLifecycle()
    val lightsState by viewModel.lights.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadLights() }
    val setup = ambiance.lights.firstOrNull { it.entityId == entityId } ?: return
    // Until the list is loaded, offer everything; HA ignores what a light can't do.
    val caps = (lightsState as? HaLightsState.Loaded)?.lights?.firstOrNull { it.entityId == entityId }?.capabilities
        ?: LightCapabilities(setOf("color_temp", "hs"))
    var dialog by remember { mutableStateOf<LightDialog?>(null) }
    val colors = LightColor.available(caps)

    HaPageScaffold(
        title = setup.name,
        subtitle = stringResource(R.string.ha_light_page_subtitle)
    ) { firstFocus ->
        AmbianceMoment.entries.forEachIndexed { index, moment ->
            val action = setup.action(moment)
            item(key = "label_$moment") { SettingsSectionLabel(text = momentText(moment)) }
            item(key = "action_$moment") {
                SettingsActionRow(
                    title = stringResource(R.string.ha_action_title),
                    subtitle = null,
                    value = actionTypeText(action.type),
                    onClick = { dialog = LightDialog.Action(moment) },
                    modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier
                )
            }
            if (action.type == LightActionType.ON && caps.brightness) {
                item(key = "brightness_$moment") {
                    SettingsActionRow(
                        title = stringResource(R.string.ha_brightness_title),
                        subtitle = null,
                        value = "${action.brightness} %",
                        onClick = { dialog = LightDialog.Brightness(moment) }
                    )
                }
            }
            if (action.type == LightActionType.ON && colors.isNotEmpty()) {
                item(key = "color_$moment") {
                    SettingsActionRow(
                        title = stringResource(R.string.ha_color_title),
                        subtitle = null,
                        value = LightColor.of(action.color)?.let { colorText(it) } ?: stringResource(R.string.ha_color_keep),
                        onClick = { dialog = LightDialog.Color(moment) }
                    )
                }
            }
        }
    }

    val open = dialog ?: return
    val action = setup.action(open.moment)
    val update: (LightAction) -> Unit = { viewModel.setLightAction(entityId, open.moment, it) }
    when (open) {
        is LightDialog.Action -> {
            val types = when (open.moment) {
                AmbianceMoment.START -> listOf(LightActionType.NOTHING, LightActionType.OFF, LightActionType.ON)
                AmbianceMoment.PAUSE -> LightActionType.entries.toList()
                AmbianceMoment.END -> listOf(LightActionType.NOTHING, LightActionType.OFF, LightActionType.RESTORE)
            }
            HaChipsDialog(
                title = momentText(open.moment),
                options = types.map { it to actionTypeText(it) },
                isSelected = { it == action.type },
                onPick = { update(action.copy(type = it)) },
                onDismiss = { dialog = null }
            )
        }
        is LightDialog.Brightness -> HaChipsDialog(
            title = stringResource(R.string.ha_brightness_title),
            options = (1..10).map { it * 10 to "${it * 10} %" },
            isSelected = { it == action.brightness },
            onPick = { update(action.copy(brightness = it)) },
            onDismiss = { dialog = null }
        )
        is LightDialog.Color -> HaChipsDialog(
            title = stringResource(R.string.ha_color_title),
            options = listOf<Pair<String?, String>>(null to stringResource(R.string.ha_color_keep)) +
                colors.map { it.key to colorText(it) },
            isSelected = { it == action.color },
            onPick = { update(action.copy(color = it)) },
            onDismiss = { dialog = null },
            width = 820
        )
    }
}

// ---------------------------------------------------------------- Chips dialog

/** A choice as chips: picking one closes the dialog, or toggles it when [multi]. */
@Composable
private fun <T> HaChipsDialog(
    title: String,
    options: List<Pair<T, String>>,
    isSelected: (T) -> Boolean,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
    multi: Boolean = false,
    width: Int = 700
) {
    val firstFocus = remember { FocusRequester() }
    val selectedIndex = options.indexOfFirst { isSelected(it.first) }.coerceAtLeast(0)
    NuvioDialog(onDismiss = onDismiss, title = title, width = width.dp) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            options.forEachIndexed { index, (value, label) ->
                SettingsChoiceChip(
                    label = label,
                    selected = isSelected(value),
                    multiSelect = multi,
                    onClick = {
                        onPick(value)
                        if (!multi) onDismiss()
                    },
                    modifier = if (index == selectedIndex) Modifier.focusRequester(firstFocus) else Modifier
                )
            }
        }
        if (multi) {
            SettingsDialogActionRow {
                SettingsDialogActionButton(text = stringResource(R.string.action_close), onClick = onDismiss, primary = true)
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
}
