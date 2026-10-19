package io.github.atengk.boot.sample.controller;

import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryRequest;
import io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryTemplate;
import io.github.atengk.boot.mybatisplus.dynamicsql.DynamicSqlExecutor;
import io.github.atengk.boot.sample.entity.SysDocumentEntity;
import io.github.atengk.boot.sample.entity.TenantOrderEntity;
import io.github.atengk.boot.sample.service.UserService;
import io.github.atengk.boot.sample.vo.UserRoleVO;
import io.github.atengk.boot.sample.vo.UserVO;
import io.github.atengk.boot.web.annotation.WebLog;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 动态查询转译与多表联表直出示例控制层
 * 显式遵循 Result 契约与接入层薄层原则，调度 Service、DynamicQueryTemplate 与 DynamicSqlExecutor
 *
 * @author Ateng
 * @since 2026-10-19
 */
@RestController
@RequestMapping("/api/dynamic-query")
@RequiredArgsConstructor
public class DynamicQuerySampleController {

    private final UserService userService;
    private final DynamicQueryTemplate dynamicQueryTemplate;
    private final org.springframework.beans.factory.ObjectProvider<DynamicSqlExecutor> dynamicSqlExecutorProvider;

    /**
     * 1. 单表动态查询直出端点
     *
     * @param request 动态查询请求契约
     * @return 分页用户视图结果
     */
    @PostMapping("/users")
    @WebLog(value = "单表动态查询用户列表", printParams = true, printCostTime = true)
    public Result<PageResult<UserVO>> queryUsers(@Valid @RequestBody DynamicQueryRequest request) {
        return Result.success(userService.pageUsersDynamic(request));
    }

    /**
     * 2. Mapper XML 联表方法引用动态查询直出端点
     *
     * @param request 动态查询请求契约
     * @return 分页用户角色视图结果
     */
    @PostMapping("/user-roles/xml")
    @WebLog(value = "XML 联表方法引用动态查询用户角色列表", printParams = true, printCostTime = true)
    public Result<PageResult<UserRoleVO>> queryUserRolesXml(@Valid @RequestBody DynamicQueryRequest request) {
        return Result.success(userService.pageUserRolesXml(request));
    }

    /**
     * 3. 外部动态 SQL 动态查询直出端点
     *
     * @param request 动态查询请求契约
     * @return 分页用户角色视图结果
     */
    @PostMapping("/user-roles/sql")
    @WebLog(value = "外部动态 SQL 动态查询用户角色列表", printParams = true, printCostTime = true)
    public Result<PageResult<UserRoleVO>> queryUserRolesSql(@Valid @RequestBody DynamicQueryRequest request) {
        DynamicSqlExecutor executor = dynamicSqlExecutorProvider.getIfAvailable();
        io.github.atengk.boot.core.util.BusinessAssert.notNull(executor, "DynamicSqlExecutor 未启用，请配置 ateng.boot.mybatis-plus.dynamic-sql.enabled=true");
        return Result.success(executor.selectPage("queryExternalUserRole", request, UserRoleVO.class));
    }

    /**
     * 4. @DataScope 数据权限协同动态查询端点
     *
     * @param request 动态查询请求契约
     * @return 分页文档结果
     */
    @PostMapping("/documents/scope")
    @WebLog(value = "数据权限协同动态查询文档列表", printParams = true, printCostTime = true)
    public Result<PageResult<SysDocumentEntity>> queryDocumentsWithScope(@Valid @RequestBody DynamicQueryRequest request) {
        return Result.success(dynamicQueryTemplate.selectPage("io.github.atengk.boot.sample.mapper.SysDocumentMapper.selectPageWithScope", request, SysDocumentEntity.class));
    }

    /**
     * 5. 多租户行级隔离协同动态查询端点
     *
     * @param request 动态查询请求契约
     * @return 分页订单结果
     */
    @PostMapping("/tenant-orders")
    @WebLog(value = "多租户行级隔离协同动态查询订单列表", printParams = true, printCostTime = true)
    public Result<PageResult<TenantOrderEntity>> queryTenantOrders(@Valid @RequestBody DynamicQueryRequest request) {
        return Result.success(dynamicQueryTemplate.selectPage(request, TenantOrderEntity.class));
    }
}
