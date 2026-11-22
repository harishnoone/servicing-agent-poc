package unit.tests.servicingagent.guardrails;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-2.1 redaction. */
class PiiSanitizerTest {

    private final PiiSanitizer s = new PiiSanitizer();

    @Test
    void redactsSsnCardBearerAndApiKeys() {
        String out = s.sanitize("SSN 123-45-6789, card 4111 1111 1111 1111, auth Bearer abc.def-ghi, "
                + "key sk_live_ABCDEFGHIJKLMNOPQRSTUV");
        assertThat(out)
                .doesNotContain("123-45-6789", "4111 1111 1111 1111", "abc.def-ghi", "sk_live_ABCDEFGHIJKLMNOPQRSTUV")
                .contains("[SSN-REDACTED]", "[CARD-REDACTED]", "Bearer [TOKEN-REDACTED]", "[KEY-REDACTED]");
    }

    @Test
    void leavesOrdinaryTextAlone() {
        assertThat(s.sanitize("Please waive my $45 late fee for ERR-504-GW")).isEqualTo("Please waive my $45 late fee for ERR-504-GW");
        assertThat(s.sanitize(null)).isNull();
    }
}
