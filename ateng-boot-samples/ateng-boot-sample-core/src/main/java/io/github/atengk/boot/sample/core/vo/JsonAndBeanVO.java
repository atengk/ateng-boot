package io.github.atengk.boot.sample.core.vo;

import io.github.atengk.boot.sample.core.model.SampleUserDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 二进制 JSON 载荷转换与 Bean 增量合并响应视图对象
 *
 * @author Ateng
 * @since 2026-11-13
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JsonAndBeanVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 序列化后用户列表 UTF-8 字节数组长度
     */
    private Integer userBytesLength;

    /**
     * 经 JsonUtil.parseList(byte[], SampleUserDTO.class) 还原的用户列表
     */
    private List<SampleUserDTO> parsedUsers;

    /**
     * 经 JsonUtil.parseObject(byte[], TypeReference) 泛型包络还原的用户数量
     */
    private Integer genericEnvelopeUserCount;

    /**
     * 经 JsonUtil.parseMap(byte[], String.class, String.class) 还原的强类型字典
     */
    private Map<String, String> parsedTypedMap;

    /**
     * 经 JsonUtil.parseMap(byte[]) 还原的通用字典
     */
    private Map<String, Object> parsedGenericMap;

    /**
     * 经 JsonUtil.deepClone 与 BeanUtil.copyIgnoreNull 增量合并后的首条用户快照
     */
    private SampleUserDTO deepClonedFirstUser;
}
