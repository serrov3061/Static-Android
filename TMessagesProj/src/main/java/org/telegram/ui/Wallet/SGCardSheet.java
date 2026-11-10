package org.telegram.ui.Wallet;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_sgcard;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ColoredImageSpan;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ProfileBalanceView;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Components.ScrimOptions;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProviderThemed;
import org.telegram.utils.glass.source.GlassSourceBitmap;
import org.telegram.ui.Components.blur3.utils.Blur3Utils;
import org.telegram.ui.Components.chat.ViewPositionWatcher;
import org.telegram.ui.GradientClip;
import org.telegram.ui.ProfileActivity;
import org.telegram.ui.Stars.StarsIntroActivity;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;
import org.telegram.ui.Stories.recorder.HintView2;

/**
 * Both cards of a user over the blurred screen, after Telegram 13.1.1's ProfileWalletCardDialog:
 * the card slides out of the profile balance pill, tilts with the gyroscope, swipes between the
 * GRAM and the Stars card. Below: send money to the user (or set up the own cards) and share.
 */
public class SGCardSheet extends Dialog {
    private final BaseFragment fragment;
    private final int currentAccount;
    private final ProfileBalanceView anchor;
    private final TLRPC.User user;
    private final TL_sgcard.TL_cards cards;
    private final boolean isSelf;
    private final RectF source = new RectF();
    private final FrameLayout root;
    private final View blurView;
    private final FrameLayout pager;
    private final SGCardView[] cardViews = new SGCardView[2];
    private final LinearLayout buttons;
    private final ProfileActivity.Button2 sendPill, sharePill;
    private final ButtonWithCounterView sendButton;
    private final TextView publicNote;
    private final LinearLayout dots;
    private final Paint blurPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF blurBounds = new RectF();
    private final GlassSourceBitmap buttonBlur = new GlassSourceBitmap();
    private final BlurredBackgroundDrawableViewFactory buttonBlurFactory;
    private Bitmap blurBitmap;
    private Insets insets = Insets.NONE;
    private float progress;
    private float clipTop;
    /** Everything of the card below this line is hidden (it is still "inside" the pill); < 0 — off. */
    private float clipBottom = -1;
    /** 0 — the other card peeks from behind the shown one, 1 — it sits in its carousel slot. */
    private float fan = 1;
    private boolean springy;
    private final GradientClip clip = new GradientClip();
    private final RectF clipRect = new RectF();
    private final RectF cardLayerBounds = new RectF();
    private ValueAnimator animator, gyroscopeAnimator, pageAnimator;
    private float gyroscope;
    private boolean dismissing, closed, opening;
    private Runnable afterDismiss;
    private final int[] rootLocation = new int[2];

    private int page;
    private float pageOffset; // 0 = GRAM, 1 = Stars
    private float touchX, touchY, pageStart;
    private boolean paging, pagingDecided;
    private VelocityTracker velocity;
    private final int touchSlop;

    public static void show(BaseFragment fragment, ProfileBalanceView anchor, long userId, int kind) {
        if (fragment == null || fragment.getParentActivity() == null) return;
        final int account = fragment.getCurrentAccount();
        final TLRPC.User user = MessagesController.getInstance(account).getUser(userId);
        final SGCardsController c = SGCardsController.getInstance(account);
        final TL_sgcard.TL_cards cached = c.getCached(userId);
        if (cached != null && !cached.cards.isEmpty() && user != null) {
            fragment.showDialog(new SGCardSheet(fragment, anchor, user, cached, kind));
            c.load(userId);
            return;
        }
        c.load(userId, true, res -> {
            final TLRPC.User u = MessagesController.getInstance(account).getUser(userId);
            if (fragment.getParentActivity() == null) return;
            if (res == null || res.cards.isEmpty() || u == null) {
                org.telegram.ui.Components.BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, getString(R.string.SgCardNoCards)).show();
                return;
            }
            fragment.showDialog(new SGCardSheet(fragment, anchor, u, res, kind));
        });
    }

    public SGCardSheet(BaseFragment fragment, ProfileBalanceView anchor, TLRPC.User user, TL_sgcard.TL_cards cards, int kind) {
        super(fragment.getParentActivity(), R.style.TransparentDialog);
        this.fragment = fragment;
        this.currentAccount = fragment.getCurrentAccount();
        this.anchor = anchor;
        this.user = user;
        this.cards = cards;
        isSelf = user != null && user.id == UserConfig.getInstance(currentAccount).getClientUserId();
        touchSlop = ViewConfiguration.get(fragment.getParentActivity()).getScaledTouchSlop();
        page = kind == TL_sgcard.KIND_STARS ? 1 : 0;
        pageOffset = page;
        if (anchor == null || !anchor.getChipBoundsOnScreen(page, source)) {
            source.set(AndroidUtilities.displaySize.x / 2f - dp(40), AndroidUtilities.displaySize.y - dp(60),
                    AndroidUtilities.displaySize.x / 2f + dp(40), AndroidUtilities.displaySize.y - dp(40));
        }
        AndroidUtilities.enableEdgeToEdge(getWindow());

        root = new FrameLayout(getContext()) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                final int width = MeasureSpec.getSize(widthMeasureSpec);
                final int height = MeasureSpec.getSize(heightMeasureSpec);
                setMeasuredDimension(width, height);
                final int safeWidth = Math.max(1, width - insets.left - insets.right);
                // the long pill shares the row with the round ones: it gets only what they leave,
                // own cards have three buttons and the pill used to push the last one off the screen
                final int buttonsAvailable = Math.max(1, safeWidth - dp(24));
                final ViewGroup.LayoutParams sendLp = sendPill.getLayoutParams();
                sendLp.width = LayoutHelper.WRAP_CONTENT;
                buttons.measure(MeasureSpec.makeMeasureSpec(buttonsAvailable, MeasureSpec.AT_MOST),
                        MeasureSpec.makeMeasureSpec(dp(60), MeasureSpec.EXACTLY));
                int buttonsTotal = 0;
                for (int i = 0; i < buttons.getChildCount(); i++) {
                    final View child = buttons.getChildAt(i);
                    final ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) child.getLayoutParams();
                    buttonsTotal += child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
                }
                if (buttonsTotal > buttonsAvailable) {
                    sendLp.width = Math.max(dp(60), sendPill.getMeasuredWidth() - (buttonsTotal - buttonsAvailable));
                    buttons.measure(MeasureSpec.makeMeasureSpec(buttonsAvailable, MeasureSpec.AT_MOST),
                            MeasureSpec.makeMeasureSpec(dp(60), MeasureSpec.EXACTLY));
                }
                publicNote.measure(MeasureSpec.makeMeasureSpec(Math.max(1, safeWidth - dp(48)), MeasureSpec.AT_MOST),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                dots.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(dp(8), MeasureSpec.EXACTLY));
                int cardWidth = Math.min(dp(420), Math.max(1, safeWidth - dp(24)));
                final int maxHeight = Math.max(1, height - safeTop() - insets.bottom - dp(140) - noteHeight());
                pager.measure(MeasureSpec.makeMeasureSpec(cardWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                if (pager.getMeasuredHeight() > maxHeight) {
                    cardWidth = Math.round(cardWidth * maxHeight / (float) Math.max(1, pager.getMeasuredHeight()));
                    pager.measure(MeasureSpec.makeMeasureSpec(Math.max(1, cardWidth), MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                }
                blurView.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            }

            @Override
            protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                getLocationOnScreen(rootLocation);
                final int centerX = insets.left + (getWidth() - insets.left - insets.right) / 2;
                final int buttonTop = getHeight() - insets.bottom - dp(16) - buttons.getMeasuredHeight();
                buttons.layout(centerX - buttons.getMeasuredWidth() / 2, buttonTop,
                        centerX + (buttons.getMeasuredWidth() + 1) / 2, buttonTop + buttons.getMeasuredHeight());
                final int noteTop = safeTop() + dp(36);
                publicNote.layout(centerX - publicNote.getMeasuredWidth() / 2, noteTop,
                        centerX + (publicNote.getMeasuredWidth() + 1) / 2, noteTop + publicNote.getMeasuredHeight());
                updateNoteColor();
                final int minTop = safeTop() + dp(24) + noteHeight();
                final int maxTop = Math.max(minTop, buttonTop - dp(40) - pager.getMeasuredHeight());
                final int cardTop = Math.max(minTop, Math.min(maxTop, (getHeight() - pager.getMeasuredHeight()) / 2));
                final int cardLeft = centerX - pager.getMeasuredWidth() / 2;
                pager.layout(cardLeft, cardTop, cardLeft + pager.getMeasuredWidth(), cardTop + pager.getMeasuredHeight());
                final int dotsTop = cardTop + pager.getMeasuredHeight() + dp(4);
                dots.layout(centerX - dots.getMeasuredWidth() / 2, dotsTop, centerX + (dots.getMeasuredWidth() + 1) / 2, dotsTop + dots.getMeasuredHeight());
                blurView.layout(0, 0, getWidth(), getHeight());
                Blur3Utils.checkBitmapSourceMatrixScale(buttonBlur, this);
                updatePages();
                updateProgress();
            }

            @Override
            public boolean dispatchTouchEvent(MotionEvent ev) {
                final int r = handlePaging(ev);
                if (r == PAGING_START) {
                    // The cards stop tilting: the swipe belongs to the pager now.
                    final MotionEvent cancel = MotionEvent.obtain(ev);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                    return true;
                }
                if (r == PAGING_CONSUME) return true;
                return super.dispatchTouchEvent(ev);
            }

            @Override
            protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
                if (child == pager && clipTop > 0) {
                    cardLayerBounds.set(0, 0, pager.getWidth(), pager.getHeight());
                    pager.getMatrix().mapRect(cardLayerBounds);
                    cardLayerBounds.offset(pager.getLeft(), pager.getTop());
                    cardLayerBounds.inset(-dp(4), -dp(4));
                    final float fade = dp(28);
                    if (cardLayerBounds.top < clipTop + fade && cardLayerBounds.intersect(0, 0, getWidth(), getHeight())) {
                        int layer = canvas.saveLayer(cardLayerBounds, null);
                        final boolean result = super.drawChild(canvas, child, drawingTime);
                        clipRect.set(cardLayerBounds.left, cardLayerBounds.top, cardLayerBounds.right, Math.min(cardLayerBounds.bottom, clipTop));
                        if (clipRect.height() > 0) canvas.drawRect(clipRect, clip.getPaint(GradientClip.TOP, 1f));
                        clipRect.set(cardLayerBounds.left, clipTop, cardLayerBounds.right, clipTop + fade);
                        clip.draw(canvas, clipRect, GradientClip.TOP, 1f);
                        canvas.restoreToCount(layer);
                        return result;
                    }
                }
                if (child == pager && clipBottom >= 0) {
                    cardLayerBounds.set(0, 0, pager.getWidth(), pager.getHeight());
                    pager.getMatrix().mapRect(cardLayerBounds);
                    cardLayerBounds.offset(pager.getLeft(), pager.getTop());
                    cardLayerBounds.inset(-dp(24), -dp(24));
                    final float fade = dp(12);
                    if (cardLayerBounds.bottom > clipBottom - fade && cardLayerBounds.intersect(0, 0, getWidth(), getHeight())) {
                        int layer = canvas.saveLayer(cardLayerBounds, null);
                        final boolean result = super.drawChild(canvas, child, drawingTime);
                        clipRect.set(cardLayerBounds.left, Math.max(cardLayerBounds.top, clipBottom), cardLayerBounds.right, cardLayerBounds.bottom);
                        if (clipRect.height() > 0) canvas.drawRect(clipRect, clip.getPaint(GradientClip.BOTTOM, 1f));
                        clipRect.set(cardLayerBounds.left, clipBottom - fade, cardLayerBounds.right, clipBottom);
                        clip.draw(canvas, clipRect, GradientClip.BOTTOM, 1f);
                        canvas.restoreToCount(layer);
                        return result;
                    }
                }
                return super.drawChild(canvas, child, drawingTime);
            }
        };
        blurView = new View(getContext()) {
            @Override
            protected void onDraw(Canvas canvas) {
                if (blurBitmap != null && !blurBitmap.isRecycled()) {
                    blurBounds.set(0, 0, getWidth(), getHeight());
                    canvas.drawBitmap(blurBitmap, null, blurBounds, blurPaint);
                }
            }

            @Override
            public boolean hasOverlappingRendering() {
                return false;
            }
        };
        blurView.setAlpha(0);
        root.addView(blurView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        root.setClipChildren(false);
        root.setClipToPadding(false);
        root.setOnClickListener(v -> dismiss());
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            root.requestLayout();
            return WindowInsetsCompat.CONSUMED;
        });

        pager = new FrameLayout(getContext());
        pager.setClipChildren(false);
        pager.setClipToPadding(false);
        pager.setAlpha(0);
        for (int i = 0; i < 2; i++) {
            final int k = i == 0 ? TL_sgcard.KIND_GRAM : TL_sgcard.KIND_STARS;
            final SGCardView view = new SGCardView(getContext(), currentAccount, false);
            TL_sgcard.TL_card c = cards.get(k);
            if (c == null) {
                c = new TL_sgcard.TL_card();
                c.kind = k;
            }
            view.bind(user, c);
            view.card.setOnClickListener(v -> { });
            view.card.setCardOnClickListener(v -> { });
            cardViews[i] = view;
            pager.addView(view, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        root.addView(pager, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        dots = new LinearLayout(getContext());
        dots.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 2; i++) {
            final View dot = new View(getContext());
            final int p = i;
            dot.setOnClickListener(v -> animatePage(p));
            dots.addView(dot, LayoutHelper.createLinear(6, 6, Gravity.CENTER_VERTICAL, 3, 0, 3, 0));
        }
        dots.setAlpha(0);
        root.addView(dots, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 8));

        buttonBlurFactory = new BlurredBackgroundDrawableViewFactory(buttonBlur);
        buttonBlurFactory.setSourceRootView(new ViewPositionWatcher(root), root);

        buttons = new LinearLayout(getContext());
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setAlpha(0);
        root.addView(buttons, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 60, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

        sendPill = new ProfileActivity.Button2(getContext());
        BlurredBackgroundDrawable background = buttonBlurFactory.create(sendPill,
                new BlurredBackgroundColorProviderThemed(fragment.getResourceProvider(), Theme.key_windowBackgroundWhite));
        background.setPadding(dp(8));
        background.setRadius(dp(22));
        sendPill.setBackground(background);
        ScaleStateListAnimator.apply(sendPill, .02f, 1.2f);
        sendButton = new ButtonWithCounterView(getContext(), fragment.getResourceProvider());
        sendButton.setUseWrapContent(true);
        sendButton.setPadding(dp(16), 0, dp(16), 0);
        sendButton.setRoundRadius(19);
        sendButton.setStateListAnimator(null);
        sendPill.addView(sendButton, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));
        sendPill.setOnClickListener(v -> {
            if (dismissing) return;
            final int k = currentKind();
            if (isSelf) {
                afterDismiss = () -> fragment.presentFragment(new SGCardsActivity());
            } else {
                afterDismiss = () -> SGSendMoneySheet.show(fragment, user, k);
            }
            dismiss();
        });
        buttons.addView(sendPill, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 60));

        sharePill = new ProfileActivity.Button2(getContext());
        BlurredBackgroundDrawable shareBackground = buttonBlurFactory.create(sharePill,
                new BlurredBackgroundColorProviderThemed(fragment.getResourceProvider(), Theme.key_windowBackgroundWhite));
        shareBackground.setPadding(dp(8));
        shareBackground.setRadius(dp(22));
        sharePill.setBackground(shareBackground);
        ScaleStateListAnimator.apply(sharePill, .04f, 1.2f);
        final ImageView shareIcon = new ImageView(getContext());
        shareIcon.setScaleType(ImageView.ScaleType.CENTER);
        shareIcon.setImageResource(R.drawable.msg_share);
        shareIcon.setColorFilter(new android.graphics.PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, fragment.getResourceProvider()), android.graphics.PorterDuff.Mode.SRC_IN));
        sharePill.addView(shareIcon, LayoutHelper.createFrame(38, 38, Gravity.CENTER));
        sharePill.setContentDescription(getString(R.string.SgCardShare));
        sharePill.setOnClickListener(v -> {
            if (dismissing) return;
            final SGCardView view = cardViews[page];
            final Bitmap image = view.renderImage();
            afterDismiss = () -> SGCards.shareCard(fragment, user, view.getKind(), image);
            dismiss();
        });
        buttons.addView(sharePill, LayoutHelper.createLinear(60, 60, 4, 0, 0, 0));

        if (isSelf) {
            // Own cards: "Transfer" next to "Share" sends GRAM / Stars of the shown card to someone.
            final ProfileActivity.Button2 transferPill = new ProfileActivity.Button2(getContext());
            final BlurredBackgroundDrawable transferBackground = buttonBlurFactory.create(transferPill,
                    new BlurredBackgroundColorProviderThemed(fragment.getResourceProvider(), Theme.key_windowBackgroundWhite));
            transferBackground.setPadding(dp(8));
            transferBackground.setRadius(dp(22));
            transferPill.setBackground(transferBackground);
            ScaleStateListAnimator.apply(transferPill, .04f, 1.2f);
            final ImageView transferIcon = new ImageView(getContext());
            transferIcon.setScaleType(ImageView.ScaleType.CENTER);
            transferIcon.setImageResource(R.drawable.msg_send);
            transferIcon.setColorFilter(new android.graphics.PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, fragment.getResourceProvider()), android.graphics.PorterDuff.Mode.SRC_IN));
            transferPill.addView(transferIcon, LayoutHelper.createFrame(38, 38, Gravity.CENTER));
            transferPill.setContentDescription(getString(R.string.SgCardTransferTitle));
            transferPill.setOnClickListener(v -> {
                if (dismissing) return;
                final int k = currentKind();
                afterDismiss = () -> SGCards.pickRecipientAndSend(fragment, k);
                dismiss();
            });
            buttons.addView(transferPill, LayoutHelper.createLinear(60, 60, 4, 0, 0, 0));
        }

        publicNote = new TextView(getContext());
        publicNote.setTextSize(14);
        publicNote.setTypeface(AndroidUtilities.bold());
        publicNote.setGravity(Gravity.CENTER);
        publicNote.setLineSpacing(dp(18) - publicNote.getPaint().getFontMetricsInt(null), 1f);
        publicNote.setAlpha(0);
        root.addView(publicNote, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        updatePageTexts();
    }

    private int currentKind() {
        return page == 1 ? TL_sgcard.KIND_STARS : TL_sgcard.KIND_GRAM;
    }

    private void updatePageTexts() {
        final int k = currentKind();
        final TL_sgcard.TL_card c = cards.get(k);
        final long balance = c == null ? 0 : c.balance;
        if (isSelf) {
            publicNote.setText(getString(R.string.SgCardPublicNote));
        } else if (user != null) {
            final String name = UserObject.getFirstName(user);
            final String amount = k == TL_sgcard.KIND_GRAM ? WalletCardComponent.formatGrams(balance) + " GRAM"
                    : WalletCardComponent.formatStarsCompact(balance) + " Stars";
            final String text = balance == 0
                    ? LocaleController.formatString(k == TL_sgcard.KIND_GRAM ? R.string.SgCardOwnsNoGram : R.string.SgCardOwnsNoStars, name)
                    : LocaleController.formatString(R.string.SgCardOwns, name, amount);
            publicNote.setText(HintView2.cutInFancyHalfText(text, publicNote.getPaint()));
        }
        final CharSequence buttonText;
        if (isSelf) {
            buttonText = getString(R.string.SgCardSetUp);
        } else if (k == TL_sgcard.KIND_GRAM) {
            final SpannableStringBuilder label = new SpannableStringBuilder("G  ").append(getString(R.string.SgCardSendGramButton));
            final ColoredImageSpan icon = new ColoredImageSpan(R.drawable.mini_gram_24);
            icon.setSize(dp(20));
            label.setSpan(icon, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            buttonText = label;
        } else {
            buttonText = StarsIntroActivity.replaceStars("⭐ " + getString(R.string.SgCardSendStarsButton));
        }
        sendButton.setText(buttonText, root.isAttachedToWindow());
        sendPill.setContentDescription(buttonText);
        for (int i = 0; i < dots.getChildCount(); i++) {
            dots.getChildAt(i).setBackground(Theme.createCircleDrawable(dp(6), Theme.multAlpha(Color.WHITE, i == page ? .9f : .35f)));
        }
    }

    // ---- paging between the GRAM and Stars cards

    private static final int PAGING_PASS = 0, PAGING_CONSUME = 1, PAGING_START = 2;

    private int handlePaging(MotionEvent ev) {
        if (progress < 1 || dismissing) return PAGING_PASS;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchX = ev.getX();
                touchY = ev.getY();
                paging = false;
                pagingDecided = false;
                if (velocity != null) velocity.recycle();
                velocity = VelocityTracker.obtain();
                velocity.addMovement(ev);
                return PAGING_PASS;
            case MotionEvent.ACTION_MOVE:
                if (velocity != null) velocity.addMovement(ev);
                final float dx = ev.getX() - touchX, dy = ev.getY() - touchY;
                if (!pagingDecided && (Math.abs(dx) > touchSlop * 2 || Math.abs(dy) > touchSlop * 2)) {
                    pagingDecided = true;
                    paging = Math.abs(dx) > Math.abs(dy) * 1.3f;
                    if (paging) {
                        if (pageAnimator != null) pageAnimator.cancel();
                        pageStart = pageOffset;
                        touchX = ev.getX();
                        return PAGING_START;
                    }
                }
                if (paging) {
                    float o = pageStart - dx / Math.max(1, pager.getWidth() + dp(24));
                    if (o < 0) o = o / 3f;
                    if (o > 1) o = 1 + (o - 1) / 3f;
                    pageOffset = o;
                    updatePages();
                    return PAGING_CONSUME;
                }
                return PAGING_PASS;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (paging) {
                    paging = false;
                    float vx = 0;
                    if (velocity != null) {
                        velocity.addMovement(ev);
                        velocity.computeCurrentVelocity(1000);
                        vx = velocity.getXVelocity();
                    }
                    int target = Math.round(pageOffset);
                    if (Math.abs(vx) > dp(400)) target = vx < 0 ? 1 : 0;
                    animatePage(Math.max(0, Math.min(1, target)));
                    return PAGING_CONSUME;
                }
                return PAGING_PASS;
        }
        return paging ? PAGING_CONSUME : PAGING_PASS;
    }

    private void animatePage(int target) {
        if (pageAnimator != null) pageAnimator.cancel();
        pageAnimator = ValueAnimator.ofFloat(pageOffset, target);
        pageAnimator.setDuration(320);
        pageAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        pageAnimator.addUpdateListener(a -> {
            pageOffset = (float) a.getAnimatedValue();
            updatePages();
        });
        pageAnimator.start();
        if (page != target) {
            page = target;
            updatePageTexts();
            root.requestLayout();
        }
    }

    private void updatePages() {
        final float w = pager.getWidth() + dp(24);
        final int front = Math.round(pageOffset);
        for (int i = 0; i < 2; i++) {
            final float d = i - pageOffset;
            final float s = 1f - .08f * Math.min(1, Math.abs(d));
            if (i == front || fan >= 1) {
                cardViews[i].setTranslationX(d * w);
                cardViews[i].setTranslationY(0);
                cardViews[i].setRotation(0);
                cardViews[i].setScaleX(s);
                cardViews[i].setScaleY(s);
            } else {
                // While the sheet opens / closes the other card peeks from behind the shown one
                // (a little aside, lower, turned and smaller) and fans out into its slot after it.
                final float side = Math.signum(d);
                final float ss = AndroidUtilities.lerp(.86f, s, fan);
                cardViews[i].setTranslationX(AndroidUtilities.lerp(side * dp(26), d * w, fan));
                cardViews[i].setTranslationY(dp(10) * (1 - fan));
                cardViews[i].setRotation(side * 5f * (1 - fan));
                cardViews[i].setScaleX(ss);
                cardViews[i].setScaleY(ss);
            }
            // The shown card is drawn above the other one.
            cardViews[i].setTranslationZ(i == front ? 1 : 0);
            // Only the shown card follows the device tilt.
            cardViews[i].card.setMotionEnabled(i == front);
        }
    }

    private void updateNoteColor() {
        boolean dark = Theme.isCurrentThemeDark();
        if (blurBitmap != null && !blurBitmap.isRecycled() && root.getWidth() > 0 && root.getHeight() > 0) {
            final int x = Utilities.clamp((int) ((publicNote.getLeft() + publicNote.getRight()) / 2f / root.getWidth() * blurBitmap.getWidth()), blurBitmap.getWidth() - 1, 0);
            final int y = Utilities.clamp((int) ((publicNote.getTop() + publicNote.getBottom()) / 2f / root.getHeight() * blurBitmap.getHeight()), blurBitmap.getHeight() - 1, 0);
            dark = AndroidUtilities.computePerceivedBrightness(blurBitmap.getPixel(x, y)) < .5f;
        }
        publicNote.setTextColor(Theme.multAlpha(dark ? Color.WHITE : Color.BLACK, .41f));
        for (int i = 0; i < dots.getChildCount(); i++) {
            dots.getChildAt(i).setBackground(Theme.createCircleDrawable(dp(6), Theme.multAlpha(dark ? Color.WHITE : Color.BLACK, i == page ? .8f : .3f)));
        }
    }

    private int noteHeight() {
        return publicNote.getVisibility() == View.VISIBLE ? dp(12) + publicNote.getMeasuredHeight() : 0;
    }

    private int safeTop() {
        return Math.max(insets.top, AndroidUtilities.statusBarHeight);
    }

    /**
     * 1:1 Telegram 13.1.1 ProfileWalletCardDialog.updateProgress, from the tapped GRAM / Stars pill.
     * Phase 1: the card, standing vertically, slides down out of the balance pill (clipped at it).
     * Phase 2: it moves to its place, scales up and rotates back to horizontal.
     * StaticGram: the pager holds both cards; the other one waits off screen and fades in on settle.
     */
    private void updateProgress() {
        final float slide = CubicBezierInterpolator.EASE_OUT.getInterpolation(clamp01(progress / .4f));
        final float settleT = clamp01((progress - .3f) / .7f);
        final float settle = CubicBezierInterpolator.EASE_OUT_QUINT.getInterpolation(settleT);
        // Opening overshoots a bit on move, scale and rotation; closing just eases back.
        final float bounce = springy ? spring(settleT) : settle;
        final float w = pager.getWidth(), h = pager.getHeight();
        final float startScale = Math.max(.2f, Math.min(1f, source.width() / Math.max(1, h)));
        final float scale = AndroidUtilities.lerp(startScale, 1f, bounce);
        final float clipY = source.centerY() - rootLocation[1];
        final float verticalHeight = w * startScale;
        final float startCenterX = source.centerX() - rootLocation[0];
        final float startCenterY = clipY - verticalHeight / 2f + verticalHeight * slide;
        final float endCenterX = pager.getLeft() + w / 2f, endCenterY = pager.getTop() + h / 2f;
        final float centerX = AndroidUtilities.lerp(startCenterX, endCenterX, bounce);
        final float centerY = AndroidUtilities.lerp(startCenterY, endCenterY, bounce);
        final float rotation = -90 * (1 - bounce);
        final float cos = (float) Math.cos(Math.toRadians(rotation)), sin = (float) Math.sin(Math.toRadians(rotation));
        // Pivot the tilt on the balance slot, so the part still near it stays small and the part already out
        // grows toward the viewer. The pivot drifts back to the card center as it settles.
        final float slotDx = startCenterX - centerX, slotDy = clipY - centerY;
        final float slotPivotX = w / 2f + (cos * slotDx + sin * slotDy) / scale;
        final float slotPivotY = h / 2f + (-sin * slotDx + cos * slotDy) / scale;
        final float pivotX = AndroidUtilities.lerp(slotPivotX, w / 2f, settle);
        final float pivotY = AndroidUtilities.lerp(slotPivotY, h / 2f, settle);
        final float fromCenterX = pivotX - w / 2f, fromCenterY = pivotY - h / 2f;
        pager.setPivotX(pivotX);
        pager.setPivotY(pivotY);
        pager.setScaleX(scale);
        pager.setScaleY(scale);
        pager.setRotation(rotation);
        pager.setRotationX(35 * (1 - bounce));
        pager.setTranslationX(centerX - pager.getLeft() - pivotX + scale * (cos * fromCenterX - sin * fromCenterY));
        pager.setTranslationY(centerY - pager.getTop() - pivotY + scale * (sin * fromCenterX + cos * fromCenterY));
        pager.setAlpha(progress > 0 ? 1 : 0);
        cardViews[1 - page].setAlpha(settle);
        // Live tilt only after the open finishes: a perspective face mid-flight costs extra GPU work.
        for (SGCardView v : cardViews) v.card.setUseGyroscope(gyroscope * settle);
        clipTop = anchor == null ? 0 : Math.max(0, clipY * (1 - settle));
        clipBottom = -1;
        if (anchor != null) anchor.setEjectProgress(slide);
        blurView.setAlpha(progress);
        buttons.setAlpha(settle);
        dots.setAlpha(settle);
        publicNote.setAlpha(settle);
        publicNote.setTranslationY(-dp(8) * (1 - settle));
        dots.setTranslationY(0);
        buttons.setTranslationY(dp(16) * (1 - settle));
        buttons.setScaleX(AndroidUtilities.lerp(.8f, 1f, settle));
        buttons.setScaleY(AndroidUtilities.lerp(.8f, 1f, settle));
        root.invalidate();
    }

    private static float spring(float t) {
        final float damping = .75f, omega = 10f;
        final float omegaD = omega * (float) Math.sqrt(1 - damping * damping);
        final float envelope = (float) Math.exp(-damping * omega * t);
        return 1 - envelope * ((float) Math.cos(omegaD * t) + damping * omega / omegaD * (float) Math.sin(omegaD * t));
    }

    private static float clamp01(float value) {
        return Math.max(0, Math.min(1, value));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setWindowAnimations(R.style.DialogNoAnimation);
        setContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        WindowManager.LayoutParams params = window.getAttributes();
        params.width = params.height = ViewGroup.LayoutParams.MATCH_PARENT;
        params.gravity = Gravity.FILL;
        params.dimAmount = 0;
        params.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
        params.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
                | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        window.setAttributes(params);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        AndroidUtilities.setLightNavigationBar(root, !Theme.isCurrentThemeDark());
        AndroidUtilities.setLightStatusBar(window, !Theme.isCurrentThemeDark());
    }

    @Override
    public void show() {
        if (!AndroidUtilities.isSafeToShow(getContext())) return;
        ScrimOptions.makeGlobalBlurBitmaps((background, button) -> {
            if (closed || dismissing) {
                background.recycle();
                button.recycle();
                return;
            }
            blurBitmap = background;
            blurView.invalidate();
            updateNoteColor();
            buttonBlur.setBitmap(button);
            root.post(() -> {
                if (closed || dismissing) return;
                Blur3Utils.checkBitmapSourceMatrixScale(buttonBlur, root);
                buttonBlurFactory.invalidateAllLinkedViews();
                cardViews[page].card.whenMaterialReady(() -> {
                    if (!closed && !dismissing) animateTo(1);
                });
            });
        });
        super.show();
        ViewCompat.requestApplyInsets(root);
    }

    private void animateTo(float target) {
        if (animator != null) animator.cancel();
        if (target == 1 && opening) return;
        if (target == 1) opening = true;
        springy = target == 1;
        pager.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        buttons.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        animator = ValueAnimator.ofFloat(progress, target);
        animator.setDuration(target == 1 ? 650 : 380);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            updateProgress();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                pager.setLayerType(View.LAYER_TYPE_NONE, null);
                buttons.setLayerType(View.LAYER_TYPE_NONE, null);
                if (target == 0) finishDismiss();
                else if (!dismissing) startGyroscope();
            }
        });
        animator.start();
    }

    private void startGyroscope() {
        if (gyroscopeAnimator != null) gyroscopeAnimator.cancel();
        gyroscopeAnimator = ValueAnimator.ofFloat(gyroscope, 1);
        gyroscopeAnimator.setDuration(320);
        gyroscopeAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT);
        gyroscopeAnimator.addUpdateListener(a -> {
            gyroscope = (float) a.getAnimatedValue();
            for (SGCardView v : cardViews) v.card.setUseGyroscope(gyroscope);
        });
        gyroscopeAnimator.start();
    }

    @Override
    public void dismiss() {
        if (closed || dismissing) return;
        dismissing = true;
        if (gyroscopeAnimator != null) gyroscopeAnimator.cancel();
        if (pageAnimator != null) pageAnimator.end();
        sendPill.setEnabled(false);
        sharePill.setEnabled(false);
        if (anchor != null) anchor.getChipBoundsOnScreen(page, source);
        if (!root.isAttachedToWindow() || progress == 0) finishDismiss();
        else animateTo(0);
    }

    private void finishDismiss() {
        if (closed) return;
        closed = true;
        pager.setLayerType(View.LAYER_TYPE_NONE, null);
        if (anchor != null) anchor.setEjectProgress(0);
        super.dismiss();
        for (SGCardView v : cardViews) v.dispose();
        buttonBlur.setBitmap(null);
        blurBitmap = null;
        if (afterDismiss != null && fragment.getParentActivity() != null) {
            Runnable action = afterDismiss;
            afterDismiss = null;
            AndroidUtilities.runOnUIThread(action);
        }
    }
}
