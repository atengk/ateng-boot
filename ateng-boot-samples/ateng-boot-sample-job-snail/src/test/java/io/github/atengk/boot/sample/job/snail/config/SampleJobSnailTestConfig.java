package io.github.atengk.boot.sample.job.snail.config;

import com.aizuda.snailjob.model.response.JobApiResponse;
import com.aizuda.snailjob.model.response.RetryApiResponse;
import com.aizuda.snailjob.model.response.WorkflowDetailApiResponse;
import io.github.atengk.boot.job.snail.event.SnailRetryDeadLetterEvent;
import io.github.atengk.boot.job.snail.template.SnailJobAdminTemplate;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisServerCommands;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * SnailJob 样例工程离线单测自洽配置桩
 *
 * <p>在非 local (即 CI 或离线本地) 环境下，向容器注入具备完整仿真契约的
 * {@link SnailJobAdminTemplate} 与内存仿真 {@link StringRedisTemplate} / {@link RedisConnectionFactory}，
 * 确保 5 大 Controller 与 {@code RedisSnailRetryContextStore} 分布式重试现场存储
 * 在脱网环境下 100% 独立自洽运行；激活 {@code local} Profile 时自动切换直连真实中间件。</p>
 *
 * @author Ateng
 * @since 2026-11-11
 */
@TestConfiguration(proxyBeanMethods = false)
public class SampleJobSnailTestConfig {

    @Bean
    @Primary
    @Profile("!local")
    public SnailJobAdminTemplate mockSnailJobAdminTemplate() {
        SnailJobAdminTemplate mock = Mockito.mock(SnailJobAdminTemplate.class);

        // 1. 任务管理 OpenAPI 行为打桩
        when(mock.addJob(any())).thenReturn(1001L);
        when(mock.updateJob(any())).thenReturn(true);
        when(mock.updateJobStatus(anyLong(), any())).thenReturn(true);
        when(mock.enableJob(anyLong())).thenReturn(true);
        when(mock.disableJob(anyLong())).thenReturn(true);
        when(mock.triggerJob(anyLong())).thenReturn(true);
        when(mock.deleteJob(anyLong())).thenReturn(true);

        JobApiResponse mockJob = new JobApiResponse();
        mockJob.setId(1001L);
        mockJob.setJobName("mockSystemWarmupJob");
        mockJob.setExecutorInfo("warmupAndProbeJobHandler");
        mockJob.setJobStatus(1);
        when(mock.getJobDetail(anyLong())).thenReturn(mockJob);

        // 2. 工作流 DAG 管理 OpenAPI 行为打桩
        when(mock.triggerWorkflow(anyLong())).thenReturn(true);
        when(mock.enableWorkflow(anyLong())).thenReturn(true);
        when(mock.disableWorkflow(anyLong())).thenReturn(true);
        when(mock.deleteWorkflow(anyLong())).thenReturn(true);

        WorkflowDetailApiResponse mockWorkflow = new WorkflowDetailApiResponse();
        mockWorkflow.setId(2001L);
        mockWorkflow.setWorkflowName("mockBillSettlementWorkflow");
        when(mock.getWorkflowBatchDetail(anyLong())).thenReturn(mockWorkflow);

        // 3. 重试运维 OpenAPI 行为打桩
        when(mock.triggerRetryTask(anyLong())).thenReturn(true);
        when(mock.updateRetryTaskStatus(anyLong(), any())).thenReturn(true);

        RetryApiResponse mockRetry = new RetryApiResponse();
        mockRetry.setId(888L);
        mockRetry.setSceneName("orderPayRetry");
        mockRetry.setRetryStatus(1);
        when(mock.queryRetryTask(anyLong())).thenReturn(mockRetry);

        return mock;
    }

    @Bean
    @Primary
    @Profile("!local")
    public RedisConnectionFactory mockRedisConnectionFactory() {
        RedisConnectionFactory factory = Mockito.mock(RedisConnectionFactory.class);
        RedisConnection connection = Mockito.mock(RedisConnection.class);
        RedisServerCommands serverCommands = Mockito.mock(RedisServerCommands.class);
        when(factory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("PONG");
        when(connection.serverCommands()).thenReturn(serverCommands);
        when(serverCommands.info()).thenReturn(new Properties());
        return factory;
    }

    @Bean(name = "stringRedisTemplate")
    @Primary
    @Profile("!local")
    public StringRedisTemplate mockStringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new InMemoryStringRedisTemplate(connectionFactory);
    }

    @Bean
    public SampleDeadLetterEventCollector sampleDeadLetterEventCollector() {
        return new SampleDeadLetterEventCollector();
    }

    /**
     * 收集死信领域事件的测试监听器（用于样例端到端断言）
     */
    public static class SampleDeadLetterEventCollector implements ApplicationListener<SnailRetryDeadLetterEvent> {

        private final List<SnailRetryDeadLetterEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void onApplicationEvent(SnailRetryDeadLetterEvent event) {
            events.add(event);
        }

        public List<SnailRetryDeadLetterEvent> getEvents() {
            return events;
        }

        public void clear() {
            events.clear();
        }
    }

    /**
     * 离线自洽内存版 {@link StringRedisTemplate} 仿真实现（支持 set/get/TTL/hasKey/delete/scan）
     */
    static class InMemoryStringRedisTemplate extends StringRedisTemplate {

        private final Map<String, TimedValue> store = new ConcurrentHashMap<>();
        private final ValueOperations<String, String> valueOps;

        @SuppressWarnings("unchecked")
        InMemoryStringRedisTemplate(RedisConnectionFactory connectionFactory) {
            setConnectionFactory(connectionFactory);
            ValueOperations<String, String> mockOps = Mockito.mock(ValueOperations.class);
            Mockito.doAnswer(inv -> {
                String key = inv.getArgument(0);
                String val = inv.getArgument(1);
                Duration ttl = inv.getArgument(2);
                Instant expiresAt = (ttl != null && !ttl.isNegative() && !ttl.isZero())
                        ? Instant.now().plus(ttl)
                        : Instant.MAX;
                store.put(key, new TimedValue(val, expiresAt));
                return null;
            }).when(mockOps).set(any(String.class), any(String.class), any(Duration.class));

            Mockito.doAnswer(inv -> {
                String key = inv.getArgument(0);
                String val = inv.getArgument(1);
                long timeout = inv.getArgument(2);
                TimeUnit unit = inv.getArgument(3);
                Instant expiresAt = timeout > 0
                        ? Instant.now().plusMillis(unit.toMillis(timeout))
                        : Instant.MAX;
                store.put(key, new TimedValue(val, expiresAt));
                return null;
            }).when(mockOps).set(any(String.class), any(String.class), anyLong(), any(TimeUnit.class));

            Mockito.doAnswer(inv -> {
                String key = inv.getArgument(0);
                String val = inv.getArgument(1);
                store.put(key, new TimedValue(val, Instant.MAX));
                return null;
            }).when(mockOps).set(any(String.class), any(String.class));

            when(mockOps.get(any())).thenAnswer(inv -> {
                String key = inv.getArgument(0);
                TimedValue entry = store.get(key);
                if (entry == null) {
                    return null;
                }
                if (Instant.now().isAfter(entry.expiresAt())) {
                    store.remove(key);
                    return null;
                }
                return entry.value();
            });
            this.valueOps = mockOps;
        }

        @Override
        public ValueOperations<String, String> opsForValue() {
            return valueOps;
        }

        @Override
        public Boolean hasKey(String key) {
            return valueOps.get(key) != null;
        }

        @Override
        public Boolean delete(String key) {
            return store.remove(key) != null;
        }

        @Override
        public Cursor<String> scan(ScanOptions options) {
            String pattern = options != null ? options.getPattern() : null;
            String prefix = (pattern != null && pattern.endsWith("*"))
                    ? pattern.substring(0, pattern.length() - 1)
                    : pattern;
            List<String> keys = store.keySet().stream()
                    .filter(k -> prefix == null || k.startsWith(prefix))
                    .toList();
            Iterator<String> iterator = keys.iterator();
            Cursor<String> cursor = Mockito.mock(Cursor.class);
            when(cursor.hasNext()).thenAnswer(inv -> iterator.hasNext());
            when(cursor.next()).thenAnswer(inv -> iterator.next());
            return cursor;
        }

        private record TimedValue(String value, Instant expiresAt) {
        }
    }
}
