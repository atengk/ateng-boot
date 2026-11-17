package io.github.atengk.boot.notification.provider;

import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.notification.channel.NotificationChannel;
import io.github.atengk.boot.notification.event.NotificationChannelEvictEvent;
import io.github.atengk.boot.notification.exception.NotificationException;
import io.github.atengk.boot.notification.exception.NotificationResultCode;
import io.github.atengk.boot.notification.exception.NotificationSendException;
import io.github.atengk.boot.notification.model.NotificationMessage;
import io.github.atengk.boot.notification.model.NotificationSendResult;
import io.github.atengk.boot.notification.model.Platform;
import io.github.atengk.boot.notification.util.NotificationDesensitizeUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 协同通知平台驱动抽象基类
 * 统一封装出站网络调用耗时统计、RestClient 渠道专属超时适配与驱逐联动、脱敏日志记录与异常转译
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Slf4j
public abstract class AbstractNotificationProvider implements NotificationProvider, ApplicationListener<NotificationChannelEvictEvent> {

    private static final int MAX_CUSTOM_CLIENT_CACHE_SIZE = 256;

    protected final Platform supportedPlatform;
    private final ReentrantLock clientCacheLock = new ReentrantLock();
    private final Map<String, CachedTimeoutClient> customClientCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedTimeoutClient> eldest) {
            return size() > MAX_CUSTOM_CLIENT_CACHE_SIZE;
        }
    };

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
        long connectMillis = connectTimeout.toMillis();
        long readMillis = readTimeout.toMillis();
        String channelKey = channel.getName();

        clientCacheLock.lock();
        try {
            CachedTimeoutClient cached = customClientCache.get(channelKey);
            if (cached != null && cached.connectTimeoutMillis() == connectMillis && cached.readTimeoutMillis() == readMillis) {
                return cached.restClient();
            }
        } finally {
            clientCacheLock.unlock();
        }

        RestClient created = createCustomRestClient(channel, defaultClient, connectTimeout, readTimeout);
        if (created != defaultClient) {
            clientCacheLock.lock();
            try {
                CachedTimeoutClient existing = customClientCache.get(channelKey);
                if (existing != null && existing.connectTimeoutMillis() == connectMillis && existing.readTimeoutMillis() == readMillis) {
                    return existing.restClient();
                }
                customClientCache.put(channelKey, new CachedTimeoutClient(connectMillis, readMillis, created));
            } finally {
                clientCacheLock.unlock();
            }
        }
        return created;
    }

    @Override
    public void onApplicationEvent(NotificationChannelEvictEvent event) {
        if (event != null) {
            evictChannelClient(event.getChannelName());
        }
    }

    /**
     * 驱逐指定渠道的定制超时 RestClient 缓存条目
     *
     * @param channelName 目标渠道名称
     */
    public void evictChannelClient(String channelName) {
        if (channelName == null || channelName.isBlank()) {
            return;
        }
        clientCacheLock.lock();
        try {
            customClientCache.remove(channelName.trim());
        } finally {
            clientCacheLock.unlock();
        }
    }

    /**
     * 清空当前驱动的全部定制超时 RestClient 缓存
     */
    public void clearCustomClientCache() {
        clientCacheLock.lock();
        try {
            customClientCache.clear();
        } finally {
            clientCacheLock.unlock();
        }
    }

    /**
     * 获取当前驱动已缓存的定制超时 RestClient 数量
     *
     * @return 缓存数量
     */
    public int getCustomClientCacheSize() {
        clientCacheLock.lock();
        try {
            return customClientCache.size();
        } finally {
            clientCacheLock.unlock();
        }
    }

    private record CachedTimeoutClient(long connectTimeoutMillis, long readTimeoutMillis, RestClient restClient) {
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
