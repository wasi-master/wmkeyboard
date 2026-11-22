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

    /**
     * Injects one mouse event, built here rather than in the keyboard because
     * a mouse button event needs the hidden MotionEvent.setActionButton, which
     * this process may call. [action] is a MotionEvent action, [buttons] the
     * button state, [actionButton] the button a BUTTON_PRESS or BUTTON_RELEASE
     * is about (0 otherwise), the scrolls are AXIS_HSCROLL and AXIS_VSCROLL for
     * ACTION_SCROLL. [downTime] is the press's uptime, or the event's own for a
     * hover or a scroll. For the Trackpad tool's mouse mode (#1411).
     */
    boolean injectMouse(int action, float x, float y, int buttons, int actionButton, float hScroll, float vScroll, long downTime, int displayId) = 5;
}
