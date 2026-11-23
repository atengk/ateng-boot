package io.github.atengk.boot.core.lifecycle;

import io.github.atengk.boot.core.util.FileUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.Ordered;

import java.nio.file.Path;

/**
 * 应用受管临时目录生命周期清理监听器
 * 监听 ContextClosedEvent 事件，在 Spring 容器正常关闭时扫描并清理应用专属临时目录下的残留文件，杜绝磁盘泄漏
 *
 * @author Ateng
 * @since 2026-10-30
 */
public class AppTempDirCleanupListener implements ApplicationListener<ContextClosedEvent>, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AppTempDirCleanupListener.class);

    private final String appName;

    public AppTempDirCleanupListener() {
        this("application");
    }

    public AppTempDirCleanupListener(String appName) {
        this.appName = (appName != null && !appName.isBlank()) ? appName : "application";
    }

    @Override
    public int getOrder() {
        // 在下线日志输出之前执行清理
        return Ordered.LOWEST_PRECEDENCE - 10;
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        // 仅处理根 ApplicationContext 关闭事件
        if (event.getApplicationContext().getParent() != null) {
            return;
        }

        try {
            Path tempDir = FileUtil.getAppTempDirectory(appName);
            int cleanedCount = FileUtil.cleanDirectorySafely(tempDir);
            if (cleanedCount > 0) {
                log.debug("[ATENG] Successfully cleaned {} temporary files in [{}]", cleanedCount, tempDir);
            }
        } catch (Throwable ex) {
            // 防御异常，绝不阻塞应用停机下线
            log.warn("[ATENG] Failed to clean temporary directory for application [{}]: {}", appName, ex.getMessage());
        }
    }
}
