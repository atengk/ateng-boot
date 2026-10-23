package io.github.atengk.boot.pay.core.certificate;

import io.github.atengk.boot.pay.enums.PayResultCode;
import io.github.atengk.boot.pay.exception.PaySignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Comparator;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 微信支付平台证书自动定时轮换与生命周期管理器
 * <p>
 * 核心特性：
 * <ol>
 *     <li><b>受管后台定时轮换</b>：基于受管后台守护线程池，在应用免重启运行态定时拉取最新平台证书并平滑替换；</li>
 *     <li><b>零中断弹性保底</b>：当定时刷新发生瞬态网络抖动或失败时，保留内存中现有的有效证书，坚决杜绝线上支付中断；</li>
 *     <li><b>多商户内存安全隔离</b>：证书按商户号（merchantId）在并发容器中物理隔离存储；</li>
 *     <li><b>100% 离线测试支持</b>：支持直接注入模拟平台证书与下载任务桩，不依赖微信真实网络环境。</li>
 * </ol>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
public class WechatAutoCertificateManager implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(WechatAutoCertificateManager.class);
    private static final Duration DEFAULT_REFRESH_INTERVAL = Duration.ofHours(1);

    /**
     * 商户证书缓存表：merchantId -> (serialNumber -> X509Certificate)
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, X509Certificate>> certificateCache = new ConcurrentHashMap<>();

    /**
     * 商户证书刷新任务表：merchantId -> downloadRunnable
     */
    private final ConcurrentHashMap<String, Runnable> downloadTaskMap = new ConcurrentHashMap<>();

    /**
     * 后台受管调度线程池
     */
    private final ScheduledThreadPoolExecutor scheduledExecutor;
    private ScheduledFuture<?> scheduledFuture;

    public WechatAutoCertificateManager() {
        this(DEFAULT_REFRESH_INTERVAL);
    }

    public WechatAutoCertificateManager(Duration refreshInterval) {
        AtomicInteger threadCount = new AtomicInteger(0);
        this.scheduledExecutor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread thread = new Thread(r, "wechat-cert-rotation-" + threadCount.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        this.scheduledExecutor.setRemoveOnCancelPolicy(true);

        long intervalMinutes = Math.max(1, refreshInterval.toMinutes());
        this.scheduledFuture = this.scheduledExecutor.scheduleWithFixedDelay(
                this::refreshAllSafely,
                intervalMinutes,
                intervalMinutes,
                TimeUnit.MINUTES
        );
        log.info("Initialized WechatAutoCertificateManager with rotation interval: {} minutes", intervalMinutes);
    }

    /**
     * 注册或更新指定商户的平台证书集合
     *
     * @param merchantId   微信商户号
     * @param certificates 证书序列号与证书映射
     */
    public void registerCertificates(String merchantId, Map<String, X509Certificate> certificates) {
        if (merchantId == null || merchantId.isBlank() || certificates == null || certificates.isEmpty()) {
            return;
        }
        ConcurrentHashMap<String, X509Certificate> targetMap =
                certificateCache.computeIfAbsent(merchantId, k -> new ConcurrentHashMap<>());
        targetMap.putAll(certificates);
        log.info("Registered/Updated {} WeChat platform certificates for merchant [{}]", certificates.size(), merchantId);
    }

    /**
     * 注册商户定时下载任务并立即同步执行一次初始拉取
     *
     * @param merchantId   微信商户号
     * @param downloadTask 证书拉取任务闭包
     */
    public void registerMerchant(String merchantId, Runnable downloadTask) {
        if (merchantId == null || merchantId.isBlank() || downloadTask == null) {
            return;
        }
        downloadTaskMap.put(merchantId, downloadTask);
        try {
            downloadTask.run();
            log.info("Successfully executed initial certificate fetch for merchant [{}]", merchantId);
        } catch (Exception e) {
            log.warn("Initial certificate fetch for merchant [{}] failed, will retry in background schedule: {}",
                    merchantId, e.getMessage());
        }
    }

    /**
     * 注销商户的证书更新任务与缓存
     *
     * @param merchantId 微信商户号
     */
    public void unregisterMerchant(String merchantId) {
        if (merchantId == null) {
            return;
        }
        downloadTaskMap.remove(merchantId);
        certificateCache.remove(merchantId);
        log.info("Unregistered merchant [{}] from WechatAutoCertificateManager", merchantId);
    }

    /**
     * 根据证书序列号获取指定商户的平台证书
     *
     * @param merchantId   微信商户号
     * @param serialNumber 证书序列号（不区分大小写）
     * @return X.509 平台证书实例
     */
    public X509Certificate getCertificate(String merchantId, String serialNumber) {
        if (merchantId == null || serialNumber == null) {
            throw new PaySignException(PayResultCode.PAY_SIGN_VERIFY_ERROR, "商户号与证书序列号不能为空");
        }
        ConcurrentHashMap<String, X509Certificate> map = certificateCache.get(merchantId);
        if (map != null) {
            for (Map.Entry<String, X509Certificate> entry : map.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(serialNumber)) {
                    return entry.getValue();
                }
            }
        }

        // 若当前缓存未命中，尝试执行一次同步刷新后再探测
        Runnable task = downloadTaskMap.get(merchantId);
        if (task != null) {
            try {
                task.run();
                ConcurrentHashMap<String, X509Certificate> updated = certificateCache.get(merchantId);
                if (updated != null) {
                    for (Map.Entry<String, X509Certificate> entry : updated.entrySet()) {
                        if (entry.getKey().equalsIgnoreCase(serialNumber)) {
                            return entry.getValue();
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("On-demand certificate refresh for merchant [{}] failed: {}", merchantId, e.getMessage());
            }
        }

        throw new PaySignException(PayResultCode.PAY_SIGN_VERIFY_ERROR,
                "未找到对应的微信支付平台证书: merchantId=" + merchantId + ", serialNumber=" + serialNumber);
    }

    /**
     * 获取指定商户当前最新可用的微信支付平台证书（优先挑选有效期最长且合法的证书）
     *
     * @param merchantId 微信商户号
     * @return 可用 X.509 平台证书
     */
    public X509Certificate getAvailableCertificate(String merchantId) {
        if (merchantId == null) {
            throw new PaySignException(PayResultCode.PAY_SIGN_VERIFY_ERROR, "商户号不能为空");
        }
        ConcurrentHashMap<String, X509Certificate> activeMap = certificateCache.get(merchantId);
        if (activeMap == null || activeMap.isEmpty()) {
            refreshCertificate(merchantId);
            activeMap = certificateCache.get(merchantId);
        }
        if (activeMap == null || activeMap.isEmpty()) {
            throw new PaySignException(PayResultCode.PAY_SIGN_VERIFY_ERROR,
                    "微信商户 [" + merchantId + "] 未下载或未注册任何可用平台证书");
        }

        Date now = new Date();
        final ConcurrentHashMap<String, X509Certificate> targetMap = activeMap;
        return targetMap.values().stream()
                .filter(cert -> {
                    try {
                        cert.checkValidity(now);
                        return true;
                    } catch (CertificateExpiredException | CertificateNotYetValidException e) {
                        return false;
                    }
                })
                .max(Comparator.comparing(X509Certificate::getNotAfter))
                .orElseGet(() -> targetMap.values().iterator().next());
    }

    /**
     * 手动触发指定商户的证书拉取与更新
     *
     * @param merchantId 微信商户号
     */
    public void refreshCertificate(String merchantId) {
        Runnable task = downloadTaskMap.get(merchantId);
        if (task != null) {
            task.run();
        }
    }

    /**
     * 手动触发全量商户的证书更新
     */
    public void refreshAll() {
        for (Map.Entry<String, Runnable> entry : downloadTaskMap.entrySet()) {
            try {
                entry.getValue().run();
                log.info("Refreshed WeChat platform certificates for merchant [{}]", entry.getKey());
            } catch (Exception e) {
                log.error("Failed to refresh WeChat platform certificates for merchant [{}]: {}",
                        entry.getKey(), e.getMessage(), e);
            }
        }
    }

    /**
     * 周期任务执行的安全兜底包装（杜绝因单商户异常导致调度中断）
     */
    private void refreshAllSafely() {
        try {
            refreshAll();
        } catch (Throwable t) {
            log.error("Unexpected error in WeChat platform certificate rotation background schedule", t);
        }
    }

    @Override
    public void destroy() {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
            scheduledFuture = null;
        }
        scheduledExecutor.shutdown();
        try {
            if (!scheduledExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduledExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduledExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        certificateCache.clear();
        downloadTaskMap.clear();
        log.info("WechatAutoCertificateManager destroyed cleanly");
    }
}
