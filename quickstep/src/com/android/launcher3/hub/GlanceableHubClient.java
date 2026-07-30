/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher3.hub;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import com.android.systemui.shared.communal.GlanceableHubWidget;
import com.android.systemui.shared.communal.IGlanceableHubOverlay;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Connection to SystemUI's {@code GlanceableHubOverlayService}.
 *
 * <p>The hub's app widget ids belong to SystemUI's {@code AppWidgetHost} and cannot be adopted by
 * Launcher — {@code AppWidgetServiceImpl} keys hosts by calling uid and package. This client
 * receives the widget list and each widget's {@code RemoteViews} instead, which
 * {@link GlanceableHubWidgetView} inflates locally.
 *
 * <p>All callbacks are delivered on the main thread.
 */
public class GlanceableHubClient {

    private static final String TAG = "GlanceableHubClient";

    private static final String ACTION_GLANCEABLE_HUB_OVERLAY =
            "com.android.systemui.action.GLANCEABLE_HUB_OVERLAY";

    /** Receives hub state. Always called on the main thread. */
    public interface Callback {
        /** Called when the connection to SystemUI comes up or goes down. */
        void onConnectedChanged(boolean connected);

        /** Called with the hub's widgets, ordered by rank. */
        void onWidgetsChanged(List<GlanceableHubWidget> widgets);
    }

    private final Context mContext;
    private final Callback mCallback;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    /**
     * Host listeners registered while disconnected, replayed once the service comes back. Keyed by
     * widget id; a widget only ever has one view in the overlay.
     */
    private final List<PendingListener> mPendingListeners = new ArrayList<>();

    @Nullable private IGlanceableHubOverlay mService;
    private boolean mBindRequested;
    private boolean mUnavailable;

    private final IGlanceableHubOverlay.IWidgetsListener mWidgetsListener =
            new IGlanceableHubOverlay.IWidgetsListener.Stub() {
                @Override
                public void onWidgetsUpdated(List<GlanceableHubWidget> widgets) {
                    List<GlanceableHubWidget> copy =
                            widgets == null ? Collections.emptyList() : new ArrayList<>(widgets);
                    copy.sort((a, b) -> Integer.compare(a.getRank(), b.getRank()));
                    mMainHandler.post(() -> mCallback.onWidgetsChanged(copy));
                }
            };

    private final ServiceConnection mConnection =
            new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder binder) {
                    mMainHandler.post(() -> handleConnected(binder));
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    mMainHandler.post(() -> handleDisconnected());
                }

                @Override
                public void onNullBinding(ComponentName name) {
                    // SystemUI returns null when the hub is disabled for this user.
                    mMainHandler.post(
                            () -> {
                                mUnavailable = true;
                                handleDisconnected();
                            });
                }
            };

    public GlanceableHubClient(Context context, Callback callback) {
        mContext = context;
        mCallback = callback;
    }

    /** Whether SystemUI exposes the hub overlay service at all on this build. */
    public boolean isSupported() {
        return resolveService() != null;
    }

    /** Binds to SystemUI. No-op if already bound. */
    @MainThread
    public void connect() {
        if (mBindRequested || mUnavailable) {
            return;
        }
        ComponentName component = resolveService();
        if (component == null) {
            mUnavailable = true;
            return;
        }
        Intent intent = new Intent(ACTION_GLANCEABLE_HUB_OVERLAY).setComponent(component);
        try {
            mBindRequested = mContext.bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
        } catch (SecurityException e) {
            Log.e(TAG, "Not allowed to bind to the glanceable hub overlay service", e);
            mUnavailable = true;
            mBindRequested = false;
        }
        if (!mBindRequested) {
            Log.w(TAG, "Could not bind to the glanceable hub overlay service");
        }
    }

    /** Unbinds from SystemUI, releasing SystemUI's app widget host lease. */
    @MainThread
    public void disconnect() {
        if (!mBindRequested) {
            return;
        }
        removeWidgetsListener();
        mBindRequested = false;
        mService = null;
        try {
            mContext.unbindService(mConnection);
        } catch (IllegalArgumentException e) {
            // Already unbound.
        }
        mCallback.onConnectedChanged(false);
    }

    /** Whether the service is currently connected. */
    public boolean isConnected() {
        return mService != null;
    }

    /**
     * Starts streaming {@code RemoteViews} for a widget. The listener is called back with the
     * widget's current views, and re-registered automatically if the connection drops.
     */
    @MainThread
    public void setWidgetHostListener(
            int appWidgetId, IGlanceableHubOverlay.IWidgetHostListener listener) {
        mPendingListeners.removeIf(pending -> pending.mAppWidgetId == appWidgetId);
        mPendingListeners.add(new PendingListener(appWidgetId, listener));
        if (mService != null) {
            try {
                mService.setWidgetHostListener(appWidgetId, listener);
            } catch (RemoteException e) {
                Log.e(TAG, "Could not set host listener for widget " + appWidgetId, e);
            }
        }
    }

    /** Stops streaming views for a widget. */
    @MainThread
    public void removeWidgetHostListener(
            int appWidgetId, IGlanceableHubOverlay.IWidgetHostListener listener) {
        mPendingListeners.removeIf(pending -> pending.mAppWidgetId == appWidgetId);
        if (mService != null) {
            try {
                mService.removeWidgetHostListener(appWidgetId, listener);
            } catch (RemoteException e) {
                Log.e(TAG, "Could not remove host listener for widget " + appWidgetId, e);
            }
        }
    }

    /**
     * Reports the size a widget is being rendered at. This has to go through SystemUI:
     * {@code AppWidgetManager#updateAppWidgetOptions} silently ignores callers that do not own the
     * widget, so calling {@code AppWidgetHostView#updateAppWidgetSize} here would do nothing.
     */
    @MainThread
    public void updateWidgetSize(
            int appWidgetId, int minWidthDp, int minHeightDp, int maxWidthDp, int maxHeightDp) {
        if (mService == null) {
            return;
        }
        try {
            mService.updateWidgetSize(
                    appWidgetId, minWidthDp, minHeightDp, maxWidthDp, maxHeightDp);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not update size of widget " + appWidgetId, e);
        }
    }

    /** Asks SystemUI to open the hub's widget editor. */
    @MainThread
    public void startEditMode() {
        if (mService == null) {
            return;
        }
        try {
            mService.startEditMode();
        } catch (RemoteException e) {
            Log.e(TAG, "Could not start hub edit mode", e);
        }
    }

    /** @see com.android.launcher3.Launcher#dump */
    public void dump(String prefix, PrintWriter w) {
        w.println(prefix + "GlanceableHubClient:");
        w.println(prefix + "\tbindRequested=" + mBindRequested);
        w.println(prefix + "\tconnected=" + (mService != null));
        w.println(prefix + "\tunavailable=" + mUnavailable);
        w.println(prefix + "\ttrackedWidgets=" + mPendingListeners.size());
    }

    @MainThread
    private void handleConnected(IBinder binder) {
        if (!mBindRequested) {
            // Disconnected while the callback was in flight.
            return;
        }
        mService = IGlanceableHubOverlay.Stub.asInterface(binder);
        try {
            mService.addWidgetsListener(mWidgetsListener);
            for (PendingListener pending : mPendingListeners) {
                mService.setWidgetHostListener(pending.mAppWidgetId, pending.mListener);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Could not register with the glanceable hub overlay service", e);
            mService = null;
            return;
        }
        mCallback.onConnectedChanged(true);
    }

    @MainThread
    private void handleDisconnected() {
        if (mService == null) {
            return;
        }
        mService = null;
        // Listeners stay in mPendingListeners so they are replayed on reconnect.
        mCallback.onConnectedChanged(false);
    }

    @MainThread
    private void removeWidgetsListener() {
        if (mService == null) {
            return;
        }
        try {
            mService.removeWidgetsListener(mWidgetsListener);
        } catch (RemoteException e) {
            // The service is going away anyway.
        }
    }

    @Nullable
    private ComponentName resolveService() {
        Intent intent = new Intent(ACTION_GLANCEABLE_HUB_OVERLAY);
        ResolveInfo info =
                mContext.getPackageManager()
                        .resolveService(intent, PackageManager.MATCH_SYSTEM_ONLY);
        if (info == null || info.serviceInfo == null) {
            return null;
        }
        return new ComponentName(info.serviceInfo.packageName, info.serviceInfo.name);
    }

    private static class PendingListener {
        final int mAppWidgetId;
        final IGlanceableHubOverlay.IWidgetHostListener mListener;

        PendingListener(int appWidgetId, IGlanceableHubOverlay.IWidgetHostListener listener) {
            mAppWidgetId = appWidgetId;
            mListener = listener;
        }
    }
}
