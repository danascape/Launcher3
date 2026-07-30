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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;

import androidx.annotation.Nullable;

import com.android.app.animation.Interpolators;
import com.android.launcher3.Launcher;
import com.android.launcher3.Utilities;
import com.android.systemui.plugins.shared.LauncherOverlayManager;
import com.android.systemui.shared.communal.GlanceableHubWidget;

import java.io.PrintWriter;
import java.util.Collections;
import java.util.List;

/**
 * Puts the glanceable hub on Launcher's "-1" page.
 *
 * <p>Replaces the Google feed overlay, which is unusable here: its {@code LauncherClient} binds a
 * service in a hardcoded package. The contract Launcher actually depends on is
 * {@link LauncherOverlayManager}, so this implements that directly and renders the panel in
 * Launcher's own window rather than attaching a second window from another process.
 *
 * <p>Widgets stay owned by SystemUI — see {@link GlanceableHubClient} for why they cannot be
 * handed over — so the panel only appears once the overlay service is connected.
 */
public class GlanceableHubOverlay
        implements LauncherOverlayManager,
                LauncherOverlayManager.LauncherOverlay,
                GlanceableHubClient.Callback,
                GlanceableHubPanel.DismissListener {

    private static final int SETTLE_DURATION_MS = 300;

    /** A fling faster than this settles in the fling's direction regardless of position. */
    private static final float FLING_THRESHOLD_PX_PER_S = 1000f;

    private final Launcher mLauncher;
    private final GlanceableHubClient mClient;
    private final GlanceableHubPanel mPanel;
    private final boolean mIsRtl;

    @Nullable private LauncherOverlayCallbacks mOverlayCallbacks;
    @Nullable private ValueAnimator mSettleAnimator;

    private boolean mAttachedToWindow;
    private boolean mOverlayAttached;
    private float mProgress;
    private float mFlingVelocity;

    public GlanceableHubOverlay(Launcher launcher) {
        mLauncher = launcher;
        mIsRtl = Utilities.isRtl(launcher.getResources());
        mClient = new GlanceableHubClient(launcher, this);
        mPanel = new GlanceableHubPanel(launcher);
        mPanel.setClient(mClient);
        mPanel.setDismissListener(this);
        // INVISIBLE rather than GONE: a GONE child is never laid out, so the panel would have no
        // width to translate by on the first frame of the gesture.
        mPanel.setVisibility(View.INVISIBLE);
    }

    /** Whether SystemUI on this build exposes the hub to us at all. */
    public boolean isSupported() {
        return mClient.isSupported();
    }

    // region LauncherOverlayManager

    @Override
    public void onAttachedToWindow() {
        if (mAttachedToWindow) {
            return;
        }
        mAttachedToWindow = true;
        addPanelToDragLayer();
        mClient.connect();
    }

    @Override
    public void onDetachedFromWindow() {
        if (!mAttachedToWindow) {
            return;
        }
        mAttachedToWindow = false;
        mClient.disconnect();
        removePanelFromDragLayer();
    }

    @Override
    public void onActivityDestroyed() {
        onDetachedFromWindow();
    }

    @Override
    public void onDeviceProvideChanged() {
        // Row heights are derived from the panel size, which the new layout will drive.
        mPanel.requestLayout();
    }

    @Override
    public void openOverlay() {
        if (!mOverlayAttached) {
            return;
        }
        animateProgressTo(1f, SETTLE_DURATION_MS);
    }

    @Override
    public void hideOverlay(boolean animate) {
        hideOverlay(animate ? SETTLE_DURATION_MS : 0);
    }

    @Override
    public void hideOverlay(int duration) {
        animateProgressTo(0f, duration);
    }

    @Override
    public void dump(String prefix, PrintWriter w) {
        w.println(prefix + "GlanceableHubOverlay:");
        w.println(prefix + "\tattachedToWindow=" + mAttachedToWindow);
        w.println(prefix + "\toverlayAttached=" + mOverlayAttached);
        w.println(prefix + "\tprogress=" + mProgress);
        mClient.dump(prefix + "\t", w);
    }

    // endregion

    // region LauncherOverlay

    @Override
    public void onScrollInteractionBegin() {
        cancelSettle();
        mFlingVelocity = 0f;
        mPanel.setVisibility(View.VISIBLE);
    }

    @Override
    public void onScrollChange(float progress, boolean rtl) {
        setProgress(progress);
    }

    @Override
    public void onScrollInteractionEnd() {
        settle();
    }

    @Override
    public void onFlingVelocity(float velocity) {
        mFlingVelocity = velocity;
    }

    @Override
    public void setOverlayCallbacks(@Nullable LauncherOverlayCallbacks callbacks) {
        mOverlayCallbacks = callbacks;
    }

    // endregion

    // region GlanceableHubClient.Callback

    @Override
    public void onConnectedChanged(boolean connected) {
        if (connected == mOverlayAttached) {
            return;
        }
        mOverlayAttached = connected;
        // Handing Launcher a null overlay removes the "-1" page entirely, which is what we want
        // when SystemUI is not there to feed it.
        mLauncher.setLauncherOverlay(connected ? this : null);
        if (!connected) {
            setProgress(0f);
            mPanel.setWidgets(Collections.emptyList());
        }
    }

    @Override
    public void onWidgetsChanged(List<GlanceableHubWidget> widgets) {
        mPanel.setWidgets(widgets);
    }

    // endregion

    // region GlanceableHubPanel.DismissListener

    @Override
    public void onDismissDragStart() {
        cancelSettle();
        mFlingVelocity = 0f;
    }

    @Override
    public void onDismissDragProgress(float progress) {
        setProgress(progress);
    }

    @Override
    public void onEditRequested() {
        // SystemUI puts the editor on top of Launcher, so get the panel out of the way first.
        mPanel.startEditMode();
        hideOverlay(true);
    }

    @Override
    public void onDismissDragEnd(float velocityPxPerSecond) {
        // A drag that closes the panel moves towards the workspace, so invert it into the same
        // sign convention the overscroll gesture uses.
        mFlingVelocity = mIsRtl ? -velocityPxPerSecond : velocityPxPerSecond;
        settle();
    }

    // endregion

    private void addPanelToDragLayer() {
        ViewGroup parent = mLauncher.getDragLayer();
        if (mPanel.getParent() == parent) {
            return;
        }
        removePanelFromDragLayer();
        parent.addView(
                mPanel,
                new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // INVISIBLE rather than GONE: a GONE child is never laid out, so the panel would have no
        // width to translate by on the first frame of the gesture.
        mPanel.setVisibility(View.INVISIBLE);
    }

    private void removePanelFromDragLayer() {
        ViewGroup parent = (ViewGroup) mPanel.getParent();
        if (parent != null) {
            parent.removeView(mPanel);
        }
    }

    private void setProgress(float progress) {
        mProgress = Utilities.boundToRange(progress, 0f, 1f);

        int width = mPanel.getWidth() > 0 ? mPanel.getWidth() : mLauncher.getDeviceProfile().widthPx;
        // The panel lives to the start of the workspace and slides in as progress goes to 1.
        float offscreen = mIsRtl ? width : -width;
        mPanel.setTranslationX(offscreen * (1f - mProgress));
        mPanel.setVisibility(mProgress == 0f ? View.INVISIBLE : View.VISIBLE);

        if (mOverlayCallbacks != null) {
            mOverlayCallbacks.onOverlayScrollChanged(mProgress);
        }
    }

    private void settle() {
        boolean open;
        if (Math.abs(mFlingVelocity) > FLING_THRESHOLD_PX_PER_S) {
            open = mFlingVelocity > 0;
        } else {
            open = mProgress > 0.5f;
        }
        animateProgressTo(open ? 1f : 0f, SETTLE_DURATION_MS);
    }

    private void animateProgressTo(float target, int duration) {
        cancelSettle();
        if (duration <= 0 || mProgress == target) {
            setProgress(target);
            return;
        }

        Interpolator interpolator = Interpolators.DECELERATE_2;
        ValueAnimator animator = ValueAnimator.ofFloat(mProgress, target);
        animator.setDuration(duration);
        animator.setInterpolator(interpolator);
        animator.addUpdateListener(a -> setProgress((float) a.getAnimatedValue()));
        animator.addListener(
                new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (mSettleAnimator == animation) {
                            mSettleAnimator = null;
                            setProgress(target);
                        }
                    }
                });
        mSettleAnimator = animator;
        animator.start();
    }

    private void cancelSettle() {
        if (mSettleAnimator != null) {
            ValueAnimator animator = mSettleAnimator;
            mSettleAnimator = null;
            animator.cancel();
        }
    }
}
