package com.vatsasarthak.notifyr;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

// Starts the full application against fresh containers, so it also proves that the Flyway
// migrations create every table Hibernate validates. Skipped when Docker is not available.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class RelayForgeApplicationTests {

	@Test
	void contextLoads() {
	}

}
