package io.github.atengk.boot.job.xxl.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * XXL-Job 执行器生产级配置属性
 *
 * <p>定义 ateng-boot-job-xxl 自动装配、执行器通信、鉴权、日志治理与超时参数</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Data
@ConfigurationProperties(prefix = "ateng.boot.job.xxl")
public class XxlJobProperties {

    /**
     * 是否启用 XXL-Job 执行器自动装配
     */
    private boolean enabled = true;

    /**
     * 调度中心核心服务地址列表，多个地址以英文逗号分隔 (如 http://127.0.0.1:8080/xxl-job-admin)
     */
    private String adminAddresses;

    /**
     * 执行器与调度中心通讯交互鉴权访问令牌 (AccessToken)
     */
    private String accessToken;

    /**
     * 执行器应用名称 (AppName)，用于调度中心执行器管理与分组路由
     */
    private String appname = "xxl-job-executor-ateng";

    /**
     * 执行器注册地址，优先于 ip:port 格式使用 (适用于容器外网映射或反向代理域名)
     */
    private String address;

    /**
     * 执行器绑定与上报 IP 地址，未显式配置时将自动探测可用网卡或容器 IP
     */
    private String ip;

    /**
     * 执行器 Netty 监听端口
     */
    private int port = 9999;

    /**
     * 执行器运行期调度日志本地物理存储根目录
     */
    private String logPath = "logs/xxl-job/jobhandler";

    /**
     * 执行器日志本地清理保留天数，达到或超过该时限的历史日志将被后台线程自动淘汰
     */
    private int logRetentionDays = 30;

    /**
     * 执行器与调度中心网络 RPC 通信超时阈值
     */
    private Duration timeout = Duration.ofSeconds(30);

    /**
     * 是否启用 SLF4J 日志自动镜像至 XXL-Job 调度执行日志文件
     */
    private boolean logMirrorEnabled = true;

    /**
     * 是否启用调度任务执行切面 (自动注入 MDC traceId 与多租户上下文)
     */
    private boolean contextAspectEnabled = true;

    /**
     * 默认任务本地硬看门狗超时时限 (如未在注解或 Admin 显式指定时生效，默认 null 表示不设全局超时)
     */
    private Duration defaultJobTimeout;

    /**
     * 是否启用受管线程池执行模式 (为 true 时将调度方法提交至 Core 受管异步线程池执行)
     */
    private boolean managedPoolEnabled = false;

    /**
     * 执行器入站 CIDR 白名单网段列表 (支持单 IP 如 127.0.0.1 或 CIDR 掩码如 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16；为空时默认放行全部)
     */
    private java.util.List<String> cidrWhitelist = new java.util.ArrayList<>();

    /**
     * 容器平滑停机任务排空最大等待超时时限 (默认 30 秒)
     */
    private Duration drainTimeout = Duration.ofSeconds(30);

    /**
     * 端口冲突探测最大顺延重试步长 (默认最多尝试探测顺延 100 个端口)
     */
    private int portDetectionMaxRetry = 100;

}
