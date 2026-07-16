package com.trade.conversation;

import java.util.UUID;

public final class ConversationIds {

    private ConversationIds() {
    }

    public static String ensure(String conversationId) {
        return conversationId == null || conversationId.isBlank() ? UUID.randomUUID().toString() : conversationId;
    }
}
