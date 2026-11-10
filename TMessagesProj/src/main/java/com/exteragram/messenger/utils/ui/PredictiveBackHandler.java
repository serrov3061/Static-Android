package com.exteragram.messenger.utils.ui;

/**
 * exteraGram 13.1.1: a fragment-provided target for the system predictive back gesture
 * ({@link org.telegram.ui.ActionBar.BaseFragment#getPredictiveBackHandler()}), used instead of the fragment
 * close animation, e.g. to dismiss something inside the fragment.
 */
public interface PredictiveBackHandler {
    void onBackProgress(float progress);

    void onBackCancelled();

    /** @return true when the back was handled */
    boolean onBackInvoked();
}
