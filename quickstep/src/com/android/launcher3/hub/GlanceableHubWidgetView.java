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

import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.widget.RemoteViews;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import com.android.systemui.shared.communal.GlanceableHubWidget;
import com.android.systemui.shared.communal.IGlanceableHubOverlay;

/**
 * Renders one glanceable hub widget inside Launcher's "-1" page.
 *
 * <p>This is an {@link AppWidgetHostView} with no host behind it. The widget id belongs to
 * SystemUI, so Launcher can neither listen to it nor mutate it; instead
 * {@link GlanceableHubClient} streams the provider info and {@link RemoteViews} across, and this
 * view applies them. Size changes are reported back to SystemUI, which owns the widget and is the
 * only process allowed to tell the provider about them.
 */
public class GlanceableHubWidgetView extends AppWidgetHostView {

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final GlanceableHubClient mClient;
    private final int mAppWidgetId;

    private int mLastReportedWidthDp = -1;
    private int mLastReportedHeightDp = -1;
    private boolean mListening;

    private final IGlanceableHubOverlay.IWidgetHostListener mListener =
            new IGlanceableHubOverlay.IWidgetHostListener.Stub() {
                @Override
                public void onUpdateProviderInfo(@Nullable AppWidgetProviderInfo appWidget) {
                    mMainHandler.post(
                            () -> {
                                if (appWidget != null) {
                                    setAppWidget(mAppWidgetId, appWidget);
                                }
                            });
                }

                @Override
                public void updateAppWidget(@Nullable RemoteViews views) {
                    // Never a deferred placeholder: SystemUI resolves those before sending, since
                    // AppWidgetService#getAppWidgetViews is uid checked and would reject us.
                    mMainHandler.post(() -> GlanceableHubWidgetView.super.updateAppWidget(views));
                }

                @Override
                public void onViewDataChanged(int viewId) {
                    // Qualified: the enclosing view implements AppWidgetHostListener too, so an
                    // unqualified call would recurse into this stub.
                    mMainHandler.post(
                            () -> GlanceableHubWidgetView.super.onViewDataChanged(viewId));
                }
            };

    public GlanceableHubWidgetView(
            Context context, GlanceableHubClient client, GlanceableHubWidget widget) {
        super(context);
        mClient = client;
        mAppWidgetId = widget.getAppWidgetId();
        setAppWidget(mAppWidgetId, widget.getProviderInfo());
    }

    /** The SystemUI-owned widget id this view renders. */
    public int getAppWidgetId() {
        return mAppWidgetId;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!mListening) {
            mListening = true;
            mClient.setWidgetHostListener(mAppWidgetId, mListener);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mListening) {
            mListening = false;
            mClient.removeWidgetHostListener(mAppWidgetId, mListener);
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        reportSize(w, h);
    }

    /**
     * Tells SystemUI the size we are rendering at so it can forward it to the provider.
     *
     * <p>{@link AppWidgetHostView#updateAppWidgetSize} is deliberately not used: it calls
     * {@code AppWidgetManager#updateAppWidgetOptions}, which checks that the caller owns the
     * widget and silently drops the update otherwise.
     */
    @MainThread
    private void reportSize(int widthPx, int heightPx) {
        if (widthPx <= 0 || heightPx <= 0) {
            return;
        }
        int widthDp = pxToDp(widthPx);
        int heightDp = pxToDp(heightPx);
        if (widthDp == mLastReportedWidthDp && heightDp == mLastReportedHeightDp) {
            return;
        }
        mLastReportedWidthDp = widthDp;
        mLastReportedHeightDp = heightDp;
        mClient.updateWidgetSize(mAppWidgetId, widthDp, heightDp, widthDp, heightDp);
    }

    private int pxToDp(int px) {
        return (int)
                (px
                        / TypedValue.applyDimension(
                                TypedValue.COMPLEX_UNIT_DIP, 1f, getResources().getDisplayMetrics()));
    }
}
