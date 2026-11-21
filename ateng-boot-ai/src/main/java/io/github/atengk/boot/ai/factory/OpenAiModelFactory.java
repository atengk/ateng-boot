package io.github.atengk.boot.ai.factory;

import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.properties.AiModelConfig;
import io.github.atengk.boot.ai.sanitizer.NativeJsonSchemaInterceptor;
import io.github.atengk.boot.ai.streaming.ActiveStreamRegistry;
import io.github.atengk.boot.ai.streaming.StreamCancellationInterceptor;
import io.github.atengk.boot.ai.streaming.StreamEventListener;
import io.github.atengk.boot.ai.streaming.StreamPropagatingExecutorService;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * OpenAI 兼容模型实例构建工厂
 *
 * @author Ateng
 * @since 2026-11-03
 */
public final class OpenAiModelFactory {

    private static final Logger log = LoggerFactory.getLogger(OpenAiModelFactory.class);

    private OpenAiModelFactory() {
    }

    /**
     * 构建同步阻塞型 OpenAiChatModel 实例 (无监听器)
     *
     * @param config 模型配置属性
     * @return OpenAiChatModel 实例
     */
    public static OpenAiChatModel createChatModel(AiModelConfig config) {
        return createChatModel(config, null);
    }

    /**
     * 构建同步阻塞型 OpenAiChatModel 实例
     *
     * @param config    模型配置属性
     * @param listeners 链路监听器列表
     * @return OpenAiChatModel 实例
     */
    public static OpenAiChatModel createChatModel(AiModelConfig config, List<ChatModelListener> listeners) {
        validateConfig(config);

        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(config.getTemperature() != null ? config.getTemperature() : 0.7)
                .timeout(config.getTimeout() != null ? config.getTimeout() : Duration.ofSeconds(60))
                .maxRetries(0) // 禁用底层 SDK 无抖动的裸重试，统一交由上层 AiTemplate 与 RetryUtil 实施带 Jitter 的指数退避治理，防御乘积级重试放大
                .logRequests(Boolean.TRUE.equals(config.getLogRequests()))
                .logResponses(Boolean.TRUE.equals(config.getLogResponses()));

        if (config.getTopP() != null) {
            builder.topP(config.getTopP());
        }
        if (config.getMaxTokens() != null) {
            builder.maxTokens(config.getMaxTokens());
        }
        if (config.getCustomHeaders() != null && !config.getCustomHeaders().isEmpty()) {
            builder.customHeaders(config.getCustomHeaders());
        }
        if (config.getResponseFormat() == io.github.atengk.boot.ai.properties.AiResponseFormat.JSON_OBJECT
                || config.getResponseFormat() == io.github.atengk.boot.ai.properties.AiResponseFormat.JSON_SCHEMA) {
            // OpenAI 规范中 json_schema 必须随每次请求附带具体 schema 结构体，无参裸设置 "json_schema" 会触发 HTTP 400；
            // 故底层统一注入 "json_object" 开启严格 JSON 输出模式，具体 JSON Schema 约束由 AiTemplate 在请求级动态注入 Prompt 并在反序列化时执行 JSR-303 双防校验
            builder.responseFormat("json_object");
        }
        if (listeners != null && !listeners.isEmpty()) {
            builder.listeners(listeners);
        }

        OpenAiChatModel model = builder.build();
        if (config.isStrictNativeJsonSchema()) {
            attachNativeJsonSchemaInterceptor(model, model.modelName());
        }
        return model;
    }

    /**
     * 构建 OpenAiEmbeddingModel 向量化模型实例
     *
     * @param config 模型配置属性
     * @return OpenAiEmbeddingModel 实例
     */
    public static OpenAiEmbeddingModel createEmbeddingModel(AiModelConfig config) {
        return createEmbeddingModel(config, null);
    }

    /**
     * 构建 OpenAiEmbeddingModel 向量化模型实例
     *
     * @param config     模型配置属性
     * @param dimensions 向量维度 (可选)
     * @return OpenAiEmbeddingModel 实例
     */
    public static OpenAiEmbeddingModel createEmbeddingModel(AiModelConfig config, Integer dimensions) {
        validateConfig(config);

        OpenAiEmbeddingModel.OpenAiEmbeddingModelBuilder builder = OpenAiEmbeddingModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .timeout(config.getTimeout() != null ? config.getTimeout() : Duration.ofSeconds(60))
                .maxRetries(0) // 禁用底层 SDK 无抖动的裸重试，统一交由上层 EmbeddingTemplate 与 RetryUtil 实施带 Jitter 的指数退避治理，防御乘积级重试放大
                .logRequests(Boolean.TRUE.equals(config.getLogRequests()))
                .logResponses(Boolean.TRUE.equals(config.getLogResponses()));

        if (dimensions != null && dimensions > 0) {
            builder.dimensions(dimensions);
        }
        if (config.getCustomHeaders() != null && !config.getCustomHeaders().isEmpty()) {
            builder.customHeaders(config.getCustomHeaders());
        }

        return builder.build();
    }

    /**
     * 构建异步流式 OpenAiStreamingChatModel 实例
     *
     * @param config    模型配置属性
     * @param listeners 链路监听器列表
     * @return OpenAiStreamingChatModel 实例
     */
    public static OpenAiStreamingChatModel createStreamingChatModel(AiModelConfig config, List<ChatModelListener> listeners) {
        return createStreamingChatModel(config, listeners, null);
    }

    /**
     * 构建带有 OkHttp 连接物理取消治理能力的异步流式 OpenAiStreamingChatModel 实例
     *
     * @param config                模型配置属性
     * @param listeners             链路监听器列表
     * @param activeStreamRegistry  活跃流式连接注册表 (可选)
     * @return OpenAiStreamingChatModel 实例
     */
    public static OpenAiStreamingChatModel createStreamingChatModel(
            AiModelConfig config,
            List<ChatModelListener> listeners,
            ActiveStreamRegistry activeStreamRegistry) {
        validateConfig(config);

        OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder = OpenAiStreamingChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(config.getTemperature() != null ? config.getTemperature() : 0.7)
                .timeout(config.getTimeout() != null ? config.getTimeout() : Duration.ofSeconds(60))
                .logRequests(Boolean.TRUE.equals(config.getLogRequests()))
                .logResponses(Boolean.TRUE.equals(config.getLogResponses()));

        if (config.getTopP() != null) {
            builder.topP(config.getTopP());
        }
        if (config.getMaxTokens() != null) {
            builder.maxTokens(config.getMaxTokens());
        }
        if (config.getCustomHeaders() != null && !config.getCustomHeaders().isEmpty()) {
            builder.customHeaders(config.getCustomHeaders());
        }
        if (config.getResponseFormat() == io.github.atengk.boot.ai.properties.AiResponseFormat.JSON_OBJECT
                || config.getResponseFormat() == io.github.atengk.boot.ai.properties.AiResponseFormat.JSON_SCHEMA) {
            // OpenAI 规范中 json_schema 必须随每次请求附带具体 schema 结构体，无参裸设置 "json_schema" 会触发 HTTP 400；
            // 故底层统一注入 "json_object" 开启严格 JSON 输出模式，具体 JSON Schema 约束由 AiTemplate 在请求级动态注入 Prompt 并在反序列化时执行 JSR-303 双防校验
            builder.responseFormat("json_object");
        }
        if (listeners != null && !listeners.isEmpty()) {
            builder.listeners(listeners);
        }

        OpenAiStreamingChatModel model = builder.build();
        if (activeStreamRegistry != null) {
            attachStreamCancellation(model, activeStreamRegistry);
        }
        if (config.isStrictNativeJsonSchema()) {
            attachNativeJsonSchemaInterceptor(model, model.modelName());
        }

        return model;
    }

    private static void attachNativeJsonSchemaInterceptor(Object model, String modelName) {
        enhanceOkHttpClient(model, modelName, "原生 JSON Schema 请求注入拦截器", okHttpClient -> {
            if (okHttpClient.interceptors().stream().anyMatch(NativeJsonSchemaInterceptor.class::isInstance)) {
                return null;
            }
            return okHttpClient.newBuilder()
                    .addInterceptor(new NativeJsonSchemaInterceptor())
                    .build();
        });
    }

    private static void attachStreamCancellation(OpenAiStreamingChatModel model, ActiveStreamRegistry registry) {
        enhanceOkHttpClient(model, model.modelName(), "OkHttp 物理连接安全取消治理设施", okHttpClient -> {
            // 1. 构建透传流式上下文与全链路快照的 OkHttp Dispatcher
            Dispatcher originalDispatcher = okHttpClient.dispatcher();
            StreamPropagatingExecutorService propagatingExecutor = new StreamPropagatingExecutorService(originalDispatcher.executorService());
            Dispatcher newDispatcher = new Dispatcher(propagatingExecutor);
            newDispatcher.setMaxRequests(originalDispatcher.getMaxRequests());
            newDispatcher.setMaxRequestsPerHost(originalDispatcher.getMaxRequestsPerHost());

            // 2. 组装取消拦截器与就绪事件监听器
            StreamCancellationInterceptor interceptor = new StreamCancellationInterceptor(registry);
            StreamEventListener eventListener = new StreamEventListener(registry);

            return okHttpClient.newBuilder()
                    .dispatcher(newDispatcher)
                    .eventListener(eventListener)
                    .addInterceptor(interceptor)
                    .build();
        });
    }

    private static void enhanceOkHttpClient(Object model, String modelName, String featureDesc,
                                            UnaryOperator<OkHttpClient> customizer) {
        try {
            Field clientField = model.getClass().getDeclaredField("client");
            clientField.setAccessible(true);
            Object client = clientField.get(model);
            if (client == null) {
                return;
            }

            Field okHttpClientField = client.getClass().getDeclaredField("okHttpClient");
            okHttpClientField.setAccessible(true);
            OkHttpClient okHttpClient = (OkHttpClient) okHttpClientField.get(client);
            if (okHttpClient == null) {
                return;
            }

            OkHttpClient enhancedClient = customizer.apply(okHttpClient);
            if (enhancedClient != null) {
                okHttpClientField.set(client, enhancedClient);
                syncRetrofitCallFactory(client, enhancedClient);
                log.info("已为大模型 [{}] 成功挂载 {}", modelName, featureDesc);
            }
        } catch (Throwable t) {
            log.warn("为大模型 [{}] 挂载 {} 失败: {}", modelName, featureDesc, t.getMessage());
        }
    }

    /**
     * 同步刷新 DefaultOpenAiClient 内部 Retrofit 代理持有的 Call.Factory，
     * 确保同步 chatCompletion 请求同样经由增强后的 OkHttpClient 拦截器链发出
     */
    private static void syncRetrofitCallFactory(Object client, OkHttpClient enhancedClient) {
        try {
            Field openAiApiField = client.getClass().getDeclaredField("openAiApi");
            openAiApiField.setAccessible(true);
            Object openAiApi = openAiApiField.get(client);
            if (openAiApi == null || !Proxy.isProxyClass(openAiApi.getClass())) {
                return;
            }
            InvocationHandler handler = Proxy.getInvocationHandler(openAiApi);
            for (Field field : handler.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                Object candidate = field.get(handler);
                if (candidate instanceof retrofit2.Retrofit retrofit) {
                    Field callFactoryField = retrofit2.Retrofit.class.getDeclaredField("callFactory");
                    callFactoryField.setAccessible(true);
                    callFactoryField.set(retrofit, enhancedClient);
                    Field cacheField = retrofit2.Retrofit.class.getDeclaredField("serviceMethodCache");
                    cacheField.setAccessible(true);
                    Object cache = cacheField.get(retrofit);
                    if (cache instanceof Map<?, ?> serviceMethodCache) {
                        serviceMethodCache.clear();
                    }
                    break;
                }
            }
        } catch (Throwable t) {
            log.debug("同步刷新 Retrofit Call.Factory 跳过: {}", t.getMessage());
        }
    }

    private static void validateConfig(AiModelConfig config) {
        if (config == null) {
            throw new AiException(AiResultCode.AI_CONFIG_ERROR, "大模型配置对象不能为空");
        }
        if (!StringUtils.hasText(config.getApiKey())) {
            throw new AiException(AiResultCode.AI_CONFIG_ERROR, "大模型 API Key 不能为空");
        }
        if (!StringUtils.hasText(config.getBaseUrl())) {
            throw new AiException(AiResultCode.AI_CONFIG_ERROR, "大模型 Base URL 不能为空");
        }
        if (!StringUtils.hasText(config.getModelName())) {
            throw new AiException(AiResultCode.AI_CONFIG_ERROR, "大模型 Model Name 不能为空");
        }
    }
}
