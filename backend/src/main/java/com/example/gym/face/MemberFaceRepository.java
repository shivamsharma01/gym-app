package com.example.gym.face;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberFaceRepository extends JpaRepository<MemberFace, Long> {

    Optional<MemberFace> findByMemberId(Long memberId);

    List<MemberFace> findByMemberIdIn(Collection<Long> memberIds);
}
