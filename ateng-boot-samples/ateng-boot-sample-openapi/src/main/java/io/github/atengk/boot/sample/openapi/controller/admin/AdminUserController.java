package io.github.atengk.boot.sample.openapi.controller.admin;

import io.github.atengk.boot.core.annotation.InEnum;
import io.github.atengk.boot.core.util.EnumUtil;
import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.validation.ValidList;
import io.github.atengk.boot.sample.openapi.model.AdminUserCreateRequest;
import io.github.atengk.boot.sample.openapi.model.AdminUserVO;
import io.github.atengk.boot.sample.openapi.model.UserStatusEnum;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;

/**
 * 运营管理端 - 用户治理控制器
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Tag(name = "运营管理端 - 用户治理", description = "面向后台管理系统管理员的用户创建、明细查询与列表治理接口")
@Validated
@RestController
@RequestMapping("/sample/openapi/admin/users")
public class AdminUserController {

    /**
     * 创建系统用户
     *
     * @param request 创建参数
     * @return 统一响应包装的新建用户视图
     */
    @Operation(summary = "创建系统用户", description = "校验账号格式、邮箱与 @InEnum 状态编码，成功后初始化并返回用户明细")
    @PostMapping
    public Result<AdminUserVO> createUser(@Valid @RequestBody AdminUserCreateRequest request) {
        UserStatusEnum status = request.getStatusCode() != null
                ? EnumUtil.fromCode(UserStatusEnum.class, request.getStatusCode()).orElse(UserStatusEnum.ENABLE)
                : UserStatusEnum.ENABLE;
        AdminUserVO user = AdminUserVO.of(
                10001L,
                request.getUsername(),
                request.getRealName(),
                request.getEmail(),
                "13800138000",
                status
        );
        return Result.success(user);
    }

    /**
     * 批量创建系统用户（演示 ValidList 原生 Array 契约）
     *
     * @param requestList 批量创建参数列表
     * @return 创建条数
     */
    @Operation(summary = "批量创建系统用户", description = "批量校验入参并初始化多个系统用户，支持 ValidList 原生 JSON 数组契约")
    @PostMapping("/batch")
    public Result<Integer> batchCreateUsers(@Valid @RequestBody ValidList<AdminUserCreateRequest> requestList) {
        return Result.success(requestList != null ? requestList.size() : 0);
    }

    /**
     * 根据用户 ID 获取用户详情
     *
     * @param id 用户主键
     * @return 用户明细
     */
    @Operation(summary = "查询用户详情", description = "根据用户主键 ID 检索基础信息与状态")
    @GetMapping("/{id}")
    public Result<AdminUserVO> getUserById(@Parameter(description = "用户唯一 ID", example = "10001") @PathVariable("id") Long id) {
        AdminUserVO user = AdminUserVO.of(id, "admin_dev", "系统管理员");
        return Result.success(user);
    }

    /**
     * 查询用户列表
     *
     * @return 用户列表数据
     */
    @Operation(summary = "查询系统用户全量列表", description = "检索全量受管用户简要信息")
    @GetMapping
    public Result<List<AdminUserVO>> listUsers() {
        List<AdminUserVO> list = List.of(
                AdminUserVO.of(10001L, "admin_dev", "系统管理员"),
                AdminUserVO.of(10002L, "operator", "普通运营人员", "operator@example.com", "13900139000")
        );
        return Result.success(list);
    }

    /**
     * 分页查询系统用户列表（演示 PageQuery / PageResult 与方法参数 @InEnum 强契约）
     *
     * @param pageQuery 分页查询参数
     * @param status    账号状态编码筛选
     * @return 标准分页结果
     */
    @Operation(summary = "分页查询系统用户", description = "使用 Core 标准 PageQuery 与 PageResult，并演示控制器参数 @InEnum 强类型枚举筛选")
    @GetMapping("/page")
    public Result<PageResult<AdminUserVO>> pageUsers(
            @Valid @ParameterObject PageQuery pageQuery,
            @Parameter(description = "账号状态编码筛选")
            @InEnum(value = UserStatusEnum.class, message = "筛选状态编码不合法")
            @RequestParam(value = "status", required = false) Integer status) {
        List<AdminUserVO> allUsers = List.of(
                AdminUserVO.of(10001L, "admin_dev", "系统管理员", "admin_dev@example.com", "13800138000", UserStatusEnum.ENABLE),
                AdminUserVO.of(10002L, "operator", "普通运营人员", "operator@example.com", "13900139000", UserStatusEnum.ENABLE),
                AdminUserVO.of(10003L, "auditor", "风控审计员", "auditor@example.com", "13700137000", UserStatusEnum.DISABLE)
        );
        List<AdminUserVO> filtered = allUsers.stream()
                .filter(u -> status == null || (u.getStatus() != null && status.equals(u.getStatus().getCode())))
                .toList();

        long current = pageQuery != null ? pageQuery.safeCurrent() : 1L;
        long size = pageQuery != null ? pageQuery.safeSize() : 10L;
        long offset = pageQuery != null ? pageQuery.offset() : 0L;
        if (offset >= filtered.size()) {
            return Result.success(PageResult.of(Collections.emptyList(), filtered.size(), current, size));
        }
        int fromIndex = (int) offset;
        int toIndex = (int) Math.min(filtered.size(), offset + size);
        return Result.success(PageResult.of(filtered.subList(fromIndex, toIndex), filtered.size(), current, size));
    }
}
