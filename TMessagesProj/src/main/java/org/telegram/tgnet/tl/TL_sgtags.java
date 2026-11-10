package org.telegram.tgnet.tl;

import org.telegram.tgnet.InputSerializedData;
import org.telegram.tgnet.OutputSerializedData;
import org.telegram.tgnet.TLMethod;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;

import java.util.ArrayList;

/**
 * StaticGram private overlay (server _schema/sgtags.tl): member tag colors.
 * Official Telegram has no color for a participant tag (rank), so the color is
 * kept outside chatParticipant/channelParticipant and fetched per chat.
 */
public class TL_sgtags {

    public static class TL_tagColor extends TLObject {
        public static final int constructor = 0x2401dfec;

        public long user_id;
        public int color;

        public static TL_tagColor TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            return TLdeserialize(TL_tagColor.class, constructor == TL_tagColor.constructor ? new TL_tagColor() : null, stream, constructor, exception);
        }

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            user_id = stream.readInt64(exception);
            color = stream.readInt32(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeInt64(user_id);
            stream.writeInt32(color);
        }
    }

    public static class TL_tagColors extends TLObject {
        public static final int constructor = 0x5d1ebc4d;

        public ArrayList<TL_tagColor> colors = new ArrayList<>();

        public static TL_tagColors TLdeserialize(InputSerializedData stream, int constructor, boolean exception) {
            return TLdeserialize(TL_tagColors.class, constructor == TL_tagColors.constructor ? new TL_tagColors() : null, stream, constructor, exception);
        }

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            colors = Vector.deserialize(stream, TL_tagColor::TLdeserialize, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            Vector.serialize(stream, colors);
        }
    }

    public static class TL_getTagColors extends TLMethod<TL_tagColors> {
        public static final int constructor = 0x0a30a2bd;

        public TLRPC.InputPeer peer;

        @Override
        public TL_tagColors deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return TL_tagColors.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            peer.serializeToStream(stream);
        }
    }

    /** color < 0 clears the color (the client default applies again). */
    public static class TL_setTagColor extends TLMethod<TLRPC.Bool> {
        public static final int constructor = 0xd31e06b5;

        public int flags;
        public TLRPC.InputPeer peer;
        public TLRPC.InputPeer participant;
        public int color = -1;

        @Override
        public TLRPC.Bool deserializeResponseT(InputSerializedData stream, int constructor, boolean exception) {
            return TLRPC.Bool.TLdeserialize(stream, constructor, exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            flags = color >= 0 ? (flags | 1) : (flags & ~1);
            stream.writeInt32(flags);
            peer.serializeToStream(stream);
            participant.serializeToStream(stream);
            if ((flags & 1) != 0) {
                stream.writeInt32(color & 0xFFFFFF);
            }
        }
    }
}
