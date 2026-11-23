package io.github.atengk.boot.job.xxl.logback;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * XXL-Job Logback 镜像追加器自动注册器
 *
 * <p>在 Spring 容器单例实例化完成后，将 {@link XxlJobLogbackAppender} 动态注册至 Logback 的根记录器 (ROOT)；
 * 并在应用销毁时平滑解绑并停止 Appender，实现生命周期的确定性释放与资源清理。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class XxlJobLogbackAppenderRegistrar implements SmartInitializingSingleton, DisposableBean {

    public static final String APPENDER_NAME = "XXL_JOB_LOGBACK_APPENDER";

    private final XxlJobLogbackAppender appender = new XxlJobLogbackAppender();

    @Override
    public void afterSingletonsInstantiated() {
        ILoggerFactory loggerFactory = LoggerFactory.getILoggerFactory();
        if (loggerFactory instanceof LoggerContext loggerContext) {
            appender.setContext(loggerContext);
            appender.setName(APPENDER_NAME);
            appender.start();

            Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
            if (rootLogger != null && rootLogger.getAppender(APPENDER_NAME) == null) {
                rootLogger.addAppender(appender);
            }
        }
    }

    @Override
    public void destroy() {
        ILoggerFactory loggerFactory = LoggerFactory.getILoggerFactory();
        if (loggerFactory instanceof LoggerContext loggerContext) {
            Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
            if (rootLogger != null) {
                rootLogger.detachAppender(appender);
            }
        }
        if (appender.isStarted()) {
            appender.stop();
        }
    }

    public XxlJobLogbackAppender getAppender() {
        return appender;
    }

}
