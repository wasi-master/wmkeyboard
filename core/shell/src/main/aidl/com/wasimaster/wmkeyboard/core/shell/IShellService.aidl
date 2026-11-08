package com.wasimaster.wmkeyboard.core.shell;

import android.os.ParcelFileDescriptor;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * The Shizuku user service: a process Shizuku starts as the shell user (or
 * root, under Sui or a rooted Shizuku) from this APK. See ShellUserService.
 */
interface IShellService {
    /** Shizuku's own teardown call; the transaction code is Shizuku's. */
    void destroy() = 16777114;

    /**
     * Runs one `sh -c` command. Returns the exit code on the first line, then
     * stdout and stderr together, at most 64 KiB.
     */
    String exec(String command) = 1;

    /** Injects a touch event into the given display. False when the system refused it. */
    boolean injectMotion(in MotionEvent event, int displayId) = 2;

    /** Injects a key event (Back, mostly) into the given display. */
    boolean injectKey(in KeyEvent event, int displayId) = 3;

    /**
     * Runs one `sh -c` command and hands back the read end of a pipe carrying
     * its stdout, uncapped: for binary output such as `screencap -p`, which
     * [exec]'s string and 64 KiB cap cannot carry. Null when it failed to start.
     */
    ParcelFileDescriptor execStream(String command) = 4;
}
