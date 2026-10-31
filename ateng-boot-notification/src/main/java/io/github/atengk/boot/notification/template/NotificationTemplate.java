package io.github.atengk.boot.notification.template;

import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.util.BusinessAssert;
import io.github.atengk.boot.core.util.TransactionUtil;
import io.github.atengk.boot.notification.channel.NotificationChannel;
import io.github.atengk.boot.notification.channel.NotificationChannelRegistry;
import io.github.atengk.boot.notification.context.NotificationContextHolder;
import io.github.atengk.boot.notification.event.NotificationSendFailedEvent;
import io.github.atengk.boot.notification.event.NotificationSendingEvent;
import io.github.atengk.boot.notification.event.NotificationSentEvent;
import io.github.atengk.boot.notification.exception.NotificationException;
import io.github.atengk.boot.notification.exception.NotificationResultCode;
import io.github.atengk.boot.notification.exception.NotificationSendException;
import io.github.atengk.boot.notification.handler.LoggingNotificationSendFailureHandler;
import io.github.atengk.boot.notification.handler.NotificationSendFailureHandler;
import io.github.atengk.boot.notification.limiter.NotificationRateLimiter;
import io.github.atengk.boot.notification.model.CardMessage;
import io.github.atengk.boot.notification.model.LinkMessage;
import io.github.atengk.boot.notification.model.MarkdownMessage;
import io.github.atengk.boot.notification.model.NotificationMessage;
import io.github.atengk.boot.notification.model.NotificationSendResult;
import io.github.atengk.boot.notification.model.Platform;
import io.github.atengk.boot.notification.model.TextMessage;
import io.github.atengk.boot.notification.properties.NotificationProperties;
import io.github.atengk.boot.notification.provider.NotificationProvider;
import io.github.atengk.boot.notification.renderer.NotificationTemplateRenderer;
import io.github.atengk.boot.notification.renderer.SimpleNotificationTemplateRenderer;
import io.github.atengk.boot.notification.util.NotificationDesensitizeUtil;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;

/**
 * 协同通知操作核心门面具体类
 * 提供同步发信、非阻塞受管异步发信（四维全链路上下文快照透传）、本地事务感知发信（sendAfterCommit）与动态模板化渲染推送
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Slf4j
public class NotificationTemplate implements NotificationOperations, ApplicationEventPublisherAware {

    @Getter
    private final NotificationProperties properties;

    @Getter
    private final NotificationChannelRegistry channelRegistry;

    private final Map<Platform, NotificationProvider> providerMap = new EnumMap<>(Platform.class);

    @Getter
    private final NotificationRateLimiter rateLimiter;

    @Getter
    private final RestClient restClient;

    @Getter
    private final NotificationTemplateRenderer templateRenderer;

    @Getter
    private final NotificationSendFailureHandler failureHandler;

    private ApplicationEventPublisher applicationEventPublisher;

    @Setter
    private Executor asyncExecutor;

    public NotificationTemplate(NotificationProperties properties,
                                NotificationChannelRegistry channelRegistry,
                                List<NotificationProvider> providers,
                                NotificationRateLimiter rateLimiter,
                                RestClient restClient) {
        this(properties, channelRegistry, providers, rateLimiter, restClient, null, null, null, null);
    }

    public NotificationTemplate(NotificationProperties properties,
                                NotificationChannelRegistry channelRegistry,
                                List<NotificationProvider> providers,
                                NotificationRateLimiter rateLimiter,
                                RestClient restClient,
                                NotificationTemplateRenderer templateRenderer,
                                NotificationSendFailureHandler failureHandler,
                                ApplicationEventPublisher applicationEventPublisher,
                                Executor asyncExecutor) {
        this.properties = properties != null ? properties : new NotificationProperties();
        this.channelRegistry = channelRegistry;
        if (providers != null) {
            for (NotificationProvider provider : providers) {
                for (Platform platform : Platform.values()) {
                    if (provider.supports(platform)) {
                        this.providerMap.put(platform, provider);
                    }
                }
            }
        }
        this.rateLimiter = rateLimiter;
        this.restClient = restClient;
        this.templateRenderer = (templateRenderer != null) ? templateRenderer : new SimpleNotificationTemplateRenderer();
        this.failureHandler = (failureHandler != null) ? failureHandler : new LoggingNotificationSendFailureHandler();
        this.applicationEventPublisher = applicationEventPublisher;
        this.asyncExecutor = asyncExecutor;
    }

    @Override
    public NotificationSendResult send(NotificationMessage message) {
        return send(null, message);
    }

    @Override
    public NotificationSendResult send(String channelName, NotificationMessage message) {
        BusinessAssert.notNull(message, "通知消息不能为空");
        long startTime = System.nanoTime();

        String resolvedChannelName = resolveChannelName(channelName);
        NotificationChannel channel = channelRegistry.get(resolvedChannelName);
        BusinessAssert.notNull(channel, "未找到目标通知渠道: " + resolvedChannelName);

        NotificationProvider provider = getProvider(channel.getPlatform());
        BusinessAssert.notNull(provider, "未找到支持平台 [" + channel.getPlatform() + "] 的通知驱动");

        publishEvent(new NotificationSendingEvent(this, channel, message));

        try {
            // 客户端主动滑动窗口频控与微退避排队（超时将直接抛出 NotificationRateLimitException）
            if (rateLimiter != null) {
                rateLimiter.acquire(channel);
            }

            // 执行驱动底层物理网络推送
            NotificationSendResult result = provider.send(channel, message, restClient);

            // 发布发送成功事件
            publishEvent(new NotificationSentEvent(this, channel, message, result));

            return result;
        } catch (Throwable ex) {
            Duration cost = Duration.ofNanos(System.nanoTime() - startTime);
            publishEvent(new NotificationSendFailedEvent(this, channel, message, ex, cost));

            if (ex instanceof NotificationException notificationException) {
                throw notificationException;
            }
            String maskedUrl = NotificationDesensitizeUtil.maskWebhook(channel.getWebhook());
            throw new NotificationSendException(channel.getName(), channel.getPlatform().name(), null,
                    NotificationResultCode.NOTIFICATION_SEND_ERROR,
                    "协同平台推送异常 [target=" + maskedUrl + "]: " + ex.getMessage(), ex);
        }
    }

    @Override
    public CompletableFuture<NotificationSendResult> sendAsync(NotificationMessage message) {
        return sendAsync(null, message);
    }

    @Override
    public CompletableFuture<NotificationSendResult> sendAsync(String channelName, NotificationMessage message) {
        BusinessAssert.notNull(message, "通知消息不能为空");
        String resolvedChannelName = resolveChannelName(channelName);
        NotificationChannel channel = channelRegistry.get(resolvedChannelName);
        BusinessAssert.notNull(channel, "未找到目标通知渠道: " + resolvedChannelName);

        Executor executor = obtainAsyncExecutor();
        Supplier<NotificationSendResult> asyncTask = AsyncContext.wrapSupplier(() -> {
            try {
                return send(resolvedChannelName, message);
            } catch (Throwable ex) {
                triggerFailureHandler(channel, message, ex);
                if (ex instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new NotificationException(NotificationResultCode.NOTIFICATION_SEND_ERROR, ex.getMessage(), ex);
            }
        });

        return CompletableFuture.supplyAsync(asyncTask, executor);
    }

    @Override
    public void sendAfterCommit(NotificationMessage message) {
        sendAfterCommit(null, message);
    }

    @Override
    public void sendAfterCommit(String channelName, NotificationMessage message) {
        BusinessAssert.notNull(message, "通知消息不能为空");
        String resolvedChannelName = resolveChannelName(channelName);
        NotificationChannel channel = channelRegistry.get(resolvedChannelName);
        BusinessAssert.notNull(channel, "未找到目标通知渠道: " + resolvedChannelName);

        Runnable commitTask = AsyncContext.wrap(() -> {
            try {
                send(resolvedChannelName, message);
            } catch (Throwable ex) {
                triggerFailureHandler(channel, message, ex);
            }
        });

        if (TransactionUtil.isSynchronizationActive()) {
            TransactionUtil.executeAfterCommit(commitTask);
        } else {
            log.debug("当前处于非事务上下文或未激活事务同步器，sendAfterCommit 就地同步执行 [channel={}]", resolvedChannelName);
            commitTask.run();
        }
    }

    @Override
    public NotificationSendResult sendTemplate(String templateContent, Map<String, Object> variables) {
        return sendTemplate(null, templateContent, variables);
    }

    @Override
    public NotificationSendResult sendTemplate(String channelName, String templateContent, Map<String, Object> variables) {
        BusinessAssert.notNull(templateContent, "模板正文不能为空");
        String rendered = templateRenderer.render(templateContent, variables);
        return send(channelName, TextMessage.of(rendered));
    }

    @Override
    public NotificationSendResult sendTemplate(String channelName, NotificationMessage messageTemplate, Map<String, Object> variables) {
        BusinessAssert.notNull(messageTemplate, "消息模板对象不能为空");
        NotificationMessage renderedMessage = renderMessage(messageTemplate, variables);
        return send(channelName, renderedMessage);
    }

    /**
     * 解析当前生效的目标渠道名称
     * 按照优先级：显式入参 -> 线程级动态切源上下文 -> 渠道动态解析器 SPI -> 全局默认渠道
     *
     * @param channelName 显式指定的渠道标识（可为 null）
     * @return 解析后的渠道标识名称
     */
    public String resolveChannelName(String channelName) {
        if (channelName != null && !channelName.isBlank()) {
            return channelName.trim();
        }
        String contextChannel = NotificationContextHolder.getChannel();
        if (contextChannel != null && !contextChannel.isBlank()) {
            return contextChannel.trim();
        }
        String resolved = channelRegistry.resolveDynamicChannel();
        if (resolved != null && !resolved.isBlank()) {
            return resolved.trim();
        }
        return channelRegistry.getDefaultChannelName();
    }

    /**
     * 根据协同平台枚举获取对应驱动实现
     *
     * @param platform 平台枚举
     * @return 对应的驱动引擎实例
     */
    public NotificationProvider getProvider(Platform platform) {
        NotificationProvider provider = providerMap.get(platform);
        if (provider != null) {
            return provider;
        }
        throw new NotificationException(NotificationResultCode.NOTIFICATION_UNSUPPORTED_PLATFORM,
                "未找到支持平台 [" + platform + "] 的协同通知驱动 NotificationProvider");
    }

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    private void publishEvent(ApplicationEvent event) {
        if (applicationEventPublisher != null) {
            try {
                applicationEventPublisher.publishEvent(event);
            } catch (Throwable ex) {
                log.warn("发布协同通知生命周期事件异常 [event={}]: {}", event.getClass().getSimpleName(), ex.getMessage());
            }
        }
    }

    private void triggerFailureHandler(NotificationChannel channel, NotificationMessage message, Throwable ex) {
        if (failureHandler != null) {
            try {
                failureHandler.onFailure(channel, message, ex);
            } catch (Throwable handlerEx) {
                log.error("执行 NotificationSendFailureHandler 补偿逻辑异常: {}", handlerEx.getMessage(), handlerEx);
            }
        }
    }

    private Executor obtainAsyncExecutor() {
        if (asyncExecutor != null) {
            return asyncExecutor;
        }
        return ForkJoinPool.commonPool();
    }

    private NotificationMessage renderMessage(NotificationMessage template, Map<String, Object> variables) {
        if (template instanceof TextMessage text) {
            String rendered = templateRenderer.render(text.getContent(), variables);
            TextMessage result = new TextMessage(rendered);
            result.setMention(text.getMention());
            return result;
        }
        if (template instanceof MarkdownMessage md) {
            String renderedTitle = templateRenderer.render(md.getTitle(), variables);
            String renderedContent = templateRenderer.render(md.getContent(), variables);
            MarkdownMessage result = new MarkdownMessage(renderedTitle, renderedContent);
            result.setMention(md.getMention());
            return result;
        }
        if (template instanceof CardMessage card) {
            String renderedTitle = templateRenderer.render(card.getTitle(), variables);
            String renderedContent = templateRenderer.render(card.getContent(), variables);
            CardMessage result = new CardMessage(renderedTitle, renderedContent);
            result.setHeaderColor(card.getHeaderColor());
            result.setBtnOrientation(card.getBtnOrientation());
            result.setMainImageUrl(card.getMainImageUrl());
            result.setButtons(card.getButtons());
            result.setSingleActionText(card.getSingleActionText());
            result.setSingleActionUrl(card.getSingleActionUrl());
            result.setMention(card.getMention());
            return result;
        }
        if (template instanceof LinkMessage link) {
            String renderedTitle = templateRenderer.render(link.getTitle(), variables);
            String renderedText = templateRenderer.render(link.getText(), variables);
            String renderedPicUrl = templateRenderer.render(link.getPicUrl(), variables);
            String renderedMessageUrl = templateRenderer.render(link.getMessageUrl(), variables);
            LinkMessage result = new LinkMessage(renderedTitle, renderedText, renderedMessageUrl, renderedPicUrl);
            result.setMention(link.getMention());
            return result;
        }
        return template;
    }
}
