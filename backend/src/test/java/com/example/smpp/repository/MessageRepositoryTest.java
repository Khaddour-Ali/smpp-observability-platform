package com.example.smpp.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.smpp.AbstractPostgresIntegrationTest;
import com.example.smpp.domain.model.Message;
import com.example.smpp.domain.model.MessageStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class MessageRepositoryTest extends AbstractPostgresIntegrationTest {

    @Autowired
    MessageRepository repository;

    @PersistenceContext
    EntityManager entityManager;

    @Test
    void saveAndFindByMessageId_received() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Message in = Message.received(UUID.randomUUID().toString(), "sys1", "123", "456", "hi", now);
        repository.save(MessageEntityMapper.toEntity(in));

        Optional<MessageEntity> loaded = repository.findByMessageId(in.getMessageId());
        assertThat(loaded).isPresent();
        Message out = MessageEntityMapper.toDomain(loaded.orElseThrow());

        assertThat(out.getBody()).isEqualTo("hi");
        assertThat(out.getStatus()).isEqualTo(MessageStatus.RECEIVED);
        assertThat(out.getQueuedAt()).isNull();
        assertThat(out.getProcessedAt()).isNull();
        assertThat(out.getAttemptCount()).isZero();
        assertThat(out.getLastError()).isNull();
    }

    @Test
    void messageStatusStoredAsReadableStringEnum() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String id = UUID.randomUUID().toString();
        repository.save(MessageEntityMapper.toEntity(Message.received(id, "s", "a", "b", "body", now)));
        entityManager.flush();

        Object raw
                = entityManager
                        .createNativeQuery(
                                "select status from messages where message_id = :id")
                        .setParameter("id", id)
                        .getSingleResult();

        assertThat(raw).hasToString(MessageStatus.RECEIVED.name());
    }

    @Test
    void countGroupedByStatus() {
        Instant t = Instant.now().truncatedTo(ChronoUnit.MICROS);
        repository.save(
                MessageEntityMapper.toEntity(Message.received(newId(), "s", "1", "2", "a", t)));
        MessageEntity queued = MessageEntityMapper.toEntity(Message.received(newId(), "s", "1", "2", "b", t.plusSeconds(1)));
        queued.setStatus(MessageStatus.QUEUED);
        queued.setQueuedAt(t.plusSeconds(2));
        queued.setUpdatedAt(t.plusSeconds(2));
        repository.save(queued);

        MessageEntity failed = MessageEntityMapper.toEntity(Message.received(newId(), "s", "1", "2", "c", t.plusSeconds(3)));
        failed.setStatus(MessageStatus.FAILED);
        failed.setLastError("boom");
        failed.setUpdatedAt(t.plusSeconds(4));
        repository.save(failed);

        MessageEntity processed = MessageEntityMapper.toEntity(Message.received(newId(), "s", "1", "2", "d", t.plusSeconds(5)));
        processed.setStatus(MessageStatus.PROCESSED);
        processed.setProcessedAt(t.plusSeconds(6));
        processed.setUpdatedAt(t.plusSeconds(6));
        repository.save(processed);

        Map<MessageStatus, Long> counts
                = repository.countGroupedByStatus().stream()
                        .collect(
                                Collectors.toMap(
                                        MessageRepository.StatusCountRow::getStatus,
                                        MessageRepository.StatusCountRow::getCount));

        assertThat(counts.get(MessageStatus.RECEIVED)).isEqualTo(1L);
        assertThat(counts.get(MessageStatus.QUEUED)).isEqualTo(1L);
        assertThat(counts.get(MessageStatus.FAILED)).isEqualTo(1L);
        assertThat(counts.get(MessageStatus.PROCESSED)).isEqualTo(1L);
    }

    @Test
    void listFailed_orderedByUpdatedAtDesc_includesLastError() {
        Instant t = Instant.now().truncatedTo(ChronoUnit.MICROS);
        MessageEntity old = MessageEntityMapper.toEntity(Message.received(newId(), "s", "1", "2", "a", t));
        old.setStatus(MessageStatus.FAILED);
        old.setLastError("first");
        old.setUpdatedAt(t);
        repository.save(old);

        MessageEntity recent = MessageEntityMapper.toEntity(Message.received(newId(), "s", "1", "2", "b", t.plusSeconds(1)));
        recent.setStatus(MessageStatus.FAILED);
        recent.setLastError("second");
        recent.setUpdatedAt(t.plusSeconds(10));
        repository.save(recent);

        List<MessageEntity> failed = repository.findAllByStatusOrderByUpdatedAtDesc(MessageStatus.FAILED);

        assertThat(failed).hasSize(2);
        assertThat(failed.get(0).getLastError()).isEqualTo("second");
        assertThat(failed.get(1).getLastError()).isEqualTo("first");
    }

    @Test
    void listFailed_withPageable_respectsLimitAndOrder() {
        Instant t = Instant.now().truncatedTo(ChronoUnit.MICROS);
        for (int i = 0; i < 5; i++) {
            MessageEntity e
                    = MessageEntityMapper.toEntity(
                            Message.received(newId(), "s", "1", "2", "row" + i, t.plusMillis(i)));
            e.setStatus(MessageStatus.FAILED);
            e.setLastError("err" + i);
            e.setUpdatedAt(t.plusSeconds(i));
            repository.save(e);
        }

        List<MessageEntity> page
                = repository.findAllByStatusOrderByUpdatedAtDesc(
                        MessageStatus.FAILED, PageRequest.of(0, 2));

        assertThat(page).hasSize(2);
        assertThat(page.get(0).getLastError()).isEqualTo("err4");
        assertThat(page.get(1).getLastError()).isEqualTo("err3");
    }

    @Test
    void countProcessedSince() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant cutoff = now.minus(60, ChronoUnit.SECONDS);

        MessageEntity inWindow = MessageEntityMapper.toEntity(Message.received(newId(), "s", "1", "2", "a", now.minusSeconds(30)));
        inWindow.setStatus(MessageStatus.PROCESSED);
        inWindow.setProcessedAt(now.minusSeconds(35));
        inWindow.setUpdatedAt(now.minusSeconds(35));
        repository.save(inWindow);

        MessageEntity outWindow = MessageEntityMapper.toEntity(Message.received(newId(), "s", "1", "2", "b", now.minusSeconds(120)));
        outWindow.setStatus(MessageStatus.PROCESSED);
        outWindow.setProcessedAt(now.minusSeconds(121));
        outWindow.setUpdatedAt(now.minusSeconds(121));
        repository.save(outWindow);

        long count
                = repository.countProcessedSince(MessageStatus.PROCESSED, cutoff);

        assertThat(count).isEqualTo(1L);
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}
