package unit.tests.servicingagent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Two modes (FR-8.1):
 * <ul>
 *   <li><b>JWT mode</b> ({@code OAUTH2_ISSUER_URI} set): Bearer tokens are validated against the
 *       issuer's OIDC metadata. {@code /api/agent/**} needs {@code SCOPE_customer.chat};
 *       {@code /api/admin/**} needs {@code SCOPE_servicing.supervisor} (also enforced by
 *       {@code @PreAuthorize} on the controller).</li>
 *   <li><b>Local demo mode</b> (no issuer): every request is permitted so Swagger UI works without an IdP.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Value("${OAUTH2_ISSUER_URI:}")
    private String issuerUri;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable);

        if (issuerUri != null && !issuerUri.isBlank()) {
            // Resolved on first token, so the app still starts when the IdP is briefly unreachable.
            JwtDecoder decoder = new SupplierJwtDecoder(() -> JwtDecoders.fromIssuerLocation(issuerUri));
            http.authorizeHttpRequests(auth -> auth
                            .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/api-docs/**").permitAll()
                            .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                            .requestMatchers("/api/demo/**").permitAll()
                            .requestMatchers("/api/agent/**").hasAuthority("SCOPE_customer.chat")
                            .requestMatchers("/api/admin/**").hasAuthority("SCOPE_servicing.supervisor")
                            .anyRequest().authenticated())
                    .oauth2ResourceServer(o -> o.jwt(jwt -> jwt.decoder(decoder)));
        } else {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        }
        return http.build();
    }
}
