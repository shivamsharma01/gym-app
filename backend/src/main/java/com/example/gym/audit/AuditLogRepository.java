package com.example.gym.audit;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {

    Page<AuditLog> findByTenantIdOrderByCreatedAtDesc(Long tenantId, Pageable pageable);

    Page<AuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("""
            select distinct a.actorUsername from AuditLog a
            where a.tenantId = :tenantId and a.actorUsername is not null
            order by a.actorUsername
            """)
    List<String> findActors(@Param("tenantId") Long tenantId);

    @Query("""
            select distinct a.actorUsername from AuditLog a
            where a.actorUsername is not null
            order by a.actorUsername
            """)
    List<String> findAllActors();

    @Query("""
            select distinct a.action from AuditLog a
            where a.tenantId = :tenantId
            order by a.action
            """)
    List<String> findActions(@Param("tenantId") Long tenantId);

    @Query("select distinct a.action from AuditLog a order by a.action")
    List<String> findAllActions();
}
