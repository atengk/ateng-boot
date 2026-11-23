package io.github.atengk.boot.sample.core.dto;

import io.github.atengk.boot.sample.core.model.SampleUserDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * 安全受限 SpEL 表达式求值与高频通用算法原语请求对象
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SpelAndPrimitivesRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 待执行的 SpEL 表达式字符串（例如："100 * 2 + 50" 或 "#user.name + '-' + #seq"）
     */
    private String expression;

    /**
     * 是否模拟高危恶意 SpEL 注入（例如通过 T(java.lang.Runtime) 触发 RCE）以验证安全沙箱拦截
     */
    private boolean simulateMaliciousSpel;

    /**
     * 基础用户对象，用于 SpEL 根对象求值及 BeanUtil 属性增量合并
     */
    private SampleUserDTO user;

    /**
     * 增量 Patch 补丁对象，用于验证 BeanUtil.copyIgnoreNull 局部非空合并
     */
    private SampleUserDTO patchUser;

    /**
     * 用于 StringUtil 算法变换的原始字符串
     */
    private String rawString;

    /**
     * 用于 ConvertUtil 安全数值转换的字符串
     */
    private String rawNumberStr;

    /**
     * 用于 DateTimeUtil 工作日智能推演的基准日期
     */
    private LocalDate baseDate;
}
