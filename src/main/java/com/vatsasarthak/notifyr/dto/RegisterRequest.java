package com.vatsasarthak.notifyr.dto;

import com.vatsasarthak.notifyr.entity.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;



@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegisterRequest {

    @NotBlank(message = "username is required")
    @Size(min = 3, max = 100, message = "username must be 3-100 characters")
    private String username;

    // BCrypt only uses the first 72 bytes of a password
    @NotBlank(message = "password is required")
    @Size(min = 8, max = 72, message = "password must be 8-72 characters")
    private String password;

    @NotNull(message = "role is required")
    private Role role;
}
