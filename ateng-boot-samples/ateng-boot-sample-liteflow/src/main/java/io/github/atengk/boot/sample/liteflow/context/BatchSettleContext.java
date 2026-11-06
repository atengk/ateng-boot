package io.github.atengk.boot.sample.liteflow.context;

import io.github.atengk.boot.sample.liteflow.model.SettleItem;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 批量结算链路全周期业务上下文
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Data
public class BatchSettleContext implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 商户标识
     */
    private String merchantId;

    /**
     * 结算批次号
     */
    private String batchNo;

    /**
     * 是否在遇到高危明细时提前终止
     */
    private boolean terminateOnSuspicious;

    /**
     * 待处理结算明细列表
     */
    private List<SettleItem> items = new ArrayList<>();

    /**
     * 实际处理项目数
     */
    private int processedCount = 0;

    /**
     * 累计成功结算金额
     */
    private BigDecimal settledAmount = BigDecimal.ZERO;

    /**
     * 已结算的明细标识列表
     */
    private List<String> settledItemIds = new ArrayList<>();

    /**
     * 税务计算降级处理的明细标识列表
     */
    private List<String> degradedItems = new ArrayList<>();

    /**
     * 纳税计算通过并处理的明细标识列表
     */
    private List<String> taxAppliedItems = new ArrayList<>();

    /**
     * 是否触发 endFlow 提前正常终止
     */
    private boolean terminatedEarly = false;

    /**
     * 批次处理汇总说明
     */
    private String summary = "";
}
