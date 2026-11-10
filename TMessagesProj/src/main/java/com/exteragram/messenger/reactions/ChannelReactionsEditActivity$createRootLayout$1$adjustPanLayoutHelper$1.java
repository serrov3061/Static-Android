package com.exteragram.messenger.reactions;

import android.view.ViewPropertyAnimator;
import android.widget.FrameLayout;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.AdjustPanLayoutHelper;
import org.telegram.ui.ActionBar.BaseFragment;

public final class ChannelReactionsEditActivity$createRootLayout$1$adjustPanLayoutHelper$1 extends AdjustPanLayoutHelper {
    final /* synthetic */ ChannelReactionsEditActivity this$0;

    @Override // org.telegram.ui.ActionBar.AdjustPanLayoutHelper
    public boolean applyTranslation() {
        return false;
    }

    /* JADX WARN: 'super' call moved to the top of the method (can break code semantics) */
    public ChannelReactionsEditActivity$createRootLayout$1$adjustPanLayoutHelper$1(FrameLayout c01681, ChannelReactionsEditActivity channelReactionsEditActivity) {
        super(c01681);
        this.this$0 = channelReactionsEditActivity;
    }

    @Override // org.telegram.ui.ActionBar.AdjustPanLayoutHelper
    public void onTransitionStart(final boolean keyboardVisible, int contentHeight) {
        if (this.this$0.actionButtonShown) {
            FrameLayout frameLayout = this.this$0.actionButtonContainer;
            if (frameLayout == null) {
                frameLayout = null;
            }
            frameLayout.setVisibility(0);
            FrameLayout frameLayout2 = this.this$0.actionButtonContainer;
            ViewPropertyAnimator viewPropertyAnimatorAlpha = (frameLayout2 != null ? frameLayout2 : null).animate().alpha(keyboardVisible ? 0.0f : 1.0f);
            final ChannelReactionsEditActivity channelReactionsEditActivity = this.this$0;
            viewPropertyAnimatorAlpha.withEndAction(new Runnable() { // from class: com.exteragram.messenger.reactions.ChannelReactionsEditActivity$createRootLayout$1$adjustPanLayoutHelper$1$$ExternalSyntheticLambda0
                @Override // java.lang.Runnable
                public final void run() {
                    ChannelReactionsEditActivity$createRootLayout$1$adjustPanLayoutHelper$1.m2167$r8$lambda$ukX2JGrlDbUrL80ti15I8LZE5M(keyboardVisible, channelReactionsEditActivity);
                }
            }).start();
        }
    }

    /* JADX INFO: renamed from: $r8$lambda$ukX-2JGrlDbUrL80ti15I8LZE5M, reason: not valid java name */
    public static void m2167$r8$lambda$ukX2JGrlDbUrL80ti15I8LZE5M(boolean z, ChannelReactionsEditActivity channelReactionsEditActivity) {
        if (z) {
            FrameLayout frameLayout = channelReactionsEditActivity.actionButtonContainer;
            if (frameLayout == null) {
                frameLayout = null;
            }
            frameLayout.setVisibility(4);
        }
    }

    @Override // org.telegram.ui.ActionBar.AdjustPanLayoutHelper
    public boolean heightAnimationEnabled() {
        return (this.this$0.isInPreviewMode() || this.this$0.isInBubbleMode() || AndroidUtilities.isTablet() || AndroidUtilities.isInMultiwindow || this.this$0.getParentLayout() == null) ? false : true;
    }
}
