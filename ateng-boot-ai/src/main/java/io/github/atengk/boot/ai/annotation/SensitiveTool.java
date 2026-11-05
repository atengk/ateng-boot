package io.github.atengk.boot.ai.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 敏感高危工具声明式防卫注解
 * <p>
 * 标注于受管 {@link dev.langchain4j.agent.tool.Tool} 方法上，声明其风险等级、审批分类与挂起超时策略。
 * 当大模型尝试调用被该注解标记的方法时，沙箱将自动拦截并生成处于 PENDING 状态的审批工单快照，
 * 阻断直接物理执行并返回工单挂起凭据回传模型，待人工审批通过后方可通过受管门面重放执行。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SensitiveTool {

    /**
     * 工具操作风险等级（默认为高风险 HIGH）
     *
     * @return 风险等级
     */
    RiskLevel riskLevel() default RiskLevel.HIGH;

    /**
     * 业务审批类型分类标识（用于多级审批路由或工单过滤，如 "FINANCIAL_PAY", "USER_DELETION"）
     *
     * @return 审批类型
     */
    String approvalType() default "DEFAULT";

    /**
     * 工单审批超时时限（ISO-8601 格式，如 "PT30M" 表示 30 分钟，留空时继承全局配置）
     *
     * @return 超时时间表达式
     */
    String timeout() default "";

    /**
     * 敏感操作业务描述说明（用于回传大模型或展示在审核界面）
     *
     * @return 操作描述
     */
    String description() default "";
}
