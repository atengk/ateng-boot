package io.github.atengk.boot.sample.core.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 大批量切片流转与有界租户配额缓存响应视图对象
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchAndCacheVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 切片产生的批次数
     */
    private Integer batchCount;

    /**
     * 各批次订单编号切片详情
     */
    @Builder.Default
    private List<List<String>> sliceDetails = Collections.emptyList();

    /**
     * 基于属性流式去重后的元素数量
     */
    private Integer distinctItemCount;

    /**
     * 有界 LRU 缓存状态演进视图
     */
    private TenantCacheStatusVO cacheStatus;

    /**
     * 深度克隆后的脱敏对象敏感手机号（内存真明文断言）
     */
    private String clonedPlaintextPhone;

    /**
     * 深度克隆后的脱敏对象敏感身份证号（内存真明文断言）
     */
    private String clonedPlaintextIdCard;
}
