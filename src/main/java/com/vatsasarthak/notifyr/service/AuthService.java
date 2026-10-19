package com.vatsasarthak.notifyr.service;

import com.vatsasarthak.notifyr.dto.AuthResponse;
import com.vatsasarthak.notifyr.dto.LoginRequest;
import com.vatsasarthak.notifyr.dto.RegisterRequest;
import com.vatsasarthak.notifyr.entity.Role;
import com.vatsasarthak.notifyr.entity.User;
import com.vatsasarthak.notifyr.exception.AdminRegistrationNotAllowedException;
import com.vatsasarthak.notifyr.exception.DuplicateUsernameException;
import com.vatsasarthak.notifyr.repository.UserRepository;
import com.vatsasarthak.notifyr.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import com.vatsasarthak.notifyr.exception.InvalidCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthResponse register(RegisterRequest request){

        if(request.getRole() == Role.ADMIN && !canRegisterAdmin()){
            throw new AdminRegistrationNotAllowedException();
        }

        userRepository.findByUsername(request.getUsername()).ifPresent(user -> {
            throw new DuplicateUsernameException(request.getUsername());
        });

        User user = User.builder()
                .username(request.getUsername())
                .password(passwordEncoder.encode(request.getPassword()))
                .role(request.getRole())
                .build();

        user = userRepository.save(user);

        String token = jwtUtil.generateToken(user.getUsername(),  user.getRole().toString());
        return new AuthResponse(token, user.getUsername(), user.getRole().toString());
    }

    public AuthResponse login(LoginRequest request){

        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(InvalidCredentialsException::new);

        if(!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new InvalidCredentialsException();
        }

        String token = jwtUtil.generateToken(user.getUsername(), user.getRole().toString());
        return new AuthResponse(token, user.getUsername(), user.getRole().toString());
    }

    // Only an authenticated ADMIN may create another ADMIN. The first ADMIN comes from configuration
    // (AdminBootstrap), so the public endpoint can never grant administrator rights, not even on a fresh install.
    private boolean canRegisterAdmin(){
        Authentication caller = SecurityContextHolder.getContext().getAuthentication();
        return caller != null && caller.isAuthenticated() && caller.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }
}
