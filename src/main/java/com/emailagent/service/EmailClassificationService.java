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
            String overrideRecipientEmail
    ) {}

    public record EmailData(
            String subject,
            String body,
            String sender,
            boolean isForwarded,
            String originalSender,
            String originalSubject,
            String originalDate
    ) {}

    public ClassificationResult classify(EmailData email, List<ForwardingRule> rules) {
        // Filter to AI-enabled rules and apply negative keyword pre-filter
        String combinedContent = (email.subject() + " " + (email.originalSubject() != null ? email.originalSubject() : "")).toLowerCase();

        List<ForwardingRule> aiRules = rules.stream()
                .filter(ForwardingRule::isAiEnabled)
                .filter(r -> {
                    if (r.getNegativeKeywords() == null || r.getNegativeKeywords().length == 0) return true;
                    boolean excluded = Arrays.stream(r.getNegativeKeywords())
                            .anyMatch(nk -> nk != null && !nk.isBlank() && combinedContent.contains(nk.toLowerCase().trim()));
                    if (excluded) {
                        // Check if positive keyword in subject overrides
                        boolean hasPositiveInSubject = r.getKeywords() != null && Arrays.stream(r.getKeywords())
                                .anyMatch(kw -> kw != null && !kw.isBlank() && combinedContent.contains(kw.toLowerCase().trim()));
                        if (hasPositiveInSubject) {
                            log.debug("[AI PRE-FILTER] Rule \"{}\" negative keyword overridden by subject positive match", r.getName());
                            return true;
                        }
                        log.debug("[AI PRE-FILTER] Rule \"{}\" excluded by negative keyword", r.getName());
                        return false;
                    }
                    return true;
                })
                .toList();

        if (aiRules.isEmpty()) {
            return new ClassificationResult(null, null, 0, "No AI-enabled rules available", null);
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
                            Map.of("role", "user", "content", userPrompt)
                    )
            ));

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
                return new ClassificationResult(null, null, 0, "AI rate limit exceeded", null);
            }
            if (response.statusCode() != 200) {
                log.error("AI gateway error {}: {}", response.statusCode(), response.body());
                return new ClassificationResult(null, null, 0, "AI classification failed", null);
            }

            JsonNode responseData = objectMapper.readTree(response.body());
            String content = responseData.path("choices").path(0).path("message").path("content").asText("");

            if (content.isBlank()) {
                return new ClassificationResult(null, null, 0, "AI returned empty response", null);
            }

            // Clean markdown code blocks
            content = content.trim();
            if (content.startsWith("```json")) content = content.substring(7);
            if (content.startsWith("```")) content = content.substring(3);
            if (content.endsWith("```")) content = content.substring(0, content.length() - 3);
            content = content.trim();

            JsonNode classification = objectMapper.readerFor(JsonNode.class)
                    .with(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES)
                    .with(JsonParser.Feature.ALLOW_SINGLE_QUOTES)
                    .readValue(content);
            String matchedRuleIdRaw = classification.hasNonNull("matched_rule_id") ? classification.get("matched_rule_id").asText(null) : null;
            String matchedRuleName = classification.hasNonNull("matched_rule_name") ? classification.get("matched_rule_name").asText(null) : null;
            double confidence = classification.path("confidence").asDouble(0);
            String reasoning = classification.path("reasoning").asText("");
            String overrideEmail = classification.hasNonNull("override_recipient_email") ? classification.get("override_recipient_email").asText(null) : null;

            // Validate matched rule ID
            String matchedRuleId = matchedRuleIdRaw;
            if (matchedRuleId != null) {
                final String ruleIdToFind = matchedRuleId;
                ForwardingRule validRule = aiRules.stream()
                        .filter(r -> r.getId().toString().equals(ruleIdToFind))
                        .findFirst()
                        .orElse(null);

                if (validRule == null && matchedRuleName != null) {
                    // Fuzzy fallback by name
                    final String nameLower = matchedRuleName.toLowerCase().trim();
                    validRule = aiRules.stream()
                            .filter(r -> r.getName().toLowerCase().trim().equals(nameLower))
                            .findFirst()
                            .orElse(null);
                    if (validRule != null) {
                        log.debug("[AI FUZZY] Recovered rule via name match: {} ({})", validRule.getName(), validRule.getId());
                        matchedRuleId = validRule.getId().toString();
                    }
                }

                if (validRule == null) {
                    log.warn("[AI] Invalid rule ID returned: {}", matchedRuleId);
                    return new ClassificationResult(null, null, 0, "AI returned invalid rule reference", null);
                }

                // Post-validation: check negative keywords against original rules
                if (validRule.getNegativeKeywords() != null && validRule.getNegativeKeywords().length > 0) {
                    boolean postExcluded = Arrays.stream(validRule.getNegativeKeywords())
                            .anyMatch(nk -> nk != null && !nk.isBlank() && combinedContent.contains(nk.toLowerCase().trim()));
                    if (postExcluded) {
                        log.warn("[AI POST-GUARD] Rule \"{}\" overridden by negative keyword", validRule.getName());
                        return new ClassificationResult(null, null, 0, "AI matched but overridden by negative keyword", null);
                    }
                }
            }

            log.debug("[AI] Classification: ruleId={}, confidence={}, reasoning={}", matchedRuleId, confidence, reasoning);
            return new ClassificationResult(matchedRuleId, matchedRuleName, confidence, reasoning, overrideEmail);

        } catch (Exception e) {
            log.error("Error during AI classification", e);
            return new ClassificationResult(null, null, 0, "AI classification error: " + e.getMessage(), null);
        }
    }

    private String buildSystemPrompt(List<ForwardingRule> rules, EmailData email) {
        StringBuilder sb = new StringBuilder();
        
        sb.append("<|im_start|>system\n");
        
        sb.append("<instructions priority=\"strict\">\n");
        sb.append("You are an email classification engine. Your ONLY job is to match the given email to one of the rules below.\n");
        sb.append("You MUST return a valid JSON object. No explanation, no markdown, no extra text.\n");
        sb.append("You MUST use the exact rule ID and rule name as they appear in the <rules> section.\n");
        sb.append("NEVER invent or placeholder values. NEVER return \"ID_HERE\" or \"NAME_HERE\".\n");
        sb.append("</instructions>\n\n");

        sb.append("<rules>\n");
        for (ForwardingRule r : rules) {
            sb.append("  <rule id=\"").append(r.getId()).append("\" name=\"").append(r.getName()).append("\">\n");
            if (r.getKeywords() != null && r.getKeywords().length > 0) {
                sb.append("    <match_if_contains>").append(String.join(", ", r.getKeywords())).append("</match_if_contains>\n");
            }
            if (r.getNegativeKeywords() != null && r.getNegativeKeywords().length > 0) {
                sb.append("    <skip_if_contains>").append(String.join(", ", r.getNegativeKeywords())).append("</skip_if_contains>\n");
            }
            if (r.getSenderPattern() != null && !r.getSenderPattern().isBlank()) {
                sb.append("    <sender_pattern>").append(r.getSenderPattern()).append("</sender_pattern>\n");
            }
            if (r.getSubjectPattern() != null && !r.getSubjectPattern().isBlank()) {
                sb.append("    <subject_pattern>").append(r.getSubjectPattern()).append("</subject_pattern>\n");
            }
            if (r.getConditions() != null && !r.getConditions().isBlank()) {
                sb.append("    <special_conditions>").append(r.getConditions()).append("</special_conditions>\n");
            }
            if (r.getAiContext() != null && !r.getAiContext().isBlank()) {
                sb.append("    <context>").append(r.getAiContext()).append("</context>\n");
            }
            sb.append("  </rule>\n");
        }
        sb.append("</rules>\n\n");

        if (email.isForwarded()) {
            sb.append("<note>This is a forwarded email. Consider both the outer and original sender/subject.</note>\n\n");
        }

        sb.append("<output_format>\n");
        sb.append("Return ONLY a JSON object with these exact fields:\n");
        sb.append("- matched_rule_id: the exact id attribute from the matched <rule> above, or null\n");
        sb.append("- matched_rule_name: the exact name attribute from the matched <rule> above, or null\n");
        sb.append("- confidence: a number between 0.0 and 1.0\n");
        sb.append("- reasoning: brief explanation\n");
        sb.append("- override_recipient_email: only set if a special_condition explicitly requires it, otherwise null\n");
        sb.append("</output_format>\n");
        
        sb.append("<|im_end|>");

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
        if (body != null && body.length() > 1200) body = body.substring(0, 1200);
        sb.append(body);
        sb.append("\n\nJSON:");
        return sb.toString();
    }
}
