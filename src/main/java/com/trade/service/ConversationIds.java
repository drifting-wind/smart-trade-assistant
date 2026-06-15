package com.trade.service;

import java.util.UUID;

final class ConversationIds {

    private ConversationIds() {
    }

    static String ensure(String conversationId) {
        return conversationId == null || conversationId.isBlank() ? UUID.randomUUID().toString() : conversationId;
    }
}
