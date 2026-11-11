package dev.local.xvideosave;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Button;

/** Drag within the app window and remember the relative position across rotations. */
final class FloatingButtonPosition implements View.OnTouchListener {
    private final ViewGroup root;
    private final Button button;
    private final SharedPreferences preferences;
    private final int slop;
    private final int edge;
    private float downX, downY;
    private int startX, startY;
    private boolean dragging;
    private boolean longPressed;
    private final Runnable longPress;
    private int lastWidth, lastHeight;

    FloatingButtonPosition(Context context, ViewGroup root, Button button) {
        this.root = root;
        this.button = button;
        longPress = () -> {
            if (!dragging && this.button.isAttachedToWindow()) {
                longPressed = true;
                this.button.performLongClick();
            }
        };
        preferences = context.getSharedPreferences("xvideosave_position", Context.MODE_PRIVATE);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        edge = Math.round(24 * context.getResources().getDisplayMetrics().density);
    }

    void install() {
        button.setOnTouchListener(this);
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            if (root.getWidth() != lastWidth || root.getHeight() != lastHeight) restore();
        });
        button.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            if (lastWidth == 0 || r-l != or-ol || b-t != ob-ot) restore();
        });
        root.post(this::restore);
    }

    private int maxX() { return Math.max(0, root.getWidth() - button.getWidth()); }
    private int minY() { return Math.min(edge, maxY()); }
    private int maxY() { return Math.max(0, root.getHeight() - button.getHeight() - edge); }
    private int clamp(int n, int min, int max) { return Math.max(min, Math.min(max, n)); }

    private void move(int x, int y) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) button.getLayoutParams();
        x = clamp(x, 0, maxX());
        y = clamp(y, minY(), maxY());
        if (lp.gravity != (Gravity.TOP | Gravity.LEFT) || lp.leftMargin != x || lp.topMargin != y) {
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.leftMargin = x;
            lp.topMargin = y;
            button.setLayoutParams(lp);
        }
    }

    private void restore() {
        if (root.getWidth() <= 0 || root.getHeight() <= 0 || button.getWidth() <= 0) return;
        lastWidth = root.getWidth();
        lastHeight = root.getHeight();
        float x = preferences.getFloat("x", 1f);
        float y = preferences.getFloat("y", .5f);
        move(Math.round(x * maxX()), Math.round(minY() + y * (maxY() - minY())));
    }

    @Override public boolean onTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getRawX(); downY = event.getRawY();
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) button.getLayoutParams();
                startX = lp.leftMargin; startY = lp.topMargin; dragging = false;
                longPressed = false;
                button.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                root.requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                if (!dragging && !longPressed && Math.hypot(dx, dy) > slop) {
                    dragging = true;
                    button.removeCallbacks(longPress);
                }
                if (dragging) move(startX + Math.round(dx), startY + Math.round(dy));
                return true;
            case MotionEvent.ACTION_UP:
                root.requestDisallowInterceptTouchEvent(false);
                button.removeCallbacks(longPress);
                if (longPressed) return true;
                if (!dragging) { view.performClick(); return true; }
                FrameLayout.LayoutParams end = (FrameLayout.LayoutParams) button.getLayoutParams();
                preferences.edit().putFloat("x", maxX() == 0 ? 0 : (float) end.leftMargin / maxX())
                    .putFloat("y", maxY() == minY() ? 0 : (float) (end.topMargin - minY()) / (maxY() - minY())).apply();
                return true;
            case MotionEvent.ACTION_CANCEL:
                root.requestDisallowInterceptTouchEvent(false);
                button.removeCallbacks(longPress);
                if (dragging) restore();
                return true;
            default: return true;
        }
    }
}
