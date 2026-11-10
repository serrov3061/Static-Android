package com.exteragram.messenger.security;

import android.os.SystemClock;

import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.Iterator;
import java.util.LinkedHashMap;

/**
 * exteraGram 13.1.1: remembers the amount of payment forms the client received (payments.getPaymentForm),
 * so the confirmation for payments.sendStarsForm / sendPaymentForm can show how much will be charged.
 * As in exteraGram the amount is bound to the SHA-256 of the serialized InputInvoice the form was asked
 * for: a plugin cannot show the price of a cheap form while paying another invoice with the same form id.
 */
public final class SensitivePaymentForms {

    private static final long TTL = 15 * 60 * 1000L;
    private static final LinkedHashMap<String, Payment> payments = new LinkedHashMap<>();

    public static final class Payment {
        public final long amount;
        public final String currency;
        final String invoiceHash;
        final long created = SystemClock.elapsedRealtime();

        Payment(long amount, String currency, String invoiceHash) {
            this.amount = amount;
            this.currency = currency;
            this.invoiceHash = invoiceHash;
        }
    }

    private SensitivePaymentForms() {
    }

    private static String key(int account, long formId) {
        return account + ":" + UserConfig.getInstance(account).getClientUserId() + ":" + formId;
    }

    /** SHA-256 (hex) of the serialized invoice, or null. */
    public static String invoiceHash(TLRPC.InputInvoice invoice) {
        if (invoice == null) return null;
        SerializedData data = null;
        try {
            data = new SerializedData(invoice.getObjectSize());
            invoice.serializeToStream(data);
            return Utilities.bytesToHex(Utilities.computeSHA256(data.toByteArray()));
        } catch (Throwable e) {
            return null;
        } finally {
            if (data != null) data.cleanup();
        }
    }

    /** Called with every request/response pair; keeps Stars / Stars-gift payment forms. */
    public static synchronized void remember(int account, TLObject request, TLObject response) {
        if (!(request instanceof TLRPC.TL_payments_getPaymentForm)
                || !(response instanceof TLRPC.TL_payments_paymentFormStars || response instanceof TLRPC.TL_payments_paymentFormStarGift)) {
            return;
        }
        final String hash = invoiceHash(((TLRPC.TL_payments_getPaymentForm) request).invoice);
        if (hash == null) return;
        final TLRPC.PaymentForm form = (TLRPC.PaymentForm) response;
        if (form.invoice == null || form.invoice.prices == null || form.invoice.prices.isEmpty()) {
            return;
        }
        long total = 0;
        try {
            for (TLRPC.TL_labeledPrice price : form.invoice.prices) {
                if (price == null) return;
                total = Math.addExact(total, price.amount);
            }
        } catch (ArithmeticException e) {
            return;
        }
        if (total < 0) return;
        payments.put(key(account, form.form_id), new Payment(total, form.invoice.currency, hash));
        while (payments.size() > 128) {
            final Iterator<String> it = payments.keySet().iterator();
            it.next();
            it.remove();
        }
    }

    public static synchronized Payment get(int account, long formId, String invoiceHash) {
        final String k = key(account, formId);
        final Payment payment = payments.get(k);
        if (payment != null && SystemClock.elapsedRealtime() - payment.created > TTL) {
            payments.remove(k);
            return null;
        }
        return payment != null && payment.invoiceHash.equals(invoiceHash) ? payment : null;
    }

    public static synchronized void clear(int account) {
        final String prefix = account + ":";
        payments.keySet().removeIf(k -> k.startsWith(prefix));
    }
}
