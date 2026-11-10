package com.exteragram.messenger.utils.ui;

import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/** exteraGram 13.1.1: keeps the profile list anchored to its top child when it is laid out again (e.g. the actions band changes height). */
public abstract class ProfileListUiHelper {

    public static void anchorTopChild(LinearLayoutManager layoutManager, RecyclerView.State state) {
        if (state.isPreLayout() || layoutManager.hasPendingScrollPosition()) {
            return;
        }
        int top = Integer.MAX_VALUE;
        int position = -1;
        for (int i = 0; i < layoutManager.getChildCount(); i++) {
            final View child = layoutManager.getChildAt(i);
            if (child == null) {
                continue;
            }
            final RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) child.getLayoutParams();
            final int childPosition = lp.getViewLayoutPosition();
            if (lp.isItemRemoved() || childPosition < 0 || childPosition >= state.getItemCount()) {
                continue;
            }
            final int childTop = layoutManager.getDecoratedTop(child) - ((ViewGroup.MarginLayoutParams) lp).topMargin;
            if (childTop < top) {
                top = childTop;
                position = childPosition;
            }
        }
        if (position != -1) {
            layoutManager.scrollToPositionWithOffset(position, top - layoutManager.getPaddingTop());
        }
    }
}
