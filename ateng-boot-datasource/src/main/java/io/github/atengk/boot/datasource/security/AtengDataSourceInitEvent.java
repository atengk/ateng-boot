package io.github.atengk.boot.datasource.security;

import com.baomidou.dynamic.datasource.creator.DataSourceProperty;
import com.baomidou.dynamic.datasource.event.DataSourceInitEvent;
import io.github.atengk.boot.core.util.DesensitizeUtil;
import io.github.atengk.boot.datasource.properties.DatasourceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;

/**
 * 数据源初始化事件监听与纯内存密码解密防卫器
 * 在连接池创建前瞬态注入明文密码以完成物理握手，并在创建完成后立即将 {@link DataSourceProperty} 还原为原始密文，
 * 严守零明文不变量，确保配置对象、Actuator 端点与堆栈中永不残留明文凭据
 *
 * @author Ateng
 * @since 2026-10-24
 */
public class AtengDataSourceInitEvent implements DataSourceInitEvent {

    private static final Logger log = LoggerFactory.getLogger(AtengDataSourceInitEvent.class);

    private final DataSourcePasswordDecryptor decryptor;
    private final DatasourceProperties datasourceProperties;

    /**
     * 瞬态密码持有者（仅在 beforeCreate 到 afterCreate 期间存活于单线程）
     */
    private final ThreadLocal<TransientCredentialHolder> transientHolder = new ThreadLocal<>();

    public AtengDataSourceInitEvent(DataSourcePasswordDecryptor decryptor, DatasourceProperties datasourceProperties) {
        this.decryptor = decryptor;
        this.datasourceProperties = datasourceProperties;
    }

    @Override
    public void beforeCreate(DataSourceProperty property) {
        if (property == null) {
            return;
        }
        // 清理前置潜在残留
        transientHolder.remove();

        boolean decryptionEnabled = datasourceProperties != null &&
                datasourceProperties.getSecurity() != null &&
                datasourceProperties.getSecurity().isPasswordDecryptionEnabled();

        String rawPassword = property.getPassword();
        if (decryptionEnabled && decryptor != null && decryptor.supports(rawPassword)) {
            String decrypted = decryptor.decrypt(rawPassword);
            transientHolder.set(new TransientCredentialHolder(property, rawPassword));
            // 纯内存瞬态替换
            property.setPassword(decrypted);
            if (log.isDebugEnabled()) {
                log.debug("ateng-boot-datasource: 数据源 [{}] 密码密文已解密进入连接池创建流程 [masked={}]",
                        property.getPoolName(), DesensitizeUtil.password(rawPassword));
            }
        }
    }

    @Override
    public void afterCreate(DataSource dataSource) {
        try {
            TransientCredentialHolder holder = transientHolder.get();
            if (holder != null && holder.property != null) {
                // 立即将 DataSourceProperty 还原为原始密文，绝不持久化明文凭据
                holder.property.setPassword(holder.originalPassword);
                if (log.isDebugEnabled()) {
                    log.debug("ateng-boot-datasource: 数据源 [{}] 连接池初始化完毕，已恢复密文状态",
                            holder.property.getPoolName());
                }
            }
        } finally {
            transientHolder.remove();
        }
    }

    private static class TransientCredentialHolder {
        private final DataSourceProperty property;
        private final String originalPassword;

        private TransientCredentialHolder(DataSourceProperty property, String originalPassword) {
            this.property = property;
            this.originalPassword = originalPassword;
        }
    }
}
