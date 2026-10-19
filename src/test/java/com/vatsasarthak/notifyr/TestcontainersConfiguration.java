package com.vatsasarthak.notifyr;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

// Real PostgreSQL, RabbitMQ and Redis for integration tests, using the same images as docker-compose.yml.
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    // generous: on a busy machine (or a cold CI runner) RabbitMQ has been seen to need ~3 minutes to boot
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
                .withStartupTimeout(STARTUP_TIMEOUT);
    }

    @Bean
    @ServiceConnection
    RabbitMQContainer rabbitContainer() {
        return new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management"))
                .withStartupTimeout(STARTUP_TIMEOUT);
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379)
                .withStartupTimeout(STARTUP_TIMEOUT);
    }
}
