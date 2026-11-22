-- Demo only. All seeded users have password: password  (BCrypt hash generated locally and verified by login).
INSERT INTO app_user (id, name, email, password, role, market) VALUES
('00000000-0000-0000-0000-000000000001','Aina Claimant','aina@demo.local','$2a$10$so4wXam5MTF.5I/POAmxdeoTMG2mOk6.VKQE96RI1lCVIYUlMNIY.','CLAIMANT','MY'),
('00000000-0000-0000-0000-000000000002','Wei Officer','wei@demo.local','$2a$10$so4wXam5MTF.5I/POAmxdeoTMG2mOk6.VKQE96RI1lCVIYUlMNIY.','OFFICER','MY'),
('00000000-0000-0000-0000-000000000003','Priya Officer','priya@demo.local','$2a$10$so4wXam5MTF.5I/POAmxdeoTMG2mOk6.VKQE96RI1lCVIYUlMNIY.','OFFICER','SG'),
('00000000-0000-0000-0000-000000000004','Mark Manager','mark@demo.local','$2a$10$so4wXam5MTF.5I/POAmxdeoTMG2mOk6.VKQE96RI1lCVIYUlMNIY.','MANAGER','MY');

INSERT INTO policy (id, policy_number, holder_id, type, market, coverage_limit, currency) VALUES
('10000000-0000-0000-0000-000000000001','MY-MOT-0001','00000000-0000-0000-0000-000000000001','MOTOR','MY',50000,'MYR'),
('10000000-0000-0000-0000-000000000002','MY-PRP-0001','00000000-0000-0000-0000-000000000001','PROPERTY','MY',300000,'MYR');
