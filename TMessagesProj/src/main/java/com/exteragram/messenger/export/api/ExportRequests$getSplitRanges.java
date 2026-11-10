package com.exteragram.messenger.export.api;

import org.telegram.tgnet.InputSerializedData;
import org.telegram.tgnet.OutputSerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;

/** messages.getSplitRanges#1cff7e08 = Vector&lt;MessageRange&gt; (restored from smali). */
public class ExportRequests$getSplitRanges extends TLObject {
    @Override
    public TLObject deserializeResponse(InputSerializedData stream, int constructor, boolean exception) {
        Vector vector = new Vector(TLRPC.TL_messageRange::TLdeserialize);
        vector.readParams(stream, exception);
        return vector;
    }

    @Override
    public void serializeToStream(OutputSerializedData stream) {
        stream.writeInt32(0x1cff7e08);
    }
}
