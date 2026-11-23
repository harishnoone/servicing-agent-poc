package poc.demo.servicing.agent.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Gemini rejects tool calls sent back without their thought_signature; the interceptor restores it. */
class GeminiThoughtSignatureInterceptorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final GeminiThoughtSignatureInterceptor interceptor = new GeminiThoughtSignatureInterceptor(mapper);

    private static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    @Test
    void signatureFromResponseIsAttachedToTheNextRequest() throws Exception {
        interceptor.rememberSignatures(b("""
            {"choices":[{"message":{"tool_calls":[{"id":"call_1","type":"function",
              "function":{"name":"getCustomerState","arguments":"{}"},
              "extra_content":{"google":{"thought_signature":"SIG-1"}}}]}}]}"""));
        byte[] out = interceptor.attachSignatures(b("""
            {"model":"m","messages":[{"role":"user","content":"hi"},
              {"role":"assistant","tool_calls":[{"id":"call_1","type":"function","function":{"name":"getCustomerState","arguments":"{}"}}]},
              {"role":"tool","tool_call_id":"call_1","content":"{}"}]}"""));
        JsonNode call = mapper.readTree(out).path("messages").get(1).path("tool_calls").get(0);
        assertThat(call.path("extra_content").path("google").path("thought_signature").asText()).isEqualTo("SIG-1");
    }

    @Test
    void requestWithoutKnownToolCallsIsUnchanged() {
        byte[] in = b("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        assertThat(interceptor.attachSignatures(in)).isSameAs(in);
    }

    @Test
    void unknownToolCallIdIsLeftAlone() {
        byte[] in = b("{\"messages\":[{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"other\"}]}]}");
        assertThat(interceptor.attachSignatures(in)).isSameAs(in);
    }

    @Test
    void malformedBodiesAreIgnored() {
        byte[] in = b("not json");
        assertThat(interceptor.attachSignatures(in)).isSameAs(in);
        interceptor.rememberSignatures(in);
        assertThat(interceptor.size()).isZero();
    }
}