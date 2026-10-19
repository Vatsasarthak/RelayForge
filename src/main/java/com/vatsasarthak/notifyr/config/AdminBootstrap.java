package com.vatsasarthak.notifyr.config;

import com.vatsasarthak.notifyr.entity.Role;
import com.vatsasarthak.notifyr.entity.User;
import com.vatsasarthak.notifyr.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

// Creates the first ADMIN from BOOTSTRAP_ADMIN_USERNAME / BOOTSTRAP_ADMIN_PASSWORD when no ADMIN exists yet.
// ADMIN accounts can otherwise only be created by an authenticated ADMIN, so nobody can make themselves
// administrator through the public registration endpoint (including on a freshly deployed instance).
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminBootstrap implements ApplicationRunner {

    static final int MIN_PASSWORD_LENGTH = 12;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${notification.bootstrap-admin.username:}")
    private String username;

    @Value("${notification.bootstrap-admin.password:}")
    private String password;

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.existsByRole(Role.ADMIN)) {
            return;
        }
        if (username.isBlank() || password.isBlank()) {
            log.warn("No ADMIN user exists. Set BOOTSTRAP_ADMIN_USERNAME and BOOTSTRAP_ADMIN_PASSWORD to create one "
                    + "at startup; admin-only endpoints are unusable until then");
            return;
        }
        if (password.length() < MIN_PASSWORD_LENGTH || password.length() > 72) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_PASSWORD must be " + MIN_PASSWORD_LENGTH + "-72 characters");
        }
        if (password.startsWith("change-me")) {
            log.warn("BOOTSTRAP_ADMIN_PASSWORD still has the placeholder value from .env.example - change it");
        }
        if (userRepository.findByUsername(username).isPresent()) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_USERNAME '" + username
                    + "' already exists as a non-admin user; choose another name");
        }
        try {
            userRepository.save(User.builder()
                    .username(username)
                    .password(passwordEncoder.encode(password))
                    .role(Role.ADMIN)
                    .build());
            log.info("Created bootstrap ADMIN user '{}'", username);
        } catch (DataIntegrityViolationException e) {
            // another instance created it at the same moment
            log.info("Bootstrap ADMIN user '{}' was created concurrently", username);
        }
    }
}
