package com.vatsasarthak.notifyr;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

// Authentication is handled by the API-key and JWT filters, so Spring Boot's default in-memory user
// (whose generated password would be printed to the log) is not created.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class RelayForgeApplication {

	public static void main(String[] args) {
		SpringApplication.run(RelayForgeApplication.class, args);
	}

}
