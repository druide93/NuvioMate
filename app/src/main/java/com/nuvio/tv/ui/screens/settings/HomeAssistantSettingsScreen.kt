@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.homeassistant.HaConnectionStatus
import com.nuvio.tv.core.homeassistant.HaFailure
import com.nuvio.tv.core.homeassistant.HaPhoneSetupServer
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme

private enum class HaField { BROKER, USERNAME, PASSWORD, DEVICE_NAME, API_URL, TOKEN }

/** Settings › Integrations › Home Assistant (NuvioMate). */
@Composable
fun HomeAssistantSettingsContent(
    viewModel: HomeAssistantSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    // A small back stack: Lights can be opened from the main page or from the ambiance.
    var stack by remember { mutableStateOf(listOf<HaPage>(HaPage.Main)) }
    BackHandler(enabled = stack.size > 1) { stack = stack.dropLast(1) }
    val open: (HaPage) -> Unit = { stack = stack + it }
    when (val current = stack.last()) {
        HaPage.Main -> HomeAssistantMainPage(
            viewModel = viewModel,
            initialFocusRequester = initialFocusRequester,
            onOpen = open
        )
        HaPage.Lights -> HaLightsPage(viewModel)
        HaPage.Ambiance -> HaAmbiancePage(
            viewModel = viewModel,
            onOpenLights = { open(HaPage.Lights) },
            onOpenLight = { open(HaPage.Light(it)) }
        )
        is HaPage.Light -> HaLightPage(viewModel, current.entityId)
    }
}

@Composable
private fun HomeAssistantMainPage(
    viewModel: HomeAssistantSettingsViewModel,
    initialFocusRequester: FocusRequester?,
    onOpen: (HaPage) -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val ambiance by viewModel.ambiance.collectAsStateWithLifecycle()
    val apiCheck by viewModel.apiCheck.collectAsStateWithLifecycle()
    val hasToken by viewModel.hasLongLivedToken.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<HaField?>(null) }
    var phoneSetup by remember { mutableStateOf(false) }
    val notSet = stringResource(R.string.mdblist_not_set)

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsDetailHeader(
            title = stringResource(R.string.ha_title),
            subtitle = stringResource(R.string.ha_subtitle)
        )
        SettingsGroupCard(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val listState = rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(key = "ha_enabled") {
                        SettingsToggleRow(
                            title = stringResource(R.string.ha_enable_title),
                            subtitle = stringResource(R.string.ha_enable_subtitle),
                            checked = settings.enabled,
                            onToggle = { viewModel.setEnabled(!settings.enabled) },
                            modifier = Modifier
                                .padding(top = NuvioTheme.spacing.xxs)
                                .then(if (initialFocusRequester != null) Modifier.focusRequester(initialFocusRequester) else Modifier)
                        )
                    }
                    item(key = "ha_status") {
                        SettingsActionRow(
                            title = stringResource(R.string.ha_status_title),
                            subtitle = null,
                            value = statusText(status),
                            valueColor = if (status is HaConnectionStatus.Connected) NuvioTheme.colors.Primary else NuvioTheme.colors.TextSecondary,
                            onClick = {}
                        )
                    }
                    item(key = "ha_phone") {
                        SettingsActionRow(
                            title = stringResource(R.string.ha_phone_title),
                            subtitle = stringResource(R.string.ha_phone_subtitle),
                            onClick = { phoneSetup = true }
                        )
                    }
                    item(key = "ha_broker") {
                        SettingsActionRow(
                            title = stringResource(R.string.ha_broker_title),
                            subtitle = stringResource(R.string.ha_broker_subtitle),
                            value = if (settings.host.isBlank()) notSet else "${settings.host}:${settings.port}",
                            onClick = { editing = HaField.BROKER }
                        )
                    }
                    item(key = "ha_username") {
                        SettingsActionRow(
                            title = stringResource(R.string.ha_username_title),
                            subtitle = stringResource(R.string.ha_username_subtitle),
                            value = settings.username.ifBlank { notSet },
                            onClick = { editing = HaField.USERNAME }
                        )
                    }
                    item(key = "ha_password") {
                        SettingsActionRow(
                            title = stringResource(R.string.ha_password_title),
                            subtitle = stringResource(R.string.ha_password_subtitle),
                            value = if (settings.password.isEmpty()) notSet else "••••••",
                            onClick = { editing = HaField.PASSWORD }
                        )
                    }
                    item(key = "ha_device_name") {
                        SettingsActionRow(
                            title = stringResource(R.string.ha_device_name_title),
                            subtitle = stringResource(R.string.ha_device_name_subtitle),
                            value = settings.deviceName,
                            onClick = { editing = HaField.DEVICE_NAME }
                        )
                    }
                    item(key = "ha_lights_label") {
                        SettingsSectionLabel(
                            text = stringResource(R.string.ha_lights_section),
                            description = stringResource(R.string.ha_lights_section_desc)
                        )
                    }
                    item(key = "ha_ambiance") {
                        SettingsActionRow(
                            title = stringResource(R.string.ha_amb_title),
                            subtitle = stringResource(R.string.ha_amb_row_subtitle),
                            value = stringResource(if (ambiance.enabled) R.string.ha_amb_on else R.string.ha_amb_off),
                            onClick = { onOpen(HaPage.Ambiance) }
                        )
                    }
                    item(key = "ha_lights") {
                        SettingsActionRow(
                            title = stringResource(R.string.ha_lights_title),
                            subtitle = stringResource(R.string.ha_lights_row_subtitle),
                            value = stringResource(R.string.ha_lights_value, ambiance.lights.size),
                            onClick = { onOpen(HaPage.Lights) }
                        )
                    }
                    item(key = "ha_api_check") {
                        SettingsActionRow(
                            title = stringResource(R.string.ha_api_check_title),
                            subtitle = stringResource(R.string.ha_api_check_subtitle),
                            value = apiCheckText(apiCheck),
                            valueColor = if (apiCheck is HaApiCheck.Ok) NuvioTheme.colors.Primary else NuvioTheme.colors.TextSecondary,
                            onClick = { viewModel.checkApi() }
                        )
                    }
                    item(key = "ha_api_url") {
                        SettingsActionRow(
                            title = stringResource(R.string.ha_api_url_title),
                            subtitle = stringResource(R.string.ha_api_url_subtitle),
                            value = settings.apiBase.ifBlank { notSet },
                            onClick = { editing = HaField.API_URL }
                        )
                    }
                    item(key = "ha_token") {
                        SettingsActionRow(
                            title = stringResource(R.string.ha_token_title),
                            subtitle = stringResource(R.string.ha_token_subtitle),
                            value = if (hasToken) "••••••" else notSet,
                            onClick = { editing = HaField.TOKEN }
                        )
                    }
                }
                SettingsVerticalScrollIndicators(state = listState)
            }
        }
    }

    when (val field = editing) {
        null -> Unit
        else -> {
            val (title, initial) = when (field) {
                HaField.BROKER -> stringResource(R.string.ha_broker_title) to
                    (if (settings.host.isBlank()) "" else "${settings.host}:${settings.port}")
                HaField.USERNAME -> stringResource(R.string.ha_username_title) to settings.username
                HaField.PASSWORD -> stringResource(R.string.ha_password_title) to ""
                HaField.DEVICE_NAME -> stringResource(R.string.ha_device_name_title) to settings.deviceName
                HaField.API_URL -> stringResource(R.string.ha_api_url_title) to settings.apiUrl
                HaField.TOKEN -> stringResource(R.string.ha_token_title) to ""
            }
            HaTextDialog(
                title = title,
                initialValue = initial,
                placeholder = when (field) {
                    HaField.BROKER -> stringResource(R.string.ha_broker_placeholder)
                    HaField.API_URL -> settings.apiBase.ifBlank { stringResource(R.string.ha_api_url_placeholder) }
                    else -> ""
                },
                isPassword = field == HaField.PASSWORD || field == HaField.TOKEN,
                onDismiss = { editing = null },
                onSave = { value ->
                    when (field) {
                        HaField.BROKER -> viewModel.setBroker(value)
                        HaField.USERNAME -> viewModel.setUsername(value)
                        HaField.PASSWORD -> viewModel.setPassword(value)
                        HaField.DEVICE_NAME -> viewModel.setDeviceName(value)
                        HaField.API_URL -> viewModel.setApiUrl(value)
                        HaField.TOKEN -> viewModel.setLongLivedToken(value)
                    }
                    editing = null
                }
            )
        }
    }

    if (phoneSetup) {
        HaPhoneSetupDialog(viewModel = viewModel, onDismiss = { phoneSetup = false })
    }
}

@Composable
private fun apiCheckText(check: HaApiCheck): String = when (check) {
    HaApiCheck.Unknown -> stringResource(R.string.ha_api_check_unknown)
    HaApiCheck.Checking -> stringResource(R.string.ha_api_check_checking)
    is HaApiCheck.Ok -> if (check.user.isAdmin) stringResource(R.string.ha_api_check_admin, check.user.name)
        else stringResource(R.string.ha_api_check_ok, check.user.name)
    is HaApiCheck.Failed -> apiFailureText(check.reason)
}

@Composable
private fun statusText(status: HaConnectionStatus): String = when (status) {
    HaConnectionStatus.Off -> stringResource(R.string.ha_status_off)
    HaConnectionStatus.Incomplete -> stringResource(R.string.ha_status_incomplete)
    HaConnectionStatus.Connecting -> stringResource(R.string.ha_status_connecting)
    HaConnectionStatus.Connected -> stringResource(R.string.ha_status_connected)
    is HaConnectionStatus.Failed -> when (status.reason) {
        HaFailure.BAD_CREDENTIALS -> stringResource(R.string.ha_status_bad_credentials)
        HaFailure.UNREACHABLE -> stringResource(R.string.ha_status_unreachable)
        HaFailure.TIMEOUT -> stringResource(R.string.ha_status_timeout)
        HaFailure.OTHER -> stringResource(R.string.ha_status_other, status.detail.orEmpty())
    }
}

@Composable
private fun HaPhoneSetupDialog(viewModel: HomeAssistantSettingsViewModel, onDismiss: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var handle by remember { mutableStateOf<HaPhoneSetupServer.Handle?>(null) }
    var noNetwork by remember { mutableStateOf(false) }
    var received by remember { mutableStateOf(false) }
    val texts = HaPhoneSetupServer.Texts(
        title = stringResource(R.string.ha_title),
        intro = stringResource(R.string.ha_page_intro),
        host = stringResource(R.string.ha_page_host),
        port = stringResource(R.string.ha_page_port),
        username = stringResource(R.string.ha_page_username),
        password = stringResource(R.string.ha_page_password),
        passwordHint = stringResource(R.string.ha_page_password_hint),
        deviceName = stringResource(R.string.ha_page_device_name),
        send = stringResource(R.string.ha_page_send),
        sent = stringResource(R.string.ha_page_sent),
        missingHost = stringResource(R.string.ha_page_missing_host)
    )
    DisposableEffect(Unit) {
        val current = HaPhoneSetupServer.Values(
            host = settings.host,
            port = settings.port,
            username = settings.username,
            password = null,
            deviceName = settings.deviceName
        )
        val h = HaPhoneSetupServer.start(texts, current) { values ->
            viewModel.applyFromPhone(values)
            received = true
        }
        handle = h
        noNetwork = h == null
        onDispose { h?.stop() }
    }
    val qr = remember(handle?.url) {
        handle?.url?.let { runCatching { QrCodeGenerator.generate(it, 360) }.getOrNull() }
    }
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.ha_phone_title),
        width = 680.dp
    ) {
        if (noNetwork) {
            Text(stringResource(R.string.ha_phone_no_network), color = NuvioTheme.colors.TextSecondary)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                qr?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(180.dp).background(Color.White).padding(6.dp)
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.ha_phone_dialog_text), color = NuvioTheme.colors.TextSecondary)
                    handle?.url?.let { Text(it, color = NuvioTheme.colors.TextPrimary, style = MaterialTheme.typography.bodySmall) }
                    if (received) Text(stringResource(R.string.ha_phone_received), color = NuvioTheme.colors.Primary)
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) { Text(stringResource(R.string.action_close)) }
        }
    }
}

@Composable
private fun HaTextDialog(
    title: String,
    initialValue: String,
    placeholder: String,
    isPassword: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    var isInputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) { runCatching { inputFocusRequester.requestFocus() } }

    NuvioDialog(onDismiss = onDismiss, title = title, width = 700.dp) {
        Card(
            onClick = { inputFocusRequester.requestFocus() },
            modifier = Modifier.fillMaxWidth().onFocusChanged { isInputFocused = it.isFocused || it.hasFocus },
            colors = CardDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundElevated,
                focusedContainerColor = NuvioTheme.colors.BackgroundElevated
            ),
            border = CardDefaults.border(
                border = Border(
                    border = androidx.compose.foundation.BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                    shape = RoundedCornerShape(10.dp)
                ),
                focusedBorder = Border(
                    border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                    shape = RoundedCornerShape(10.dp)
                )
            ),
            shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
            scale = CardDefaults.scale(focusedScale = 1f)
        ) {
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = NuvioTheme.spacing.md)) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(inputFocusRequester)
                        .onKeyEvent { event ->
                            event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
                                event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                        },
                    singleLine = true,
                    visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done,
                        keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Uri
                    ),
                    keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = NuvioTheme.colors.TextPrimary),
                    cursorBrush = SolidColor(if (isInputFocused) NuvioTheme.colors.Primary else Color.Transparent),
                    decorationBox = { inner ->
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextTertiary)
                        }
                        inner()
                    }
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundElevated,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) { Text(stringResource(R.string.action_cancel)) }
            Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
            Button(
                onClick = { onSave(value) },
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary
                )
            ) { Text(stringResource(R.string.action_save)) }
        }
    }
}
