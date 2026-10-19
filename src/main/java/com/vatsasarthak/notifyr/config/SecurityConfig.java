package com.vatsasarthak.notifyr.config;

import org.springframework.http.HttpMethod;
import com.vatsasarthak.notifyr.security.JwtAuthFilter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final ApiKeyAuthFilter apiKeyAuthFilter;
    private final JwtAuthFilter jwtAuthFilter;
    private final ErrorResponseWriter errorResponseWriter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // actuator is served on the separate management port (MANAGEMENT_PORT), not the API port
                .requestMatchers("/auth/**", "/actuator/**", "/swagger-ui.html", "/swagger-ui/**",
                        "/v3/api-docs/**", "/error").permitAll()
                .requestMatchers("/admin/**").hasRole("ADMIN")
                // notifications belong to the user who created them, so a user identity (JWT) is required
                .requestMatchers(HttpMethod.GET, "/notifications/health").permitAll()
                .requestMatchers(HttpMethod.POST, "/notifications/*/retry").hasRole("ADMIN")
                .requestMatchers("/notifications/**").authenticated()
                .requestMatchers(HttpMethod.POST, "/recipients/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/recipients/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/recipients/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PATCH, "/recipients/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/templates/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/templates/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/campaigns/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/campaigns/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/campaigns/**").hasRole("ADMIN")
                // remaining reads of recipients/templates/campaigns are protected by the X-API-KEY filter only
                .anyRequest().permitAll()
            )
            .exceptionHandling(ex -> ex
                // no or invalid JWT -> 401; valid JWT without the required role -> 403
                .authenticationEntryPoint((request, response, authException) ->
                        errorResponseWriter.write(request, response, HttpServletResponse.SC_UNAUTHORIZED,
                                "Unauthorized: a valid Bearer token is required"))
                .accessDeniedHandler((request, response, accessDeniedException) ->
                        errorResponseWriter.write(request, response, HttpServletResponse.SC_FORBIDDEN,
                                "Forbidden: insufficient role"))
            )
            .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
