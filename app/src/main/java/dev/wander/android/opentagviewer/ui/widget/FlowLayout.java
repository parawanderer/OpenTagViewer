package dev.wander.android.opentagviewer.ui.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Lays children out left to right, wrapping to a new row when the current one runs out of
 * width, and reports the height that actually took.
 * <br>
 * A GridLayout with a fixed column count would misuse the space on anything that is not the
 * screen it was tuned for - too cramped on a tablet, overflowing on a small phone. Here the
 * number of items per row falls out of the measured width instead.
 * <br>
 * Rows are centred horizontally, which reads better than a ragged last row for a set of
 * equally sized items such as avatars.
 */
public class FlowLayout extends ViewGroup {

    private final List<List<View>> rows = new ArrayList<>();
    private final List<Integer> rowHeights = new ArrayList<>();
    private final List<Integer> rowWidths = new ArrayList<>();

    public FlowLayout(Context context) {
        super(context);
    }

    public FlowLayout(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public FlowLayout(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new MarginLayoutParams(getContext(), attrs);
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    @Override
    protected LayoutParams generateLayoutParams(LayoutParams p) {
        return new MarginLayoutParams(p);
    }

    @Override
    protected boolean checkLayoutParams(LayoutParams p) {
        return p instanceof MarginLayoutParams;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        this.rows.clear();
        this.rowHeights.clear();
        this.rowWidths.clear();

        final int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        final int maxWidth = MeasureSpec.getSize(widthMeasureSpec)
                - getPaddingLeft() - getPaddingRight();

        // UNSPECIFIED means nothing is constraining us, so wrapping would never trigger and
        // every child would end up on one row. Treat it as a single row on purpose.
        final boolean canWrap = widthMode != MeasureSpec.UNSPECIFIED;

        List<View> row = new ArrayList<>();
        int rowWidth = 0;
        int rowHeight = 0;
        int totalHeight = 0;
        int widestRow = 0;

        for (int i = 0; i < getChildCount(); i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() == GONE) {
                continue;
            }

            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0);

            final MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
            final int childWidth = child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
            final int childHeight = child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;

            if (canWrap && !row.isEmpty() && rowWidth + childWidth > maxWidth) {
                this.rows.add(row);
                this.rowHeights.add(rowHeight);
                this.rowWidths.add(rowWidth);
                totalHeight += rowHeight;
                widestRow = Math.max(widestRow, rowWidth);

                row = new ArrayList<>();
                rowWidth = 0;
                rowHeight = 0;
            }

            row.add(child);
            rowWidth += childWidth;
            rowHeight = Math.max(rowHeight, childHeight);
        }

        if (!row.isEmpty()) {
            this.rows.add(row);
            this.rowHeights.add(rowHeight);
            this.rowWidths.add(rowWidth);
            totalHeight += rowHeight;
            widestRow = Math.max(widestRow, rowWidth);
        }

        final int measuredWidth = widthMode == MeasureSpec.EXACTLY
                ? MeasureSpec.getSize(widthMeasureSpec)
                : widestRow + getPaddingLeft() + getPaddingRight();

        setMeasuredDimension(
                resolveSize(measuredWidth, widthMeasureSpec),
                resolveSize(totalHeight + getPaddingTop() + getPaddingBottom(), heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        final int contentWidth = getMeasuredWidth() - getPaddingLeft() - getPaddingRight();
        int y = getPaddingTop();

        for (int rowIndex = 0; rowIndex < this.rows.size(); rowIndex++) {
            final List<View> row = this.rows.get(rowIndex);
            int x = getPaddingLeft() + ((contentWidth - this.rowWidths.get(rowIndex)) / 2);

            for (View child : row) {
                final MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();

                final int childLeft = x + lp.leftMargin;
                final int childTop = y + lp.topMargin;

                child.layout(
                        childLeft,
                        childTop,
                        childLeft + child.getMeasuredWidth(),
                        childTop + child.getMeasuredHeight());

                x += child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
            }

            y += this.rowHeights.get(rowIndex);
        }
    }
}
