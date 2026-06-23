package com.emailagent.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MicrosoftTokenService {

    private final NimbusJwtDecoder jwtDecoder;

    public MicrosoftTokenService(
            @Value("${microsoft.tenant-id}") String tenantId,
            @Value("${microsoft.client-id}") String clientId) {

        String jwksUri = "https://login.microsoftonline.com/" + tenantId + "/discovery/v2.0/keys";

        this.jwtDecoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();

        JwtClaimValidator<List<String>> audienceValidator = new JwtClaimValidator<>(
                JwtClaimNames.AUD,
                aud -> aud != null && aud.contains(clientId)
        );

        if ("common".equalsIgnoreCase(tenantId)) {
            // Multi-tenant / common: tokens carry the real tenant ID in `iss`, never "common".
            // Validate only that the issuer is a legitimate Microsoft tenant URL.
            JwtClaimValidator<String> issuerPatternValidator = new JwtClaimValidator<>(
                    JwtClaimNames.ISS,
                    iss -> iss != null
                            && iss.startsWith("https://login.microsoftonline.com/")
                            && iss.endsWith("/v2.0")
            );

            this.jwtDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    new JwtTimestampValidator(),
                    issuerPatternValidator,
                    audienceValidator
            ));
        } else {
            // Single-tenant: validate exact issuer match.
            String issuer = "https://login.microsoftonline.com/" + tenantId + "/v2.0";

            this.jwtDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(issuer),
                    audienceValidator
            ));
        }
    }

    public Jwt validateAndDecode(String idToken) {
        return jwtDecoder.decode(idToken);
    }

    public String extractEmail(Jwt jwt) {
        String email = jwt.getClaimAsString("preferred_username");
        if (email == null || email.isBlank()) {
            email = jwt.getClaimAsString("email");
        }
        return email != null ? email.trim().toLowerCase() : null;
    }
}
