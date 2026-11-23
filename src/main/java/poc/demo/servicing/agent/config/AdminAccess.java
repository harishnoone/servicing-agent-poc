package poc.demo.servicing.agent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Tells {@code @PreAuthorize} expressions whether JWT validation is active.
 * When no issuer is configured (local demo without an IdP) the supervisor guard is open,
 * matching {@link SecurityConfig}; with an issuer, the scope check is enforced.
 */
@Component("adminAccess")
public class AdminAccess {

    private final boolean jwtEnabled;

    public AdminAccess(@Value("${OAUTH2_ISSUER_URI:}") String issuerUri) {
        this.jwtEnabled = issuerUri != null && !issuerUri.isBlank();
    }

    public boolean jwtEnabled() {
        return jwtEnabled;
    }
}
