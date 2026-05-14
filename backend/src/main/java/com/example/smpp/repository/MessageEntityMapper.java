package com.example.smpp.repository;

import com.example.smpp.domain.model.Message;

/**
 * Maps between domain {@link Message} and {@link MessageEntity}. No business
 * rules.
 */
public final class MessageEntityMapper {

    private MessageEntityMapper() {
    }

    public static MessageEntity toEntity(Message message) {
        MessageEntity e = new MessageEntity();
        e.setMessageId(message.getMessageId());
        e.setSystemId(message.getSystemId());
        e.setSourceAddr(message.getSourceAddr());
        e.setDestinationAddr(message.getDestinationAddr());
        e.setBody(message.getBody());
        e.setStatus(message.getStatus());
        e.setReceivedAt(message.getReceivedAt());
        e.setQueuedAt(message.getQueuedAt());
        e.setProcessedAt(message.getProcessedAt());
        e.setAttemptCount(message.getAttemptCount());
        e.setLastError(message.getLastError());
        e.setTraceContext(message.getTraceContext());
        e.setUpdatedAt(message.getUpdatedAt());
        return e;
    }

    public static Message toDomain(MessageEntity e) {
        return new Message(
                e.getMessageId(),
                e.getSystemId(),
                e.getSourceAddr(),
                e.getDestinationAddr(),
                e.getBody(),
                e.getStatus(),
                e.getReceivedAt(),
                e.getQueuedAt(),
                e.getProcessedAt(),
                e.getAttemptCount(),
                e.getLastError(),
                e.getTraceContext(),
                e.getUpdatedAt());
    }
}
