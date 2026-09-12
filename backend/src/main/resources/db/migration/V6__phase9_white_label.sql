-- Phase 9: white-label branding on gym_profile (URLs; empty = frontend defaults).

ALTER TABLE gym_profile
    ADD COLUMN display_name VARCHAR(150) NULL AFTER hours,
    ADD COLUMN logo_url VARCHAR(500) NULL AFTER display_name,
    ADD COLUMN hero_image_url VARCHAR(500) NULL AFTER logo_url,
    ADD COLUMN training_image_url VARCHAR(500) NULL AFTER hero_image_url,
    ADD COLUMN facilities_image_url VARCHAR(500) NULL AFTER training_image_url,
    ADD COLUMN section_training_title VARCHAR(120) NULL AFTER facilities_image_url,
    ADD COLUMN section_training_body VARCHAR(1000) NULL AFTER section_training_title,
    ADD COLUMN section_facilities_title VARCHAR(120) NULL AFTER section_training_body,
    ADD COLUMN section_facilities_body VARCHAR(1000) NULL AFTER section_facilities_title;
