package com.example.gym.device;

import com.example.gym.common.logging.FlowLog;
import com.example.gym.device.domain.ReaderRevision;
import com.example.gym.device.repo.ReaderRevisionRepository;
import com.example.gym.member.Member;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The revision cursor of one reader. Every writer of that reader's desired state calls
 * {@link #lock} before it bumps a revision, allocates a device user id, or records an
 * acknowledgement, so those writers run one at a time per reader. Other readers are not blocked.
 */
@Component
public class ReaderRevisions {

    private final ReaderRevisionRepository repository;
    private final EntityManager entityManager;

    public ReaderRevisions(ReaderRevisionRepository repository, EntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    public Optional<ReaderRevision> find(Long deviceId) {
        return repository.findByDeviceId(deviceId);
    }

    /**
     * Holds this reader's row lock until the transaction ends. The refresh is a locking read, so
     * the values are the latest committed ones even when this transaction's snapshot is older.
     */
    public ReaderRevision lock(Long tenantId, Long deviceId) {
        long started = System.nanoTime();
        ReaderRevision cursor = repository.findByDeviceId(deviceId)
                .orElseGet(() -> repository.saveAndFlush(new ReaderRevision(tenantId, deviceId)));
        ReaderRevision locked = reload(cursor);
        long waitMs = (System.nanoTime() - started) / 1_000_000L;
        // Same-reader writers (list batches, desired apply, review, seed) share this row; Entry/Exit do not.
        if (waitMs >= 50) {
            FlowLog.info("device", "reader_revision lock deviceId={} waitMs={}", deviceId, waitMs);
        }
        return locked;
    }

    /**
     * Rereads a row that belongs to a locked reader, with a locking read. A row read before the
     * lock may predate the previous holder's commit. Pending changes are flushed first so the
     * reread does not discard them. A row this transaction already holds the lock on is current
     * and is returned as it is: a second refresh is read without the lock, from the transaction's
     * older snapshot, and would replace the current values with earlier ones.
     */
    public <T> T reload(T row) {
        if (entityManager.contains(row) && entityManager.getLockMode(row) == LockModeType.PESSIMISTIC_WRITE) {
            return row;
        }
        entityManager.flush();
        entityManager.refresh(row, LockModeType.PESSIMISTIC_WRITE);
        return row;
    }

    /**
     * True when the member row still carries the version this transaction read. The check is a
     * locking read that never waits: a row another transaction is changing counts as changed. A
     * caller holding a reader lock must not wait on a member row, because a member change can hold
     * that row while it waits for the same reader.
     */
    public boolean unchangedSince(Member member) {
        List<?> rows = entityManager.createNativeQuery(
                        "SELECT version FROM member WHERE id = ?1 FOR SHARE SKIP LOCKED")
                .setParameter(1, member.getId())
                .getResultList();
        return rows.size() == 1 && Objects.equals(((Number) rows.getFirst()).longValue(), member.getVersion());
    }
}
