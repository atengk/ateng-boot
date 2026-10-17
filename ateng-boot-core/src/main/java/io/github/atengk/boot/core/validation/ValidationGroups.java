package io.github.atengk.boot.core.validation;

/**
 * 通用 JSR-303 / Jakarta Validation 校验分组契约标准件
 * 集中收敛全团队高频通用的分组 Marker 接口，避免各子系统与领域模块私有化空类定义
 *
 * @author Ateng
 * @since 2026-10-17
 */
public final class ValidationGroups {

    private ValidationGroups() {
    }

    /**
     * 新增操作校验分组（例如主键 ID 为空，必填业务属性校验）
     */
    public interface Create {
    }

    /**
     * 更新操作校验分组（例如主键 ID 必填，部分修改字段校验）
     */
    public interface Update {
    }

    /**
     * 查询操作校验分组（例如分页查询、范围检索参数校验）
     */
    public interface Query {
    }

    /**
     * 状态变更校验分组（例如启用/禁用、审核驳回等状态流转参数校验）
     */
    public interface Status {
    }
}
