package com.android.launcher3.uioverrides

import android.app.Activity
import android.os.Bundle
import com.android.launcher3.LauncherPrefs
import com.android.systemui.plugins.shared.LauncherOverlayManager
import com.android.systemui.plugins.shared.LauncherOverlayManager.LauncherOverlay
import com.android.systemui.plugins.shared.LauncherOverlayManager.LauncherOverlayCallbacks
import com.google.android.libraries.launcherclient.ISerializableScrollCallback
import com.google.android.libraries.launcherclient.LauncherClient
import com.google.android.libraries.launcherclient.LauncherClientCallbacks
import com.google.android.libraries.launcherclient.StaticInteger

/**
 * Implements [LauncherOverlay] and passes all the corresponding events to [LauncherClient].
 *
 * Implements [LauncherClientCallbacks] and sends all the corresponding callbacks to the launcher.
 */
class OverlayCallbackImpl(private val mLauncher: QuickstepLauncher) :
    LauncherOverlay,
    LauncherClientCallbacks,
    LauncherOverlayManager,
    ISerializableScrollCallback {
    private val mClient: LauncherClient
    private var mLauncherOverlayCallbacks: LauncherOverlayCallbacks? = null
    private var mWasOverlayAttached = false
    private var mFlags = 0

    init {
        val enableFeed = true
        mClient = LauncherClient(
            mLauncher,
            this,
            StaticInteger((if (enableFeed) 1 else 0) or 2 or 4 or 8),
        )
    }

    fun reconnect() {
        mClient.reconnect()
    }

    fun setEnableFeed(enable: Boolean) {
        mClient.setEnableFeed(enable)
        reconnect()
    }

    override fun onDeviceProvideChanged() {
        mClient.redraw()
    }

    override fun onAttachedToWindow() {
        mClient.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        mClient.onDetachedFromWindow()
    }

    override fun openOverlay() {
        mClient.showOverlay(true)
    }

    override fun hideOverlay(animate: Boolean) {
        mClient.hideOverlay(animate)
    }

    override fun hideOverlay(duration: Int) {
        mClient.hideOverlay(duration)
    }

    override fun onActivityCreated(activity: Activity, bundle: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) {
        mClient.onStart()
    }

    override fun onActivityResumed(activity: Activity) {
        mClient.onResume()
    }

    override fun onActivityPaused(activity: Activity) {
        mClient.onPause()
    }

    override fun onActivityStopped(activity: Activity) {
        mClient.onStop()
    }

    override fun onActivitySaveInstanceState(activity: Activity, bundle: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        mClient.onDestroy()
        mClient.mDestroyed = true
    }

    override fun onOverlayScrollChanged(progress: Float) {
        mLauncherOverlayCallbacks?.onOverlayScrollChanged(progress)
    }

    override fun onServiceStateChanged(overlayAttached: Boolean, hotwordActive: Boolean) {
        onServiceStateChanged(overlayAttached)
    }

    override fun onServiceStateChanged(overlayAttached: Boolean) {
        if (overlayAttached != mWasOverlayAttached) {
            mWasOverlayAttached = overlayAttached
            mLauncher.setLauncherOverlay(if (overlayAttached) this else null)
        }
    }

    override fun onScrollInteractionBegin() {
        mClient.startScroll()
    }

    override fun onScrollInteractionEnd() {
        mClient.endScroll()
    }

    override fun onScrollChange(progress: Float, rtl: Boolean) {
        mClient.setScroll(progress)
    }

    override fun setOverlayCallbacks(callbacks: LauncherOverlayCallbacks?) {
        mLauncherOverlayCallbacks = callbacks
    }

    override fun setPersistentFlags(flags: Int) {
        val newFlags = flags and (8 or 16)
        if (newFlags != mFlags) {
            mFlags = newFlags
            LauncherPrefs.get(mLauncher).devicePrefs.edit().putInt(PREF_PERSIST_FLAGS, newFlags).apply()
        }
    }

    companion object {
        private const val PREF_PERSIST_FLAGS = "pref_persistent_flags"
    }
}
