package com.vatsasarthak.notifyr.service;

import com.vatsasarthak.notifyr.dto.AuthResponse;
import com.vatsasarthak.notifyr.dto.LoginRequest;
import com.vatsasarthak.notifyr.dto.RegisterRequest;
import com.vatsasarthak.notifyr.entity.Role;
import com.vatsasarthak.notifyr.entity.User;
import com.vatsasarthak.notifyr.exception.AdminRegistrationNotAllowedException;
import com.vatsasarthak.notifyr.exception.DuplicateUsernameException;
import com.vatsasarthak.notifyr.exception.InvalidCredentialsException;
import com.vatsasarthak.notifyr.repository.UserRepository;
import com.vatsasarthak.notifyr.security.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private JwtUtil jwtUtil;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder, jwtUtil);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void register_storesBcryptHashNotThePlainPassword() {
        when(userRepository.findByUsername("bob")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(jwtUtil.generateToken("bob", "API_CLIENT")).thenReturn("token");

        AuthResponse response = authService.register(new RegisterRequest("bob", "s3cret-password", Role.API_CLIENT));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getPassword()).isNotEqualTo("s3cret-password").startsWith("$2");
        assertThat(passwordEncoder.matches("s3cret-password", saved.getValue().getPassword())).isTrue();
        assertThat(response.getToken()).isEqualTo("token");
        assertThat(response.getRole()).isEqualTo("API_CLIENT");
    }

    @Test
    void register_duplicateUsernameIsRejected() {
        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(new User()));

        assertThatThrownBy(() -> authService.register(new RegisterRequest("bob", "s3cret-password", Role.API_CLIENT)))
                .isInstanceOf(DuplicateUsernameException.class);
        verify(userRepository, never()).save(any());
    }

    // regression: the public endpoint used to let anyone become the first ADMIN of a fresh installation
    @Test
    void register_anonymousCannotCreateAdmin_evenWhenNoAdminExistsYet() {
        assertThatThrownBy(() -> authService.register(new RegisterRequest("root", "s3cret-password", Role.ADMIN)))
                .isInstanceOf(AdminRegistrationNotAllowedException.class);
        verify(userRepository, never()).save(any());
        verify(userRepository, never()).existsByRole(any());
    }

    @Test
    void register_anonymousCannotCreateAdmin() {
        assertThatThrownBy(() -> authService.register(new RegisterRequest("eve", "s3cret-password", Role.ADMIN)))
                .isInstanceOf(AdminRegistrationNotAllowedException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void register_apiClientCannotCreateAdmin() {
        authenticateAs("ROLE_API_CLIENT");

        assertThatThrownBy(() -> authService.register(new RegisterRequest("eve", "s3cret-password", Role.ADMIN)))
                .isInstanceOf(AdminRegistrationNotAllowedException.class);
    }

    @Test
    void register_adminCanCreateAnotherAdmin() {
        authenticateAs("ROLE_ADMIN");
        when(userRepository.findByUsername("second")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        authService.register(new RegisterRequest("second", "s3cret-password", Role.ADMIN));

        verify(userRepository).save(any(User.class));
    }

    @Test
    void login_wrongPasswordOrUnknownUserGiveTheSameError() {
        User user = User.builder().username("bob").password(passwordEncoder.encode("right-password"))
                .role(Role.API_CLIENT).build();
        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(user));
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("bob", "wrong-password")))
                .isInstanceOf(InvalidCredentialsException.class);
        assertThatThrownBy(() -> authService.login(new LoginRequest("ghost", "right-password")))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid username or password");
    }

    @Test
    void login_correctCredentialsReturnToken() {
        User user = User.builder().username("bob").password(passwordEncoder.encode("right-password"))
                .role(Role.ADMIN).build();
        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(user));
        when(jwtUtil.generateToken("bob", "ADMIN")).thenReturn("jwt");

        AuthResponse response = authService.login(new LoginRequest("bob", "right-password"));

        assertThat(response.getToken()).isEqualTo("jwt");
        assertThat(response.getRole()).isEqualTo("ADMIN");
    }

    private void authenticateAs(String authority) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "caller", null, List.of(new SimpleGrantedAuthority(authority))));
    }
}
