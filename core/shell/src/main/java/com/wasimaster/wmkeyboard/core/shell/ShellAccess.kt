package com.wasimaster.wmkeyboard.core.shell

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.view.KeyEvent
import android.view.MotionEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.File

/** One shell command's result: [code] is the exit status, -1 on timeout. */
data class ShellResult(val code: Int, val output: String) {
    val ok: Boolean get() = code == 0
}

/** Which identity commands run under right now. */
enum class ShellKind { NONE, SHIZUKU, ROOT }

/**
 * Commands as the shell user, for window routes no ordinary app may take:
 * `am start --windowingMode`, WM Shell's split command, `am task resize`,
 * secure settings, and the experimental emulated window's display and input.
 *
 * Two doors, both opt-in:
 *
 *  * **Shizuku** (or Sui): the user installs and starts it, then grants the
 *    keyboard on its prompt. Commands go through [ShellUserService], which
 *    Shizuku starts from this APK as the shell user.
 *  * **Root**: only once the user turns **Use root** on ([rootAllowed]); `su`
 *    is never touched before, so a root manager never prompts unasked.
 *
 * Shizuku wins when both are there: it needs no prompt per command.
 */
object ShellAccess {

    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 0x5e11
    private const val BIND_TIMEOUT_MS = 5_000L
    private const val ROOT_TIMEOUT_MS = 10_000L

    /** Set by the launcher's settings: may commands go through `su`? */
    @Volatile
    var rootAllowed: Boolean = false

    private val _granted = MutableStateFlow(false)

    /** Shizuku is running and has granted this app; re-checked on every [refresh]. */
    val shizukuGranted: StateFlow<Boolean> = _granted.asStateFlow()

    private val service = MutableStateFlow<IShellService?>(null)
    private val bindLock = Mutex()
    private var listening = false

    /** Starts listening for Shizuku's binder. Cheap and idempotent; call from any onCreate. */
    fun init() {
        if (listening) return
        listening = true
        runCatching {
            Shizuku.addBinderReceivedListenerSticky { refresh() }
            Shizuku.addBinderDeadListener {
                service.value = null
                _granted.value = false
            }
            Shizuku.addRequestPermissionResultListener { code, _ -> if (code == REQUEST_CODE) refresh() }
        }
    }

    fun refresh() {
        _granted.value = shizukuRunning() && runCatching {
            !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    fun shizukuRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun shizukuInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) }.isSuccess

    /** Opens Shizuku's grant prompt; [shizukuGranted] flips when the user answers. */
    fun requestShizuku(): Boolean = runCatching {
        if (!shizukuRunning() || Shizuku.isPreV11()) return false
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            refresh()
        } else {
            Shizuku.requestPermission(REQUEST_CODE)
        }
        true
    }.getOrDefault(false)

    /** Whether an `su` binary is on the usual paths. Says nothing about whether it will grant. */
    val suPresent: Boolean by lazy {
        listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/debug_ramdisk/su", "/data/adb/ksu/bin/su")
            .any { File(it).exists() } ||
            System.getenv("PATH").orEmpty().split(':').any { File(it, "su").exists() }
    }

    /** Which door a command would take now, without starting anything. */
    fun kind(): ShellKind = when {
        _granted.value -> ShellKind.SHIZUKU
        rootAllowed && suPresent -> ShellKind.ROOT
        else -> ShellKind.NONE
    }

    val available: Boolean get() = kind() != ShellKind.NONE

    /** Runs [command] as the shell (or root); null when no door is open or it failed to start. */
    suspend fun exec(context: Context, command: String): ShellResult? = withContext(Dispatchers.IO) {
        when (kind()) {
            ShellKind.SHIZUKU -> runCatching { parse(userService(context)?.exec(command)) }.getOrNull()
            ShellKind.ROOT -> withTimeoutOrNull(ROOT_TIMEOUT_MS) {
                runCatching { parse(ShellUserService.runCommand(listOf("su", "-c", command))) }.getOrNull()
            }
            ShellKind.NONE -> null
        }
    }

    /** Touch injection for the emulated window. Shizuku only: root has no binder to call. */
    suspend fun injectMotion(context: Context, event: MotionEvent, displayId: Int): Boolean =
        runCatching { userService(context)?.injectMotion(event, displayId) ?: false }.getOrDefault(false)

    suspend fun injectKey(context: Context, event: KeyEvent, displayId: Int): Boolean =
        runCatching { userService(context)?.injectKey(event, displayId) ?: false }.getOrDefault(false)

    /**
     * The touch path's fast form: no coroutine per event, the already-bound
     * service or nothing. Call [connect] once before a window opens.
     */
    fun injectMotionNow(event: MotionEvent, displayId: Int): Boolean =
        runCatching { service.value?.injectMotion(event, displayId) ?: false }.getOrDefault(false)

    fun injectKeyNow(event: KeyEvent, displayId: Int): Boolean =
        runCatching { service.value?.injectKey(event, displayId) ?: false }.getOrDefault(false)

    /** Binds the user service ahead of [injectMotionNow]; true once it is up. */
    suspend fun connect(context: Context): Boolean = userService(context) != null

    /** Can inject input: what the emulated window needs on top of [available]. */
    val canInject: Boolean get() = _granted.value

    private fun parse(raw: String?): ShellResult? {
        raw ?: return null
        val newline = raw.indexOf('\n')
        val code = raw.substring(0, if (newline < 0) raw.length else newline).trim().toIntOrNull() ?: -1
        return ShellResult(code, if (newline < 0) "" else raw.substring(newline + 1))
    }

    private suspend fun userService(context: Context): IShellService? {
        service.value?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        if (!_granted.value) return null
        return bindLock.withLock {
            service.value?.takeIf { it.asBinder().isBinderAlive }?.let { return@withLock it }
            val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, ShellUserService::class.java.name))
                .daemon(false)
                .processNameSuffix("shell")
                .tag("wm-shell")
                .version(SERVICE_VERSION)
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
                    if (binder != null && binder.pingBinder()) service.value = IShellService.Stub.asInterface(binder)
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    service.value = null
                }
            }
            withContext(Dispatchers.Main) { runCatching { Shizuku.bindUserService(args, connection) } }
            withTimeoutOrNull(BIND_TIMEOUT_MS) { service.filterNotNull().first() }
        }
    }

    /** Bump when [IShellService] changes, so Shizuku replaces a running old copy. */
    private const val SERVICE_VERSION = 1
}
