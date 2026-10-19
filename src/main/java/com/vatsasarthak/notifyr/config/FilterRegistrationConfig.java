package com.vatsasarthak.notifyr.config;

import com.vatsasarthak.notifyr.security.JwtAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FilterRegistrationConfig {

    private final ApiKeyAuthFilter apiKeyAuthFilter;
    private final JwtAuthFilter jwtAuthFilter;

    @Bean
    public FilterRegistrationBean<ApiKeyAuthFilter> disableApiKeyAutoRegistration(){
        FilterRegistrationBean<ApiKeyAuthFilter> registration =
                new FilterRegistrationBean<>(apiKeyAuthFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<JwtAuthFilter> disableJwtAutoRegistration(){
        FilterRegistrationBean<JwtAuthFilter> registration =
                new FilterRegistrationBean<>(jwtAuthFilter);
        registration.setEnabled(false);
        return registration;
    }

}
