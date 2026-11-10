package com.exteragram.messenger.feed;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

/**
 * exteraGram feed: posts in the feed carry synthetic message ids (FeedMessageIdentityMap). Before a TL
 * request is sent, ConnectionsManager passes it here; message id fields (id, msg_id, *_msg_id, lists of
 * them, inputInvoiceMessage) holding a synthetic id are replaced with the channel's real id, and the
 * request peer / channel is fixed up to the post's channel.
 *
 * Fast path: returns immediately when the feed was never opened in this account or holds no posts
 * (FeedController.peekInstance == null or hasNoSyntheticIds), so normal requests are untouched.
 */
public final class FeedRequestNormalizer {

    private static final Field[] EMPTY_FIELDS = new Field[0];
    private static final ClassMetadata EMPTY_METADATA = new ClassMetadata(null, null, null, null, EMPTY_FIELDS);
    private static final ConcurrentHashMap<Class<?>, ClassMetadata> metadataCache = new ConcurrentHashMap<>();

    private FeedRequestNormalizer() {
    }

    private static final class ClassMetadata {
        final Field requestPeerField;
        final Field peerField;
        final Field channelField;
        final Field invoiceField;
        final Field[] messageIdFields;

        ClassMetadata(Field requestPeerField, Field peerField, Field channelField, Field invoiceField, Field[] messageIdFields) {
            this.requestPeerField = requestPeerField;
            this.peerField = peerField;
            this.channelField = channelField;
            this.invoiceField = invoiceField;
            this.messageIdFields = messageIdFields;
        }
    }

    private static long mergeResolvedDialogIds(long a, long b) {
        if (a == 0) {
            return b;
        }
        if (b == 0 || a == b) {
            return a;
        }
        return 0;
    }

    public static TLObject normalize(int account, TLObject request) {
        if (request == null) {
            return null;
        }
        FeedController controller = FeedController.peekInstance(account);
        if (controller == null || controller.hasNoSyntheticIds() || !request.getClass().getName().startsWith("org.telegram.tgnet.")) {
            return request;
        }
        try {
            ClassMetadata metadata = getMetadata(request);
            if (metadata.messageIdFields.length != 0 || metadata.invoiceField != null) {
                normalizeMessageIds(account, controller, request, metadata);
                normalizeInvoice(account, controller, getFieldValue(metadata.invoiceField, request));
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return request;
    }

    private static ClassMetadata getMetadata(Object obj) {
        if (obj == null) {
            return EMPTY_METADATA;
        }
        Class<?> cls = obj.getClass();
        ClassMetadata metadata = metadataCache.get(cls);
        if (metadata == null) {
            metadata = buildMetadata(cls);
            metadataCache.put(cls, metadata);
        }
        return metadata;
    }

    private static ClassMetadata buildMetadata(Class<?> cls) {
        Field[] fields;
        try {
            fields = cls.getFields();
        } catch (Exception e) {
            reportFailure("fields of " + cls.getName(), e);
            fields = EMPTY_FIELDS;
        }
        Field fromPeer = null;
        Field peer = null;
        Field channel = null;
        Field invoice = null;
        ArrayList<Field> idFields = null;
        for (Field field : fields) {
            String name = field.getName();
            if ("from_peer".equals(name) && fromPeer == null) {
                fromPeer = field;
            } else if ("peer".equals(name) && peer == null) {
                peer = field;
            } else if ("channel".equals(name) && channel == null) {
                channel = field;
            } else if ("invoice".equals(name) && invoice == null) {
                invoice = field;
            }
            if (isMessageIdField(field)) {
                if (idFields == null) {
                    idFields = new ArrayList<>();
                }
                idFields.add(field);
            }
        }
        return new ClassMetadata(fromPeer != null ? fromPeer : peer, peer, channel, invoice, idFields != null ? idFields.toArray(new Field[0]) : EMPTY_FIELDS);
    }

    private static void normalizeMessageIds(int account, FeedController controller, Object obj, ClassMetadata metadata) {
        Field requestPeerField = metadata.requestPeerField;
        long dialogId = getDialogId(requestPeerField, obj);
        if (dialogId == 0) {
            dialogId = getDialogId(metadata.peerField, obj);
        }
        if (dialogId == 0) {
            dialogId = getChannelDialogId(metadata.channelField, obj);
        }
        long resolved = normalizeMessageIdFields(controller, obj, metadata);
        if (resolved == 0 || resolved == dialogId) {
            return;
        }
        if (requestPeerField != null) {
            setInputPeer(account, requestPeerField, obj, resolved);
        } else if (metadata.channelField != null) {
            setInputChannel(account, metadata.channelField, obj, resolved);
        }
    }

    private static long normalizeMessageIdFields(FeedController controller, Object obj, ClassMetadata metadata) {
        long resolved = 0;
        if (obj == null) {
            return 0;
        }
        for (Field field : metadata.messageIdFields) {
            resolved = mergeResolvedDialogIds(resolved, normalizeMessageIdField(controller, obj, field));
        }
        return resolved;
    }

    private static boolean isMessageIdField(Field field) {
        if (field == null || Modifier.isStatic(field.getModifiers())) {
            return false;
        }
        String name = field.getName();
        return "id".equals(name) || "msg_id".equals(name) || name.endsWith("_msg_id");
    }

    private static void normalizeInvoice(int account, FeedController controller, Object invoice) {
        if (invoice instanceof TLRPC.TL_inputInvoiceMessage) {
            normalizeMessageIds(account, controller, invoice, getMetadata(invoice));
        }
    }

    private static long normalizeMessageIdField(FeedController controller, Object obj, Field field) {
        long resolved = 0;
        try {
            Object value = field.get(obj);
            if (value instanceof Integer) {
                int id = (Integer) value;
                long dialogId = controller.resolveRealDialogId(id);
                if (dialogId != 0) {
                    field.setInt(obj, controller.resolveRealMessageId(dialogId, id));
                    return dialogId;
                }
            } else if (value instanceof ArrayList) {
                ArrayList<?> list = (ArrayList<?>) value;
                for (int i = 0; i < list.size(); i++) {
                    Object item = list.get(i);
                    if (item instanceof Integer) {
                        int id = (Integer) item;
                        long dialogId = controller.resolveRealDialogId(id);
                        if (dialogId != 0) {
                            setListInteger(list, i, controller.resolveRealMessageId(dialogId, id));
                            resolved = mergeResolvedDialogIds(resolved, dialogId);
                        }
                    }
                }
                return resolved;
            }
        } catch (Exception e) {
            reportFailure("message id field " + field.getName(), e);
        }
        return resolved;
    }

    @SuppressWarnings("unchecked")
    private static void setListInteger(ArrayList<?> list, int index, int value) {
        ((ArrayList<Object>) list).set(index, value);
    }

    private static void setInputPeer(int account, Field field, Object obj, long dialogId) {
        if (account < 0) {
            return;
        }
        try {
            TLRPC.InputPeer inputPeer = MessagesController.getInstance(account).getInputPeer(dialogId);
            if (inputPeer != null) {
                field.set(obj, inputPeer);
            }
        } catch (Exception e) {
            reportFailure("peer field " + field.getName(), e);
        }
    }

    private static void setInputChannel(int account, Field field, Object obj, long dialogId) {
        if (account < 0 || dialogId >= 0) {
            return;
        }
        try {
            TLRPC.InputChannel inputChannel = MessagesController.getInstance(account).getInputChannel(-dialogId);
            if (inputChannel != null) {
                field.set(obj, inputChannel);
            }
        } catch (Exception e) {
            reportFailure("channel field " + field.getName(), e);
        }
    }

    private static long getDialogId(Field field, Object obj) {
        if (field == null) {
            return 0;
        }
        try {
            Object value = field.get(obj);
            if (value instanceof TLRPC.InputPeer) {
                return DialogObject.getPeerDialogId((TLRPC.InputPeer) value);
            }
        } catch (Exception e) {
            reportFailure("peer field " + field.getName(), e);
        }
        return 0;
    }

    private static long getChannelDialogId(Field field, Object obj) {
        Object value = getFieldValue(field, obj);
        if (value instanceof TLRPC.InputChannel) {
            long channelId = ((TLRPC.InputChannel) value).channel_id;
            return channelId == 0 ? 0 : -channelId;
        }
        return 0;
    }

    private static Object getFieldValue(Field field, Object obj) {
        if (field == null) {
            return null;
        }
        try {
            return field.get(obj);
        } catch (Exception e) {
            reportFailure("field " + field.getName(), e);
            return null;
        }
    }

    private static void reportFailure(String what, Exception e) {
        FileLog.e("FeedRequestNormalizer failed to normalize " + what, e);
    }
}
