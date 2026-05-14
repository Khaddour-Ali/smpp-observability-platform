package com.example.smpp.repository;

import com.example.smpp.domain.model.MessageStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MessageRepository extends JpaRepository<MessageEntity, String> {

    Optional<MessageEntity> findByMessageId(String messageId);

    List<MessageEntity> findAllByStatusOrderByReceivedAtAsc(MessageStatus status, Pageable pageable);

    @Query("select m.status as status, count(m) as count from MessageEntity m group by m.status")
    List<StatusCountRow> countGroupedByStatus();

    List<MessageEntity> findAllByStatusOrderByUpdatedAtDesc(MessageStatus status);

    List<MessageEntity> findAllByStatusOrderByUpdatedAtDesc(MessageStatus status, Pageable pageable);

    @Query("select count(m) from MessageEntity m where m.status = :status and m.processedAt >= :since")
    long countProcessedSince(@Param("status") MessageStatus status, @Param("since") Instant since);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE MessageEntity m SET m.status = :queued, m.queuedAt = :ts, m.updatedAt = :ts"
            + " WHERE m.messageId = :id AND m.status = :received")
    int updateReceivedToQueued(
            @Param("id") String messageId,
            @Param("received") MessageStatus received,
            @Param("queued") MessageStatus queued,
            @Param("ts") Instant ts);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE MessageEntity m SET m.status = :processed, m.processedAt = :ts, m.updatedAt = :ts"
            + " WHERE m.messageId = :id AND m.status = :queued")
    int updateQueuedToProcessed(
            @Param("id") String messageId,
            @Param("queued") MessageStatus queued,
            @Param("processed") MessageStatus processed,
            @Param("ts") Instant ts);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE MessageEntity m SET m.status = :failed, m.lastError = :err, m.updatedAt = :ts"
            + " WHERE m.messageId = :id AND m.status = :queued")
    int updateQueuedToFailed(
            @Param("id") String messageId,
            @Param("queued") MessageStatus queued,
            @Param("failed") MessageStatus failed,
            @Param("err") String lastError,
            @Param("ts") Instant ts);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE MessageEntity m SET m.attemptCount = m.attemptCount + 1, m.updatedAt = :ts"
            + " WHERE m.messageId = :id AND m.status = :queued")
    int incrementAttemptCountForQueued(
            @Param("id") String messageId, @Param("queued") MessageStatus queued, @Param("ts") Instant ts);

    interface StatusCountRow {

        MessageStatus getStatus();

        long getCount();
    }
}
