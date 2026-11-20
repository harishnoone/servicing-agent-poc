package io.github.harishnoone.servicingagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.harishnoone.servicingagent.telemetry.TraceCollector;
import io.github.harishnoone.servicingagent.telemetry.TurnState;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hybrid knowledge retrieval over canonical Markdown policies and historical case notes.
 *
 * <p>FR-4.1: hybrid scoring = BM25 (exact recall on codes such as {@code ERR-504-GW})
 * fused with a dense similarity signal. The dense signal is a local hashed character
 * n-gram embedding, so the stack runs offline (NFR-1); swap in a real embedding model
 * by replacing {@link #embed(String)}.
 * <p>FR-4.2: canonical policy chunks are boosted over case notes; superseded cases are
 * further down-weighted.
 * <p>FR-4.3: a case with {@code superseded_by} is flagged so the model follows the
 * canonical policy and says the legacy guidance was superseded.
 * <p>FR-6.1: the response carries a retrieval confidence; below the configured
 * threshold the agent must escalate.
 */
@Component
public class KnowledgeRetrievalTools {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRetrievalTools.class);

    static final String AUTHORITY_CANONICAL = "CANONICAL_POLICY";
    static final String AUTHORITY_CASE      = "HISTORICAL_CASE";

    private static final int    EMBED_DIM        = 512;
    private static final double BM25_WEIGHT      = 0.6;
    private static final double VECTOR_WEIGHT    = 0.4;
    private static final double SUPERSEDED_PENALTY = 0.5;
    private static final int    HEADING_WEIGHT     = 3;
    /** Hyphenated token containing a digit, e.g. err-504-gw, sec-4, inv-2025-456-001. */
    private static final Pattern IDENTIFIER = Pattern.compile("(?=.*\\d)[a-z0-9]+(?:-[a-z0-9]+)+");
    private static final double IDENTIFIER_MATCH_CONFIDENCE = 0.9;
    private static final double SUPERSEDED_SURFACE_MIN = 0.5;
    private static final int    MAX_SURFACED_SUPERSEDED = 1;
    /** Matched IDF mass at which lexical evidence counts as fully sufficient (about two rare terms). */
    private static final double IDF_SATURATION     = 4.0;
    private static final Pattern HEADING = Pattern.compile("^(#{2,3})\\s+(.*)$");
    private static final Pattern HEADING_NUMBER = Pattern.compile("^(\\d+(?:\\.\\d+)*)\\.?\\s.*");
    private static final Pattern TOKEN = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "is", "are", "was", "were", "be", "to", "of", "and", "or", "in", "on",
            "for", "it", "i", "my", "me", "you", "can", "do", "does", "how", "what", "why", "this",
            "that", "with", "as", "at", "by", "if", "please", "your", "our", "we", "get", "got", "should");

    private final ObjectMapper objectMapper;
    private final TraceCollector traceCollector;
    private final TurnState turnState;
    private final int topK;
    private final double canonicalBoost;
    private final double minConfidence;

    private final List<Chunk> index = new ArrayList<>();
    private final Map<String, Integer> docFreq = new HashMap<>();
    private double avgChunkLen = 1.0;

    public KnowledgeRetrievalTools(
            ObjectMapper objectMapper,
            TraceCollector traceCollector,
            TurnState turnState,
            @Value("${agent.retrieval.top-k:3}") int topK,
            @Value("${agent.retrieval.canonical-boost:2.0}") double canonicalBoost,
            @Value("${agent.escalation.min-retrieval-confidence:0.72}") double minConfidence) {
        this.objectMapper   = objectMapper;
        this.traceCollector = traceCollector;
        this.turnState      = turnState;
        this.topK           = topK;
        this.canonicalBoost = canonicalBoost;
        this.minConfidence  = minConfidence;
    }

    // -------------------------------------------------------------------------
    // Indexing
    // -------------------------------------------------------------------------

    @PostConstruct
    public void buildIndex() {
        loadPolicyDocuments();
        loadHistoricalCases();
        computeCorpusStats();
        log.info("Knowledge index built: {} chunks", index.size());
    }

    private void loadPolicyDocuments() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:/policies/*.md");
            for (Resource resource : resources) {
                String filename = Objects.requireNonNullElse(resource.getFilename(), "policy.md");
                String content  = resource.getContentAsString(StandardCharsets.UTF_8);
                chunkMarkdown(filename, content).forEach(index::add);
            }
        } catch (IOException e) {
            log.warn("No policy documents loaded: {}", e.getMessage());
        }
    }

    /** Splits a Markdown file into one chunk per {@code ##}/{@code ###} section. */
    static List<Chunk> chunkMarkdown(String filename, String markdown) {
        List<Chunk> chunks = new ArrayList<>();
        String title = markdown.lines().filter(l -> l.startsWith("# ")).findFirst()
                .map(l -> l.substring(2).trim()).orElse("");
        String heading = null;
        StringBuilder body = new StringBuilder();
        for (String line : markdown.split("\\R")) {
            Matcher m = HEADING.matcher(line);
            if (m.matches()) {
                addSection(chunks, filename, title, heading, body);
                heading = m.group(2).trim();
                body = new StringBuilder();
            } else if (heading != null) {
                body.append(line).append('\n');
            }
        }
        addSection(chunks, filename, title, heading, body);
        return chunks;
    }

    private static void addSection(List<Chunk> chunks, String filename, String title, String heading, StringBuilder body) {
        if (heading == null || body.toString().strip().length() < 40) {
            return;
        }
        Matcher num = HEADING_NUMBER.matcher(heading);
        String anchor = num.matches()
                ? "sec-" + num.group(1)
                : heading.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        String text = heading + "\n" + body.toString().strip();
        // Heading terms count HEADING_WEIGHT times (BM25F-style field boost): section titles name the topic.
        // The document title gives every chunk its topic (e.g. "SLA Credit Policy").
        String indexText = title + "\n" + (heading + "\n").repeat(HEADING_WEIGHT) + body;
        chunks.add(new Chunk(filename + "#" + anchor, text, AUTHORITY_CANONICAL, null, indexText));
    }

    private void loadHistoricalCases() {
        ClassPathResource resource = new ClassPathResource("cases/historical_cases.json");
        if (!resource.exists()) {
            return;
        }
        try (InputStream in = resource.getInputStream()) {
            for (JsonNode c : objectMapper.readTree(in)) {
                String id = c.path("case_id").asText(UUID.randomUUID().toString());
                String text = String.join("\n",
                        c.path("category").asText(""),
                        c.path("resolution").asText(""),
                        c.path("notes").asText(""));
                String supersededBy = c.path("superseded_by").isNull() || c.path("superseded_by").isMissingNode()
                        ? null : c.path("superseded_by").asText();
                index.add(new Chunk(id, text, AUTHORITY_CASE, supersededBy));
            }
        } catch (IOException e) {
            log.warn("Historical cases not loaded: {}", e.getMessage());
        }
    }

    private void computeCorpusStats() {
        double totalLen = 0;
        for (Chunk chunk : index) {
            totalLen += chunk.tokens().size();
            for (String t : new HashSet<>(chunk.tokens())) {
                docFreq.merge(t, 1, Integer::sum);
            }
        }
        avgChunkLen = index.isEmpty() ? 1.0 : totalLen / index.size();
    }

    // -------------------------------------------------------------------------
    // Tool
    // -------------------------------------------------------------------------

    @Tool(description = "Search canonical policy documents and historical case notes using hybrid "
            + "BM25 + semantic retrieval with authority weighting. Canonical policies always outrank "
            + "historical cases. Use for policy questions, error code troubleshooting and eligibility "
            + "guidance. Cite results inline as [documentId]. Honour the 'guidance' field.")
    public SearchResponse searchPoliciesAndCases(
            @ToolParam(description = "Search query, include exact error codes or policy terms") String query) {
        long start = System.currentTimeMillis();
        SearchResponse response = search(query);
        turnState.recordTool("searchPoliciesAndCases");
        turnState.recordRetrieval(
                response.results().stream().map(SearchResult::documentId).toList(),
                response.retrievalConfidence());
        turnState.recordSnippets(response.results().stream()
                .map(r -> new TurnState.RetrievedSnippet(r.documentId(), r.authorityLevel(), r.superseded(),
                        r.supersededBy(), r.content()))
                .toList());
        traceCollector.record(String.format("TOOL_CALL: searchPoliciesAndCases (confidence=%.2f)",
                response.retrievalConfidence()), System.currentTimeMillis() - start);
        return response;
    }

    /** Pure search logic, separate from tracing for unit tests. */
    SearchResponse search(String query) {
        List<String> qTokens = tokenize(query);
        List<String> contentTokens = qTokens.stream().filter(t -> !STOPWORDS.contains(t)).toList();
        if (contentTokens.isEmpty() || index.isEmpty()) {
            return new SearchResponse(List.of(), 0.0, true,
                    "No usable query terms or empty index. Escalate with escalateWithSummary.");
        }

        double[] qVec = embed(query);
        double maxBm25 = 0.0;
        double[] bm25 = new double[index.size()];
        for (int i = 0; i < index.size(); i++) {
            bm25[i] = bm25Score(contentTokens, index.get(i));
            maxBm25 = Math.max(maxBm25, bm25[i]);
        }

        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < index.size(); i++) {
            Chunk chunk = index.get(i);
            double lexical = maxBm25 == 0 ? 0 : bm25[i] / maxBm25;
            double dense   = cosine(qVec, chunk.vector());
            double hybrid  = BM25_WEIGHT * lexical + VECTOR_WEIGHT * dense;
            double weighted = hybrid;
            if (AUTHORITY_CANONICAL.equals(chunk.authority())) {
                weighted *= canonicalBoost;
            } else if (chunk.supersededBy() != null) {
                weighted *= SUPERSEDED_PENALTY;
            }
            scored.add(new Scored(chunk, weighted, dense, lexical));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<Scored> top = new ArrayList<>(scored.stream().filter(s -> s.score() > 0).limit(topK).toList());
        // FR-4.3: a strongly matching superseded case is surfaced (after the canonical results) so the
        // answer can explicitly say the legacy guidance no longer applies, instead of silently hiding it.
        scored.stream()
                .filter(s -> !top.contains(s) && s.chunk().supersededBy() != null && s.lexical() >= SUPERSEDED_SURFACE_MIN)
                .limit(MAX_SURFACED_SUPERSEDED)
                .forEach(top::add);

        double confidence = top.isEmpty() ? 0.0 : confidence(contentTokens, top.get(0));
        boolean low = confidence < minConfidence;

        List<SearchResult> results = top.stream().map(s -> new SearchResult(
                s.chunk().id(), s.chunk().text(), s.chunk().authority(),
                s.chunk().supersededBy() != null, s.chunk().supersededBy(), round(s.score()))).toList();

        return new SearchResponse(results, round(confidence), low, guidance(results, low));
    }

    private String guidance(List<SearchResult> results, boolean lowConfidence) {
        StringBuilder g = new StringBuilder();
        if (lowConfidence) {
            g.append("Retrieval confidence is below the threshold. Do not answer from these results; "
                    + "call escalateWithSummary. ");
        }
        for (SearchResult r : results) {
            if (r.superseded()) {
                g.append("Result [").append(r.documentId()).append("] is SUPERSEDED by ")
                        .append(r.supersededBy()).append(". Do not follow its resolution; follow the active "
                                + "canonical policy and tell the customer the legacy guidance was superseded. ");
            }
        }
        if (g.isEmpty()) {
            g.append("Cite each policy claim inline as [documentId].");
        }
        return g.toString().strip();
    }

    /**
     * Confidence in [0,1] for the top result. Lexical evidence is the IDF-weighted share of
     * corpus-known query terms found in the chunk, scaled by how much IDF mass matched in absolute
     * terms (so one common word cannot produce high confidence). Words unknown to the corpus are
     * ignored. Blended with the dense similarity.
     */
    private double confidence(List<String> contentTokens, Scored top) {
        Set<String> chunkTokens = new HashSet<>(top.chunk().tokens());
        double knownIdf = 0, matchedIdf = 0;
        for (String t : new LinkedHashSet<>(contentTokens)) {
            if (docFreq.containsKey(t)) {
                double idf = idf(t);
                knownIdf += idf;
                if (chunkTokens.contains(t)) {
                    matchedIdf += idf;
                }
            }
        }
        if (knownIdf == 0) {
            return 0.0;
        }
        // FR-4.1: an exact identifier hit (error code, section id, invoice id) is decisive on its own.
        boolean identifierHit = contentTokens.stream().anyMatch(t -> IDENTIFIER.matcher(t).matches() && chunkTokens.contains(t));
        if (identifierHit) {
            return Math.max(IDENTIFIER_MATCH_CONFIDENCE, lexicalConfidence(matchedIdf, knownIdf, top));
        }
        return lexicalConfidence(matchedIdf, knownIdf, top);
    }

    private double lexicalConfidence(double matchedIdf, double knownIdf, Scored top) {
        double lexical = (matchedIdf / knownIdf) * Math.min(1.0, matchedIdf / IDF_SATURATION);
        return Math.min(1.0, 0.8 * lexical + 0.2 * Math.min(1.0, top.dense() * 2));
    }
    // -------------------------------------------------------------------------
    // Scoring helpers
    // -------------------------------------------------------------------------

    private double idf(String term) {
        int n = docFreq.getOrDefault(term, 0);
        return Math.log(1.0 + (index.size() - n + 0.5) / (n + 0.5));
    }

    private double bm25Score(List<String> queryTokens, Chunk chunk) {
        final double k1 = 1.5, b = 0.75;
        Map<String, Integer> tf = new HashMap<>();
        for (String t : chunk.tokens()) {
            tf.merge(t, 1, Integer::sum);
        }
        double len = chunk.tokens().size();
        double score = 0;
        for (String q : new LinkedHashSet<>(queryTokens)) {
            int f = tf.getOrDefault(q, 0);
            if (f == 0) continue;
            score += idf(q) * (f * (k1 + 1)) / (f + k1 * (1 - b + b * (len / avgChunkLen)));
        }
        return score;
    }

    /** Lowercases and extracts alphanumeric tokens, keeping hyphenated codes whole and split. */
    static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        Matcher m = TOKEN.matcher(text.toLowerCase());
        while (m.find()) {
            String tok = m.group();
            tokens.add(tok);
            if (tok.contains("-")) {
                tokens.addAll(Arrays.asList(tok.split("-")));
            }
        }
        return tokens;
    }

    /** Local dense stand-in: hashed character-trigram term-frequency vector, L2-normalised. */
    static double[] embed(String text) {
        double[] v = new double[EMBED_DIM];
        if (text == null) return v;
        String s = " " + text.toLowerCase().replaceAll("[^a-z0-9]+", " ").strip() + " ";
        for (int i = 0; i + 3 <= s.length(); i++) {
            v[Math.floorMod(s.substring(i, i + 3).hashCode(), EMBED_DIM)] += 1.0;
        }
        double norm = 0;
        for (double d : v) norm += d * d;
        norm = Math.sqrt(norm);
        if (norm > 0) for (int i = 0; i < v.length; i++) v[i] /= norm;
        return v;
    }

    private static double cosine(double[] a, double[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) dot += a[i] * b[i];
        return dot;
    }

    private static double round(double d) {
        return Math.round(d * 1000.0) / 1000.0;
    }

    // -------------------------------------------------------------------------
    // Types
    // -------------------------------------------------------------------------

    record Chunk(String id, String text, String authority, String supersededBy,
                 List<String> tokens, double[] vector) {
        Chunk(String id, String text, String authority, String supersededBy) {
            this(id, text, authority, supersededBy, text);
        }

        Chunk(String id, String text, String authority, String supersededBy, String indexText) {
            this(id, text, authority, supersededBy, tokenize(indexText), embed(text));
        }
    }

    private record Scored(Chunk chunk, double score, double dense, double lexical) {}

    /**
     * One matched chunk.
     *
     * @param documentId     citation id, e.g. {@code billing_waivers_policy.md#sec-4.2} or a case id
     * @param supersededBy   canonical reference that replaces this case, or {@code null}
     */
    public record SearchResult(
            String documentId, String content, String authorityLevel,
            boolean superseded, String supersededBy, double score) {}

    /**
     * Tool response.
     *
     * @param retrievalConfidence 0.0 to 1.0 confidence in the top result
     * @param lowConfidence       true when below {@code agent.escalation.min-retrieval-confidence}
     * @param guidance            instructions for the synthesis step (supersession, escalation)
     */
    public record SearchResponse(
            List<SearchResult> results, double retrievalConfidence,
            boolean lowConfidence, String guidance) {}
}
