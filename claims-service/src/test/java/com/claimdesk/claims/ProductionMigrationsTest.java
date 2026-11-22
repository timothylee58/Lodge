package com.claimdesk.claims;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Default configuration (no dev profile) must never create the demo accounts. Regression for a review finding. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ProductionMigrationsTest {
    @Container static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
        r.add("claimdesk.jwt.secret", () -> "production-like-test-secret-0123456789abcdef");
        // deliberately NO spring.flyway.locations override: this is what a non-dev deployment runs
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @Test
    void noDemoUsersOrPoliciesAndNoBackdoorLogin() throws Exception {
        assertThat(jdbc.queryForObject("select count(*) from app_user", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from policy", Integer.class)).isZero();
        var res = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"mark@demo.local\",\"password\":\"password\"}")).andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void databaseRejectsNonAsciiEmails() {
        assertThatThrownBy(() -> jdbc.update("insert into app_user (id,name,email,password,role,market) values "
                + "(gen_random_uuid(),'x','aına@demo.local','x','CLAIMANT','MY')"))
                .hasMessageContaining("chk_app_user_email_ascii");
    }
}
