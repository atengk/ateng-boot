package io.github.atengk.boot.sample.core.model;

import io.github.atengk.boot.core.annotation.FieldSensitive;
import io.github.atengk.boot.core.enums.SensitiveType;
import io.github.atengk.boot.core.validation.ValidationGroups;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Core 样例用户数据传输模型
 * 用于验证脱敏注解、JSR-303 分组校验、对象克隆与序列化
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SampleUserDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户 ID（Update 分组必填）
     */
    @NotNull(groups = ValidationGroups.Update.class, message = "修改时用户ID不能为空")
    private Long id;

    /**
     * 中文姓名（脱敏掩码）
     */
    @NotBlank(message = "姓名不能为空")
    @FieldSensitive(SensitiveType.CHINESE_NAME)
    private String name;

    /**
     * 手机号码（脱敏掩码）
     */
    @NotBlank(message = "手机号不能为空")
    @FieldSensitive(SensitiveType.MOBILE_PHONE)
    private String phone;

    /**
     * 身份证号码（脱敏掩码）
     */
    @FieldSensitive(SensitiveType.ID_CARD)
    private String idCard;

    /**
     * 电子邮箱（脱敏掩码）
     */
    @FieldSensitive(SensitiveType.EMAIL)
    private String email;

    /**
     * 访问令牌（首尾保留字符数脱敏）
     */
    @FieldSensitive(value = SensitiveType.KEEP_HEAD_TAIL, keepHead = 2, keepTail = 2, maskChar = '#')
    private String secretToken;

    /**
     * 自定义编码（区间脱敏）
     */
    @FieldSensitive(startInclude = 2, endExclude = 5, maskChar = '#')
    private String customCode;

    /**
     * 账户状态（Status 分组必填）
     */
    @NotNull(groups = ValidationGroups.Status.class, message = "变更状态不能为空")
    private Integer status;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}
