package com.emailagent.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.emailagent.model.ForwardingRule;
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

    public ClassificationResult classify(String lastMessage, String previousMessages, List<ForwardingRule> rules) {

        try {
            List<Map<String, Object>> rulesList = new ArrayList<>();
            for (int i = 0; i < rules.size(); i++) {
                ForwardingRule r = rules.get(i);
                Map<String, Object> ruleMap = new LinkedHashMap<>();
                ruleMap.put("ruleId", r.getId().toString());
                ruleMap.put("ruleName", r.getName() != null ? r.getName() : "");
                ruleMap.put("ruleContext", r.getAiContext() != null ? r.getAiContext() : "");
                ruleMap.put("priority", r.getPriority());
                rulesList.add(ruleMap);
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("lastMessage", lastMessage != null ? lastMessage : "");
            payload.put("previousMessages", previousMessages != null ? previousMessages : "");
            payload.put("rules", rulesList);

            String requestBody = objectMapper.writeValueAsString(payload);
            
            try {
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
                Files.writeString(Paths.get("C:\\Users\\User\\Desktop\\EmailAgent\\requests", "request_" + timestamp + ".json"), requestBody);
            } catch (Exception fileEx) {
                log.warn("Failed to save AI request to file", fileEx);
            }

            log.debug("[AI REQUEST] {}", requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(gatewayUrl))
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

            if (response.body() == null || response.body().isBlank()) {
                return new ClassificationResult(null, null, 0, "AI returned empty response", null, null);
            }

            JsonNode classification = objectMapper.readTree(response.body());

            String matchedRuleId = classification.hasNonNull("matchedRuleId")
                    ? classification.get("matchedRuleId").asText(null)
                    : null;
            String llmMatchedRuleId = classification.hasNonNull("llmMatchedRuleId")
                    ? classification.get("llmMatchedRuleId").asText(null)
                    : null;
            String llmReasoning = classification.path("llmReasoning").asText(null);
            double confidence = classification.path("matchingScore").asDouble(0);

            // Prefer the primary match; fall back to the LLM's suggestion
            String effectiveRuleId = matchedRuleId != null ? matchedRuleId : llmMatchedRuleId;

            String matchedRuleName = null;
            String overrideEmail = null;

            if (effectiveRuleId != null) {
                final String ruleIdToFind = effectiveRuleId;
                ForwardingRule validRule = rules.stream()
                        .filter(r -> r.getId().toString().equals(ruleIdToFind))
                        .findFirst()
                        .orElse(null);

                if (validRule == null) {
                    log.warn("[AI] Invalid rule ID returned: {}", effectiveRuleId);
                    return new ClassificationResult(null, null, 0, "AI returned invalid rule reference", null, null);
                }

                matchedRuleName = validRule.getName();

                String combinedContent = ((lastMessage != null ? lastMessage : "")
                        + " " + (previousMessages != null ? previousMessages : "")).toLowerCase();
                overrideEmail = resolveOverrideEmail(validRule, combinedContent);
            }

            log.debug("[AI] Classification: ruleId={}, score={}, reasoning={}", effectiveRuleId, confidence, llmReasoning);
            return new ClassificationResult(effectiveRuleId, matchedRuleName, confidence, llmReasoning, overrideEmail, null);

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
        // sb.append("Subject: ").append(email.subject()).append("\n");
        sb.append("Subject: ").append("").append("\n");
        if (email.isForwarded()) {
            sb.append("Original Sender: ").append(email.originalSender()).append("\n");
            // sb.append("Original Subject: ").append(email.originalSubject()).append("\n");
            sb.append("Original Subject: ").append("").append("\n");
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