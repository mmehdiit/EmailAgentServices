package com.emailagent.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.emailagent.model.ForwardingRule;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailClassificationService {

    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .build();

    @Value("${ai.gateway.url}")
    private String gatewayUrl;

    @Value("${ai.gateway.api-key}")
    private String apiKey;

    @Value("${ai.gateway.model}")
    private String model;

    public record ClassificationResult(
            String matchedRuleId,
            String matchedRuleName,
            double confidence,
            String reasoning,
            String overrideRecipientEmail,
            String negativeKeywordOverride) {
    }

    public record EmailData(
            String subject,
            String body,
            String sender,
            boolean isForwarded,
            String originalSender,
            String originalSubject,
            String originalDate) {
    }

    public ClassificationResult classify(EmailData email, List<ForwardingRule> rules) {

        // Combined content for keyword matching — subject + body + original subject
        String combinedContent = ((email.subject() != null ? email.subject() : "") + " " +
                (email.body() != null ? email.body() : "") + " " +
                (email.originalSubject() != null ? email.originalSubject() : "")).toLowerCase();

        // -------------------------------------------------------------------------
        // STAGE 1: Pure Java keyword matching — no AI involved
        // If a keyword matches AND no exclude keyword matches → return immediately
        // -------------------------------------------------------------------------
        for (ForwardingRule r : rules) {
            boolean hasKeywordMatch = r.getKeywords() != null && r.getKeywords().length > 0 &&
                    Arrays.stream(r.getKeywords())
                            .anyMatch(kw -> kw != null && !kw.isBlank() &&
                                    combinedContent.contains(kw.toLowerCase().trim()));

            if (!hasKeywordMatch)
                continue;

            boolean hasExcludeMatch = r.getNegativeKeywords() != null && r.getNegativeKeywords().length > 0 &&
                    Arrays.stream(r.getNegativeKeywords())
                            .anyMatch(nk -> nk != null && !nk.isBlank() &&
                                    combinedContent.contains(nk.toLowerCase().trim()));

            if (!hasExcludeMatch) {
                // Check special conditions for override recipient
                String overrideEmail = resolveOverrideEmail(r, combinedContent);
                log.debug("[KEYWORD MATCH] Rule \"{}\" matched directly, skipping AI", r.getName());
                return new ClassificationResult(
                        r.getId().toString(), r.getName(), 1.0, "Direct keyword match", overrideEmail, null);
            } else {
                log.debug("[KEYWORD AMBIGUOUS] Rule \"{}\" has both keyword and exclude keyword match, deferring to AI",
                        r.getName());
            }
        }

        // -------------------------------------------------------------------------
        // STAGE 2: AI — only for emails that keyword matching couldn't resolve
        // Only pass AI-enabled rules, and only their context (no keyword lists)
        // -------------------------------------------------------------------------
        List<ForwardingRule> aiRules = rules.stream()
                .filter(ForwardingRule::isAiEnabled)
                .filter(r -> {
                    // Drop rules whose exclude keywords clearly apply with no positive override
                    if (r.getNegativeKeywords() == null || r.getNegativeKeywords().length == 0)
                        return true;
                    boolean excluded = Arrays.stream(r.getNegativeKeywords())
                            .anyMatch(nk -> nk != null && !nk.isBlank() &&
                                    combinedContent.contains(nk.toLowerCase().trim()));
                    if (!excluded)
                        return true;
                    // Keep if a positive keyword also matches — let AI decide
                    boolean hasPositiveOverride = r.getKeywords() != null &&
                            Arrays.stream(r.getKeywords())
                                    .anyMatch(kw -> kw != null && !kw.isBlank() &&
                                            combinedContent.contains(kw.toLowerCase().trim()));
                    if (hasPositiveOverride) {
                        log.debug("[AI PRE-FILTER] Rule \"{}\" kept for AI — positive keyword overrides exclusion",
                                r.getName());
                        return true;
                    }
                    log.debug("[AI PRE-FILTER] Rule \"{}\" excluded by negative keyword", r.getName());
                    return false;
                })
                .toList();

        if (aiRules.isEmpty()) {
            return new ClassificationResult(null, null, 0, "No rules matched", null, null);
        }

        String systemPrompt = buildSystemPrompt(aiRules, email);
        String userPrompt = buildUserPrompt(email);

        try {
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "model", model,
                    "response_format", Map.of("type", "json_object"),
                    "max_tokens", 512,
                    "temperature", 0,
                    "keep_alive", "10m",
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userPrompt))));

            log.debug("[AI REQUEST] {}", requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(gatewayUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.debug("[AI RESPONSE] status={} body={}", response.statusCode(), response.body());

            if (response.statusCode() == 429) {
                log.warn("AI rate limit exceeded");
                return new ClassificationResult(null, null, 0, "AI rate limit exceeded", null, null);
            }
            if (response.statusCode() != 200) {
                log.error("AI gateway error {}: {}", response.statusCode(), response.body());
                return new ClassificationResult(null, null, 0, "AI classification failed", null, null);
            }

            JsonNode responseData = objectMapper.readTree(response.body());
            String content = responseData.path("choices").path(0).path("message").path("content").asText("");

            if (content.isBlank()) {
                return new ClassificationResult(null, null, 0, "AI returned empty response", null, null);
            }

            // Clean markdown code blocks if model wraps anyway
            content = content.trim();
            if (content.startsWith("```json"))
                content = content.substring(7);
            if (content.startsWith("```"))
                content = content.substring(3);
            if (content.endsWith("```"))
                content = content.substring(0, content.length() - 3);
            content = content.trim();

            JsonNode classification = objectMapper.readerFor(JsonNode.class)
                    .with(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES)
                    .with(JsonParser.Feature.ALLOW_SINGLE_QUOTES)
                    .readValue(content);

            String matchedRuleIdRaw = classification.hasNonNull("matched_rule_id")
                    ? classification.get("matched_rule_id").asText(null)
                    : null;
            String matchedRuleName = classification.hasNonNull("matched_rule_name")
                    ? classification.get("matched_rule_name").asText(null)
                    : null;
            double confidence = classification.path("confidence").asDouble(0);
            String reasoning = classification.path("reasoning").asText("");
            String overrideEmail = classification.hasNonNull("override_recipient_email")
                    ? classification.get("override_recipient_email").asText(null)
                    : null;

            // Rescue: model returned null but reasoning clearly names a rule
            if (matchedRuleIdRaw == null && reasoning != null && !reasoning.isBlank()) {
                String reasoningLower = reasoning.toLowerCase();
                ForwardingRule rescued = aiRules.stream()
                        .filter(r -> reasoningLower.contains(r.getName().toLowerCase()))
                        .findFirst()
                        .orElse(null);
                if (rescued != null) {
                    log.debug("[AI RESCUE] null result rescued from reasoning — matched rule \"{}\"",
                            rescued.getName());
                    matchedRuleIdRaw = rescued.getId().toString();
                    matchedRuleName = rescued.getName();
                }
            }

            // Validate matched rule ID
            String matchedRuleId = matchedRuleIdRaw;
            if (matchedRuleId != null) {
                final String ruleIdToFind = matchedRuleId;
                ForwardingRule validRule = aiRules.stream()
                        .filter(r -> r.getId().toString().equals(ruleIdToFind))
                        .findFirst()
                        .orElse(null);

                // Fuzzy fallback by name if ID didn't match
                if (validRule == null && matchedRuleName != null) {
                    final String nameLower = matchedRuleName.toLowerCase().trim();
                    validRule = aiRules.stream()
                            .filter(r -> r.getName().toLowerCase().trim().equals(nameLower))
                            .findFirst()
                            .orElse(null);
                    if (validRule != null) {
                        log.debug("[AI FUZZY] Recovered rule via name match: {} ({})", validRule.getName(),
                                validRule.getId());
                        matchedRuleId = validRule.getId().toString();
                    }
                }

                if (validRule == null) {
                    log.warn("[AI] Invalid rule ID returned: {}", matchedRuleId);
                    return new ClassificationResult(null, null, 0, "AI returned invalid rule reference", null, null);
                }

                // Post-guard: double-check exclude keywords weren't violated
                if (validRule.getNegativeKeywords() != null && validRule.getNegativeKeywords().length > 0) {
                    String matchedNegKw = Arrays.stream(validRule.getNegativeKeywords())
                            .filter(nk -> nk != null && !nk.isBlank() &&
                                    combinedContent.contains(nk.toLowerCase().trim()))
                            .findFirst().orElse(null);
                    if (matchedNegKw != null) {
                        log.warn("[AI POST-GUARD] Rule \"{}\" overridden by negative keyword \"{}\"",
                                validRule.getName(), matchedNegKw);
                        return new ClassificationResult(null, null, 0, "AI matched but overridden by negative keyword",
                                null, matchedNegKw);
                    }
                }

                // Resolve override email from special conditions if AI didn't provide one
                if (overrideEmail == null) {
                    overrideEmail = resolveOverrideEmail(validRule, combinedContent);
                }
            }

            log.debug("[AI] Classification: ruleId={}, confidence={}, reasoning={}", matchedRuleId, confidence,
                    reasoning);
            return new ClassificationResult(matchedRuleId, matchedRuleName, confidence, reasoning, overrideEmail, null);

        } catch (Exception e) {
            log.error("Error during AI classification", e);
            return new ClassificationResult(null, null, 0, "AI classification error: " + e.getMessage(), null, null);
        }
    }

    /**
     * Checks special conditions for override recipient email.
     * Currently handles the "collection of vehicle / total loss" condition by text
     * match.
     * Extend this method as more special conditions are added.
     */
    private String resolveOverrideEmail(ForwardingRule rule, String combinedContent) {
        if (rule.getConditions() == null || rule.getConditions().isBlank())
            return null;
        String conditions = rule.getConditions().toLowerCase();
        // Extract "always forward to <email>" pattern from conditions
        if (conditions.contains("always forward to")) {
            // Check if the trigger phrase is present in the email
            boolean triggerPresent = combinedContent.contains("collection of total loss") ||
                    combinedContent.contains("collection of vehicle");
            if (triggerPresent) {
                // Extract the email from the conditions string
                int idx = rule.getConditions().toLowerCase().indexOf("always forward to");
                String afterKeyword = rule.getConditions().substring(idx + "always forward to".length()).trim();
                String[] tokens = afterKeyword.split("\\s+");
                if (tokens.length > 0 && tokens[0].contains("@")) {
                    return tokens[0];
                }
            }
        }
        return null;
    }

    /**
     * AI stage prompt — only context and intent, no keyword lists.
     * Keywords already failed at this point; the model should reason semantically.
     */
    private String buildSystemPrompt(List<ForwardingRule> rules, EmailData email) {
        StringBuilder sb = new StringBuilder();

        sb.append("You are an email classification engine for a motor insurance company.\n");
        sb.append(
                "Direct keyword matching did not resolve this email. Use semantic understanding to find the best matching rule.\n\n");

        sb.append("RULES:\n");
        for (ForwardingRule r : rules) {
            sb.append("ID: ").append(r.getId())
                    .append(" | Name: \"").append(r.getName()).append("\"\n");
            if (r.getAiContext() != null && !r.getAiContext().isBlank()) {
                sb.append("  Description: ").append(r.getAiContext().trim()).append("\n");
            }
            if (r.getConditions() != null && !r.getConditions().isBlank()) {
                sb.append("  Special condition: ").append(r.getConditions().trim()).append("\n");
            }
            sb.append("\n");
        }

        if (email.isForwarded()) {
            sb.append("Note: This is a forwarded email. Consider both the outer and original sender/subject.\n\n");
        }
        sb.append(
                "IMPORTANT: If you identify a matching rule, you MUST populate matched_rule_id with the exact ID string shown above. Returning null for matched_rule_id when a rule matches is incorrect.\n\n");
        sb.append("Return ONLY a valid JSON object with these exact fields:\n");
        sb.append("{\n");
        sb.append("  \"matched_rule_id\": \"<exact ID from the rules above, or null>\",\n");
        sb.append("  \"matched_rule_name\": \"<exact name from the rules above, or null>\",\n");
        sb.append("  \"confidence\": <0.0 to 1.0>,\n");
        sb.append("  \"reasoning\": \"<one sentence>\",\n");
        sb.append("  \"override_recipient_email\": <email string if special condition applies, otherwise null>\n");
        sb.append("}");

        return sb.toString();
    }

    private String buildUserPrompt(EmailData email) {
        StringBuilder sb = new StringBuilder();
        sb.append("From: ").append(email.sender()).append("\n");
        sb.append("Subject: ").append(email.subject()).append("\n");
        if (email.isForwarded()) {
            sb.append("Original Sender: ").append(email.originalSender()).append("\n");
            sb.append("Original Subject: ").append(email.originalSubject()).append("\n");
        }
        sb.append("\n");
        String body = email.body();
        if (body != null && body.length() > 1200)
            body = body.substring(0, 1200);
        sb.append(body);
        sb.append("\n\nJSON:");
        return sb.toString();
    }
}