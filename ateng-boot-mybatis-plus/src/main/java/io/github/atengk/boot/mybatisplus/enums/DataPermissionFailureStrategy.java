package io.github.atengk.boot.mybatisplus.enums;

import io.github.atengk.boot.core.enums.IBaseEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 细粒度数据权限 SPI 异常失败策略枚举
 * <p>
 * 定义 {@link io.github.atengk.boot.mybatisplus.permission.DataPermissionHandler} 生成 AST 条件发生非受控异常时的安全兜底行为，
 * 防止异常发生时静默降级为全表放行导致越权泄漏。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-24
 */
@Getter
@AllArgsConstructor
public enum DataPermissionFailureStrategy implements IBaseEnum<String> {

    /**
     * 安全拒止（默认）：记录错误日志并注入 {@code 1 = 0} 恒假条件，查询返回空集、更新/删除影响 0 行
     */
    DENY_ALL("deny_all", "安全拒止：注入 1 = 0 条件"),

    /**
     * 快速失败：保留原始因果链直接抛出受控 {@link io.github.atengk.boot.core.exception.BusinessException}
     */
    THROW("throw", "快速失败：保留 cause 链抛出受控异常"),

    /**
     * 降级放行：仅记录错误日志并返回 {@code null} 跳过当前表权限过滤（仅建议存量项目过渡兼容使用）
     */
    FAIL_OPEN("fail_open", "降级放行：记录错误日志并返回 null");

    /**
     * 策略编码
     */
    private final String code;

    /**
     * 策略说明标签
     */
    private final String label;
}
