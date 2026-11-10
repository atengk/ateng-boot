package io.github.atengk.boot.sample.datasource.controller;

import com.baomidou.dynamic.datasource.creator.DataSourceProperty;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.datasource.template.DynamicDatasourceTemplate;
import io.github.atengk.boot.sample.datasource.config.DatasourceSampleInitConfig;
import io.github.atengk.boot.sample.datasource.dto.DataSourceCreateRequest;
import io.github.atengk.boot.sample.datasource.service.DatasourceLifecycleScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 动态数据源生命周期治理控制器
 * <p>
 * 提供运行时动态挂载新数据源、初始化表结构、业务写入以及超时等待在途连接归还的优雅排空注销。
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@RestController
@RequestMapping("/sample/datasource/lifecycle")
@RequiredArgsConstructor
public class DatasourceLifecycleController {

    private final DynamicDatasourceTemplate dynamicDatasourceTemplate;
    private final DatasourceSampleInitConfig datasourceSampleInitConfig;
    private final DatasourceLifecycleScenarioService lifecycleScenarioService;

    /**
     * 1. 运行时动态注册新数据源并初始化建表
     *
     * @param request 动态数据源创建入参
     * @return 操作成功结果
     */
    @PostMapping("/add")
    public Result<String> addDynamicDataSource(@Valid @RequestBody DataSourceCreateRequest request) {
        log.info("[LifecycleController] 动态挂载新数据源: {}", request.getDsKey());
        DataSourceProperty property = new DataSourceProperty();
        property.setPoolName(request.getDsKey());
        property.setUrl(request.getUrl());
        property.setUsername(request.getUsername());
        property.setPassword(request.getPassword());
        property.setDriverClassName(request.getDriverClassName());

        dynamicDatasourceTemplate.addDataSource(request.getDsKey(), property, request.isPreWarm());
        datasourceSampleInitConfig.initTableForDataSource(request.getDsKey());

        if (request.isPreWarm()) {
            int target = request.getPreWarmConnections() != null ? request.getPreWarmConnections() : 5;
            int actual = dynamicDatasourceTemplate.preWarmDataSource(request.getDsKey(), target);
            log.info("[LifecycleController] 数据源 [{}] 成功预热 {} 个连接", request.getDsKey(), actual);
        }

        return Result.success("Dynamic datasource [" + request.getDsKey() + "] registered and initialized successfully");
    }

    /**
     * 2. 在动态挂载的数据源上执行订单业务写入
     *
     * @param dsKey   目标数据源标识
     * @param orderNo 业务订单号
     * @return 操作成功结果
     */
    @PostMapping("/order/create")
    public Result<String> createOrderInDynamicDataSource(@RequestParam String dsKey,
                                                         @RequestParam String orderNo) {
        lifecycleScenarioService.createOrderInDynamicDataSource(dsKey, orderNo);
        return Result.success("Order [" + orderNo + "] successfully created in datasource [" + dsKey + "]");
    }

    /**
     * 3. 超时优雅排空在途事务并安全注销数据源
     *
     * @param dsKey 数据源标识
     * @param drain 是否等待在途事务连接排空 (默认 true)
     * @return 操作成功结果
     */
    @DeleteMapping("/remove/{dsKey}")
    public Result<String> removeDynamicDataSource(@PathVariable String dsKey,
                                                  @RequestParam(defaultValue = "true") boolean drain) {
        log.info("[LifecycleController] 优雅排空注销数据源 [dsKey={}, drain={}]", dsKey, drain);
        dynamicDatasourceTemplate.removeDataSource(dsKey, drain);
        return Result.success("Datasource [" + dsKey + "] unregistered successfully (drain=" + drain + ")");
    }
}
