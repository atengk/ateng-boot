package io.github.atengk.boot.social.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OAuth2 State 服务端防篡改上下文模型
 * <p>
 * 配合服务端 State 缓存存储，在发起授权时持久化多租户、目标跳转深链与自定义业务参数，
 * 授权回调成功后服务端还原该上下文，彻底杜绝在浏览器 URL Query 中明文传递敏感参数导致篡改攻击。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StateContext implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 多租户上下文租户 ID
     */
    private String tenantId;

    /**
     * 业务登录授权完成后的前端重定向 Deep-Link 地址
     */
    private String redirectUrl;

    /**
     * 业务自定义扩展上下文参数字典（如推广渠道来源、邀请人 ID、一次性防伪码等）
     */
    @Builder.Default
    private Map<String, Object> extraParams = new LinkedHashMap<>();

    /**
     * 上下文创建时间戳（毫秒）
     */
    @Builder.Default
    private long createdAt = System.currentTimeMillis();

    /**
     * 获取不可变的业务扩展参数字典
     *
     * @return 业务扩展参数
     */
    public Map<String, Object> getExtraParams() {
        if (extraParams == null) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(extraParams);
    }
}
