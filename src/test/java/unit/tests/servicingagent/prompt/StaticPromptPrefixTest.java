package unit.tests.servicingagent.prompt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-3.5 / FR-3.6: the cached prefix is stable, complete and free of per-customer data. */
class StaticPromptPrefixTest {

    private static final String SYSTEM = "You are a Tier-1 agent.\nRule 1.";

    private StaticPromptPrefix prefix(boolean enabled) {
        return new StaticPromptPrefix(SYSTEM, enabled,
                "classpath:prompts/policy-digest.md", "classpath:prompts/few-shot-examples.md");
    }

    @Test
    void enabledPrefixHasSystemDigestAndExamplesInThatOrder() {
        String text = prefix(true).text();
        assertThat(text).startsWith(SYSTEM);
        assertThat(text.indexOf("Policy digest")).isGreaterThan(text.indexOf("Rule 1."));
        assertThat(text.indexOf("Worked examples")).isGreaterThan(text.indexOf("Policy digest"));
        assertThat(text).contains("$50.00", "evaluateWaiverRules", "ERR-504-GW");
    }

    @Test
    void prefixIsIdenticalAcrossInstances() {
        assertThat(prefix(true).text()).isEqualTo(prefix(true).text());
        assertThat(prefix(true).version()).isEqualTo(prefix(true).version()).hasSize(12);
    }

    @Test
    void prefixContainsNoCustomerSpecificOrTimeVaryingData() {
        String text = prefix(true).text();
        assertThat(text).doesNotContainPattern("cust_\\d+");
        assertThat(text).doesNotContainPattern("\\d{4}-\\d{2}-\\d{2}T");
        assertThat(text).doesNotContain("Customer ID:", "Session ID:");
    }

    @Test
    void digestIsMarkedNotCitable() {
        assertThat(prefix(true).text()).contains("NOT a citable");
    }

    @Test
    void disabledUsesSystemPromptOnlyAndChangesVersion() {
        StaticPromptPrefix off = prefix(false);
        assertThat(off.text()).isEqualTo(SYSTEM);
        assertThat(off.version()).isNotEqualTo(prefix(true).version());
        assertThat(off.estimatedTokens()).isLessThan(prefix(true).estimatedTokens());
    }
}