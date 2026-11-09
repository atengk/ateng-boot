package io.github.atengk.boot.sample.mybatisplus.controller;

import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.mybatisplus.entity.UserEntity;
import io.github.atengk.boot.sample.mybatisplus.model.ProductSkuVO;
import io.github.atengk.boot.sample.mybatisplus.model.UserCreateDTO;
import io.github.atengk.boot.sample.mybatisplus.model.UserVO;
import io.github.atengk.boot.sample.mybatisplus.service.SoftDeleteUniqueKeyScenarioService;
import io.github.atengk.boot.sample.mybatisplus.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
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
 * 实体五级继承阶梯、生命周期自动填充与逻辑删除闭环体验控制器
 * 对应模块文档第 3 章，演示雪花发号、DefaultMetaObjectHandler 审计填充、
 * BaseDeleteIdEntity 联合唯一索引防冲突软删除以及 AtengLogicDeleteInnerInterceptor 回收站穿透与恢复闭环。
 *
 * @author Ateng
 * @since 2026-11-08
 */
@RestController
@RequestMapping("/sample/mybatis-plus/lifecycle")
@RequiredArgsConstructor
public class MpEntityLifecycleController {

    private final UserService userService;
    private final SoftDeleteUniqueKeyScenarioService softDeleteService;

    /**
     * 创建用户并触发雪花主键发号与审计元数据自动填充
     * 支持通过 operator 模拟当前登录上下文，或通过 explicitCreateBy 验证防御性不覆盖已有值
     *
     * @param dto 创建用户请求载荷
     * @return 填充完毕并脱敏输出的用户视图对象
     */
    @PostMapping("/user")
    public Result<UserVO> createUser(@Valid @RequestBody UserCreateDTO dto) {
        boolean boundContext = StringUtils.hasText(dto.getOperator());
        if (boundContext) {
            UserContextHolder.set(dto.getOperator());
        }
        try {
            UserEntity entity = new UserEntity(
                    dto.getName(),
                    dto.getAge(),
                    dto.getEmail(),
                    dto.getPhone(),
                    dto.getDeptId() != null ? dto.getDeptId() : 100L
            );
            if (StringUtils.hasText(dto.getExplicitCreateBy())) {
                entity.setCreateBy(dto.getExplicitCreateBy());
            }
            userService.save(entity);
            UserEntity saved = userService.getById(entity.getId());
            return Result.success(UserVO.fromEntity(saved));
        } finally {
            if (boundContext) {
                UserContextHolder.clear();
            }
        }
    }

    /**
     * 查询有效用户列表（若当前表为空，按需幂等初始化 2 条演示种子数据，保障零前置 cURL 体验）
     *
     * @return 用户安全视图列表
     */
    @GetMapping("/users")
    public Result<List<UserVO>> listUsers() {
        if (userService.count() == 0L) {
            userService.save(new UserEntity("张三", 28, "zhangsan@example.com", "13812345678", 100L));
            userService.save(new UserEntity("李四", 32, "lisi@example.com", "13987654321", 200L));
        }
        List<UserEntity> entities = userService.list();
        if (entities == null || entities.isEmpty()) {
            return Result.success(Collections.emptyList());
        }
        return Result.success(entities.stream().map(UserVO::fromEntity).toList());
    }

    /**
     * 演示商品 SKU 联合唯一索引 (sku_code, deleted) 在多次“新增 -> 软删除 -> 同编码重建”闭环中零物理冲突
     *
     * @param skuCode 商品 SKU 唯一编码
     * @return 包含历史已软删除记录与最新活跃记录的 SKU 视图列表
     */
    @PostMapping("/sku/cycle-soft-delete")
    public Result<List<ProductSkuVO>> cycleSoftDelete(
            @RequestParam(defaultValue = "SKU-999") String skuCode) {
        return Result.success(softDeleteService.executeCycleSoftDelete(skuCode));
    }

    /**
     * 分页查询商品 SKU 回收站全量视图（依托 pageWithDeleted 穿透逻辑删除，同时展示正常与已删除商品）
     *
     * @param pageQuery 标准分页查询契约
     * @return 商品 SKU 分页视图结果
     */
    @GetMapping("/sku/recycle-bin")
    public Result<PageResult<ProductSkuVO>> pageSkuRecycleBin(PageQuery pageQuery) {
        return Result.success(softDeleteService.pageRecycleBin(pageQuery));
    }

    /**
     * 按主键 ID 恢复已逻辑删除的商品 SKU 记录，并自动刷新 updateTime 与 updateBy 审计字段
     *
     * @param id       商品 SKU 主键 ID
     * @param operator 执行恢复的操作人标识
     * @return 恢复后的商品 SKU 视图对象
     */
    @PostMapping("/sku/{id}/restore")
    public Result<ProductSkuVO> restoreSkuById(
            @PathVariable Long id,
            @RequestParam(defaultValue = "admin-restorer") String operator) {
        return Result.success(softDeleteService.restoreSkuById(id, operator));
    }

    /**
     * 按商品 SKU 编码条件批量恢复逻辑删除记录（若传入空条件将触发底层防全表误恢复卫语句拦截）
     *
     * @param skuCode 商品 SKU 编码（留空则触发防全表误恢复拦截演示）
     * @return 成功恢复的记录行数
     */
    @PostMapping("/sku/restore-by-code")
    public Result<Integer> restoreSkuByCode(@RequestParam(required = false) String skuCode) {
        return Result.success(softDeleteService.restoreSkuByCode(skuCode));
    }

    /**
     * 统计商品 SKU 回收站全量记录数（依托 countWithDeleted 穿透逻辑删除，包含正常与已删除记录）
     *
     * @return 回收站记录总数
     */
    @GetMapping("/sku/recycle-bin/count")
    public Result<Long> countSkuRecycleBin() {
        return Result.success(softDeleteService.countRecycleBin());
    }

    /**
     * 基于强类型 VO 投影分页查询商品 SKU 回收站视图（依托 pageWithDeleted 零实体泄露）
     *
     * @param pageQuery 标准分页查询契约
     * @return 商品 SKU 分页视图结果
     */
    @GetMapping("/sku/recycle-bin/vo-page")
    public Result<PageResult<ProductSkuVO>> pageSkuRecycleBinVO(PageQuery pageQuery) {
        return Result.success(softDeleteService.pageRecycleBinVO(pageQuery));
    }

    /**
     * 按主键 ID 物理彻底硬删除商品 SKU 记录（无视 @TableLogic，直接执行物理 DELETE FROM 语句）
     *
     * @param id 商品 SKU 主键 ID
     * @return 是否彻底物理清除成功
     */
    @PostMapping("/sku/{id}/purge")
    public Result<Boolean> purgeSkuPhysically(@PathVariable Long id) {
        return Result.success(softDeleteService.purgeSkuPhysically(id));
    }
}
