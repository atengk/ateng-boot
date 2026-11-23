package io.github.atengk.boot.sample.core.dto;

import io.github.atengk.boot.sample.core.model.SampleUserDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 二进制 JSON 载荷转换与 Bean 增量合并请求对象
 *
 * @author Ateng
 * @since 2026-11-13
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JsonAndBeanRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 待通过二进制 JSON 字节数组流转校验的用户列表
     */
    @Valid
    @NotEmpty(message = "待传输用户列表不能为空")
    private List<SampleUserDTO> users;

    /**
     * 扩展属性字典（用于验证 byte[] Map 双重载还原）
     */
    @NotEmpty(message = "扩展属性字典不能为空")
    private Map<String, String> metadata;

    /**
     * 可选增量补丁用户对象（用于验证 BeanUtil.copyIgnoreNull 与深拷贝隔离）
     */
    private SampleUserDTO patchUser;
}
