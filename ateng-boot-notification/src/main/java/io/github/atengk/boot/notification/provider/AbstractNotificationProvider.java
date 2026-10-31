package io.github.atengk.boot.notification.provider;

import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.notification.channel.NotificationChannel;
import io.github.atengk.boot.notification.exception.NotificationException;
import io.github.atengk.boot.notification.exception.NotificationResultCode;
import io.github.atengk.boot.notification.exception.NotificationSendException;
import io.github.atengk.boot.notification.model.NotificationMessage;
import io.github.atengk.boot.notification.model.NotificationSendResult;
import io.github.atengk.boot.notification.model.Platform;
import io.github.atengk.boot.notification.util.NotificationDesensitizeUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 协同通知平台驱动抽象基类
 * 统一封装出站网络调用耗时统计、RestClient 渠道专属超时适配、脱敏日志记录与异常转译
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Slf4j
public abstract class AbstractNotificationProvider implements NotificationProvider {

    protected final Platform supportedPlatform;
    private final ConcurrentMap<String, RestClient> customClientCache = new ConcurrentHashMap<>();

    protected AbstractNotificationProvider(Platform supportedPlatform) {
        this.supportedPlatform = supportedPlatform;
    }

    @Override
    public boolean supports(Platform platform) {
        return this.supportedPlatform == platform;
    }

    @Override
    public NotificationSendResult send(NotificationChannel channel, NotificationMessage message, RestClient restClient) {
        if (!supports(channel.getPlatform())) {
            throw new NotificationException(NotificationResultCode.NOTIFICATION_UNSUPPORTED_PLATFORM,
                    "驱动 [" + getClass().getSimpleName() + "] 不支持协同平台: " + channel.getPlatform());
        }

        String messageId = IdUtil.simpleUUID();
        long startTime = System.nanoTime();
        RestClient effectiveClient = resolveRestClient(channel, restClient);

        try {
            String rawResponse = doSend(channel, message, effectiveClient, messageId);
            Duration cost = Duration.ofNanos(System.nanoTime() - startTime);
            return NotificationSendResult.success(messageId, channel.getName(), channel.getPlatform(), cost, rawResponse);
        } catch (NotificationSendException ex) {
            throw ex;
        } catch (NotificationException ex) {
            throw ex;
        } catch (RestClientResponseException ex) {
            String rawResponse = ex.getResponseBodyAsString();
            HttpStatusCode status = ex.getStatusCode();
            NotificationResultCode resultCode = determineResultCodeFromStatus(status);
            String maskedUrl = NotificationDesensitizeUtil.maskWebhook(channel.getWebhook());
            throw new NotificationSendException(channel.getName(), channel.getPlatform().name(), rawResponse, resultCode,
                    "协同平台 HTTP 请求异常 [status=" + status.value() + ", target=" + maskedUrl + "]: " + ex.getMessage(), ex);
        } catch (ResourceAccessException ex) {
            String maskedUrl = NotificationDesensitizeUtil.maskWebhook(channel.getWebhook());
            throw new NotificationSendException(channel.getName(), channel.getPlatform().name(), null,
                    NotificationResultCode.NOTIFICATION_CONNECT_TIMEOUT,
                    "协同平台网络连接或读取超时 [target=" + maskedUrl + "]: " + ex.getMessage(), ex);
        } catch (Throwable ex) {
            String maskedUrl = NotificationDesensitizeUtil.maskWebhook(channel.getWebhook());
            if (isNetworkException(ex)) {
                throw new NotificationSendException(channel.getName(), channel.getPlatform().name(), null,
                        NotificationResultCode.NOTIFICATION_CONNECT_TIMEOUT,
                        "协同平台网络连接或通讯异常 [target=" + maskedUrl + "]: " + ex.getMessage(), ex);
            }
            throw new NotificationSendException(channel.getName(), channel.getPlatform().name(), null,
                    NotificationResultCode.NOTIFICATION_SEND_ERROR,
                    "协同平台推送未知异常 [target=" + maskedUrl + "]: " + ex.getMessage(), ex);
        }
    }

    private boolean isNetworkException(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof java.net.SocketException
                    || current instanceof java.net.SocketTimeoutException
                    || current instanceof java.net.ConnectException
                    || current instanceof java.net.UnknownHostException
                    || current instanceof java.io.EOFException
                    || current instanceof org.springframework.web.client.ResourceAccessException
                    || current instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 子类实现具体平台的网络组装、加签与发送逻辑
     *
     * @param channel    渠道配置
     * @param message    领域消息
     * @param restClient 经超时期调整的 HTTP 客户端
     * @param messageId  全局消息 ID
     * @return 平台原始响应字符串
     * @throws Exception 发信异常
     */
    protected abstract String doSend(NotificationChannel channel, NotificationMessage message,
                                     RestClient restClient, String messageId) throws Exception;

    protected RestClient resolveRestClient(NotificationChannel channel, RestClient defaultClient) {
        if (channel.getConnectTimeout() == null && channel.getReadTimeout() == null) {
            return defaultClient;
        }
        Duration connectTimeout = channel.getConnectTimeout() != null ? channel.getConnectTimeout() : Duration.ofSeconds(3);
        Duration readTimeout = channel.getReadTimeout() != null ? channel.getReadTimeout() : Duration.ofSeconds(5);
        String cacheKey = channel.getName() + ":" + connectTimeout.toMillis() + ":" + readTimeout.toMillis();
        return customClientCache.computeIfAbsent(cacheKey, key -> createCustomRestClient(channel, defaultClient, connectTimeout, readTimeout));
    }

    private RestClient createCustomRestClient(NotificationChannel channel, RestClient defaultClient,
                                              Duration connectTimeout, Duration readTimeout) {
        try {
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(connectTimeout)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
            factory.setReadTimeout(readTimeout);
            return defaultClient.mutate().requestFactory(factory).build();
        } catch (Throwable ex) {
            log.warn("Failed to adapt custom timeout RestClient for channel [{}]: {}", channel.getName(), ex.getMessage());
            return defaultClient;
        }
    }

    protected NotificationResultCode determineResultCodeFromStatus(HttpStatusCode status) {
        int code = status.value();
        if (code == 401 || code == 403) {
            return NotificationResultCode.NOTIFICATION_AUTHENTICATION_ERROR;
        }
        if (code == 429) {
            return NotificationResultCode.NOTIFICATION_RATE_LIMIT_EXCEEDED;
        }
        return NotificationResultCode.NOTIFICATION_SEND_ERROR;
    }
}
