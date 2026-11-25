package poc.demo.servicing.agent.replay;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Found live (agent planner, 2026-11-24): the model escalated, its last call timed out, and the fallback reply still offered a supervisor. */
class ReconcileEscalationTest {

    private static final String DENIAL = "I reviewed the $25.00 late fee against [billing_waivers_policy.md#sec-4.2]. "
            + "I cannot waive it: Account tier 'Standard' is not eligible. If you would like, I can ask a supervisor "
            + "to review it.";

    @Test
    void modelEscalationReplacesTheSupervisorOffer() {
        String reply = DeterministicReplayAgent.reconcileEscalation(DENIAL, true);
        assertThat(reply).doesNotContain("If you would like").contains("cannot waive it")
                .endsWith("passed your case to a Tier-2 specialist with the full context, so you will not need to repeat yourself.");
    }

    @Test
    void replyIsUnchangedWithoutModelEscalationOrWhenItAlreadySaysSo() {
        assertThat(DeterministicReplayAgent.reconcileEscalation(DENIAL, false)).isEqualTo(DENIAL);
        String already = "Of course. I have passed your conversation to a Tier-2 specialist.";
        assertThat(DeterministicReplayAgent.reconcileEscalation(already, true)).isEqualTo(already);
    }
}
