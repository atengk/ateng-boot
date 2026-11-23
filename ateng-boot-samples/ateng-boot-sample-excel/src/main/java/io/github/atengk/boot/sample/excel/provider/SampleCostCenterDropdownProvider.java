package io.github.atengk.boot.sample.excel.provider;

import io.github.atengk.boot.excel.annotation.DropdownProvider;
import org.springframework.stereotype.Component;

/**
 * 企业级超长成本中心动态下拉选项提供者
 * <p>
 * 演示当动态下拉候选项拼接总字符数突破 255 字符（安全阈值 200 字符）时，
 * {@code DropdownSheetWriteHandler} 优先从 Spring 容器获取 Provider Bean，
 * 并自动创建隐藏字典工作表 {@code _ateng_dropdown_dict} 与工作簿级 {@code Name} 命名区域公式引用实现防截断下拉约束。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-13
 */
@Component
public class SampleCostCenterDropdownProvider implements DropdownProvider {

    @Override
    public String[] getOptions() {
        return new String[]{
                "CC-1001-总部基础平台研发中心",
                "CC-1002-云原生基础设施工程部",
                "CC-1003-企业级中间件架构演进部",
                "CC-1004-数据智能与风控算法中心",
                "CC-2001-华东区企业解决方案交付部",
                "CC-2002-华南区企业解决方案交付部",
                "CC-2003-华北区政企生态合作事业部",
                "CC-2004-西南区行业数字化拓展部",
                "CC-3001-集团财务核算与资金管理部",
                "CC-3002-人力资源与组织发展中心",
                "CC-3003-法务合规与信息安全审计部",
                "CC-3004-供应链采购与资产运营中心",
                "CC-4001-全球客户成功与技术支持中心",
                "CC-4002-数字营销与品牌增长运营部"
        };
    }
}
