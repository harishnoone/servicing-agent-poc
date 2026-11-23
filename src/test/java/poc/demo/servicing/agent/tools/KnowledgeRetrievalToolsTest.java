package poc.demo.servicing.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import poc.demo.servicing.agent.tools.KnowledgeRetrievalTools.SearchResponse;
import poc.demo.servicing.agent.tools.KnowledgeRetrievalTools.SearchResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-4.1 to FR-4.3 and the FR-6.1 confidence threshold, against the real policy corpus. */
class KnowledgeRetrievalToolsTest {

    private static KnowledgeRetrievalTools tools;

    @BeforeAll
    static void index() {
        tools = new KnowledgeRetrievalTools(new ObjectMapper(), null, null, 3, 2.0, 0.72);
        tools.buildIndex();
    }

    @Test
    void waiverQueryReturnsSection42WithHighConfidence() {
        SearchResponse r = tools.search("late fee waiver policy eligibility");
        assertThat(r.results()).isNotEmpty();
        assertThat(r.results().get(0).documentId()).isEqualTo("billing_waivers_policy.md#sec-4.2");
        assertThat(r.results().get(0).authorityLevel()).isEqualTo("CANONICAL_POLICY");
        assertThat(r.lowConfidence()).isFalse();
        assertThat(r.retrievalConfidence()).isGreaterThanOrEqualTo(0.72);
    }

    @Test
    void errorCodeRanksCanonicalFixAboveSupersededCase() {
        SearchResponse r = tools.search("Our gateway is throwing ERR-504-GW, how do we fix it?");
        assertThat(r.results().get(0).documentId()).isEqualTo("troubleshooting_guide.md#sec-1.1");
        assertThat(r.lowConfidence()).isFalse();

        SearchResult legacy = r.results().stream()
                .filter(x -> x.documentId().equals("CASE-2024-0317")).findFirst().orElseThrow();
        assertThat(legacy.superseded()).isTrue();
        assertThat(legacy.supersededBy()).contains("troubleshooting_guide.md");
        assertThat(r.guidance()).contains("SUPERSEDED").contains("CASE-2024-0317");

        int canonicalRank = r.results().indexOf(r.results().get(0));
        assertThat(r.results().indexOf(legacy)).isGreaterThan(canonicalRank);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "Our production gateway keeps returning ERR-504-GW. What is the fix?",
            "ERR-504-GW again on the gateway",
            "Production API Gateway throwing ERR-504-GW"})
    void demoPhrasingsOfErr504AreConfident(String q) {
        SearchResponse r = tools.search(q);
        assertThat(r.results().get(0).documentId()).isEqualTo("troubleshooting_guide.md#sec-1.1");
        assertThat(r.lowConfidence()).isFalse();
    }

    @Test
    void slaCreditQuestionFindsP1Section() {
        SearchResponse r = tools.search("What SLA credit do I get for a P1 outage longer than 4 hours?");
        assertThat(r.results().get(0).documentId()).isEqualTo("sla_credit_policy.md#sec-4.1");
        assertThat(r.lowConfidence()).isFalse();
    }

    @Test
    void unrelatedQueryIsLowConfidence() {
        SearchResponse r = tools.search("what is your favourite pizza topping");
        assertThat(r.lowConfidence()).isTrue();
        assertThat(r.guidance()).contains("escalateWithSummary");
    }

    @Test
    void markdownChunksGetSectionAnchors() {
        var chunks = KnowledgeRetrievalTools.chunkMarkdown("p.md", """
                # Title
                ## 4. Rules
                ### 4.2 Late Fee Waiver Eligibility
                A Tier-1 agent may approve a late-fee waiver when all criteria are satisfied.
                """);
        assertThat(chunks).extracting(KnowledgeRetrievalTools.Chunk::id).containsExactly("p.md#sec-4.2");
    }
}
