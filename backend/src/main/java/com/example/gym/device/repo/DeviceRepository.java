package com.example.gym.device.repo;

import com.example.gym.device.domain.Device;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface DeviceRepository extends JpaRepository<Device, Long> {

    Optional<Device> findByPublicId(String publicId);

    Page<Device> findByTenantId(Long tenantId, Pageable pageable);

    List<Device> findByTenantId(Long tenantId);

    List<Device> findByGatewayId(Long gatewayId);

    /** Forgets the reader's list checksum so its next reconcile compares the full list. */
    @Transactional
    @Modifying
    @Query("update Device d set d.rosterDigest = null where d.id = :id and d.rosterDigest is not null")
    int clearRosterDigest(@Param("id") Long id);

    interface RosterState {
        String getRosterDigest();

        Instant getRosterComparedAt();
    }

    @Query("select d.rosterDigest as rosterDigest, d.rosterComparedAt as rosterComparedAt from Device d where d.id = :id")
    Optional<RosterState> findRosterState(@Param("id") Long id);

    /** Records the checksum of a user list the server has just compared in full. */
    @Transactional
    @Modifying
    @Query("update Device d set d.rosterDigest = :digest, d.rosterComparedAt = :at where d.id = :id")
    int saveRosterDigest(@Param("id") Long id, @Param("digest") String digest, @Param("at") Instant at);
}
