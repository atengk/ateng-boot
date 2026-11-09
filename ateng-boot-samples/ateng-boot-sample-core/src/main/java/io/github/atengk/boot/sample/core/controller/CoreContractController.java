package io.github.atengk.boot.sample.core.controller;

import io.github.atengk.boot.core.model.OptionItem;
import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.util.EnumUtil;
import io.github.atengk.boot.core.validation.ValidList;
import io.github.atengk.boot.sample.core.dto.CustomerOnboardingRequest;
import io.github.atengk.boot.sample.core.dto.OrderCheckRequest;
import io.github.atengk.boot.sample.core.enums.OrderStatusEnum;
import io.github.atengk.boot.sample.core.service.CoreDataProcessingScenarioService;
import io.github.atengk.boot.sample.core.service.CoreResilienceScenarioService;
import io.github.atengk.boot.sample.core.vo.OrderCheckVO;
import io.github.atengk.boot.sample.core.vo.OrderSummaryVO;
import io.github.atengk.boot.sample.core.vo.PageNormalizeVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 传输契约、因果异常与分层校验领域控制器
 * 演示并自验统一响应模型 Result、PageQuery/PageResult 分页传输契约、
 * BusinessAssert 语义化断言、BusinessException 双模异常堆栈、
 * ValidList 批量级联校验、ValidatorUtil 与通用字典枚举 IBaseEnum / EnumUtil / @InEnum
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Slf4j
@RestController
@RequestMapping("/sample/core/contract")
@RequiredArgsConstructor
public class CoreContractController {

    private final CoreResilienceScenarioService resilienceScenarioService;
    private final CoreDataProcessingScenarioService dataProcessingScenarioService;

    /**
     * 1. 订单结算前置断言与双模堆栈分析
     * 演示 Result 统一响应契约、BusinessAssert 业务断言与 BusinessException 双模堆栈治理机制
     *
     * @param request 订单结算校验请求
     * @return 订单校验结果视图
     */
    @PostMapping("/order-check")
    public Result<OrderCheckVO> checkOrder(@RequestBody @Valid OrderCheckRequest request) {
        log.info("接收订单结算前置校验请求: orderNo={}, amount={}, simulateDriver={}",
                request.getOrderNo(), request.getAmount(), request.getSimulateDriverFailure());
        OrderCheckVO checkVO = resilienceScenarioService.checkOrderCheckout(request);
        return Result.success(checkVO);
    }

    /**
     * 2. 订单分页查询
     * 演示 PageQuery 入参边界约束与 PageResult 分页出参解耦
     *
     * @param query 分页入参（标配 current >= 1, size >= 1）
     * @return 订单摘要分页结果
     */
    @GetMapping("/orders/page")
    public Result<PageResult<OrderSummaryVO>> queryOrdersPage(@Valid PageQuery query) {
        log.info("接收订单分页查询请求: current={}, size={}, sorts={}",
                query.getCurrent(), query.getSize(), query.getSorts());
        PageResult<OrderSummaryVO> pageResult = resilienceScenarioService.queryOrderPage(query);
        return Result.success(pageResult);
    }

    /**
     * 3. 批量客户入驻校验
     * 演示 ValidList 泛型包装器解决 Spring MVC 集合校验穿透缺陷，结合 @InEnum 强类型枚举第一道防线
     *
     * @param customerList 批量客户列表（自动级联触发每个元素字段校验与枚举校验）
     * @return 成功入驻客户数
     */
    @PostMapping("/users/batch-validate")
    public Result<Integer> batchValidateCustomers(@RequestBody @Valid ValidList<CustomerOnboardingRequest> customerList) {
        log.info("接收批量客户入驻校验请求: batchSize={}", customerList.size());
        int count = dataProcessingScenarioService.batchOnboardCustomers(customerList);
        return Result.success(count);
    }

    /**
     * 4. 通用字典枚举选项列表渲染
     * 演示 IBaseEnum 契约、@EnumAlias 别名与 EnumUtil.toOptions 转换为标准下拉选项模型 OptionItem
     *
     * @return 订单状态字典选项列表
     */
    @GetMapping("/enums/options")
    public Result<List<OptionItem<Integer>>> getOrderStatusOptions() {
        log.info("请求获取订单状态通用枚举选项列表");
        List<OptionItem<Integer>> options = EnumUtil.toOptions(OrderStatusEnum.class);
        return Result.success(options);
    }

    /**
     * 5. 编程式安全分页纠偏与防溢出偏移量计算
     * 演示 PageQuery.normalize、safeCurrent、safeSize 与 offset 编程式非 Web 容器消费防御
     *
     * @param current 原始页码（支持 0、负数、大数）
     * @param size    原始单页大小（支持 0、负数、超出 1000 上限）
     * @return 分页纠偏后安全指标
     */
    @GetMapping("/page-normalize")
    public Result<PageNormalizeVO> normalizePage(
            @RequestParam(required = false, defaultValue = "0") long current,
            @RequestParam(required = false, defaultValue = "-5") long size) {
        PageQuery query = new PageQuery(current, size);
        long rawCurrent = query.getCurrent();
        long rawSize = query.getSize();
        long safeCurrent = query.safeCurrent();
        long safeSize = query.safeSize();
        long offset = query.offset();
        query.normalize();

        PageNormalizeVO vo = PageNormalizeVO.builder()
                .rawCurrent(rawCurrent)
                .rawSize(rawSize)
                .safeCurrent(safeCurrent)
                .safeSize(safeSize)
                .offset(offset)
                .normalizedCurrent(query.getCurrent())
                .normalizedSize(query.getSize())
                .build();

        return Result.success(vo);
    }
}
