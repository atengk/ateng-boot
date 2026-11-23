package io.github.atengk.boot.ai;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.tool.ToolExecution;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import io.github.atengk.boot.ai.checkpoint.MemoryCheckpointSaver;
import io.github.atengk.boot.ai.graph.BaseGraphState;
import io.github.atengk.boot.ai.graph.GraphWorkflow;
import io.github.atengk.boot.ai.graph.StateSnapshotVo;
import io.github.atengk.boot.ai.graph.debate.DebateConsensusWorkflow;
import io.github.atengk.boot.ai.graph.debate.DebateParticipant;
import io.github.atengk.boot.ai.graph.pipeline.PipelineStage;
import io.github.atengk.boot.ai.graph.pipeline.SequentialPipelineWorkflow;
import io.github.atengk.boot.ai.guardrail.AiGuardrailProperties;
import io.github.atengk.boot.ai.guardrail.DefaultAiGuardrail;
import io.github.atengk.boot.ai.mcp.HttpMcpClientAdapter;
import io.github.atengk.boot.ai.memory.episodic.EpisodicMemoryTemplate;
import io.github.atengk.boot.ai.memory.episodic.MemoryFact;
import io.github.atengk.boot.ai.openapi.OpenApiToolAdapter;
import io.github.atengk.boot.ai.openapi.OpenApiToolProperties;
import io.github.atengk.boot.ai.properties.EpisodicMemoryProperties;
import io.github.atengk.boot.ai.registrar.AiAgentFactoryBean;
import io.github.atengk.boot.ai.registry.AiModelRegistry;
import io.github.atengk.boot.ai.streaming.ActiveStreamRegistry;
import io.github.atengk.boot.ai.streaming.GuardrailedTokenStream;
import io.github.atengk.boot.ai.streaming.SseTokenStreamBridge;
import io.github.atengk.boot.ai.streaming.StreamContextHolder;
import io.github.atengk.boot.ai.template.EmbeddingTemplate;
import okhttp3.Call;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 虚拟线程防 Pinning 并发锁改造多线程争用与无死锁专项验证测试
 *
 * @author Ateng
 * @since 2026-11-21
 */
@DisplayName("并发锁争用安全与无死锁专项测试")
class ConcurrencyLockContentionTest {

    @AfterEach
    void clearContext() {
        StreamContextHolder.clear();
    }

    @Test
    @DisplayName("SseTokenStreamBridge.pipe 自动透传 GuardrailedTokenStream 的 streamId、注册表与 StreamContextHolder")
    void testPipePropagatesStreamIdRegistryAndBindsContextDuringStart() {
        ActiveStreamRegistry registry = new ActiveStreamRegistry();
        Call activeCall = mock(Call.class);

        AiGuardrailProperties props = new AiGuardrailProperties();
        props.setEnabled(true);
        props.setBannedKeywords(List.of("违禁词"));
        DefaultAiGuardrail guardrail = new DefaultAiGuardrail(props);

        AtomicReference<String> streamIdSeenDuringStart = new AtomicReference<>();
        AtomicReference<Consumer<String>> nextRef = new AtomicReference<>();

        TokenStream rawStream = new TokenStream() {
            private Consumer<Throwable> errHandler;

            @Override
            public TokenStream onNext(Consumer<String> tokenHandler) {
                nextRef.set(tokenHandler);
                return this;
            }

            @Override
            public TokenStream onRetrieved(Consumer<List<Content>> contentHandler) {
                return this;
            }

            @Override
            public TokenStream onToolExecuted(Consumer<ToolExecution> toolExecuteHandler) {
                return this;
            }

            @Override
            public TokenStream onComplete(Consumer<Response<AiMessage>> completionHandler) {
                return this;
            }

            @Override
            public TokenStream onError(Consumer<Throwable> errorHandler) {
                this.errHandler = errorHandler;
                return this;
            }

            @Override
            public TokenStream ignoreErrors() {
                return this;
            }

            @Override
            public void start() {
                String currentId = StreamContextHolder.getStreamId();
                streamIdSeenDuringStart.set(currentId);
                registry.register(currentId, activeCall::cancel);
                nextRef.get().accept("包含违禁词的内容");
                if (errHandler != null) {
                    // noop
                }
            }
        };

        GuardrailedTokenStream wrapped = new GuardrailedTokenStream(rawStream, guardrail, "pipe-guard-001", registry);
        SseEmitter emitter = new SseEmitter(5000L);

        SseTokenStreamBridge bridge = SseTokenStreamBridge.pipe(wrapped, emitter);

        assertThat(bridge.getStreamId()).isEqualTo("pipe-guard-001");
        assertThat(bridge.getStreamRegistry()).isSameAs(registry);
        assertThat(streamIdSeenDuringStart.get()).isEqualTo("pipe-guard-001");
        assertThat(StreamContextHolder.getStreamId()).isNull();
        verify(activeCall).cancel();
    }

    @Test
    @DisplayName("ReentrantLock 改造组件在多线程高并发争用下均无死锁且数据一致")
    void testConcurrentContentionAcrossLockUpgradedComponents() throws Exception {
        int threads = 8;
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(threads);
        pool.setMaxPoolSize(threads);
        pool.setQueueCapacity(threads);
        pool.setThreadNamePrefix("lock-contention-test-");
        pool.initialize();
        try {
            // 1. DefaultAiGuardrail 并发动态加词与读写检查
            AiGuardrailProperties guardrailProps = new AiGuardrailProperties();
            guardrailProps.setEnabled(true);
            guardrailProps.setBannedKeywords(List.of("seed-ban"));
            DefaultAiGuardrail guardrail = new DefaultAiGuardrail(guardrailProps);

            // 2. GraphWorkflow / SequentialPipelineWorkflow / DebateConsensusWorkflow 并发编译与读取
            TestGraphWorkflow graphWorkflow = new TestGraphWorkflow();
            graphWorkflow.setCheckpointSaver(new MemoryCheckpointSaver());
            TestSequentialWorkflow pipelineWorkflow = new TestSequentialWorkflow();
            TestDebateWorkflow debateWorkflow = new TestDebateWorkflow();

            // 3. OpenApiToolAdapter 与 HttpMcpClientAdapter 并发加载与重载
            OpenApiToolAdapter openApiAdapter = new OpenApiToolAdapter(
                    "{\"openapi\":\"3.0.1\",\"info\":{\"title\":\"Test\",\"version\":\"1.0\"},\"paths\":{\"/ping\":{\"get\":{\"operationId\":\"ping\",\"summary\":\"ping\"}}}}",
                    new OpenApiToolProperties(),
                    RestClient.create()
            );
            HttpClient mockHttpClient = mock(HttpClient.class);
            @SuppressWarnings("unchecked")
            HttpResponse<String> mockHttpResponse = mock(HttpResponse.class);
            when(mockHttpResponse.statusCode()).thenReturn(200);
            when(mockHttpResponse.body()).thenReturn("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"tools\":[]}}");
            when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(mockHttpResponse);
            HttpMcpClientAdapter mcpAdapter = new HttpMcpClientAdapter(
                    "mcp-concurrent", "http://localhost:8080/mcp", Duration.ofSeconds(5), null, mockHttpClient);

            // 4. AiAgentFactoryBean 并发懒初始化
            AiModelRegistry registry = new AiModelRegistry();
            ChatLanguageModel mockChat = mock(ChatLanguageModel.class);
            registry.register("default", mockChat);
            ApplicationContext appCtx = mock(ApplicationContext.class);
            when(appCtx.getBean(AiModelRegistry.class)).thenReturn(registry);
            when(appCtx.getBeanProvider(any(Class.class))).thenReturn(null);
            AiAgentFactoryBean<SampleAgent> factoryBean = new AiAgentFactoryBean<>();
            factoryBean.setAgentInterface(SampleAgent.class);
            factoryBean.setApplicationContext(appCtx);
            factoryBean.setModel("default");

            // 5. EpisodicMemoryTemplate 本地索引 ReentrantLock 并发读写清理
            dev.langchain4j.model.embedding.EmbeddingModel mockEmbeddingModel =
                    mock(dev.langchain4j.model.embedding.EmbeddingModel.class);
            when(mockEmbeddingModel.embed(any(TextSegment.class)))
                    .thenReturn(Response.from(dev.langchain4j.data.embedding.Embedding.from(new float[]{0.1f, 0.2f})));
            EmbeddingTemplate embeddingTemplate = new EmbeddingTemplate(mockEmbeddingModel);
            InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
            EpisodicMemoryProperties episodicProps = new EpisodicMemoryProperties();
            episodicProps.setEnableLocalCache(true);
            episodicProps.setLocalCacheCapacity(32);
            @SuppressWarnings("unchecked")
            ObjectProvider<dev.langchain4j.store.embedding.EmbeddingStore<TextSegment>> storeProvider = mock(ObjectProvider.class);
            when(storeProvider.getIfAvailable()).thenReturn(store);
            EpisodicMemoryTemplate memoryTemplate = new EpisodicMemoryTemplate(
                    embeddingTemplate, storeProvider, null, null, episodicProps, null);
            memoryTemplate.remember(MemoryFact.builder()
                    .userId("user-concurrent")
                    .subject("user")
                    .predicate("topic")
                    .statement("thread-safe")
                    .build());

            // 6. SseTokenStreamBridge 并发事件推流
            SseTokenStreamBridge bridge = new SseTokenStreamBridge(new SseEmitter(5000L));

            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Boolean>> futures = new ArrayList<>();

            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit((Callable<Boolean>) () -> {
                    ready.countDown();
                    go.await(5, TimeUnit.SECONDS);

                    guardrail.addBannedKeywords(List.of("dyn-word-" + idx));
                    guardrail.inspectPrompt("normal text " + idx);

                    StateSnapshotVo result = graphWorkflow.start("thread-" + idx, new SimpleState(Map.of("v", idx)));
                    assertThat(result).isNotNull();
                    assertThat(pipelineWorkflow.getStages()).hasSize(1);
                    assertThat(debateWorkflow.getDebaters()).hasSize(1);

                    Map<ToolSpecification, ToolExecutor> openApiTools = openApiAdapter.getTools();
                    assertThat(openApiTools).isNotEmpty();
                    if (idx % 2 == 0) {
                        openApiAdapter.reload();
                    }
                    assertThat(mcpAdapter.getTools()).isNotNull();

                    assertThat(factoryBean.getObject()).isNotNull();
                    assertThat(memoryTemplate.listFacts("user-concurrent", 10)).isNotEmpty();

                    bridge.onNext("chunk-" + idx + " ");
                    return true;
                }));
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            for (Future<Boolean> f : futures) {
                assertThat(f.get(15, TimeUnit.SECONDS)).isTrue();
            }
            bridge.onComplete(Response.from(AiMessage.from("done")));
        } finally {
            pool.shutdown();
        }
    }

    public interface SampleAgent {
        String ask(@UserMessage String question);
    }

    static class SimpleState extends BaseGraphState {
        SimpleState(Map<String, Object> initData) {
            super(initData);
        }
    }

    static class TestGraphWorkflow extends GraphWorkflow<SimpleState> {
        TestGraphWorkflow() {
            super("concurrentGraph", SimpleState::new);
        }

        @Override
        protected void initGraph(StateGraph<SimpleState> graph) throws GraphStateException {
            graph.addNode("echo", node_async(state -> Map.of("ok", true)));
            graph.addEdge(START, "echo");
            graph.addEdge("echo", END);
        }
    }

    static class TestSequentialWorkflow extends SequentialPipelineWorkflow<SimpleState> {
        TestSequentialWorkflow() {
            super("concurrentSeq", SimpleState::new);
        }

        @Override
        protected List<PipelineStage<SimpleState>> registerStages() {
            return List.of(PipelineStage.of("only", state -> Map.of("done", true)));
        }
    }

    static class TestDebateWorkflow extends DebateConsensusWorkflow<SimpleState> {
        TestDebateWorkflow() {
            super("concurrentDebate", SimpleState::new);
        }

        @Override
        protected List<DebateParticipant<SimpleState>> registerDebaters() {
            return List.of(DebateParticipant.of("solo", "AFFIRMATIVE", "sole", (s, r) -> Map.of("ok", true)));
        }

        @Override
        protected boolean checkConsensus(SimpleState state) {
            return true;
        }

        @Override
        protected Map<String, Object> adjudicate(SimpleState state) {
            return Map.of("verdict", "APPROVED");
        }
    }
}
