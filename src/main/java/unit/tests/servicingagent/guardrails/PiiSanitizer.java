package unit.tests.servicingagent.guardrails;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * FR-2.1 — Strips PII from any string before it reaches LLM prompt assembly
 * or telemetry logging. Applied as an explicit pre-processing step; never
 * relies on the LLM to self-censor.
 *
 * <p>Replacements are applied in a fixed order so that more-specific patterns
 * (SSN, credit card) run before the broader API-key sweep.
 */
@Component
public class PiiSanitizer {

    // 1. Social Security Numbers: 123-45-6789
    private static final Pattern SSN_PATTERN =
            Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");

    // 2. 16-digit credit/debit card numbers, optional spaces or hyphens between groups
    private static final Pattern CARD_PATTERN =
            Pattern.compile("\\b\\d{4}[- ]?\\d{4}[- ]?\\d{4}[- ]?\\d{4}\\b");

    // 3. HTTP Authorization Bearer tokens
    private static final Pattern BEARER_PATTERN =
            Pattern.compile("Bearer\\s+[A-Za-z0-9\\-._~+/]+=*");

    // 4. Raw API / secret key literals: sk_abc123…, pk-xyz…, ak_…, api_…
    private static final Pattern API_KEY_PATTERN =
            Pattern.compile("\\b(sk|pk|ak|api)[_-][A-Za-z0-9_\\-]{20,}");

    /**
     * Returns {@code input} with all matched PII patterns replaced by safe
     * placeholder tokens. The input string is never mutated; a new string is
     * always returned (or {@code null} if {@code input} is {@code null}).
     *
     * @param input raw text, possibly containing PII
     * @return sanitized text safe for logging and prompt assembly
     */
    public String sanitize(String input) {
        if (input == null) {
            return null;
        }
        String result = SSN_PATTERN.matcher(input).replaceAll("[SSN-REDACTED]");
        result = CARD_PATTERN.matcher(result).replaceAll("[CARD-REDACTED]");
        result = BEARER_PATTERN.matcher(result).replaceAll("Bearer [TOKEN-REDACTED]");
        result = API_KEY_PATTERN.matcher(result).replaceAll("[KEY-REDACTED]");
        return result;
    }
}
