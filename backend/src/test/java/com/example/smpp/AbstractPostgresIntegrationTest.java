package com.example.smpp;

import java.net.ServerSocket;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Single shared PostgreSQL container for integration tests. Started once per fork (static
 * init) so Spring receives a live JDBC URL before context refresh.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
public abstract class AbstractPostgresIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    /** Free local TCP port for the embedded Cloudhopper SMPP listener (avoids clashes). */
    private static final int SMPP_IT_PORT;

    static {
        POSTGRES.start();
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            SMPP_IT_PORT = socket.getLocalPort();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void registerDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("smpp.server.port", () -> String.valueOf(SMPP_IT_PORT));
    }

    protected static int smppIntegrationListenPort() {
        return SMPP_IT_PORT;
    }
}
