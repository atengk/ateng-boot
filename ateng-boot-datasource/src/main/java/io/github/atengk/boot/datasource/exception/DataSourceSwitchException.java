package io.github.atengk.boot.datasource.exception;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.datasource.enums.DatasourceResultCode;
import lombok.Getter;

/**
 * 动态数据源切换异常（假切源致命风险异常）
 * <p>
 * 当检测到在 Spring 单物理连接事务 (@Transactional) 中尝试切换不同物理数据源时抛出，
 * 彻底消除底层物理连接被 Spring 事务管理器静默复用导致的假切源与串库漏洞。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Getter
public class DataSourceSwitchException extends BusinessException {

    private final String currentDataSourceKey;
    private final String targetDataSourceKey;

    public DataSourceSwitchException(String currentDataSourceKey, String targetDataSourceKey, String message) {
        super(DatasourceResultCode.DATASOURCE_SWITCH_ERROR, message);
        this.currentDataSourceKey = currentDataSourceKey;
        this.targetDataSourceKey = targetDataSourceKey;
    }
}
