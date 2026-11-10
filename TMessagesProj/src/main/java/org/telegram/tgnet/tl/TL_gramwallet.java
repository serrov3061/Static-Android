package org.telegram.tgnet.tl;

import org.telegram.tgnet.InputSerializedData;
import org.telegram.tgnet.OutputSerializedData;
import org.telegram.tgnet.TLMethod;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

/**
 * StaticGram private overlay (server _schema/gramwallet.tl): the "Деньги" GRAM wallet.
 * Amounts are in nanoton (1 GRAM = 1e9).
 */
public class TL_gramwallet {

    /** gramwallet.balance#08f07d41 wallet:long gram:long */
    public static class TL_balance extends TLObject {
        public static final int constructor = 0x08f07d41;

        public long wallet;
        public long gram;

        public static TL_balance TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            return TLdeserialize(TL_balance.class, constructor == TL_balance.constructor ? new TL_balance() : null, stream, constructor, exception);
        }

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            wallet = stream.readInt64(exception);
            gram = stream.readInt64(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt64(wallet);
            stream.writeInt64(gram);
        }
    }

    /** gramwallet.result#ff0a2275 wallet:long fee:long replayed:Bool */
    public static class TL_result extends TLObject {
        public static final int constructor = 0xff0a2275;

        public long wallet;
        public long fee;
        public boolean replayed;

        public static TL_result TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            return TLdeserialize(TL_result.class, constructor == TL_result.constructor ? new TL_result() : null, stream, constructor, exception);
        }

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            wallet = stream.readInt64(exception);
            fee = stream.readInt64(exception);
            replayed = stream.readBool(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt64(wallet);
            stream.writeInt64(fee);
            stream.writeBool(replayed);
        }
    }

    /** gramwallet.getBalance#aa6a9dac = gramwallet.Balance */
    public static class TL_getBalance extends TLMethod<TL_balance> {
        public static final int constructor = 0xaa6a9dac;

        @Override
        public TL_balance deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return TL_balance.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
        }
    }

    /** gramwallet.send#270ecb99 user_id:long amount:long comment:string request_key:string = gramwallet.Result */
    public static class TL_send extends TLMethod<TL_result> {
        public static final int constructor = 0x270ecb99;

        public long user_id;
        public long amount;
        public String comment = "";
        public String request_key;

        @Override
        public TL_result deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return TL_result.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt64(user_id);
            stream.writeInt64(amount);
            stream.writeString(comment == null ? "" : comment);
            stream.writeString(request_key);
        }
    }

    /**
     * gramwallet.transferNft#376c68d3 flags:# nft_address:string to_user:flags.0?InputUser to_address:flags.1?string
     * deposit_payload:flags.2?bytes request_key:string = Bool
     * Moves an NFT out of the caller's custodial wallet; exactly one target (user, address or deposit payload).
     */
    /** gramwallet.sendToAddress flags:# address:string amount:long comment:string request_key:string = gramwallet.Result */
    public static class TL_sendToAddress extends TLMethod<TL_result> {
        public static final int constructor = 0xf4fd69e6;

        public int flags;
        public String address;
        public long amount;
        public String comment;
        public String request_key;

        @Override
        public TL_result deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return TL_result.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt32(flags);
            stream.writeString(address);
            stream.writeInt64(amount);
            stream.writeString(comment);
            stream.writeString(request_key);
        }
    }

    public static class TL_transferNft extends TLMethod<TLRPC.Bool> {
        public static final int constructor = 0x376c68d3;

        public int flags;
        public String nft_address;
        public TLRPC.InputUser to_user;
        public String to_address;
        public byte[] deposit_payload;
        public String request_key;

        @Override
        public TLRPC.Bool deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return TLRPC.Bool.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            flags = to_user != null ? (flags | 1) : (flags & ~1);
            flags = to_address != null ? (flags | 2) : (flags & ~2);
            flags = deposit_payload != null ? (flags | 4) : (flags & ~4);
            stream.writeInt32(flags);
            stream.writeString(nft_address);
            if ((flags & 1) != 0) {
                to_user.serializeToStream(stream);
            }
            if ((flags & 2) != 0) {
                stream.writeString(to_address);
            }
            if ((flags & 4) != 0) {
                stream.writeByteArray(deposit_payload);
            }
            stream.writeString(request_key);
        }
    }
}
