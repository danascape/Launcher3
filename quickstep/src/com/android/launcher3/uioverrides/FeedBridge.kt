package com.android.launcher3.uioverrides

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process

/**
 * Resolves the launcher overlay ("feed") service.
 *
 * Only [FEED_PACKAGE] is ever bound; there is no provider discovery and no signature
 * verification, so the package must be part of the system image.
 */
object FeedBridge {

    /** The single overlay provider this launcher binds to. */
    const val FEED_PACKAGE = "dev.danascape.launcher3feed.helloworld"

    private const val OVERLAY_ACTION = "com.android.launcher3.WINDOW_OVERLAY"

    /** Intent used both to resolve and to bind the overlay service. */
    @JvmStatic
    fun overlayIntent(context: Context): Intent =
        Intent(OVERLAY_ACTION)
            .setPackage(FEED_PACKAGE)
            .setData(
                Uri.parse("app://${context.packageName}:${Process.myUid()}")
                    .buildUpon()
                    .appendQueryParameter("v", OVERLAY_VERSION)
                    .appendQueryParameter("cv", OVERLAY_CLIENT_VERSION)
                    .build()
            )

    /** Whether [FEED_PACKAGE] is installed and exposes the overlay service. */
    @JvmStatic
    fun isAvailable(context: Context): Boolean =
        context.packageManager.resolveService(overlayIntent(context), 0) != null

    private const val OVERLAY_VERSION = "7"
    private const val OVERLAY_CLIENT_VERSION = "9"
}
