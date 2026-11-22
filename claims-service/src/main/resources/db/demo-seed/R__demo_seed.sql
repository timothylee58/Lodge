-- DEMO DATA. Loaded ONLY when this location is on spring.flyway.locations (the dev profile and the tests do that).
-- Never part of the default migration set: these accounts (incl. a MANAGER) all have the password "password".
-- Repeatable and conflict-tolerant, so it is safe to re-run and never overwrites existing rows.
INSERT INTO app_user (id, name, email, password, role, market) VALUES
('00000000-0000-0000-0000-000000000001','Aina Claimant','aina@demo.local','$2a$10$so4wXam5MTF.5I/POAmxdeoTMG2mOk6.VKQE96RI1lCVIYUlMNIY.','CLAIMANT','MY'),
('00000000-0000-0000-0000-000000000002','Wei Officer','wei@demo.local','$2a$10$so4wXam5MTF.5I/POAmxdeoTMG2mOk6.VKQE96RI1lCVIYUlMNIY.','OFFICER','MY'),
('00000000-0000-0000-0000-000000000003','Priya Officer','priya@demo.local','$2a$10$so4wXam5MTF.5I/POAmxdeoTMG2mOk6.VKQE96RI1lCVIYUlMNIY.','OFFICER','SG'),
('00000000-0000-0000-0000-000000000004','Mark Manager','mark@demo.local','$2a$10$so4wXam5MTF.5I/POAmxdeoTMG2mOk6.VKQE96RI1lCVIYUlMNIY.','MANAGER','MY')
ON CONFLICT DO NOTHING;

INSERT INTO policy (id, policy_number, holder_id, type, market, coverage_limit, currency) VALUES
('10000000-0000-0000-0000-000000000001','MY-MOT-0001','00000000-0000-0000-0000-000000000001','MOTOR','MY',50000,'MYR'),
('10000000-0000-0000-0000-000000000002','MY-PRP-0001','00000000-0000-0000-0000-000000000001','PROPERTY','MY',300000,'MYR')
ON CONFLICT DO NOTHING;
