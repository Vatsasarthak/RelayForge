package com.vatsasarthak.notifyr.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Adds the X-API-KEY header and the JWT bearer token to Swagger UI's "Authorize" dialog.
@Configuration
public class OpenApiConfig {

    private static final String API_KEY_SCHEME = "apiKey";
    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI relayForgeOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("RelayForge API")
                        .version("0.0.1")
                        .description("Every endpoint except /auth/** needs the X-API-KEY header. "
                                + "/notifications/** also needs a Bearer token (from /auth/login); users only "
                                + "see their own notifications. Creating or changing recipients, templates and "
                                + "campaigns, and /admin/**, need the token of an ADMIN user."))
                .components(new Components()
                        .addSecuritySchemes(API_KEY_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-API-KEY"))
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME).addList(BEARER_SCHEME));
    }
}
