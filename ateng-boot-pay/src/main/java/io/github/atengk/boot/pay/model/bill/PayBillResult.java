package io.github.atengk.boot.pay.model.bill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 财务对账单解析全量结果（内存集合模式）
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayBillResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 对账汇总信息
     */
    private PayBillSummary summary;

    /**
     * 对账明细记录列表（保证空安全，绝不返回 null）
     */
    @Builder.Default
    private List<PayBillEntry> entries = new ArrayList<>();

    public List<PayBillEntry> getEntries() {
        if (entries == null) {
            return Collections.emptyList();
        }
        return entries;
    }

    public int size() {
        return entries != null ? entries.size() : 0;
    }

    public boolean isEmpty() {
        return entries == null || entries.isEmpty();
    }
}
