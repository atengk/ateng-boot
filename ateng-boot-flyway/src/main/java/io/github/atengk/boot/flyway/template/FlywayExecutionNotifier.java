package io.github.atengk.boot.flyway.template;

import io.github.atengk.boot.flyway.config.FlywayConfigurationSupport;
import io.github.atengk.boot.flyway.enums.FlywayExecutionMode;
import io.github.atengk.boot.flyway.event.FlywayMigrationCompletedEvent;
import io.github.atengk.boot.flyway.event.FlywayMigrationFailedEvent;
import io.github.atengk.boot.flyway.listener.FlywayMigrationListener;
import io.github.atengk.boot.flyway.model.MigrationOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Flyway 迁移领域事件与 SPI 监听器分发辅助器
 * <p>
 * 负责合并容器级 {@link FlywayMigrationListener} Bean 与编程式监听器，
 * 隔离监听器回调异常以免干扰迁移主流程，并同步广播脱敏领域事件。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-17
 */
final class FlywayExecutionNotifier {

    private static final Logger log = LoggerFactory.getLogger(FlywayExecutionNotifier.class);

    private final Object eventSource;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectProvider<FlywayMigrationListener> listenersProvider;
    private final List<FlywayMigrationListener> manualListeners = new CopyOnWriteArrayList<>();

    FlywayExecutionNotifier(Object eventSource,
                            ApplicationEventPublisher eventPublisher,
                            ObjectProvider<FlywayMigrationListener> listenersProvider) {
        this.eventSource = eventSource;
        this.eventPublisher = eventPublisher;
        this.listenersProvider = listenersProvider;
    }

    void addListener(FlywayMigrationListener listener) {
        if (listener != null && !manualListeners.contains(listener)) {
            manualListeners.add(listener);
        }
    }

    void removeListener(FlywayMigrationListener listener) {
        if (listener != null) {
            manualListeners.remove(listener);
        }
    }

    void notifyBeforeMigrate(String targetKey) {
        for (FlywayMigrationListener listener : resolveListeners()) {
            try {
                listener.onBeforeMigrate(targetKey);
            } catch (Exception e) {
                log.warn("FlywayMigrationListener#onBeforeMigrate 执行异常 [target={}]: {}",
                        targetKey, FlywayConfigurationSupport.sanitizeSensitiveText(e.getMessage()));
            }
        }
    }

    void notifySucceeded(MigrationOutcome outcome) {
        for (FlywayMigrationListener listener : resolveListeners()) {
            try {
                listener.onMigrationSucceeded(outcome);
            } catch (Exception e) {
                log.warn("FlywayMigrationListener#onMigrationSucceeded 执行异常 [target={}]: {}",
                        outcome.getTargetKey(), FlywayConfigurationSupport.sanitizeSensitiveText(e.getMessage()));
            }
        }
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new FlywayMigrationCompletedEvent(eventSource, outcome));
        }
    }

    void notifyFailed(String targetKey,
                      FlywayExecutionMode mode,
                      String safeMessage,
                      MigrationOutcome outcome,
                      Throwable cause) {
        for (FlywayMigrationListener listener : resolveListeners()) {
            try {
                listener.onMigrationFailed(targetKey, outcome, cause);
            } catch (Exception e) {
                log.warn("FlywayMigrationListener#onMigrationFailed 执行异常 [target={}]: {}",
                        targetKey, FlywayConfigurationSupport.sanitizeSensitiveText(e.getMessage()));
            }
        }
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new FlywayMigrationFailedEvent(
                    eventSource, targetKey, mode, safeMessage, outcome, cause));
        }
    }

    private List<FlywayMigrationListener> resolveListeners() {
        Set<FlywayMigrationListener> combined = new LinkedHashSet<>();
        if (listenersProvider != null) {
            listenersProvider.orderedStream().forEach(combined::add);
        }
        combined.addAll(manualListeners);
        return new ArrayList<>(combined);
    }
}
