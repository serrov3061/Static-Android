package org.telegram.ui.Components;

import android.util.SparseBooleanArray;
import android.view.MotionEvent;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;

/**
 * Drag-to-select for grids. exteraGram 13.1.1: real range selection - dragging back restores
 * the initial state of items that left the range, auto-scroll keeps selecting under the finger.
 */
public class RecyclerViewItemRangeSelector implements RecyclerView.OnItemTouchListener {

    private RecyclerView recyclerView;

    private int lastDraggedIndex = -1;
    private int initialSelection = -1;
    private int currentSelection = -1;
    private final SparseBooleanArray initialSelectedStates = new SparseBooleanArray();
    private boolean dragSelectActive;

    private int hotspotTopBoundStart;
    private int hotspotTopBoundEnd;
    private int hotspotBottomBoundStart;
    private int hotspotBottomBoundEnd;
    private boolean inTopHotspot;
    private boolean inBottomHotspot;

    private int autoScrollVelocity;
    private float lastTouchX;
    private float lastTouchY;

    private int hotspotHeight = AndroidUtilities.dp(80);
    private int hotspotOffsetTop;
    private int hotspotOffsetBottom;

    private final RecyclerViewItemRangeSelectorDelegate delegate;

    private static final int AUTO_SCROLL_DELAY = 15;

    public interface RecyclerViewItemRangeSelectorDelegate {
        int getItemCount();
        void setSelected(View view, int index, boolean selected);
        boolean isSelected(int index);
        boolean isIndexSelectable(int index);
        void onStartStopSelection(boolean start);
    }

    private final Runnable autoScrollRunnable = new Runnable() {
        @Override
        public void run() {
            if (recyclerView == null) {
                return;
            }
            if (inTopHotspot) {
                recyclerView.scrollBy(0, -autoScrollVelocity);
                applySelectionAtTouchPosition();
                AndroidUtilities.runOnUIThread(this, AUTO_SCROLL_DELAY);
            } else if (inBottomHotspot) {
                recyclerView.scrollBy(0, autoScrollVelocity);
                applySelectionAtTouchPosition();
                AndroidUtilities.runOnUIThread(this, AUTO_SCROLL_DELAY);
            }
        }
    };

    public RecyclerViewItemRangeSelector(RecyclerViewItemRangeSelectorDelegate delegate) {
        this.delegate = delegate;
    }

    @Override
    public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent e) {
        boolean adapterIsEmpty = rv.getAdapter() == null || rv.getAdapter().getItemCount() == 0;
        boolean result = dragSelectActive && !adapterIsEmpty;
        if (result) {
            recyclerView = rv;
            if (hotspotHeight > -1) {
                hotspotTopBoundStart = hotspotOffsetTop;
                hotspotTopBoundEnd = hotspotOffsetTop + hotspotHeight;
                hotspotBottomBoundStart = rv.getMeasuredHeight() - hotspotHeight - hotspotOffsetBottom;
                hotspotBottomBoundEnd = rv.getMeasuredHeight() - hotspotOffsetBottom;
            }
        }
        if (result && (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL)) {
            onDragSelectionStop();
        }
        return result;
    }

    @Override
    public void onTouchEvent(RecyclerView rv, MotionEvent e) {
        lastTouchX = e.getX();
        lastTouchY = e.getY();
        View v = rv.findChildViewUnder(e.getX(), e.getY());
        int itemPosition = v != null ? rv.getChildAdapterPosition(v) : RecyclerView.NO_POSITION;
        float y = e.getY();
        switch (e.getAction()) {
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                onDragSelectionStop();
                return;
            case MotionEvent.ACTION_MOVE:
                if (hotspotHeight > -1) {
                    if (y >= hotspotTopBoundStart && y <= hotspotTopBoundEnd) {
                        inBottomHotspot = false;
                        if (!inTopHotspot) {
                            inTopHotspot = true;
                            AndroidUtilities.cancelRunOnUIThread(autoScrollRunnable);
                            AndroidUtilities.runOnUIThread(autoScrollRunnable);
                        }
                        autoScrollVelocity = (int) ((hotspotTopBoundEnd - hotspotTopBoundStart) - (y - hotspotTopBoundStart)) / 2;
                    } else if (y >= hotspotBottomBoundStart && y <= hotspotBottomBoundEnd) {
                        inTopHotspot = false;
                        if (!inBottomHotspot) {
                            inBottomHotspot = true;
                            AndroidUtilities.cancelRunOnUIThread(autoScrollRunnable);
                            AndroidUtilities.runOnUIThread(autoScrollRunnable);
                        }
                        autoScrollVelocity = (int) ((y + hotspotBottomBoundEnd) - (hotspotBottomBoundStart + hotspotBottomBoundEnd)) / 2;
                    } else if (inTopHotspot || inBottomHotspot) {
                        AndroidUtilities.cancelRunOnUIThread(autoScrollRunnable);
                        inTopHotspot = false;
                        inBottomHotspot = false;
                    }
                }
                if (itemPosition == RecyclerView.NO_POSITION || lastDraggedIndex == itemPosition || !isSelectableIndex(itemPosition)) {
                    return;
                }
                applyRangeSelection(itemPosition);
                lastDraggedIndex = itemPosition;
                break;
        }
    }

    @Override
    public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {

    }

    /**
     * Starts a drag selection from {@code position}, toggling it relative to its current state.
     */
    public boolean startSelection(View view, int position) {
        if (dragSelectActive) {
            return false;
        }
        lastDraggedIndex = -1;
        initialSelection = -1;
        currentSelection = -1;
        initialSelectedStates.clear();
        AndroidUtilities.cancelRunOnUIThread(autoScrollRunnable);
        inTopHotspot = false;
        inBottomHotspot = false;
        if (!isSelectableIndex(position)) {
            dragSelectActive = false;
            return false;
        }
        delegate.onStartStopSelection(true);
        dragSelectActive = true;
        currentSelection = initialSelection = lastDraggedIndex = position;
        setIndexSelected(view, position, !getInitialSelected(position));
        return true;
    }

    private void applyRangeSelection(int position) {
        if (initialSelection == -1 || currentSelection == -1) {
            return;
        }
        int oldMin = Math.min(initialSelection, currentSelection);
        int oldMax = Math.max(initialSelection, currentSelection);
        int newMin = Math.min(initialSelection, position);
        int newMax = Math.max(initialSelection, position);
        if (newMin < oldMin) {
            toggleRangeSelected(newMin, oldMin - 1);
        }
        if (newMax > oldMax) {
            toggleRangeSelected(oldMax + 1, newMax);
        }
        if (oldMin < newMin) {
            restoreRangeSelected(oldMin, newMin - 1);
        }
        if (oldMax > newMax) {
            restoreRangeSelected(newMax + 1, oldMax);
        }
        currentSelection = position;
    }

    private void applySelectionAtTouchPosition() {
        if (recyclerView == null) {
            return;
        }
        View v = recyclerView.findChildViewUnder(lastTouchX, lastTouchY);
        if (v == null) {
            return;
        }
        int position = recyclerView.getChildAdapterPosition(v);
        if (position == RecyclerView.NO_POSITION || position == lastDraggedIndex || !isSelectableIndex(position)) {
            return;
        }
        applyRangeSelection(position);
        lastDraggedIndex = position;
    }

    private void setIndexSelected(int index, boolean selected) {
        RecyclerView.ViewHolder holder = recyclerView != null ? recyclerView.findViewHolderForAdapterPosition(index) : null;
        setIndexSelected(holder != null ? holder.itemView : null, index, selected);
    }

    private void setIndexSelected(View view, int index, boolean selected) {
        if (isSelectableIndex(index) && delegate.isSelected(index) != selected) {
            delegate.setSelected(view, index, selected);
        }
    }

    private void toggleRangeSelected(int from, int to) {
        for (int i = from; i <= to; i++) {
            if (isSelectableIndex(i)) {
                setIndexSelected(i, !getInitialSelected(i));
            }
        }
    }

    private void restoreRangeSelected(int from, int to) {
        for (int i = from; i <= to; i++) {
            if (isSelectableIndex(i)) {
                setIndexSelected(i, getInitialSelected(i));
            }
        }
    }

    private boolean getInitialSelected(int index) {
        if (initialSelectedStates.indexOfKey(index) < 0) {
            initialSelectedStates.put(index, delegate.isSelected(index));
        }
        return initialSelectedStates.get(index);
    }

    private boolean isSelectableIndex(int index) {
        return index >= 0 && index < delegate.getItemCount() && delegate.isIndexSelectable(index);
    }

    private void onDragSelectionStop() {
        if (!dragSelectActive) {
            return;
        }
        dragSelectActive = false;
        lastDraggedIndex = -1;
        initialSelection = -1;
        currentSelection = -1;
        initialSelectedStates.clear();
        inTopHotspot = false;
        inBottomHotspot = false;
        AndroidUtilities.cancelRunOnUIThread(autoScrollRunnable);
        delegate.onStartStopSelection(false);
    }
}
