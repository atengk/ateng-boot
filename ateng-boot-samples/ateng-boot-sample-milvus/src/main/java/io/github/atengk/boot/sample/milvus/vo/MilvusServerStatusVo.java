package io.github.atengk.boot.sample.milvus.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Milvus 服务端状态视图对象
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MilvusServerStatusVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 客户端与服务端连通探针状态
     */
    private Boolean ready;

    /**
     * Milvus 服务端版本号 (如 "v2.4.4")
     */
    private String serverVersion;
}
