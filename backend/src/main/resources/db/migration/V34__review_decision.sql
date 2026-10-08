-- V14: a staff decision is stored on the review row and becomes a desired revision.
-- A rejected enrollment removes the device user and does not create a member.

ALTER TABLE device_review_item
    ADD COLUMN decision VARCHAR(32) NULL,
    ADD COLUMN actor VARCHAR(100) NULL,
    ADD COLUMN prior_state VARCHAR(255) NULL,
    ADD COLUMN chosen_state VARCHAR(255) NULL,
    ADD COLUMN decision_revision BIGINT NULL,
    ADD COLUMN verification_error VARCHAR(255) NULL,
    ADD COLUMN resolved BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE pending_enrollment
    ADD COLUMN decision VARCHAR(32) NULL,
    ADD COLUMN actor VARCHAR(100) NULL,
    ADD COLUMN prior_state VARCHAR(255) NULL,
    ADD COLUMN chosen_state VARCHAR(255) NULL,
    ADD COLUMN decision_revision BIGINT NULL,
    ADD COLUMN verification_error VARCHAR(255) NULL,
    ADD COLUMN resolved BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE desired_member DROP FOREIGN KEY fk_desired_member_member;
ALTER TABLE desired_member MODIFY member_id BIGINT NULL;
ALTER TABLE desired_member
    ADD CONSTRAINT fk_desired_member_member FOREIGN KEY (member_id) REFERENCES member (id);
