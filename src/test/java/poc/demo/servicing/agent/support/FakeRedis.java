package poc.demo.servicing.agent.support;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Map-backed {@link StringRedisTemplate} mock covering the operations the app uses. */
public final class FakeRedis {

    public final Map<String, String> store = new ConcurrentHashMap<>();
    public final StringRedisTemplate template = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    public FakeRedis() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(i -> store.get(i.<String>getArgument(0)));
        doAnswer(i -> { store.put(i.getArgument(0), i.getArgument(1)); return null; })
                .when(ops).set(anyString(), anyString());
        doAnswer(i -> { store.put(i.getArgument(0), i.getArgument(1)); return null; })
                .when(ops).set(anyString(), anyString(), any(Duration.class));
        when(ops.setIfAbsent(anyString(), anyString()))
                .thenAnswer(i -> store.putIfAbsent(i.getArgument(0), i.getArgument(1)) == null);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(i -> store.putIfAbsent(i.getArgument(0), i.getArgument(1)) == null);
        when(template.delete(anyString())).thenAnswer(i -> store.remove(i.<String>getArgument(0)) != null);
    }
}
