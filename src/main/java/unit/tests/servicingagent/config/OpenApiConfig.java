package unit.tests.servicingagent.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Customises the OpenAPI 3 specification exposed at {@code /api-docs}.
 *
 * <p>Provides descriptive metadata and three operation tags — Agent, Demo,
 * and Admin — that organise Swagger UI for the live panel demo.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        // Bearer scheme gives Swagger UI an Authorize button for JWT mode (ignored in local demo mode).
        return new OpenAPI()
                .components(new Components().addSecuritySchemes("bearer-jwt", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"))
                .info(new Info()
                        .title("Servicing Agent POC")
                        .version("1.0")
                        .description("""
                                Autonomous Tier-1 customer servicing agent. \
                                Use Swagger UI to drive the live demo: mutate warehouse data, \
                                run agent turns, inspect reasoning traces, and pull KPI reports.""")
                        .contact(new Contact()
                                .name("Servicing Agent POC")
                                .url("http://localhost:8080/swagger-ui.html")))
                .addTagsItem(new Tag()
                        .name("Agent")
                        .description("AI agent chat and KPI reporting"))
                .addTagsItem(new Tag()
                        .name("Demo")
                        .description("Live CDC mutation for demo scenarios"))
                .addTagsItem(new Tag()
                        .name("Admin")
                        .description("OAuth2-protected supervisor overrides"));
    }
}
