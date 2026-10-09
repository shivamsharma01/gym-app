package com.example.gym.device;

import com.example.gym.device.domain.ReaderRevision;
import com.example.gym.device.repo.ReaderRevisionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
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
        ReaderRevision cursor = repository.findByDeviceId(deviceId)
                .orElseGet(() -> repository.saveAndFlush(new ReaderRevision(tenantId, deviceId)));
        return reload(cursor);
    }

    /**
     * Rereads a row that belongs to a locked reader, with a locking read. A row read before the
     * lock may predate the previous holder's commit. Pending changes are flushed first so the
     * reread does not discard them.
     */
    public <T> T reload(T row) {
        entityManager.flush();
        entityManager.refresh(row, LockModeType.PESSIMISTIC_WRITE);
        return row;
    }
}
