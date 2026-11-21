package io.github.atengk.boot.ai.sanitizer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.atengk.boot.core.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 原生 JSON Schema OkHttp 请求拦截器。
 * <p>
 * 当 {@link NativeJsonSchemaContext#get()} 存在绑定规格时，拦截发往大模型的 JSON POST 请求体，
 * 将 {@code response_format} 注入为 OpenAI Structured Outputs 原生格式：
 * {@code {"type":"json_schema","json_schema":{"name":...,"strict":true,"schema":{...}}}}。
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Slf4j
public class NativeJsonSchemaInterceptor implements Interceptor {

    private static final MediaType APPLICATION_JSON = MediaType.get("application/json; charset=utf-8");

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        NativeJsonSchemaContext.SchemaSpec spec = NativeJsonSchemaContext.get();
        if (spec == null || !"POST".equalsIgnoreCase(request.method())) {
            return chain.proceed(request);
        }

        RequestBody body = request.body();
        if (body == null) {
            return chain.proceed(request);
        }

        MediaType contentType = body.contentType();
        if (contentType != null && !contentType.subtype().toLowerCase(Locale.ROOT).contains("json")) {
            return chain.proceed(request);
        }

        String rawPayload = readBodyAsUtf8(body);
        if (rawPayload.isBlank()) {
            return chain.proceed(request);
        }

        Request rewrittenRequest = rewriteRequest(request, contentType, rawPayload, spec);
        return chain.proceed(rewrittenRequest);
    }

    private Request rewriteRequest(Request originalRequest, MediaType contentType, String rawPayload,
                                   NativeJsonSchemaContext.SchemaSpec spec) {
        try {
            JsonNode root = JsonUtil.readTree(rawPayload);
            if (!(root instanceof ObjectNode rootObject)) {
                return originalRequest;
            }
            JsonNode schemaTree = JsonUtil.readTree(spec.schemaJson());
            ObjectNode jsonSchemaWrapper = rootObject.objectNode();
            jsonSchemaWrapper.put("name", spec.name());
            jsonSchemaWrapper.put("strict", true);
            jsonSchemaWrapper.set("schema", schemaTree);

            ObjectNode responseFormatNode = rootObject.objectNode();
            responseFormatNode.put("type", "json_schema");
            responseFormatNode.set("json_schema", jsonSchemaWrapper);
            rootObject.set("response_format", responseFormatNode);

            String rewrittenJson = rootObject.toString();
            MediaType targetMediaType = contentType != null ? contentType : APPLICATION_JSON;
            RequestBody rewrittenBody = RequestBody.create(rewrittenJson, targetMediaType);
            return originalRequest.newBuilder()
                    .method(originalRequest.method(), rewrittenBody)
                    .build();
        } catch (Exception e) {
            log.warn("[AI-JSON-SCHEMA] 注入原生 json_schema 请求体失败，回退透传原请求: {}", e.getMessage(), e);
            return originalRequest;
        }
    }

    private String readBodyAsUtf8(RequestBody body) throws IOException {
        Buffer buffer = new Buffer();
        body.writeTo(buffer);
        return buffer.readString(StandardCharsets.UTF_8);
    }
}
