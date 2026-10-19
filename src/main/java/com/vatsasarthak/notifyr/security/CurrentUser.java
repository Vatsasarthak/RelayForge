package com.vatsasarthak.notifyr.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

// The authenticated caller as set by JwtAuthFilter (principal = username, authority = ROLE_<role>).
public record CurrentUser(String username, boolean admin) {

    public static CurrentUser get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof String username)) {
            throw new IllegalStateException("No authenticated user in the security context");
        }
        boolean admin = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        return new CurrentUser(username, admin);
    }

    public boolean canAccess(String owner) {
        return admin || username.equals(owner);
    }
}
