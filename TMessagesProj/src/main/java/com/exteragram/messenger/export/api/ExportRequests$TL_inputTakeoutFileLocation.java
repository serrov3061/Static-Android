package com.exteragram.messenger.export.api;

import org.telegram.tgnet.OutputSerializedData;
import org.telegram.tgnet.TLRPC;

/** inputTakeoutFileLocation#29be5899 (restored from smali). */
public class ExportRequests$TL_inputTakeoutFileLocation extends TLRPC.InputFileLocation {
    @Override
    public void serializeToStream(OutputSerializedData stream) {
        stream.writeInt32(0x29be5899);
    }
}
