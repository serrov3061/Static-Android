package com.exteragram.messenger.pdf.ui;

/** Runnable that invalidates the PDF pages overlay (was an R8 synthetic lambda). */
public final class PdfPagesView$$ExternalSyntheticLambda0 implements Runnable {
    private final PdfPagesView view;

    public PdfPagesView$$ExternalSyntheticLambda0(PdfPagesView view) {
        this.view = view;
    }

    @Override
    public void run() {
        view.invalidateOverlay();
    }
}
