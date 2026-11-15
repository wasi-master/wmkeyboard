package com.wasimaster.wmkeyboard.app

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.settings.BackupDestination
import com.wasimaster.wmkeyboard.core.settings.BackupLocation
import com.wasimaster.wmkeyboard.core.settings.ICloudConfig
import com.wasimaster.wmkeyboard.core.settings.NasConfig
import com.wasimaster.wmkeyboard.core.settings.PCloudRegion
import com.wasimaster.wmkeyboard.core.settings.sink.ICloudLogin
import com.wasimaster.wmkeyboard.core.settings.sink.ICloudSession
import com.wasimaster.wmkeyboard.core.settings.sink.ICloudSink
import com.wasimaster.wmkeyboard.core.settings.sink.MegaLogin
import com.wasimaster.wmkeyboard.core.settings.sink.NasLogin
import com.wasimaster.wmkeyboard.core.settings.sink.NasSink
import com.wasimaster.wmkeyboard.core.settings.sink.PCloudLogin
import com.wasimaster.wmkeyboard.core.settings.sink.SinkError
import com.wasimaster.wmkeyboard.core.settings.sink.WebDavSink
import com.wasimaster.wmkeyboard.core.settings.sink.proton.ProtonLogin
import kotlinx.coroutines.launch

/*
 * The editor parts of the locations that sign in to an account with a
 * password (pCloud, MEGA, iCloud Drive, Proton Drive) or a NAS (Synology,
 * QNAP), and NFS. Each sign-in runs from the sheet and writes what it gets
 * into the draft, which Save keeps; a password the location does not need
 * later lives only in this composition.
 */

/** A sign-in's busy spinner and its last failure, under the buttons. */
@Composable
private fun SignInStatus(busy: Boolean, problem: String?) {
    if (busy) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.backup_auto_oauth_signing_in), style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (problem != null) StateBanner(problem, tone = BannerTone.WARNING)
}

/** The words for a failed sign-in: a refused password, or the error a run would show. */
private fun signInProblem(context: android.content.Context, error: SinkError, type: BackupDestination): String =
    if (error == SinkError.PERMISSION_LOST) {
        context.getString(R.string.backup_cloud_sign_in_failed)
    } else {
        autoBackupErrorText(context, error.name, type) ?: context.getString(R.string.backup_auto_error_io)
    }

/** Who is signed in, and Sign out, for an account location that is signed in. */
@Composable
private fun SignedInRow(account: String, detail: String? = null, onSignOut: () -> Unit) {
    WmRow(
        title = stringResource(R.string.backup_cloud_signed_in_as, account),
        subtitle = detail,
        icon = SettingsRowIcons[R.string.backup_auto_dest_dropbox],
    )
    OutlinedButton(onClick = onSignOut, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(stringResource(R.string.backup_auto_oauth_sign_out))
    }
}

/** The folder in the account, for every cloud location here. */
@Composable
private fun CloudFolderField(value: String, onChange: (String) -> Unit) {
    StoredTextField(
        label = stringResource(R.string.backup_location_folder_label),
        value = value,
        supporting = stringResource(R.string.backup_cloud_folder_hint),
        onChange = onChange,
    )
}

/** The code field and its button, once an account has asked for one. */
@Composable
private fun CodeField(code: String, hint: String, busy: Boolean, onCode: (String) -> Unit, onSubmit: () -> Unit) {
    StoredTextField(
        label = stringResource(R.string.backup_cloud_code_label),
        value = code,
        supporting = hint,
        keyboardType = KeyboardType.Number,
        onChange = onCode,
    )
    FilledTonalButton(
        enabled = !busy && code.count { it.isDigit() } >= MIN_CODE_DIGITS,
        onClick = onSubmit,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    ) { Text(stringResource(R.string.backup_cloud_code_submit)) }
}

private const val MIN_CODE_DIGITS = 6

/** Box: the browser sign-in Dropbox and OneDrive use, and the folder. */
@Composable
internal fun BoxPart(
    repository: com.wasimaster.wmkeyboard.core.settings.SettingsRepository,
    current: BackupLocation,
    draft: BackupLocation,
    onMessage: (String) -> Unit,
    onChange: (BackupLocation) -> Unit,
) {
    SignInPart(repository, current, onMessage)
    CloudFolderField(draft.box.folder) { onChange(draft.copy(box = draft.box.copy(folder = it))) }
}

/** pCloud: the region, the email and password once, a code if asked, and the folder. */
@Composable
internal fun PCloudPart(draft: BackupLocation, onChange: (BackupLocation) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pcloud = draft.pcloud
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var token by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    fun handle(outcome: PCloudLogin.Outcome) {
        busy = false
        when (outcome) {
            is PCloudLogin.Outcome.SignedIn -> {
                token = null
                password = ""
                onChange(draft.copy(pcloud = pcloud.copy(auth = outcome.auth)))
            }
            is PCloudLogin.Outcome.NeedsCode -> token = outcome.token
            is PCloudLogin.Outcome.OtherRegion -> {
                problem = context.getString(R.string.backup_pcloud_other_region)
                onChange(draft.copy(pcloud = pcloud.copy(region = outcome.region)))
            }
            is PCloudLogin.Outcome.Failed -> problem = if (token != null) {
                context.getString(R.string.backup_cloud_code_failed)
            } else {
                signInProblem(context, outcome.error, BackupDestination.PCLOUD)
            }
        }
    }

    if (pcloud.configured) {
        SignedInRow(pcloud.email) { onChange(draft.copy(pcloud = pcloud.copy(auth = ""))) }
    } else {
        ChoiceSetting(
            title = R.string.backup_pcloud_region_title,
            options = listOf(
                PCloudRegion.US to stringResource(R.string.backup_pcloud_region_us),
                PCloudRegion.EU to stringResource(R.string.backup_pcloud_region_eu),
            ),
            selected = pcloud.region,
            default = PCloudRegion.US,
            info = stringResource(R.string.backup_pcloud_region_info),
        ) { onChange(draft.copy(pcloud = pcloud.copy(region = it))) }
        StoredTextField(
            label = stringResource(R.string.backup_cloud_email_label),
            value = pcloud.email,
            supporting = "",
            keyboardType = KeyboardType.Email,
        ) { onChange(draft.copy(pcloud = pcloud.copy(email = it.trim()))) }
        StoredTextField(
            label = stringResource(R.string.backup_auto_ftp_password_label),
            value = password,
            supporting = stringResource(R.string.backup_cloud_password_hint),
            password = true,
        ) { password = it }
        FilledTonalButton(
            enabled = !busy && pcloud.email.isNotBlank() && password.isNotEmpty(),
            onClick = {
                busy = true
                problem = null
                scope.launch { handle(PCloudLogin.signIn(pcloud.region, pcloud.email, password)) }
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        ) { Text(stringResource(R.string.backup_auto_oauth_sign_in)) }
        token?.let { pending ->
            CodeField(code, stringResource(R.string.backup_cloud_code_hint), busy, { code = it }) {
                busy = true
                problem = null
                scope.launch { handle(PCloudLogin.submitCode(pcloud.region, pending, code)) }
            }
        }
        SignInStatus(busy, problem)
    }
    CloudFolderField(pcloud.folder) { onChange(draft.copy(pcloud = pcloud.copy(folder = it))) }
    StateBanner(stringResource(R.string.backup_pcloud_info))
}

/** Synology or QNAP: the address, the account, the folder, the certificate, and a one-time 2FA sign-in. */
@Composable
internal fun NasPart(draft: BackupLocation, onForgetCertificate: () -> Unit, onChange: (BackupLocation) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val nas = draft.nas
    val synology = draft.type == BackupDestination.SYNOLOGY
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    fun edit(change: (NasConfig) -> NasConfig) = onChange(draft.copy(nas = change(nas)))

    StoredTextField(
        label = stringResource(R.string.backup_nas_server_label),
        value = nas.server,
        supporting = stringResource(if (synology) R.string.backup_nas_server_hint_synology else R.string.backup_nas_server_hint_qnap),
        keyboardType = KeyboardType.Uri,
    ) { edit { n -> n.copy(server = it.trim(), certPin = "") } }
    val address = NasSink.baseUrl(nas.server, if (synology) 5001 else 443)
    if (address != null && address.scheme != "https" && !WebDavSink.isTailnet(address.toString())) {
        StateBanner(stringResource(R.string.backup_nas_cleartext), tone = BannerTone.WARNING)
    }
    StoredTextField(
        label = stringResource(R.string.backup_auto_ftp_user_label),
        value = nas.user,
        supporting = "",
    ) { edit { n -> n.copy(user = it.trim(), deviceToken = "") } }
    StoredTextField(
        label = stringResource(R.string.backup_auto_ftp_password_label),
        value = nas.password,
        supporting = "",
        password = true,
    ) { edit { n -> n.copy(password = it) } }
    StoredTextField(
        label = stringResource(R.string.backup_location_folder_label),
        value = nas.path,
        supporting = stringResource(if (synology) R.string.backup_nas_path_hint_synology else R.string.backup_nas_path_hint_qnap),
    ) { edit { n -> n.copy(path = it) } }
    ToggleSetting(
        R.string.backup_nas_own_cert_title,
        stringResource(R.string.backup_nas_own_cert_subtitle),
        nas.ownCertificate,
        default = false,
        info = stringResource(R.string.backup_nas_own_cert_info),
    ) { on -> edit { it.copy(ownCertificate = on, certPin = "") } }
    if (nas.ownCertificate) {
        WmRow(
            title = stringResource(R.string.backup_nas_cert_title),
            subtitle = if (nas.certPin.isEmpty()) {
                stringResource(R.string.backup_nas_cert_none)
            } else {
                stringResource(R.string.backup_nas_cert_value, nas.certPin.take(CERT_PREFIX))
            },
            icon = SettingsRowIcons[R.string.backup_sftp_host_key_title],
        )
        if (nas.certPin.isNotEmpty()) {
            OutlinedButton(onClick = onForgetCertificate, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(stringResource(R.string.backup_nas_cert_forget))
            }
        }
    }
    WmRow(
        title = stringResource(R.string.backup_nas_two_step_title),
        subtitle = stringResource(if (nas.deviceToken.isNotEmpty()) R.string.backup_nas_two_step_done else R.string.backup_nas_two_step_hint),
        icon = SettingsRowIcons[R.string.backup_sftp_host_key_title],
    )
    if (nas.configured) {
        CodeField(code, stringResource(R.string.backup_cloud_code_hint), busy, { code = it }) {
            busy = true
            problem = null
            scope.launch {
                var pin = nas.certPin
                val pinned = { seen: String -> if (pin.isEmpty()) pin = seen }
                val answer = if (synology) NasLogin.synology(nas, code, pinned) else NasLogin.qnap(nas, code, pinned)
                busy = false
                val token = answer.deviceToken
                when {
                    answer.sid != null && token != null -> {
                        code = ""
                        edit { it.copy(deviceToken = token, certPin = pin) }
                    }
                    answer.sid != null -> problem = context.getString(R.string.backup_cloud_code_failed)
                    else -> problem = if (answer.needsCode || answer.error == SinkError.PERMISSION_LOST) {
                        context.getString(R.string.backup_cloud_code_failed)
                    } else {
                        signInProblem(context, answer.error ?: SinkError.IO, draft.type)
                    }
                }
            }
        }
        SignInStatus(busy, problem)
    }
}

private const val CERT_PREFIX = 16

/** NFS: the server, the export and a folder in it, the ids to write as, and the ports. */
@Composable
internal fun NfsPart(draft: BackupLocation, onChange: (BackupLocation) -> Unit) {
    val nfs = draft.nfs
    StoredTextField(
        label = stringResource(R.string.backup_auto_ftp_host_label),
        value = nfs.host,
        supporting = stringResource(R.string.backup_smb_host_hint),
        keyboardType = KeyboardType.Uri,
    ) { onChange(draft.copy(nfs = nfs.copy(host = it.trim()))) }
    StoredTextField(
        label = stringResource(R.string.backup_nfs_export_label),
        value = nfs.export,
        supporting = stringResource(R.string.backup_nfs_export_hint),
    ) { onChange(draft.copy(nfs = nfs.copy(export = it.trim()))) }
    StoredTextField(
        label = stringResource(R.string.backup_location_folder_label),
        value = nfs.path,
        supporting = stringResource(R.string.backup_nfs_path_hint),
    ) { onChange(draft.copy(nfs = nfs.copy(path = it))) }
    StoredTextField(
        label = stringResource(R.string.backup_nfs_uid_label),
        value = nfs.uid.toString(),
        supporting = stringResource(R.string.backup_nfs_ids_hint),
        keyboardType = KeyboardType.Number,
    ) { entered -> entered.toIntOrNull()?.let { onChange(draft.copy(nfs = nfs.copy(uid = it))) } }
    StoredTextField(
        label = stringResource(R.string.backup_nfs_gid_label),
        value = nfs.gid.toString(),
        supporting = "",
        keyboardType = KeyboardType.Number,
    ) { entered -> entered.toIntOrNull()?.let { onChange(draft.copy(nfs = nfs.copy(gid = it))) } }
    StoredTextField(
        label = stringResource(R.string.backup_nfs_port_label),
        value = nfs.port.toString(),
        supporting = stringResource(R.string.backup_nfs_ports_hint),
        keyboardType = KeyboardType.Number,
    ) { entered -> entered.toIntOrNull()?.let { onChange(draft.copy(nfs = nfs.copy(port = it))) } }
    StoredTextField(
        label = stringResource(R.string.backup_nfs_mount_port_label),
        value = nfs.mountPort.toString(),
        supporting = "",
        keyboardType = KeyboardType.Number,
    ) { entered -> entered.toIntOrNull()?.let { onChange(draft.copy(nfs = nfs.copy(mountPort = it))) } }
    StateBanner(stringResource(R.string.backup_nfs_info), tone = BannerTone.WARNING)
}

/** MEGA: the email and password once, a code if asked, and the folder. */
@Composable
internal fun MegaPart(draft: BackupLocation, onChange: (BackupLocation) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mega = draft.mega
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var needsCode by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    fun attempt() {
        busy = true
        problem = null
        scope.launch {
            val outcome = MegaLogin.signIn(mega.email, password, if (needsCode) code else "")
            busy = false
            when (outcome) {
                is MegaLogin.Outcome.SignedIn -> {
                    password = ""
                    code = ""
                    needsCode = false
                    onChange(draft.copy(mega = mega.copy(sessionId = outcome.sessionId, masterKey = outcome.masterKey)))
                }
                MegaLogin.Outcome.NeedsCode -> {
                    if (needsCode) problem = context.getString(R.string.backup_cloud_code_failed)
                    needsCode = true
                }
                is MegaLogin.Outcome.Failed -> problem = signInProblem(context, outcome.error, BackupDestination.MEGA)
            }
        }
    }

    if (mega.configured) {
        SignedInRow(mega.email) { onChange(draft.copy(mega = mega.copy(sessionId = "", masterKey = ""))) }
    } else {
        StoredTextField(
            label = stringResource(R.string.backup_cloud_email_label),
            value = mega.email,
            supporting = "",
            keyboardType = KeyboardType.Email,
        ) { onChange(draft.copy(mega = mega.copy(email = it.trim()))) }
        StoredTextField(
            label = stringResource(R.string.backup_auto_ftp_password_label),
            value = password,
            supporting = stringResource(R.string.backup_cloud_password_hint),
            password = true,
        ) { password = it }
        FilledTonalButton(
            enabled = !busy && mega.email.isNotBlank() && password.isNotEmpty(),
            onClick = ::attempt,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        ) { Text(stringResource(R.string.backup_auto_oauth_sign_in)) }
        if (needsCode) CodeField(code, stringResource(R.string.backup_cloud_code_hint), busy, { code = it }, ::attempt)
        SignInStatus(busy, problem)
    }
    CloudFolderField(mega.folder) { onChange(draft.copy(mega = mega.copy(folder = it))) }
    StateBanner(stringResource(R.string.backup_mega_info))
}

/** iCloud Drive: the Apple Account and password, the code from a trusted device, and the folder. */
@Composable
internal fun ICloudPart(draft: BackupLocation, onChange: (BackupLocation) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val icloud = draft.icloud
    var code by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<ICloudLogin.Outcome.NeedsCode?>(null) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val clientId = icloud.clientId.ifEmpty { ICloudSession.CLIENT_ID }

    fun handle(outcome: ICloudLogin.Outcome) {
        busy = false
        when (outcome) {
            is ICloudLogin.Outcome.SignedIn -> {
                pending = null
                code = ""
                onChange(
                    draft.copy(
                        icloud = icloud.copy(
                            trustToken = outcome.trustToken,
                            cookies = outcome.cookies,
                            clientId = clientId,
                            trustedAtMs = System.currentTimeMillis(),
                        ),
                    ),
                )
            }
            is ICloudLogin.Outcome.NeedsCode -> pending = outcome
            ICloudLogin.Outcome.NeedsApproval -> problem = context.getString(R.string.backup_icloud_needs_approval)
            is ICloudLogin.Outcome.Failed -> problem = if (pending != null) {
                context.getString(R.string.backup_cloud_code_failed)
            } else {
                signInProblem(context, outcome.error, BackupDestination.ICLOUD)
            }
        }
    }

    if (icloud.configured) {
        val until = DateFormat.getMediumDateFormat(context).format(java.util.Date(icloud.trustedAtMs + ICloudSink.TRUST_LIFETIME_MS))
        SignedInRow(icloud.appleId, stringResource(R.string.backup_icloud_trusted_until, until)) {
            onChange(draft.copy(icloud = ICloudConfig(appleId = icloud.appleId, folder = icloud.folder, clientId = icloud.clientId)))
        }
    }
    StoredTextField(
        label = stringResource(R.string.backup_icloud_apple_id_label),
        value = icloud.appleId,
        supporting = "",
        keyboardType = KeyboardType.Email,
    ) { onChange(draft.copy(icloud = icloud.copy(appleId = it.trim(), trustToken = "", cookies = ""))) }
    StoredTextField(
        label = stringResource(R.string.backup_auto_ftp_password_label),
        value = icloud.password,
        supporting = stringResource(R.string.backup_icloud_password_hint),
        password = true,
    ) { onChange(draft.copy(icloud = icloud.copy(password = it))) }
    FilledTonalButton(
        enabled = !busy && icloud.appleId.isNotBlank() && icloud.password.isNotEmpty(),
        onClick = {
            busy = true
            problem = null
            pending = null
            scope.launch { handle(ICloudLogin.start(icloud.appleId, icloud.password, clientId)) }
        },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    ) { Text(stringResource(R.string.backup_auto_oauth_sign_in)) }
    pending?.let { waiting ->
        CodeField(
            code,
            stringResource(if (waiting.bySms) R.string.backup_icloud_code_hint_sms else R.string.backup_icloud_code_hint_device),
            busy,
            { code = it },
        ) {
            busy = true
            problem = null
            scope.launch { handle(ICloudLogin.finish(waiting.session, code)) }
        }
    }
    SignInStatus(busy, problem)
    CloudFolderField(icloud.folder) { onChange(draft.copy(icloud = icloud.copy(folder = it))) }
    StateBanner(stringResource(R.string.backup_icloud_info))
}

/** Proton Drive: the account and password once, the mailbox password and code when asked, and the folder. */
@Composable
internal fun ProtonPart(draft: BackupLocation, onChange: (BackupLocation) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val proton = draft.proton
    var password by remember { mutableStateOf("") }
    var mailbox by remember { mutableStateOf("") }
    var askMailbox by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<ProtonLogin.Pending?>(null) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    fun handle(outcome: ProtonLogin.Outcome) {
        busy = false
        when (outcome) {
            is ProtonLogin.Outcome.SignedIn -> {
                password = ""
                mailbox = ""
                code = ""
                pending = null
                onChange(
                    draft.copy(
                        proton = proton.copy(
                            uid = outcome.uid,
                            accessToken = outcome.accessToken,
                            refreshToken = outcome.refreshToken,
                            saltedKeyPass = outcome.saltedKeyPass,
                        ),
                    ),
                )
            }
            is ProtonLogin.Outcome.NeedsCode -> pending = outcome.state
            ProtonLogin.Outcome.NeedsMailboxPassword -> askMailbox = true
            ProtonLogin.Outcome.NeedsHumanVerification -> problem = context.getString(R.string.backup_proton_captcha)
            is ProtonLogin.Outcome.Failed -> problem = if (pending != null) {
                context.getString(R.string.backup_cloud_code_failed)
            } else {
                signInProblem(context, outcome.error, BackupDestination.PROTON)
            }
        }
    }

    if (proton.configured) {
        SignedInRow(proton.user) {
            onChange(draft.copy(proton = proton.copy(uid = "", accessToken = "", refreshToken = "", saltedKeyPass = "")))
        }
    } else {
        StoredTextField(
            label = stringResource(R.string.backup_proton_user_label),
            value = proton.user,
            supporting = "",
            keyboardType = KeyboardType.Email,
        ) { onChange(draft.copy(proton = proton.copy(user = it.trim()))) }
        StoredTextField(
            label = stringResource(R.string.backup_auto_ftp_password_label),
            value = password,
            supporting = stringResource(R.string.backup_cloud_password_hint),
            password = true,
        ) { password = it }
        if (askMailbox) {
            StoredTextField(
                label = stringResource(R.string.backup_proton_mailbox_label),
                value = mailbox,
                supporting = stringResource(R.string.backup_proton_mailbox_hint),
                password = true,
            ) { mailbox = it }
        }
        FilledTonalButton(
            enabled = !busy && proton.user.isNotBlank() && password.isNotEmpty() && (!askMailbox || mailbox.isNotEmpty()),
            onClick = {
                busy = true
                problem = null
                pending = null
                scope.launch { handle(ProtonLogin.signIn(proton.user, password, mailbox)) }
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        ) { Text(stringResource(R.string.backup_auto_oauth_sign_in)) }
        pending?.let { waiting ->
            CodeField(code, stringResource(R.string.backup_cloud_code_hint), busy, { code = it }) {
                busy = true
                problem = null
                scope.launch { handle(ProtonLogin.submitCode(waiting, code)) }
            }
        }
        SignInStatus(busy, problem)
    }
    CloudFolderField(proton.folder) { onChange(draft.copy(proton = proton.copy(folder = it))) }
    StateBanner(stringResource(R.string.backup_proton_info))
}
