-- A gym has one gateway. The row stays its own table because it holds the credential,
-- enrollment, and connection, which are not columns of the gym.

ALTER TABLE gateway
    DROP INDEX idx_gateway_tenant,
    ADD UNIQUE KEY uq_gateway_tenant (tenant_id);
