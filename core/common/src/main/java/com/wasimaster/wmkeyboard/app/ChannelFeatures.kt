package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.config.BuildConfig

/**
 * Features the Google Play build leaves out because Play will not accept the
 * permission or component behind them. app/src/play/AndroidManifest.xml
 * removes the declarations; this is the settings half, so no row offers a
 * grant the installed APK cannot hold. The keyboard side needs no gate: both
 * features already check the grant at runtime and stand aside without it.
 *
 * The first two came out of Play's review of version code 26 (2026-09-28).
 */
object ChannelFeatures {
    /**
     * Screenshots in the clipboard, behind READ_MEDIA_IMAGES (and
     * READ_EXTERNAL_STORAGE below API 33). Play ruled the watcher "infrequent
     * access" that the photo picker should serve, which it cannot: there is no
     * moment of picking, only a MediaStore observer.
     */
    val SCREENSHOT_CLIPS: Boolean = !BuildConfig.ENABLE_PLAY_STORE

    /**
     * TalkBack gesture pass-through, behind TouchPassthroughService. Without
     * it, Gestures mode is Explore by another name, so the mode goes too.
     */
    val GESTURE_PASSTHROUGH: Boolean = !BuildConfig.ENABLE_PLAY_STORE

    /**
     * The floating AI button's screen reading (#697), behind
     * ScreenContextService. Same Play rule as the pass-through service. The
     * button itself stays: its chat opens without the screen's text.
     */
    val SCREEN_CONTEXT: Boolean = !BuildConfig.ENABLE_PLAY_STORE

    /**
     * The app launcher's split-screen helper, behind SplitScreenHelperService.
     * Same Play rule again. Split screen stays wherever the keyboard's own
     * launch-adjacent start works, which is stock Android 12L and later.
     */
    val SPLIT_SCREEN_HELPER: Boolean = !BuildConfig.ENABLE_PLAY_STORE

    /**
     * The clock tool's ring service, a foreground service of type
     * systemExempted that plays an alarm while it rings. Play keeps that type
     * for apps whose core job is an alarm clock, and a keyboard is not one.
     * Without it an alarm rings through the insistent alarm notification
     * channel instead: the system's alarm sound, no per-alarm sound or
     * fade-in, and the ring screen still comes up as a full-screen intent.
     */
    val CLOCK_RING_SERVICE: Boolean = !BuildConfig.ENABLE_PLAY_STORE

    /**
     * The clock's ring screen over the lock screen, behind
     * USE_FULL_SCREEN_INTENT. Play keeps that permission for alarm-clock and
     * calling apps. Without it a ringing alarm is a heads-up notification
     * whose tap opens the ring screen.
     */
    val CLOCK_FULL_SCREEN: Boolean = !BuildConfig.ENABLE_PLAY_STORE

    /**
     * The gallery tool's own grid of the photo library (#708), behind
     * READ_MEDIA_IMAGES and READ_MEDIA_VIDEO: the same permissions Play
     * refused for the screenshot watcher. Without them the tool opens
     * Android's photo picker instead, which needs no permission at all, and
     * sends what is picked the same way.
     */
    val GALLERY_BROWSER: Boolean = !BuildConfig.ENABLE_PLAY_STORE

    /**
     * The keyboard's own file manager, behind All files access, which Play
     * keeps for apps whose core job is managing files. Without it every tool
     * that picks a file goes to Android's picker, as it always did.
     */
    val FILE_MANAGER: Boolean = !BuildConfig.ENABLE_PLAY_STORE

    /**
     * The SupportKori link on About › Supporters. Play's Payments policy lets
     * an app lead users to no payment method but Play's own billing, so the
     * Play build offers tips through Play billing instead (app/.../tips).
     * Those tippers join the same Supporters list.
     */
    val SUPPORTKORI_LINK: Boolean = !BuildConfig.ENABLE_PLAY_STORE
}
