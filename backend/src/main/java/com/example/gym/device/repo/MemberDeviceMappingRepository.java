package com.example.gym.device.repo;

import com.example.gym.device.domain.MemberDeviceMapping;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberDeviceMappingRepository extends JpaRepository<MemberDeviceMapping, Long> {

    Optional<MemberDeviceMapping> findByPublicId(String publicId);

    List<MemberDeviceMapping> findByMemberId(Long memberId);

    List<MemberDeviceMapping> findByMemberIdIn(Collection<Long> memberIds);

    @org.springframework.data.jpa.repository.Query("select distinct m.memberId from MemberDeviceMapping m")
    List<Long> findDistinctMemberIds();

    List<MemberDeviceMapping> findByDeviceId(Long deviceId);

    Optional<MemberDeviceMapping> findByDeviceIdAndDeviceUserId(Long deviceId, String deviceUserId);

    Optional<MemberDeviceMapping> findFirstByDeviceIdAndPendingDeviceUserId(Long deviceId, String pendingDeviceUserId);

    /** Every reader id in use or being moved to in the tenant, with the member holding it. */
    @org.springframework.data.jpa.repository.Query("""
            select m from MemberDeviceMapping m
            where m.tenantId = :tenantId
              and (m.deviceUserId = :deviceUserId or m.pendingDeviceUserId = :deviceUserId)
            """)
    List<MemberDeviceMapping> findHolding(@org.springframework.data.repository.query.Param("tenantId") Long tenantId,
                                          @org.springframework.data.repository.query.Param("deviceUserId") String deviceUserId);

    @org.springframework.data.jpa.repository.Query(
            "select m.deviceUserId from MemberDeviceMapping m where m.tenantId = :tenantId")
    List<String> findDeviceUserIds(@org.springframework.data.repository.query.Param("tenantId") Long tenantId);

    @org.springframework.data.jpa.repository.Query("""
            select m.pendingDeviceUserId from MemberDeviceMapping m
            where m.tenantId = :tenantId and m.pendingDeviceUserId is not null
            """)
    List<String> findPendingDeviceUserIds(@org.springframework.data.repository.query.Param("tenantId") Long tenantId);

    /**
     * Every id this reader holds or may soon hold: mapped, being moved to, seen on a reader list,
     * on an unacknowledged projection, or reported occupied. These are locking reads, so a caller
     * holding the reader lock sees ids committed by the previous holder.
     */
    @org.springframework.data.jpa.repository.Query(value = """
            (SELECT device_user_id FROM member_device_mapping WHERE device_id = :deviceId FOR SHARE)
            UNION ALL
            (SELECT pending_device_user_id FROM member_device_mapping
              WHERE device_id = :deviceId AND pending_device_user_id IS NOT NULL FOR SHARE)
            UNION ALL
            (SELECT device_user_id FROM device_observed_user WHERE device_id = :deviceId FOR SHARE)
            UNION ALL
            (SELECT device_user_id FROM desired_member WHERE device_id = :deviceId
              AND (revision > :applied OR acked_revision IS NULL OR acked_revision < revision) FOR SHARE)
            UNION ALL
            (SELECT device_user_id FROM reader_blocked_user WHERE device_id = :deviceId FOR SHARE)
            """, nativeQuery = true)
    List<String> findTakenDeviceUserIds(@org.springframework.data.repository.query.Param("deviceId") Long deviceId,
                                        @org.springframework.data.repository.query.Param("applied") long applied);

    boolean existsByDeviceIdAndDeviceUserId(Long deviceId, String deviceUserId);

    boolean existsByDeviceIdAndMemberId(Long deviceId, Long memberId);

    /** A locking read, so a caller holding the reader lock sees a mapping the previous holder committed. */
    @org.springframework.data.jpa.repository.Query(value = """
            SELECT COUNT(*) FROM member_device_mapping
             WHERE device_id = :deviceId AND member_id = :memberId FOR SHARE
            """, nativeQuery = true)
    long countLocked(@org.springframework.data.repository.query.Param("deviceId") Long deviceId,
                     @org.springframework.data.repository.query.Param("memberId") Long memberId);

    @org.springframework.data.jpa.repository.Query(value = """
            SELECT member_id FROM member_device_mapping WHERE device_id = :deviceId FOR SHARE
            """, nativeQuery = true)
    List<Long> lockMemberIdsByDeviceId(@org.springframework.data.repository.query.Param("deviceId") Long deviceId);

    Optional<MemberDeviceMapping> findByDeviceIdAndMemberId(Long deviceId, Long memberId);

    /** A locking read: sees a mapping another transaction committed after this one's snapshot. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query(
            "select m from MemberDeviceMapping m where m.deviceId = :deviceId and m.memberId = :memberId")
    Optional<MemberDeviceMapping> lockByDeviceIdAndMemberId(
            @org.springframework.data.repository.query.Param("deviceId") Long deviceId,
            @org.springframework.data.repository.query.Param("memberId") Long memberId);
}
