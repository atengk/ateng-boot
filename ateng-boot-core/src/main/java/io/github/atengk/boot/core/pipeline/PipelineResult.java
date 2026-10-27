package io.github.atengk.boot.core.pipeline;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;

import java.util.Objects;
import java.util.function.Function;

/**
 * 管道执行结果契约模型
 * 承载流水线执行状态、短路阶段名称、中断原因及异常因果链
 *
 * @author Ateng
 * @since 2026-10-27
 */
public final class PipelineResult {

    private final boolean success;
    private final boolean terminated;
    private final String terminatedStageName;
    private final String interruptReason;
    private final Throwable failureCause;

    private PipelineResult(boolean success, boolean terminated, String terminatedStageName, String interruptReason, Throwable failureCause) {
        this.success = success;
        this.terminated = terminated;
        this.terminatedStageName = terminatedStageName;
        this.interruptReason = interruptReason;
        this.failureCause = failureCause;
    }

    /**
     * 构造成功结果
     *
     * @return 成功结果对象
     */
    public static PipelineResult success() {
        return new PipelineResult(true, false, null, null, null);
    }

    /**
     * 构造阶段校验不通过导致的短路终止结果
     *
     * @param stageName 触发短路的阶段名称
     * @return 终止结果对象
     */
    public static PipelineResult terminated(String stageName) {
        return new PipelineResult(false, true, stageName, null, null);
    }

    /**
     * 构造带显式中断原因的短路终止结果
     *
     * @param stageName 触发短路的阶段名称
     * @param reason    中断原因
     * @return 终止结果对象
     */
    public static PipelineResult terminated(String stageName, String reason) {
        return new PipelineResult(false, true, stageName, reason, null);
    }

    /**
     * 构造阶段发生未捕获异常的失败结果
     *
     * @param stageName 发生异常的阶段名称
     * @param cause     异常对象
     * @return 失败结果对象
     */
    public static PipelineResult failure(String stageName, Throwable cause) {
        return new PipelineResult(false, false, stageName, null, cause);
    }

    /**
     * 判断管道是否全部阶段顺利执行完成
     *
     * @return 成功返回 true
     */
    public static boolean isSuccess(PipelineResult result) {
        return result != null && result.isSuccess();
    }

    public boolean isSuccess() {
        return success;
    }

    public boolean isTerminated() {
        return terminated;
    }

    public boolean isFailed() {
        return failureCause != null;
    }

    public String getTerminatedStageName() {
        return terminatedStageName;
    }

    public String getInterruptReason() {
        return interruptReason;
    }

    public Throwable getFailureCause() {
        return failureCause;
    }

    /**
     * 当管道未顺利完成时快速失败抛出标准业务异常，成功时返回当前结果支持链式调用
     * 1. 若因异常失败，抛出包含 cause 的 BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR)；
     * 2. 若因短路中断，抛出包含中断说明或阶段名的 BusinessException(CommonResultCode.BAD_REQUEST)
     *
     * @return 当前成功的结果对象，支持链式调用
     */
    public PipelineResult orThrow() {
        if (success) {
            return this;
        }
        if (failureCause != null) {
            if (failureCause instanceof BusinessException be) {
                throw be;
            }
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR,
                    "管道阶段 [" + terminatedStageName + "] 执行异常: " + failureCause.getMessage(), failureCause);
        }
        if (terminated) {
            String msg = (interruptReason != null && !interruptReason.isBlank())
                    ? interruptReason
                    : "管道阶段 [" + terminatedStageName + "] 校验未通过中断";
            throw new BusinessException(CommonResultCode.BAD_REQUEST, msg);
        }
        return this;
    }

    /**
     * 当管道未顺利完成时，使用用户提供的自定义异常构造器快速失败抛出异常，成功时返回当前结果支持链式调用
     *
     * @param exceptionSupplier 异常构造函数
     * @return 当前成功的结果对象，支持链式调用
     */
    public PipelineResult orThrow(Function<PipelineResult, ? extends RuntimeException> exceptionSupplier) {
        Objects.requireNonNull(exceptionSupplier, "异常构造函数 exceptionSupplier 不能为 null");
        if (!success) {
            throw exceptionSupplier.apply(this);
        }
        return this;
    }

    @Override
    public String toString() {
        return "PipelineResult{" +
                "success=" + success +
                ", terminated=" + terminated +
                ", terminatedStageName='" + terminatedStageName + '\'' +
                ", interruptReason='" + interruptReason + '\'' +
                ", failureCause=" + failureCause +
                '}';
    }
}
