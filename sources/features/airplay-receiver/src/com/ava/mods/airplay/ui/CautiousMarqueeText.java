package com.ava.mods.airplay.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * View-port of Ava host {@code CautiousMarqueeAnnotatedText}.
 * <p>
 * Critical: the scrolling row must measure children with <b>UNSPECIFIED</b> width.
 * A normal horizontal {@link LinearLayout} passes {@code AT_MOST(parent)}, which
 * clamps each {@link TextView} to the viewport and visually "pinches" glyphs on
 * both edges — the original clip bug.
 */
public final class CautiousMarqueeText extends FrameLayout {

    private static final int INITIAL_DELAY_MS = 2_000;
    /** Gap between tail of copy #1 and head of copy #2 while scrolling. */
    private static final int SPACING_DP = 50;
    private static final float VELOCITY_DP_PER_SEC = 24f;

    private final UnboundedRow row;
    private final TextView primary;
    private final View spacer;
    private final TextView duplicate;

    private ValueAnimator animator;
    private boolean marqueeActive;
    private int restGravity = Gravity.CENTER_HORIZONTAL;
    private CharSequence currentText = "";
    private String runKey = "";

    public CautiousMarqueeText(Context context) {
        super(context);
        // Clip at padding edges (caller sets ≥50dp L/R) so glyphs are not
        // cut at the screen bezel; allow children to draw wider than us.
        setClipChildren(true);
        setClipToPadding(true);

        row = new UnboundedRow(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(false);
        row.setFocusable(false);

        primary = makeLabel(context);
        duplicate = makeLabel(context);
        duplicate.setVisibility(GONE);

        spacer = new View(context);
        spacer.setVisibility(GONE);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(dp(SPACING_DP), 1));

        row.addView(primary, wrap());
        row.addView(spacer);
        row.addView(duplicate, wrap());

        addView(row, new LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL | Gravity.START));
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static TextView makeLabel(Context context) {
        TextView tv = new TextView(context);
        tv.setSingleLine(true);
        tv.setMaxLines(1);
        tv.setHorizontallyScrolling(true);
        tv.setEllipsize(null);
        tv.setIncludeFontPadding(false);
        // Never let framework cap our measured width to the parent.
        tv.setHorizontallyScrolling(true);
        return tv;
    }

    public void setText(CharSequence text) {
        CharSequence next = text != null ? text : "";
        if (TextUtils.equals(currentText, next)) return;
        currentText = next;
        primary.setText(currentText);
        duplicate.setText(currentText);
        runKey = "";
        requestRelayoutMarquee();
    }

    public CharSequence getText() {
        return currentText;
    }

    public void setTextColor(int color) {
        primary.setTextColor(color);
        duplicate.setTextColor(color);
    }

    public void setTextSize(int unit, float size) {
        primary.setTextSize(unit, size);
        duplicate.setTextSize(unit, size);
        runKey = "";
        requestRelayoutMarquee();
    }

    public void setTypeface(Typeface tf) {
        primary.setTypeface(tf);
        duplicate.setTypeface(tf);
        runKey = "";
        requestRelayoutMarquee();
    }

    public void setShadowLayer(float radius, float dx, float dy, int color) {
        primary.setShadowLayer(radius, dx, dy, color);
        duplicate.setShadowLayer(radius, dx, dy, color);
    }

    public void setLetterSpacing(float spacing) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;
        primary.setLetterSpacing(spacing);
        duplicate.setLetterSpacing(spacing);
        runKey = "";
        requestRelayoutMarquee();
    }

    public void setAllCaps(boolean allCaps) {
        primary.setAllCaps(allCaps);
        duplicate.setAllCaps(allCaps);
        runKey = "";
        requestRelayoutMarquee();
    }

    /** Gravity used when the line fits (centered chrome: Center). */
    public void setRestGravity(int gravity) {
        restGravity = gravity;
        if (!marqueeActive) applyRestLayout();
    }

    public void setContentPadding(int left, int top, int right, int bottom) {
        setPadding(left, top, right, bottom);
        runKey = "";
        requestRelayoutMarquee();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw) {
            runKey = "";
            requestRelayoutMarquee();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        requestRelayoutMarquee();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopMarquee(true);
        marqueeActive = false;
        runKey = "";
        super.onDetachedFromWindow();
    }

    private void requestRelayoutMarquee() {
        post(new Runnable() {
            @Override
            public void run() {
                applyMarqueeState();
            }
        });
    }

    private void applyMarqueeState() {
        int avail = getWidth() - getPaddingLeft() - getPaddingRight();
        if (avail <= 0 || TextUtils.isEmpty(currentText)) {
            showStatic();
            return;
        }

        // True intrinsic width (must not be AT_MOST-constrained).
        primary.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int textW = primary.getMeasuredWidth();
        boolean shouldMarquee = textW > avail;

        if (!shouldMarquee) {
            showStatic();
            return;
        }

        showOverflowLayout();
        String key = currentText + "|" + avail + "|" + textW;
        if (key.equals(runKey) && animator != null && animator.isRunning()) {
            return;
        }
        runKey = key;
        marqueeActive = true;
        startMarquee(textW);
    }

    private void showStatic() {
        stopMarquee(true);
        marqueeActive = false;
        runKey = "";
        spacer.setVisibility(GONE);
        duplicate.setVisibility(GONE);
        primary.setEllipsize(TextUtils.TruncateAt.END);
        applyRestLayout();
    }

    private void applyRestLayout() {
        primary.setGravity(restGravity);
        LinearLayout.LayoutParams primaryLp =
                (LinearLayout.LayoutParams) primary.getLayoutParams();
        if (primaryLp != null) {
            primaryLp.width = LinearLayout.LayoutParams.MATCH_PARENT;
            primary.setLayoutParams(primaryLp);
        }
        row.setTranslationX(0f);
        LayoutParams lp = (LayoutParams) row.getLayoutParams();
        if (lp != null) {
            lp.width = LayoutParams.MATCH_PARENT;
            lp.gravity = Gravity.CENTER_VERTICAL
                    | (restGravity & Gravity.HORIZONTAL_GRAVITY_MASK);
            row.setLayoutParams(lp);
        }
    }

    private void showOverflowLayout() {
        primary.setEllipsize(null);
        primary.setGravity(Gravity.START);
        LinearLayout.LayoutParams primaryLp =
                (LinearLayout.LayoutParams) primary.getLayoutParams();
        if (primaryLp != null) {
            primaryLp.width = LinearLayout.LayoutParams.WRAP_CONTENT;
            primary.setLayoutParams(primaryLp);
        }
        LinearLayout.LayoutParams spacerLp =
                (LinearLayout.LayoutParams) spacer.getLayoutParams();
        if (spacerLp != null) {
            spacerLp.width = dp(SPACING_DP);
            spacer.setLayoutParams(spacerLp);
        }
        spacer.setVisibility(VISIBLE);
        duplicate.setVisibility(VISIBLE);
        LayoutParams lp = (LayoutParams) row.getLayoutParams();
        if (lp != null) {
            lp.width = LayoutParams.WRAP_CONTENT;
            lp.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
            row.setLayoutParams(lp);
        }
    }

    /**
     * Infinite loop: [text][50dp][text], offset 0 → -(textW+spacing).
     * Seamless wrap (repeatDelay = 0).
     */
    private void startMarquee(int textW) {
        stopMarquee(false);
        final int spacingPx = dp(SPACING_DP);
        final int distance = textW + spacingPx;
        if (distance <= 0) return;

        float velocityPx = VELOCITY_DP_PER_SEC * getResources().getDisplayMetrics().density;
        long durationMs = Math.max(1L, Math.round(distance / velocityPx * 1000.0));

        row.setTranslationX(0f);
        animator = ValueAnimator.ofFloat(0f, -distance);
        animator.setStartDelay(INITIAL_DELAY_MS);
        animator.setDuration(durationMs);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.RESTART);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                row.setTranslationX((Float) animation.getAnimatedValue());
            }
        });
        animator.start();
    }

    private void stopMarquee(boolean resetTranslation) {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        if (resetTranslation) row.setTranslationX(0f);
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    /**
     * Horizontal row that never clamps children to the parent width.
     * Without this, long titles measure as viewport-wide and get edge-clipped.
     */
    private static final class UnboundedRow extends LinearLayout {
        UnboundedRow(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int totalW = getPaddingLeft() + getPaddingRight();
            int maxH = getPaddingTop() + getPaddingBottom();
            final int count = getChildCount();
            for (int i = 0; i < count; i++) {
                View child = getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                LayoutParams lp = (LayoutParams) child.getLayoutParams();
                // Force UNSPECIFIED width so TextView keeps full glyph run.
                int childHeightSpec = getChildMeasureSpec(
                        heightMeasureSpec,
                        getPaddingTop() + getPaddingBottom() + lp.topMargin + lp.bottomMargin,
                        lp.height);
                child.measure(
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                        childHeightSpec);
                totalW += child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
                maxH = Math.max(maxH,
                        child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin
                                + getPaddingTop() + getPaddingBottom());
            }
            // Report our true content width; parent FrameLayout will clip.
            setMeasuredDimension(
                    Math.max(totalW, getSuggestedMinimumWidth()),
                    Math.max(maxH, getSuggestedMinimumHeight()));
        }
    }
}
