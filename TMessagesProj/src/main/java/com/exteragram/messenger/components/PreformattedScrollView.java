package com.exteragram.messenger.components;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import com.exteragram.messenger.utils.MarkdownUtils;
import java.util.ArrayList;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.tgnet.tl.TL_iv;

// exteraGram: horizontal scroll container for code/.txt blocks in Instant View.
// Chunks of one source share a Group, so their horizontal scroll positions stay in sync.
public abstract class PreformattedScrollView extends HorizontalScrollView {
    private Group group;

    public PreformattedScrollView(Context context) {
        super(context);
    }

    public void setBlock(TL_iv.PageBlock pageBlock) {
        MarkdownUtils.PreformattedChunk preformattedChunk = pageBlock instanceof MarkdownUtils.PreformattedChunk ? (MarkdownUtils.PreformattedChunk) pageBlock : null;
        Group oldGroup = this.group;
        if (oldGroup != null) {
            oldGroup.remove(this);
        }
        Group newGroup = preformattedChunk != null ? preformattedChunk.scrollGroup : null;
        this.group = newGroup;
        if (newGroup != null && isAttachedToWindow()) {
            this.group.add(this);
        }
        setHorizontalScrollBarEnabled(preformattedChunk == null);
        boolean joinsPrevious = preformattedChunk != null && preformattedChunk.joinsPrevious;
        boolean joinsNext = preformattedChunk != null && preformattedChunk.joinsNext;
        setPadding(0, joinsPrevious ? 0 : AndroidUtilities.dp(8.0f), 0, joinsNext ? 0 : AndroidUtilities.dp(8.0f));
        if (getChildCount() > 0) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) getChildAt(0).getLayoutParams();
            lp.topMargin = joinsPrevious ? 0 : AndroidUtilities.dp(12.0f);
            lp.bottomMargin = AndroidUtilities.dp(joinsNext ? 4.0f : 12.0f);
        }
    }

    public int adjustContentWidth(int width) {
        Group g = this.group;
        return g != null ? g.adjustContentWidth(this, width) : width;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (this.group != null && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            this.group.driver = this;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        Group g = this.group;
        if (g == null || g.driver != this) {
            return;
        }
        g.scrollTo(this, l);
    }

    @Override
    public void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        Group g = this.group;
        if (g != null) {
            scrollTo(g.scrollX, getScrollY());
        }
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        Group g = this.group;
        if (g != null) {
            g.add(this);
            scrollTo(g.scrollX, getScrollY());
            if (getChildCount() <= 0 || getChildAt(0).getMeasuredWidth() >= g.contentWidth) {
                return;
            }
            View child = getChildAt(0);
            AndroidUtilities.runOnUIThread(child::requestLayout);
        }
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Group g = this.group;
        if (g != null) {
            g.remove(this);
        }
    }

    public static final class Group {
        private int contentWidth;
        private PreformattedScrollView driver;
        private int scrollX;
        private final ArrayList<PreformattedScrollView> views = new ArrayList<>();

        private void add(PreformattedScrollView view) {
            if (this.views.contains(view)) {
                return;
            }
            this.views.add(view);
        }

        private void remove(PreformattedScrollView view) {
            this.views.remove(view);
            if (this.driver == view) {
                this.driver = null;
            }
        }

        private void scrollTo(PreformattedScrollView source, int x) {
            this.scrollX = x;
            for (int i = 0; i < this.views.size(); i++) {
                PreformattedScrollView other = this.views.get(i);
                if (other != source) {
                    other.scrollTo(x, other.getScrollY());
                }
            }
        }

        private int adjustContentWidth(PreformattedScrollView source, int width) {
            if (width > this.contentWidth) {
                this.contentWidth = width;
                for (int i = 0; i < this.views.size(); i++) {
                    PreformattedScrollView other = this.views.get(i);
                    if (other != source && other.getChildCount() > 0) {
                        View child = other.getChildAt(0);
                        AndroidUtilities.runOnUIThread(child::requestLayout);
                    }
                }
            }
            return this.contentWidth;
        }
    }
}
