package com.emailagent.service;

import com.emailagent.exception.ApiException;
import com.emailagent.model.OutlookConnection;
import com.emailagent.model.User;
import com.emailagent.repository.OutlookConnectionRepository;
import com.emailagent.repository.UserRepository;
import com.emailagent.repository.UserRoleRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutlookService {

    private final OutlookConnectionRepository connectionRepository;
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final ObjectMapper objectMapper;

    @Value("${microsoft.client-id}")
    private String clientId;

    @Value("${microsoft.client-secret}")
    private String clientSecret;

    @Value("${microsoft.tenant-id}")
    private String tenantId;

    @Value("${microsoft.redirect-uri}")
    private String defaultRedirectUri;

    private static final String SCOPES =
            "https://graph.microsoft.com/User.Read " +
            "https://graph.microsoft.com/Mail.ReadWrite " +
            "https://graph.microsoft.com/Mail.Send " +
            "offline_access";

    public String buildAuthUrl(String frontendOrigin, String providedRedirectUri) {
        String redirectUri = providedRedirectUri != null ? providedRedirectUri
                : (frontendOrigin != null ? frontendOrigin.replaceAll("/$", "") + "/dashboard" : defaultRedirectUri);

        String url = "https://login.microsoftonline.com/" + tenantId + "/oauth2/v2.0/authorize" +
                "?client_id=" + encode(clientId) +
                "&response_type=code" +
                "&redirect_uri=" + encode(redirectUri) +
                "&scope=" + encode(SCOPES) +
                "&response_mode=query";

        log.info("Built OAuth URL with redirect: {}", redirectUri);
        return url;
    }

    @Transactional
    public String exchangeCodeForTokens(UUID userId, String code, String frontendOrigin, String providedRedirectUri) {
        String redirectUri = providedRedirectUri != null ? providedRedirectUri
                : (frontendOrigin != null ? frontendOrigin.replaceAll("/$", "") + "/dashboard" : defaultRedirectUri);

        try {
            HttpClient client = HttpClient.newHttpClient();
            Map<String, String> formData = Map.of(
                    "client_id", clientId,
                    "client_secret", clientSecret,
                    "code", code,
                    "redirect_uri", redirectUri,
                    "grant_type", "authorization_code"
            );

            String formBody = formData.entrySet().stream()
                    .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                    .collect(Collectors.joining("&"));

            HttpRequest tokenRequest = HttpRequest.newBuilder()
                    .uri(URI.create("https://login.microsoftonline.com/" + tenantId + "/oauth2/v2.0/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(formBody))
                    .build();

            HttpResponse<String> tokenResponse = client.send(tokenRequest, HttpResponse.BodyHandlers.ofString());
            if (tokenResponse.statusCode() != 200) {
                log.error("Token exchange failed: {} - {}", tokenResponse.statusCode(), tokenResponse.body());
                throw ApiException.internal("Failed to exchange authorization code");
            }

            JsonNode tokenData = objectMapper.readTree(tokenResponse.body());
            String accessToken = tokenData.get("access_token").asText();
            String refreshToken = tokenData.get("refresh_token").asText();
            long expiresIn = tokenData.get("expires_in").asLong();

            // Fetch user email
            HttpRequest userInfoRequest = HttpRequest.newBuilder()
                    .uri(URI.create("https://graph.microsoft.com/v1.0/me"))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();

            HttpResponse<String> userInfoResponse = client.send(userInfoRequest, HttpResponse.BodyHandlers.ofString());
            if (userInfoResponse.statusCode() != 200) {
                throw ApiException.internal("Failed to fetch Outlook user info");
            }

            JsonNode userInfo = objectMapper.readTree(userInfoResponse.body());
            String emailAddress = userInfo.hasNonNull("mail")
                    ? userInfo.get("mail").asText()
                    : userInfo.get("userPrincipalName").asText();

            // Store or update connection
            OutlookConnection connection = connectionRepository.findByUserId(userId)
                    .orElse(new OutlookConnection());
            connection.setUserId(userId);
            connection.setEmailAddress(emailAddress);
            connection.setAccessToken(accessToken);
            connection.setRefreshToken(refreshToken);
            connection.setTokenExpiry(OffsetDateTime.now().plusSeconds(expiresIn));
            connectionRepository.save(connection);

            log.info("Stored Outlook connection for user {} with email {}", userId, emailAddress);
            return emailAddress;

        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error exchanging code for tokens", e);
            throw ApiException.internal("Outlook connection failed: " + e.getMessage());
        }
    }

    public Optional<OutlookConnection> getConnection(UUID userId) {
        return connectionRepository.findByUserId(userId);
    }

    @Transactional
    public void disconnectOutlook(UUID userId) {
        connectionRepository.deleteByUserId(userId);
    }

    @Transactional
    public String getValidAccessToken(UUID userId) {
        String role = userRoleRepository.findByUserId(userId)
                .map(ur -> ur.getRole())
                .orElse("user");

        UUID tokenOwnerId = userId;

        if ("user".equals(role)) {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> ApiException.badRequest("User not found"));
            if (user.getDepartmentId() == null) {
                throw ApiException.badRequest("User is not assigned to a department");
            }
            User admin = userRepository.findAdminByDepartmentId(user.getDepartmentId())
                    .orElseThrow(() -> ApiException.badRequest("No admin found for department"));
            tokenOwnerId = admin.getId();
            log.debug("User {} resolved to department admin {} for token access", userId, tokenOwnerId);
        }

        OutlookConnection connection = connectionRepository.findByUserId(tokenOwnerId)
                .orElseThrow(() -> ApiException.badRequest("No Outlook connection found for department admin"));

        if (connection.getTokenExpiry().isAfter(OffsetDateTime.now().plusMinutes(5))) {
            return connection.getAccessToken();
        }

        return refreshAccessToken(connection);
    }

    @Transactional
    public String refreshAccessToken(OutlookConnection connection) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            Map<String, String> formData = Map.of(
                    "client_id", clientId,
                    "client_secret", clientSecret,
                    "refresh_token", connection.getRefreshToken(),
                    "grant_type", "refresh_token",
                    "scope", "User.Read Mail.Read Mail.ReadWrite Mail.Send offline_access"
            );

            String formBody = formData.entrySet().stream()
                    .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                    .collect(Collectors.joining("&"));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://login.microsoftonline.com/" + tenantId + "/oauth2/v2.0/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(formBody))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw ApiException.internal("Failed to refresh access token");
            }

            JsonNode tokenData = objectMapper.readTree(response.body());
            String newAccessToken = tokenData.get("access_token").asText();
            String newRefreshToken = tokenData.has("refresh_token")
                    ? tokenData.get("refresh_token").asText()
                    : connection.getRefreshToken();
            long expiresIn = tokenData.get("expires_in").asLong();

            connection.setAccessToken(newAccessToken);
            connection.setRefreshToken(newRefreshToken);
            connection.setTokenExpiry(OffsetDateTime.now().plusSeconds(expiresIn));
            connectionRepository.save(connection);

            log.debug("Token refreshed for user {}", connection.getUserId());
            return newAccessToken;

        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error refreshing token", e);
            throw ApiException.internal("Token refresh failed");
        }
    }

    public JsonNode callGraphApi(String accessToken, String endpoint) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            URI uri = UriComponentsBuilder
                    .fromUriString("https://graph.microsoft.com/v1.0/me/" + endpoint)
                    .build()
                    .encode()
                    .toUri();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Prefer", "outlook.body-content-type=\"html\"")
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401) {
                throw ApiException.unauthorized("Outlook token expired or invalid");
            }
            if (response.statusCode() != 200) {
                log.error("Graph API error {}: {}", response.statusCode(), response.body());
                throw ApiException.internal("Graph API request failed: " + response.statusCode());
            }

            return objectMapper.readTree(response.body());

        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error calling Graph API endpoint: {}", endpoint, e);
            throw ApiException.internal("Graph API call failed: " + e.getMessage());
        }
    }

    public void patchMessage(String accessToken, String messageId, String jsonBody) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://graph.microsoft.com/v1.0/me/messages/" + messageId))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.error("PATCH message failed {}: {}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.error("Error patching message {}", messageId, e);
        }
    }

    public void forwardMessage(String accessToken, String messageId, String toEmail, String comment) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            String body = objectMapper.writeValueAsString(Map.of(
                    "toRecipients", new Object[]{
                            Map.of("emailAddress", Map.of("address", toEmail))
                    },
                    "comment", comment
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://graph.microsoft.com/v1.0/me/messages/" + messageId + "/forward"))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.error("Forward message failed {}: {}", response.statusCode(), response.body());
                throw ApiException.internal("Failed to forward email: " + response.statusCode());
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error forwarding message", e);
            throw ApiException.internal("Failed to forward email: " + e.getMessage());
        }
    }

    public void replyToMessage(String accessToken, String messageId, String comment) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            String body = objectMapper.writeValueAsString(Map.of("comment", comment));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://graph.microsoft.com/v1.0/me/messages/" + messageId + "/reply"))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                log.error("Reply message failed {}: {}", response.statusCode(), response.body());
                throw ApiException.internal("Failed to reply to email: " + response.statusCode());
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error replying to message", e);
            throw ApiException.internal("Failed to reply to email: " + e.getMessage());
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
