package com.example.gym.device.repo;

import com.example.gym.device.domain.MemberDeviceMapping;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberDeviceMappingRepository extends JpaRepository<MemberDeviceMapping, Long> {

    Optional<MemberDeviceMapping> findByPublicId(String publicId);

    List<MemberDeviceMapping> findByMemberId(Long memberId);

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
     * Every id this reader holds or may soon hold: mapped, being moved to, on the last roster,
     * on an unacknowledged projection, or reported occupied. These are locking reads, so a caller
     * holding the reader lock sees ids committed by the previous holder.
     */
    @org.springframework.data.jpa.repository.Query(value = """
            (SELECT device_user_id FROM member_device_mapping WHERE device_id = :deviceId FOR SHARE)
            UNION ALL
            (SELECT pending_device_user_id FROM member_device_mapping
              WHERE device_id = :deviceId AND pending_device_user_id IS NOT NULL FOR SHARE)
            UNION ALL
            (SELECT device_user_id FROM device_user_snapshot WHERE device_id = :deviceId FOR SHARE)
            UNION ALL
            (SELECT device_user_id FROM desired_member WHERE device_id = :deviceId AND revision > :applied FOR SHARE)
            UNION ALL
            (SELECT device_user_id FROM reader_blocked_user WHERE device_id = :deviceId FOR SHARE)
            """, nativeQuery = true)
    List<String> findTakenDeviceUserIds(@org.springframework.data.repository.query.Param("deviceId") Long deviceId,
                                        @org.springframework.data.repository.query.Param("applied") long applied);

    boolean existsByDeviceIdAndDeviceUserId(Long deviceId, String deviceUserId);

    boolean existsByDeviceIdAndMemberId(Long deviceId, Long memberId);

    Optional<MemberDeviceMapping> findByDeviceIdAndMemberId(Long deviceId, Long memberId);
}
