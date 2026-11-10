package com.exteragram.messenger.export.api;

import org.telegram.tgnet.InputSerializedData;
import org.telegram.tgnet.OutputSerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.Vector;

/** contacts.getSaved#82f1e39f = Vector&lt;SavedContact&gt; (restored from smali). */
public class ExportRequests$TL_contacts_getSaved extends TLObject {
    @Override
    public TLObject deserializeResponse(InputSerializedData stream, int constructor, boolean exception) {
        Vector vector = new Vector(ExportRequests$SavedContact::TLdeserialize);
        vector.readParams(stream, exception);
        return vector;
    }

    @Override
    public void serializeToStream(OutputSerializedData stream) {
        stream.writeInt32(0x82f1e39f);
    }
}
