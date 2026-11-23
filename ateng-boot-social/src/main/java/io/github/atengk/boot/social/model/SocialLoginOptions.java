package io.github.atengk.boot.social.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 社交登录多端治理与会话签发控制参数
 * <p>
 * 深度联动 Sa-Token 会话机制，原生支持 deviceType、loginType、自定义会话超时与业务扩展参数装载。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SocialLoginOptions implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 终端设备类型（如 PC, APP, MINI_PROGRAM, H5 等）
     */
    private String deviceType;

    /**
     * Sa-Token 账号体系类型标识（如 login, user, admin，默认空使用全局默认体系）
     */
    private String loginType;

    /**
     * 会话自定义有效时间（若指定则覆盖全局 Sa-Token timeout 配置）
     */
    private Duration timeout;

    /**
     * 是否记住登录状态 / 持久化 Cookie
     */
    private Boolean isLastingCookie;

    /**
     * 随会话写入 Sa-Token 内部 Session 的业务扩展参数字典
     */
    @Builder.Default
    private Map<String, Object> extraData = new LinkedHashMap<>();

    /**
     * 获取不可变的业务扩展字典
     *
     * @return 扩展数据
     */
    public Map<String, Object> getExtraData() {
        if (extraData == null) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(extraData);
    }
}
