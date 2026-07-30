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

import android.content.Context;
import android.graphics.Outline;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.annotation.Nullable;

import com.android.launcher3.R;
import com.android.systemui.shared.communal.GlanceableHubWidget;

import java.util.List;

/**
 * The "-1" page: a vertical column of the glanceable hub's widgets.
 *
 * <p>The hub itself lays widgets out on a six row grid, so each widget's {@code spanY} is
 * interpreted against a row height of one sixth of this panel — a full span widget fills the page,
 * matching what the same widget looks like on the hub.
 *
 * <p>Widget views are keyed by app widget id and reused across list updates so that a reorder or an
 * unrelated widget being added does not tear down and re-register every other widget.
 */
public class GlanceableHubPanel extends FrameLayout {

    /** Rows in the hub's grid. A widget with this span fills the page. */
    private static final int GRID_ROWS = 6;

    /** Notified about gestures on the panel that the overlay has to act on. */
    public interface DismissListener {
        /** A horizontal drag that should close the panel has started. */
        void onDismissDragStart();

        /** The panel has been dragged by {@code progress}, where 1 is fully open. */
        void onDismissDragProgress(float progress);

        /** The drag ended; settle open or closed. */
        void onDismissDragEnd(float velocityPxPerSecond);

        /** The user long pressed empty space and wants the hub's widget editor. */
        void onEditRequested();
    }

    private final ScrollView mScrollView;
    private final LinearLayout mColumn;
    private final SparseArray<View> mWidgetViews = new SparseArray<>();
    private final int mTouchSlop;
    private final int mGap;
    private final int mPadding;
    private final float mCornerRadius;

    @Nullable private GlanceableHubClient mClient;
    @Nullable private DismissListener mDismissListener;

    private float mDownX;
    private float mDownY;
    private boolean mDismissing;
    private long mDragStartTime;

    public GlanceableHubPanel(Context context) {
        super(context);

        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mGap = getResources().getDimensionPixelSize(R.dimen.glanceable_hub_widget_gap);
        mPadding = getResources().getDimensionPixelSize(R.dimen.glanceable_hub_padding);
        mCornerRadius = getResources().getDimension(R.dimen.glanceable_hub_widget_corner_radius);

        setBackgroundResource(R.color.glanceable_hub_scrim);

        mColumn = new LinearLayout(context);
        mColumn.setOrientation(LinearLayout.VERTICAL);
        mColumn.setClipChildren(false);

        mScrollView = new ScrollView(context);
        mScrollView.setFillViewport(true);
        mScrollView.setClipToPadding(false);
        mScrollView.setPadding(mPadding, mPadding, mPadding, mPadding);
        mScrollView.addView(
                mColumn,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(
                mScrollView,
                new LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Only fires when no widget consumed the long press, i.e. on empty space, mirroring how
        // the hub itself enters edit mode.
        setOnLongClickListener(
                v -> {
                    if (mDismissListener == null) {
                        return false;
                    }
                    mDismissListener.onEditRequested();
                    return true;
                });
    }

    /** Sets the connection used to render widgets. Must be called before {@link #setWidgets}. */
    public void setClient(GlanceableHubClient client) {
        mClient = client;
    }

    public void setDismissListener(DismissListener listener) {
        mDismissListener = listener;
    }

    /** Rebuilds the column from {@code widgets}, which must already be sorted by rank. */
    public void setWidgets(List<GlanceableHubWidget> widgets) {
        if (mClient == null) {
            return;
        }

        SparseArray<View> reusable = mWidgetViews.clone();
        mWidgetViews.clear();
        mColumn.removeAllViews();

        for (GlanceableHubWidget widget : widgets) {
            int id = widget.getAppWidgetId();
            View view = reusable.get(id);
            reusable.remove(id);

            if (view == null || !matchesState(view, widget)) {
                view = createWidgetView(widget);
            }

            mWidgetViews.put(id, view);
            mColumn.addView(view, buildLayoutParams(widget, view));
        }

        // The trailing gap would push a full span widget past the fold, so drop it.
        int last = mColumn.getChildCount() - 1;
        if (last >= 0) {
            ((LinearLayout.LayoutParams) mColumn.getChildAt(last).getLayoutParams()).bottomMargin =
                    0;
        }

        // Anything left in `reusable` is gone from the hub; dropping the reference is enough,
        // GlanceableHubWidgetView unregisters itself in onDetachedFromWindow.
    }

    /** Height in pixels of one hub grid row, given the panel's current height. */
    private int rowHeight() {
        int usable = getHeight() - 2 * mPadding - (GRID_ROWS - 1) * mGap;
        if (usable <= 0) {
            // Laid out before measurement; fall back to a sane row so views are not zero height.
            usable =
                    getResources().getDisplayMetrics().heightPixels
                            - 2 * mPadding
                            - (GRID_ROWS - 1) * mGap;
        }
        return Math.max(1, usable / GRID_ROWS);
    }

    private LinearLayout.LayoutParams buildLayoutParams(GlanceableHubWidget widget, View view) {
        int span = Math.max(1, Math.min(GRID_ROWS, widget.getSpanY()));
        view.setTag(R.id.glanceable_hub_widget_span, span);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, heightForSpan(span));
        lp.bottomMargin = mGap;
        return lp;
    }

    private int heightForSpan(int span) {
        return span * rowHeight() + (span - 1) * mGap;
    }

    private View createWidgetView(GlanceableHubWidget widget) {
        View view;
        if (widget.getState() == GlanceableHubWidget.STATE_AVAILABLE
                && widget.getProviderInfo() != null) {
            view = new GlanceableHubWidgetView(getContext(), mClient, widget);
        } else {
            view = createPendingView(widget);
        }
        view.setTag(R.id.glanceable_hub_widget_state, widget.getState());
        view.setClipToOutline(true);
        view.setOutlineProvider(
                new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View v, Outline outline) {
                        outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), mCornerRadius);
                    }
                });
        return view;
    }

    /** Placeholder shown while a widget's app is still installing. */
    private View createPendingView(GlanceableHubWidget widget) {
        FrameLayout container = new FrameLayout(getContext());
        container.setBackgroundResource(R.color.glanceable_hub_pending_widget_background);
        if (widget.getIcon() != null) {
            ImageView icon = new ImageView(getContext());
            icon.setImageBitmap(widget.getIcon());
            int size =
                    getResources()
                            .getDimensionPixelSize(R.dimen.glanceable_hub_pending_widget_icon_size);
            LayoutParams lp = new LayoutParams(size, size);
            lp.gravity = android.view.Gravity.CENTER;
            container.addView(icon, lp);
        }
        return container;
    }

    private boolean matchesState(View view, GlanceableHubWidget widget) {
        Object state = view.getTag(R.id.glanceable_hub_widget_state);
        return state instanceof Integer && (Integer) state == widget.getState();
    }

    /** Opens the hub's widget editor. */
    public void startEditMode() {
        if (mClient != null) {
            mClient.startEditMode();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (h == oldh) {
            return;
        }
        // Row height is derived from the panel height, so every child needs a new height.
        for (int i = 0; i < mColumn.getChildCount(); i++) {
            View child = mColumn.getChildAt(i);
            Object span = child.getTag(R.id.glanceable_hub_widget_span);
            if (span instanceof Integer) {
                ViewGroup.LayoutParams lp = child.getLayoutParams();
                lp.height = heightForSpan((Integer) span);
                child.setLayoutParams(lp);
            }
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = ev.getX();
                mDownY = ev.getY();
                mDismissing = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!mDismissing && mDismissListener != null) {
                    float dx = ev.getX() - mDownX;
                    float dy = ev.getY() - mDownY;
                    // Only a horizontal drag towards the workspace closes the panel; vertical
                    // drags belong to the widget list.
                    if (isClosingDirection(dx)
                            && Math.abs(dx) > mTouchSlop
                            && Math.abs(dx) > Math.abs(dy)) {
                        mDismissing = true;
                        mDragStartTime = ev.getEventTime();
                        mDismissListener.onDismissDragStart();
                        return true;
                    }
                }
                break;
            default:
                break;
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!mDismissing || mDismissListener == null) {
            return super.onTouchEvent(ev);
        }
        float dx = ev.getX() - mDownX;
        float travel = getWidth() == 0 ? 0 : Math.abs(dx) / getWidth();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                mDismissListener.onDismissDragProgress(Math.max(0f, 1f - travel));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                long elapsed = Math.max(1, ev.getEventTime() - mDragStartTime);
                float velocity = dx / elapsed * 1000f;
                mDismissing = false;
                mDismissListener.onDismissDragEnd(velocity);
                return true;
            default:
                return true;
        }
    }

    private boolean isClosingDirection(float dx) {
        // The panel sits to the start of the workspace, so it closes in the reverse of the
        // direction that opened it.
        return getLayoutDirection() == LAYOUT_DIRECTION_RTL ? dx > 0 : dx < 0;
    }
}
