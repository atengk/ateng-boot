package io.github.atengk.boot.sample.http.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * RestClientRegistry 多实例与 mTLS/代理治理元数据探针视图对象
 *
 * @author Ateng
 * @since 2026-11-14
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegistryInspectVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 当前 RestClientRegistry 中缓存的命名客户端总数
     */
    private int registeredCount;

    /**
     * 已注册客户端名称列表
     */
    private List<String> registeredClientNames;

    /**
     * 各命名客户端多实例配置与治理能力明细
     */
    private List<RegistryClientItemVO> clients;
}
