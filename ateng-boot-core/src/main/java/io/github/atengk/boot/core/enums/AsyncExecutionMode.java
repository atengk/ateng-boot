package io.github.atengk.boot.core.enums;

/**
 * 异步任务执行器线程运行模式枚举
 * 控制异步线程池采用平台线程（Platform Threads）、虚拟线程（Virtual Threads）或跟随全局环境自适应推导
 *
 * @author Ateng
 * @since 2026-10-22
 */
public enum AsyncExecutionMode {

    /**
     * 自适应模式（默认）：依据 Spring Boot 3 全局虚拟线程开关（spring.threads.virtual.enabled）及 JDK 运行时自适应匹配
     */
    AUTO,

    /**
     * 强制使用平台线程池（ThreadPoolTaskExecutor），适用于 CPU 密集型计算或需避免 Carrier Pinning 的场景
     */
    PLATFORM,

    /**
     * 强制使用虚拟线程执行器（SimpleAsyncTaskExecutor），适用于高并发阻塞 IO 或微服务出站调用场景
     */
    VIRTUAL
}
