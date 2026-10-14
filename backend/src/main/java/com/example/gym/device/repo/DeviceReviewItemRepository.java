package com.example.gym.device.repo;

import com.example.gym.device.domain.DeviceReviewItem;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeviceReviewItemRepository extends JpaRepository<DeviceReviewItem, Long> {

    Optional<DeviceReviewItem> findByPublicId(String publicId);

    /** Fails when more than one member has an item at this id. */
    Optional<DeviceReviewItem> findByDeviceIdAndDeviceUserId(Long deviceId, String deviceUserId);

    Optional<DeviceReviewItem> findByDeviceIdAndDeviceUserIdAndMemberId(Long deviceId, String deviceUserId,
                                                                        Long memberId);

    boolean existsByDeviceIdAndMemberIdAndKind(Long deviceId, Long memberId, String kind);

    List<DeviceReviewItem> findByDeviceIdAndMemberIdAndKindAndResolvedFalse(Long deviceId, Long memberId, String kind);

    List<DeviceReviewItem> findByDeviceId(Long deviceId);

    List<DeviceReviewItem> findByMemberId(Long memberId);

    List<DeviceReviewItem> findByTenantIdAndResolvedFalse(Long tenantId);
}
