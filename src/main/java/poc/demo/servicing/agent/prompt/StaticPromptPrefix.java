package poc.demo.servicing.agent.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The static, cacheable prefix of every LLM request (FR-3.5, FR-3.6): persona and rules from
 * {@code agent.prompts.system}, then a policy digest and few-shot examples from the classpath.
 *
 * <p>Providers cache only an exact prefix, so this text is built once at startup and never contains
 * customer data or timestamps. Customer id, session id and the customer message go in the user
 * message, after the prefix. The digest is orientation only: citations still come from retrieval.
 */
@Component
public class StaticPromptPrefix {

    private static final Logger log = LoggerFactory.getLogger(StaticPromptPrefix.class);

    private final String text;
    private final String synthesisText;
    private final String version;
    private final int estimatedTokens;
    private final boolean cachingEnabled;

    public StaticPromptPrefix(String systemPrompt, boolean cachingEnabled, String digestPath, String fewShotPath) {
        this(systemPrompt, "", cachingEnabled, digestPath, fewShotPath);
    }

    @Autowired
    public StaticPromptPrefix(
            @Value("${agent.prompts.system}") String systemPrompt,
            @Value("${agent.prompts.synthesis:}") String synthesisPrompt,
            @Value("${agent.prompt-caching.enabled:true}") boolean cachingEnabled,
            @Value("${agent.prompt-caching.policy-digest:classpath:prompts/policy-digest.md}") String digestPath,
            @Value("${agent.prompt-caching.few-shot:classpath:prompts/few-shot-examples.md}") String fewShotPath) {
        this.cachingEnabled = cachingEnabled;
        String digest = cachingEnabled ? read(digestPath).strip() : "";
        this.text = cachingEnabled
                ? systemPrompt.strip() + "\n\n" + digest + "\n\n" + read(fewShotPath).strip()
                : systemPrompt.strip();
        String synth = synthesisPrompt == null ? "" : synthesisPrompt.strip();
        this.synthesisText = cachingEnabled && !synth.isEmpty() ? synth + "\n\n" + digest : synth;
        this.version = sha256(text).substring(0, 12);
        this.estimatedTokens = estimateTokens(text);
        log.info("Static prompt prefix: caching={} version={} ~{} tokens", cachingEnabled, version, estimatedTokens);
    }

    /** Full system prompt for agentic mode (model plans and calls tools). Identical on every call. */
    public String text() { return text; }

    /** System prompt for single-pass mode (one call, no tools). Identical on every call. */
    public String synthesisText() { return synthesisText; }

    /** Short content hash. A change means every provider cache entry for the old prefix is dead. */
    public String version() { return version; }

    /** Rough estimate (4 characters per token); real counts come from provider usage metadata. */
    public int estimatedTokens() { return estimatedTokens; }

    public boolean cachingEnabled() { return cachingEnabled; }

    static int estimateTokens(String s) { return (s.length() + 3) / 4; }

    private static String read(String location) {
        String path = location.startsWith("classpath:") ? location.substring("classpath:".length()) : location;
        try (var in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read prompt resource " + location, e);
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}