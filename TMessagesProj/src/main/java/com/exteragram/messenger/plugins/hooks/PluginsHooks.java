package com.exteragram.messenger.plugins.hooks;

import com.exteragram.messenger.plugins.PluginsConstants;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

public interface PluginsHooks {
    PostRequestResult executePostRequestHook(String requestName, int account, TLObject response, TLRPC.TL_error error);

    TLObject executePreRequestHook(String requestName, int account, TLObject request);

    SendMessagesHelper.SendMessageParams executeSendMessageHook(int account, SendMessagesHelper.SendMessageParams params);

    TLRPC.Update executeUpdateHook(String updateName, int account, TLRPC.Update update);

    TLRPC.Updates executeUpdatesHook(String containerName, int account, TLRPC.Updates updates);

    public static final class PostRequestResult {
        private TLRPC.TL_error error;
        private TLObject response;

        public PostRequestResult(TLObject tLObject, TLRPC.TL_error tL_error) {
            this.response = tLObject;
            this.error = tL_error;
        }

        public final TLObject getResponse() {
            return this.response;
        }

        public final void setResponse(TLObject tLObject) {
            this.response = tLObject;
        }

        public final TLRPC.TL_error getError() {
            return this.error;
        }

        public final void setError(TLRPC.TL_error tL_error) {
            this.error = tL_error;
        }
    }
}
