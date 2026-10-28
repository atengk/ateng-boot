package io.github.atengk.boot.sample.satoken.controller;

import io.github.atengk.boot.core.model.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 动态 URL 路径权限匹配示例控制器
 * 接口自身无需标注 @SaCheckPermission，由拦截器根据 DynamicPathPermissionProvider 动态进行 AntPath 与 Method 鉴权
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Slf4j
@RestController
@RequestMapping("/api/dynamic/resource")
public class SampleDynamicPermissionController {

    /**
     * 动态权限查询接口（需 dynamic:resource:view 权限）
     *
     * @return 业务数据
     */
    @GetMapping("/data")
    public Result<String> getData() {
        return Result.success("动态资源查询成功");
    }

    /**
     * 动态权限创建接口（需 dynamic:resource:create 权限）
     *
     * @return 业务数据
     */
    @PostMapping("/data")
    public Result<String> createData() {
        return Result.success("动态资源创建成功");
    }

    /**
     * 动态权限删除接口（需 dynamic:resource:delete AND dynamic:resource:audit 双权限）
     *
     * @return 业务数据
     */
    @DeleteMapping("/data")
    public Result<String> deleteData() {
        return Result.success("动态资源删除成功");
    }

    /**
     * 未受动态权限规则约束的端点（直接通行）
     *
     * @return 业务数据
     */
    @GetMapping("/unprotected")
    public Result<String> unprotected() {
        return Result.success("未受规则约束的公开资源");
    }
}
