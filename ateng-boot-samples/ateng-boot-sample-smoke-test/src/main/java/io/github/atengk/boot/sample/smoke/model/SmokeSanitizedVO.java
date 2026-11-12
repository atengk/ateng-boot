package io.github.atengk.boot.sample.smoke.model;

import io.github.atengk.boot.core.annotation.FieldSensitive;
import io.github.atengk.boot.core.enums.SensitiveType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 视图层脱敏响应 VO
 * 内存中保存原始明文，仅在 Jackson 序列化输出 JSON 时触发 @FieldSensitive 动态掩码
 *
 * @author Ateng
 * @since 2026-11-12
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SmokeSanitizedVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 业务名称
     */
    private String title;

    /**
     * 状态编码
     */
    private String status;

    /**
     * 脱敏展示手机号
     */
    @FieldSensitive(SensitiveType.MOBILE_PHONE)
    private String mobile;
}
