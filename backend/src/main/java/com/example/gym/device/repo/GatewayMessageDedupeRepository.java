package com.example.gym.device.repo;

import com.example.gym.device.domain.GatewayMessageDedupe;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GatewayMessageDedupeRepository extends JpaRepository<GatewayMessageDedupe, String> {

    @Modifying
    @Query("delete from GatewayMessageDedupe d where d.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
