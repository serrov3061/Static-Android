package org.telegram.tgnet.tl;

import org.telegram.tgnet.InputSerializedData;
import org.telegram.tgnet.OutputSerializedData;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLMethod;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;

import java.util.ArrayList;

/**
 * StaticGram private overlay (server _schema/sgcard.tl): profile wallet cards.
 * Kind 0 is the GRAM card (balance in nanoton), kind 1 the Stars card (balance in Stars).
 */
public class TL_sgcard {

    public static final int KIND_GRAM = 0;
    public static final int KIND_STARS = 1;
    public static final int MAX_STICKERS = 12;

    /** sgcard.sticker#ae3e8045 flags:# gift:flags.0?StarGift document:flags.1?Document x:double y:double scale:double rotation:double */
    public static class TL_sticker extends TLObject {
        public static final int constructor = 0xae3e8045;

        public int flags;
        public TL_stars.StarGift gift;
        public TLRPC.Document document;
        public double x, y, scale, rotation;

        public static TL_sticker TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            return TLdeserialize(TL_sticker.class, constructor == TL_sticker.constructor ? new TL_sticker() : null, stream, constructor, exception);
        }

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            flags = stream.readInt32(exception);
            if ((flags & 1) != 0) {
                gift = TL_stars.StarGift.TLdeserialize(stream, stream.readInt32(exception), exception);
            }
            if ((flags & 2) != 0) {
                document = TLRPC.Document.TLdeserialize(stream, stream.readInt32(exception), exception);
            }
            x = stream.readDouble(exception);
            y = stream.readDouble(exception);
            scale = stream.readDouble(exception);
            rotation = stream.readDouble(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            flags = gift != null ? (flags | 1) : (flags & ~1);
            flags = document != null ? (flags | 2) : (flags & ~2);
            stream.writeInt32(flags);
            if (gift != null) gift.serializeToStream(stream);
            if (document != null) document.serializeToStream(stream);
            stream.writeDouble(x);
            stream.writeDouble(y);
            stream.writeDouble(scale);
            stream.writeDouble(rotation);
        }
    }

    /** sgcard.inputSticker#44d6c962 flags:# gift_slug:flags.0?string document:flags.1?InputDocument x:double y:double scale:double rotation:double */
    public static class TL_inputSticker extends TLObject {
        public static final int constructor = 0x44d6c962;

        public int flags;
        public String gift_slug;
        public TLRPC.InputDocument document;
        public double x, y, scale, rotation;

        public static TL_inputSticker TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            return TLdeserialize(TL_inputSticker.class, constructor == TL_inputSticker.constructor ? new TL_inputSticker() : null, stream, constructor, exception);
        }

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            flags = stream.readInt32(exception);
            if ((flags & 1) != 0) gift_slug = stream.readString(exception);
            if ((flags & 2) != 0) document = TLRPC.InputDocument.TLdeserialize(stream, stream.readInt32(exception), exception);
            x = stream.readDouble(exception);
            y = stream.readDouble(exception);
            scale = stream.readDouble(exception);
            rotation = stream.readDouble(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            flags = gift_slug != null ? (flags | 1) : (flags & ~1);
            flags = document != null ? (flags | 2) : (flags & ~2);
            stream.writeInt32(flags);
            if (gift_slug != null) stream.writeString(gift_slug);
            if (document != null) document.serializeToStream(stream);
            stream.writeDouble(x);
            stream.writeDouble(y);
            stream.writeDouble(scale);
            stream.writeDouble(rotation);
        }
    }

    /** sgcard.card#70632282 flags:# kind:int background:flags.0?StarGift stickers:Vector<sgcard.Sticker> balance:long */
    public static class TL_card extends TLObject {
        public static final int constructor = 0x70632282;

        public int flags;
        public int kind;
        public TL_stars.StarGift background;
        public ArrayList<TL_sticker> stickers = new ArrayList<>();
        public long balance;

        public static TL_card TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            return TLdeserialize(TL_card.class, constructor == TL_card.constructor ? new TL_card() : null, stream, constructor, exception);
        }

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            flags = stream.readInt32(exception);
            kind = stream.readInt32(exception);
            if ((flags & 1) != 0) {
                background = TL_stars.StarGift.TLdeserialize(stream, stream.readInt32(exception), exception);
            }
            stickers = Vector.deserialize(stream, TL_sticker::TLdeserialize, exception);
            balance = stream.readInt64(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            flags = background != null ? (flags | 1) : (flags & ~1);
            stream.writeInt32(flags);
            stream.writeInt32(kind);
            if (background != null) background.serializeToStream(stream);
            Vector.serialize(stream, stickers);
            stream.writeInt64(balance);
        }
    }

    /** sgcard.cards#81f8a95e flags:# purchased:flags.0?true cards:Vector<sgcard.Card> users:Vector<User> */
    public static class TL_cards extends TLObject {
        public static final int constructor = 0x81f8a95e;

        public int flags;
        public boolean purchased;
        public ArrayList<TL_card> cards = new ArrayList<>();
        public ArrayList<TLRPC.User> users = new ArrayList<>();

        public static TL_cards TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            return TLdeserialize(TL_cards.class, constructor == TL_cards.constructor ? new TL_cards() : null, stream, constructor, exception);
        }

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            flags = stream.readInt32(exception);
            purchased = (flags & 1) != 0;
            cards = Vector.deserialize(stream, TL_card::TLdeserialize, exception);
            users = Vector.deserialize(stream, TLRPC.User::TLdeserialize, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            flags = purchased ? (flags | 1) : (flags & ~1);
            stream.writeInt32(flags);
            Vector.serialize(stream, cards);
            Vector.serialize(stream, users);
        }

        public TL_card get(int kind) {
            for (TL_card c : cards) {
                if (c.kind == kind) return c;
            }
            return null;
        }
    }

    /** sgcard.starsResult#704a54d8 balance:long replayed:Bool */
    public static class TL_starsResult extends TLObject {
        public static final int constructor = 0x704a54d8;

        public long balance;
        public boolean replayed;

        public static TL_starsResult TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            return TLdeserialize(TL_starsResult.class, constructor == TL_starsResult.constructor ? new TL_starsResult() : null, stream, constructor, exception);
        }

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            balance = stream.readInt64(exception);
            replayed = stream.readBool(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt64(balance);
            stream.writeBool(replayed);
        }
    }

    /** sgcard.getCards#a4ea727d user_id:InputUser = sgcard.Cards */
    public static class TL_getCards extends TLMethod<TL_cards> {
        public static final int constructor = 0xa4ea727d;

        public TLRPC.InputUser user_id;

        @Override
        public TL_cards deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return TL_cards.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            user_id.serializeToStream(stream);
        }
    }

    /** sgcard.saveCard#68500e80 flags:# kind:int background_slug:flags.0?string stickers:Vector<sgcard.InputSticker> = sgcard.Card */
    public static class TL_saveCard extends TLMethod<TL_card> {
        public static final int constructor = 0x68500e80;

        public int flags;
        public int kind;
        public String background_slug;
        public ArrayList<TL_inputSticker> stickers = new ArrayList<>();

        @Override
        public TL_card deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return TL_card.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            flags = background_slug != null ? (flags | 1) : (flags & ~1);
            stream.writeInt32(flags);
            stream.writeInt32(kind);
            if (background_slug != null) stream.writeString(background_slug);
            Vector.serialize(stream, stickers);
        }
    }

    /** sgcard.sendStars#a9341b5a user_id:InputUser amount:long comment:string request_key:string = sgcard.StarsResult */
    public static class TL_sendStars extends TLMethod<TL_starsResult> {
        public static final int constructor = 0xa9341b5a;

        public TLRPC.InputUser user_id;
        public long amount;
        public String comment = "";
        public String request_key;

        @Override
        public TL_starsResult deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return TL_starsResult.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            user_id.serializeToStream(stream);
            stream.writeInt64(amount);
            stream.writeString(comment == null ? "" : comment);
            stream.writeString(request_key);
        }
    }

    /** sgcard.getMoneyPrivacy#7d6e382c = account.PrivacyRules (who can send me money: Stars and GRAM). */
    public static class TL_getMoneyPrivacy extends TLMethod<TL_account.privacyRules> {
        public static final int constructor = 0x7d6e382c;

        @Override
        public TL_account.privacyRules deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return TL_account.privacyRules.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
        }
    }

    /** sgcard.setMoneyPrivacy#f59b65ff rules:Vector<InputPrivacyRule> = account.PrivacyRules */
    public static class TL_setMoneyPrivacy extends TLMethod<TL_account.privacyRules> {
        public static final int constructor = 0xf59b65ff;

        public ArrayList<TLRPC.InputPrivacyRule> rules = new ArrayList<>();

        @Override
        public TL_account.privacyRules deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return TL_account.privacyRules.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            Vector.serialize(stream, rules);
        }
    }

    /**
     * Serialization self-check (no JVM test source set in this project): writes each
     * sgcard type, reads it back and compares the fields. Returns null when all pass,
     * otherwise the name of the first failing check.
     */
    public static String selfCheck() {
        try {
            TL_inputSticker in = new TL_inputSticker();
            in.gift_slug = "PlushPepe-12";
            in.x = .25; in.y = .75; in.scale = .3; in.rotation = -45;
            TL_inputSticker in2 = roundTrip(in, TL_inputSticker::TLdeserialize);
            if (in2 == null || !"PlushPepe-12".equals(in2.gift_slug) || in2.document != null || in2.x != .25 || in2.y != .75 || in2.scale != .3 || in2.rotation != -45) return "inputSticker";

            TL_card card = new TL_card();
            card.kind = KIND_STARS;
            card.balance = 1234567890123L;
            TL_sticker st = new TL_sticker();
            st.x = .5; st.y = .5; st.scale = .2; st.rotation = 10;
            card.stickers.add(st);
            TL_card card2 = roundTrip(card, TL_card::TLdeserialize);
            if (card2 == null || card2.kind != KIND_STARS || card2.balance != 1234567890123L || card2.background != null || card2.stickers.size() != 1 || card2.stickers.get(0).rotation != 10) return "card";

            TL_cards cards = new TL_cards();
            cards.purchased = true;
            cards.cards.add(card);
            TL_cards cards2 = roundTrip(cards, TL_cards::TLdeserialize);
            if (cards2 == null || !cards2.purchased || cards2.get(KIND_STARS) == null || cards2.get(KIND_GRAM) != null) return "cards";

            TL_starsResult r = new TL_starsResult();
            r.balance = 42; r.replayed = true;
            TL_starsResult r2 = roundTrip(r, TL_starsResult::TLdeserialize);
            if (r2 == null || r2.balance != 42 || !r2.replayed) return "starsResult";

            TL_gramwallet.TL_result g = new TL_gramwallet.TL_result();
            g.wallet = 5_000_000_000L; g.fee = 1; g.replayed = false;
            TL_gramwallet.TL_result g2 = roundTrip(g, TL_gramwallet.TL_result::TLdeserialize);
            if (g2 == null || g2.wallet != 5_000_000_000L || g2.fee != 1 || g2.replayed) return "gramwallet.result";
            return null;
        } catch (Throwable e) {
            return e.toString();
        }
    }

    private interface Reader<T> {
        T read(InputSerializedData stream, int constructor, boolean exception);
    }

    private static <T extends TLObject> T roundTrip(TLObject object, Reader<T> reader) {
        SerializedData out = new SerializedData(object.getObjectSize());
        object.serializeToStream(out);
        SerializedData in = new SerializedData(out.toByteArray());
        T result = reader.read(in, in.readInt32(true), true);
        if (in.remaining() != 0) return null;
        return result;
    }
}
