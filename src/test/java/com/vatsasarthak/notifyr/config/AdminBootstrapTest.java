package com.vatsasarthak.notifyr.config;

import com.vatsasarthak.notifyr.entity.Role;
import com.vatsasarthak.notifyr.entity.User;
import com.vatsasarthak.notifyr.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapTest {

    @Mock private UserRepository userRepository;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Test
    void createsTheFirstAdminFromConfiguration() {
        when(userRepository.existsByRole(Role.ADMIN)).thenReturn(false);
        when(userRepository.findByUsername("root")).thenReturn(Optional.empty());

        bootstrap("root", "a-long-admin-password").run(null);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getRole()).isEqualTo(Role.ADMIN);
        assertThat(saved.getValue().getUsername()).isEqualTo("root");
        assertThat(passwordEncoder.matches("a-long-admin-password", saved.getValue().getPassword())).isTrue();
    }

    @Test
    void doesNothingWhenAnAdminAlreadyExists() {
        when(userRepository.existsByRole(Role.ADMIN)).thenReturn(true);

        bootstrap("root", "a-long-admin-password").run(null);

        verify(userRepository, never()).save(any());
    }

    @Test
    void doesNothingWithoutConfiguredCredentials() {
        when(userRepository.existsByRole(Role.ADMIN)).thenReturn(false);

        bootstrap("", "").run(null);

        verify(userRepository, never()).save(any());
    }

    @Test
    void rejectsAShortPassword() {
        when(userRepository.existsByRole(Role.ADMIN)).thenReturn(false);

        assertThatThrownBy(() -> bootstrap("root", "short").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("12-72");
        verify(userRepository, never()).save(any());
    }

    @Test
    void doesNotPromoteAnExistingNonAdminUser() {
        when(userRepository.existsByRole(Role.ADMIN)).thenReturn(false);
        when(userRepository.findByUsername("root")).thenReturn(Optional.of(new User()));

        assertThatThrownBy(() -> bootstrap("root", "a-long-admin-password").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");
        verify(userRepository, never()).save(any());
    }

    private AdminBootstrap bootstrap(String username, String password) {
        AdminBootstrap bootstrap = new AdminBootstrap(userRepository, passwordEncoder);
        ReflectionTestUtils.setField(bootstrap, "username", username);
        ReflectionTestUtils.setField(bootstrap, "password", password);
        return bootstrap;
    }
}
