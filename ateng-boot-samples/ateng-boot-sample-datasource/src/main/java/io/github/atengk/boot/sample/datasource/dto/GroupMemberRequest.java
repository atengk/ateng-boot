package io.github.atengk.boot.sample.datasource.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分组数据源节点成员增删请求传输对象
 *
 * @author Ateng
 * @since 2026-11-19
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupMemberRequest {

    /**
     * 分组数据源标识（例如 tenant_1001_slave）
     */
    @NotBlank(message = "分组标识不能为空")
    private String groupKey;

    /**
     * 物理成员数据源标识（例如 slave）
     */
    @NotBlank(message = "成员数据源标识不能为空")
    private String memberKey;
}
