package poc.demo.servicing.agent.tools;

import poc.demo.servicing.agent.model.CustomerProfile;
import poc.demo.servicing.agent.model.WaiverEvaluationResult;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-5.2: the four policy rules and their reason codes, including boundaries. */
class DeterministicRulesToolsTest {

    private final DeterministicRulesTools rules = new DeterministicRulesTools(
            new BigDecimal("50.00"), 12, "Gold,Enterprise", 0, null, null, null, null);

    private static CustomerProfile profile(String tier, int tenure, int waivers) {
        return new CustomerProfile("c1", "Test", tier, tenure, BigDecimal.TEN, waivers, 0L, List.of(), List.of());
    }

    @ParameterizedTest(name = "{0}/{1}m/{2} waivers/${3} -> {4}")
    @CsvSource({
            "Gold,       36, 0, 45.00, APPROVED",
            "Enterprise, 12, 0, 50.00, APPROVED",            // both boundaries inclusive
            "Gold,       36, 0, 50.01, FEE_TOO_HIGH",
            "Standard,   36, 0, 10.00, TIER_INELIGIBLE",
            "Gold,       11, 0, 10.00, TENURE_TOO_SHORT",
            "Enterprise, 48, 1, 35.00, WAIVER_LIMIT_REACHED", // Marcus: second waiver not allowed
            "Gold,       36, 0, 0,     INVALID_AMOUNT",
            "Gold,       36, 0, -5,    INVALID_AMOUNT"
    })
    void evaluatesPolicy(String tier, int tenure, int waivers, BigDecimal amount, String expected) {
        WaiverEvaluationResult r = rules.evaluate(profile(tier, tenure, waivers), amount);
        assertThat(r.reasonCode()).isEqualTo(expected);
        assertThat(r.eligible()).isEqualTo("APPROVED".equals(expected));
        assertThat(r.policyCitation()).isEqualTo("billing_waivers_policy.md#sec-4.2");
    }
}
