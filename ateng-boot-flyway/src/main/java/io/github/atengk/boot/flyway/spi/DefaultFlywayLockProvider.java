package io.github.atengk.boot.flyway.spi;

import java.time.Duration;

/**
 * 缺省迁移互斥锁实现（委托 Flyway 原生数据库表级/咨询锁处理多节点竞争）
 *
 * @author Ateng
 * @since 2026-11-17
 */
public class DefaultFlywayLockProvider implements FlywayLockProvider {

    @Override
    public boolean tryLock(String targetKey, Duration waitTimeout) {
        return true;
    }

    @Override
    public void unlock(String targetKey) {
        // 缺省模式完全交由 Flyway 原生数据库事务/表锁自动释锁
    }
}
