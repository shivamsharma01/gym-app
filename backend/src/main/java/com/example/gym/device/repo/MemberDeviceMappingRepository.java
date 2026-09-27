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

    boolean existsByDeviceIdAndDeviceUserId(Long deviceId, String deviceUserId);

    boolean existsByDeviceIdAndMemberId(Long deviceId, Long memberId);

    Optional<MemberDeviceMapping> findByDeviceIdAndMemberId(Long deviceId, Long memberId);
}
