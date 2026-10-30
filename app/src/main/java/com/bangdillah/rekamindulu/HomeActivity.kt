package com.bangdillah.rekamindulu

/**
 * HomeActivity - Backward-compatible subclass of MainActivity.
 * Inherits the complete MediaProjection API implementation and audio recording support.
 */
class HomeActivity : MainActivity() {
    companion object {
        var isSessionUnlocked: Boolean
            get() = MainActivity.isSessionUnlocked
            set(value) { MainActivity.isSessionUnlocked = value }

        var hasPendingSaveDialog: Boolean
            get() = MainActivity.hasPendingSaveDialog
            set(value) { MainActivity.hasPendingSaveDialog = value }
    }
}
