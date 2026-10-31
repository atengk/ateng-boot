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

}
