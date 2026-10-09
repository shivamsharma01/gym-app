package com.example.gym.face;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GatewayFaceUploadRepository extends JpaRepository<GatewayFaceUpload, Long> {

    Optional<GatewayFaceUpload> findByPublicId(String publicId);

    Optional<GatewayFaceUpload> findFirstByTenantIdAndSha256AndConsumedFalseOrderByIdDesc(
            Long tenantId, String sha256);

    List<GatewayFaceUpload> findByCreatedAtBefore(Instant cutoff);
}
