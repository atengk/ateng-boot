package io.github.atengk.boot.sample.core.vo;

import io.github.atengk.boot.sample.core.model.SampleUserDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 安全受限 SpEL 求值与高频通用算法原语响应视图对象
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SpelAndPrimitivesVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 安全 SpEL 表达式合法求值结果
     */
    private Object spelResult;

    /**
     * 高危恶意注入是否被只读沙箱成功阻断
     */
    private boolean rceBlocked;

    /**
     * 恶意表达式被阻断时的异常提示信息
     */
    private String blockedMessage;

    /**
     * 通过 LambdaUtil 强类型推导出的 JavaBean 属性名
     */
    private String lambdaFieldName;

    /**
     * 通过 BeanUtil.copyIgnoreNull 局部非空合并后的最终用户对象
     */
    private SampleUserDTO mergedUser;

    /**
     * 工作日智能推演与跨度计算结果
     */
    private WorkdayCalcVO workdayResult;

    /**
     * 字符串变换算法结果视图
     */
    private StringTransformVO stringTransformResult;

    /**
     * 通过 ConvertUtil 容错转换后的 Long 数值
     */
    private Long convertedLong;

    /**
     * 通过 RandomUtil 生成的 6 位无锁安全短信随机码
     */
    private String randomSmsCode;

    /**
     * 不可变元组 Pair 与 Triple 实战描述文本
     */
    private String tupleDemo;
}
