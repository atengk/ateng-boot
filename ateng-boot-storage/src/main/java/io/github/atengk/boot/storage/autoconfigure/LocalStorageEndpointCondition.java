package io.github.atengk.boot.storage.autoconfigure;

import io.github.atengk.boot.storage.properties.StorageProperties;
import io.github.atengk.boot.storage.properties.StorageSourceProperties;
import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Map;

/**
 * 本地存储安全端点（预签名下载与直传）自动装配激活条件
 * 当全局单源类型为 local（含未显式指定 type 的默认场景）或混合多源 sources 中存在任一 local 源时激活
 *
 * @author Ateng
 * @since 2026-11-14
 */
class LocalStorageEndpointCondition extends SpringBootCondition {

    private static final String PREFIX = "ateng.boot.storage";
    private static final String TYPE_LOCAL = "local";
    private static final String DEFAULT_PRIMARY = "primary";

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        ConditionMessage.Builder message = ConditionMessage.forCondition("Local Storage Endpoints");
        StorageProperties properties = Binder.get(context.getEnvironment())
                .bind(PREFIX, StorageProperties.class)
                .orElseGet(StorageProperties::new);

        Map<String, StorageSourceProperties> sources = properties.getSources();
        if (sources != null && !sources.isEmpty()) {
            for (Map.Entry<String, StorageSourceProperties> entry : sources.entrySet()) {
                StorageSourceProperties sourceProps = entry.getValue();
                if (sourceProps != null && isLocalType(sourceProps.getType())) {
                    return ConditionOutcome.match(message.found("local storage source").items(entry.getKey()));
                }
            }
            String primary = (properties.getPrimary() != null && !properties.getPrimary().isBlank())
                    ? properties.getPrimary().trim()
                    : DEFAULT_PRIMARY;
            if (!sources.containsKey(primary) && isLocalType(properties.getType())) {
                return ConditionOutcome.match(message.found("fallback primary local storage type")
                        .items(properties.getType()));
            }
            return ConditionOutcome.noMatch(message.didNotFind("any local storage source in sources or primary").atAll());
        }

        if (isLocalType(properties.getType())) {
            return ConditionOutcome.match(message.found("local storage type").items(properties.getType()));
        }
        return ConditionOutcome.noMatch(message.didNotFind("local storage type").items(properties.getType()));
    }

    private boolean isLocalType(String type) {
        return type == null || type.isBlank() || TYPE_LOCAL.equalsIgnoreCase(type.trim());
    }
}
