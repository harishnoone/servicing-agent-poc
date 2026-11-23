package poc.demo.servicing.agent.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/agent/chat} (FR-2.2: validated at the boundary).
 *
 * @param customerId     account id, letters/digits/underscore/hyphen, at most 32 chars
 * @param message        customer message, at most 2000 chars
 * @param conversationId optional session id for grouping turns
 */
public record ChatRequest(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9_\\-]{1,32}$",
                message = "must be 1-32 characters: letters, digits, underscore or hyphen") String customerId,
        @NotBlank @Size(max = 2000, message = "must be at most 2000 characters") String message,
        @Pattern(regexp = "^[A-Za-z0-9_\\-]{0,64}$", message = "must be at most 64 characters: letters, digits, underscore or hyphen") String conversationId
) {}
