package com.wasimaster.wmkeyboard.app

/**
 * Trampoline for the file manager's storage pair on Android 7 to 10: the
 * disclosure, then the system dialog. The panel re-reads the grant when the
 * keyboard comes back. From Android 11 the grant is
 * [SpecialAccess.ALL_FILES], through [SpecialAccessActivity].
 */
class FilesPermissionActivity : PermissionRequestActivity() {
    override val disclosure = PermissionDisclosures.FILES
}
