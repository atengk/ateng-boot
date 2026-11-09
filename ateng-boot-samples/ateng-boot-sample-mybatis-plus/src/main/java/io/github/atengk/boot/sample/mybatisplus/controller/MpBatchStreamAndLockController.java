package io.github.atengk.boot.sample.mybatisplus.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.mybatisplus.entity.ProductSkuEntity;
import io.github.atengk.boot.sample.mybatisplus.entity.TaskQueueEntity;
import io.github.atengk.boot.sample.mybatisplus.model.BatchSkuDTO;
import io.github.atengk.boot.sample.mybatisplus.model.ProductSkuVO;
import io.github.atengk.boot.sample.mybatisplus.model.SkuDeductDTO;
import io.github.atengk.boot.sample.mybatisplus.model.TaskQueueVO;
import io.github.atengk.boot.sample.mybatisplus.service.HighPerfBatchScenarioService;
import io.github.atengk.boot.sample.mybatisplus.service.PessimisticLockScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 原生高性能批处理、流式游标、并发锁与全表防卫业务控制器
 * 对应模块文档第 7 章，演示：
 * 1. BatchUtil / AtengBaseService 原生单条多值批量插入（insertBatch / insertBatchSomeColumn）
 * 2. 双方言原生批量 Upsert 增强（upsertBatch / @ConflictKeys 原地更新）
 * 3. 乱序主键升序重排防死锁批量更新（updateBatch 配合 CASE WHEN 空值保护）
 * 4. StreamingQueryTemplate 受管游标流式分批与 forEachWhile 提前短路消费
 * 5. OptimisticLockTemplate / updateWithOptimisticRetry 乐观锁并发自旋扣减
 * 6. LockMode.SKIP_LOCKED 悲观排队跳锁抢单消费
 * 7. BlockAttackInnerInterceptor 防全表更新删除拦截
 *
 * @author Ateng
 * @since 2026-11-08
 */
@RestController
@RequestMapping("/sample/mybatis-plus/high-perf")
@RequiredArgsConstructor
public class MpBatchStreamAndLockController {

    private final HighPerfBatchScenarioService highPerfService;
    private final PessimisticLockScenarioService pessimisticLockService;

    /**
     * 原生单条多值批量插入商品 SKU
     * 自动补齐雪花主键 ID、创建审计字段，单条 SQL 多值拼接杜绝循环刷屏
     *
     * @param dtoList SKU 数据列表
     * @return 成功插入的商品 SKU 视图列表（零实体泄露）
     */
    @PostMapping("/batch/insert")
    public Result<List<ProductSkuVO>> insertBatch(@Valid @RequestBody List<BatchSkuDTO> dtoList) {
        return Result.success(highPerfService.insertBatchSkus(dtoList));
    }

    /**
     * 双方言原生批量 Upsert 商品 SKU
     * 自适应生成 MySQL ON DUPLICATE KEY UPDATE 或 PostgreSQL @ConflictKeys ON CONFLICT 语法树
     *
     * @param dtoList SKU 数据列表（已存在冲突键则原地更新，不存在则新增）
     * @return 累计受影响总行数
     */
    @PostMapping("/batch/upsert")
    public Result<Integer> upsertBatch(@Valid @RequestBody List<BatchSkuDTO> dtoList) {
        return Result.success(highPerfService.upsertBatchSkus(dtoList));
    }

    /**
     * 乱序主键升序重排防死锁批量更新
     * AntiDeadlockSqlSource 内存中强制按 ID 升序排序，CASE WHEN 动态更新非空字段并保护空值
     *
     * @param dtoList 包含乱序 ID 与选择性更新字段的 SKU 列表
     * @return 更新后的商品 SKU 视图列表
     */
    @PostMapping("/batch/update")
    public Result<List<ProductSkuVO>> updateBatch(@RequestBody List<BatchSkuDTO> dtoList) {
        return Result.success(highPerfService.updateBatchSkus(dtoList));
    }

    /**
     * 受管游标流式分批导出商品 SKU
     * 在受管只读事务中开启游标，分批切片消费，try-with-resources 确定性闭合游标与释放数据库连接防 JVM 堆内存 OOM
     *
     * @param batchSize 单批次切片大小（默认 2）
     * @return 分批切片导出摘要
     */
    @GetMapping("/stream/export")
    public Result<Map<String, Object>> streamExport(@RequestParam(defaultValue = "2") int batchSize) {
        return Result.success(highPerfService.streamExportSkus(batchSize));
    }

    /**
     * 受管游标流式检索（支持 Predicate 提前短路退出）
     *
     * @param limit 最大拉取条数限制（默认 3）
     * @return 提前短路退出的商品 SKU 视图列表
     */
    @GetMapping("/stream/while")
    public Result<List<ProductSkuVO>> streamWhile(@RequestParam(defaultValue = "3") int limit) {
        return Result.success(highPerfService.streamWhileSkus(limit));
    }

    /**
     * 乐观锁并发自旋重试扣减价格
     * 基于 RetryUtil 与 Version 字段进行冲突探测，自动重新加载最新版本并指数退避重试
     *
     * @param deductDTO 扣减请求传输对象
     * @return 扣减成功后的商品 SKU 视图（version 自动自增）
     */
    @PostMapping("/lock/optimistic/deduct")
    public Result<ProductSkuVO> deductSkuPrice(@Valid @RequestBody SkuDeductDTO deductDTO) {
        Long targetSkuId = deductDTO.getSkuId();
        if (targetSkuId == null || targetSkuId <= 0) {
            ProductSkuEntity firstSku = highPerfService.getOne(Wrappers.lambdaQuery(ProductSkuEntity.class).last("LIMIT 1"));
            if (firstSku == null) {
                firstSku = new ProductSkuEntity("SKU_OPT_001", "乐观锁演示商品");
                highPerfService.save(firstSku);
            }
            targetSkuId = firstSku.getId();
        }
        return Result.success(highPerfService.deductSkuPriceWithOptimisticRetry(targetSkuId, deductDTO.getDeductAmount()));
    }

    /**
     * 基于 LockMode.SKIP_LOCKED 悲观排队跳锁抢占消费任务队列
     * 并发事务下自动跳过其他事务已锁定的行，无死锁、无排队阻塞
     *
     * @param limit 单次拉取抢占任务数量上限（默认 2）
     * @return 成功锁定并抢占到的任务视图列表
     */
    @PostMapping("/lock/pessimistic/grab")
    public Result<List<TaskQueueVO>> grabTasksWithSkipLocked(@RequestParam(defaultValue = "2") int limit) {
        if (pessimisticLockService.count(Wrappers.lambdaQuery(TaskQueueEntity.class).eq(TaskQueueEntity::getStatus, "PENDING")) == 0) {
            pessimisticLockService.saveBatch(List.of(
                    new TaskQueueEntity("并发任务_001", "PENDING", 0),
                    new TaskQueueEntity("并发任务_002", "PENDING", 0),
                    new TaskQueueEntity("并发任务_003", "PENDING", 0)
            ));
        }
        List<TaskQueueEntity> tasks = pessimisticLockService.pollAndLockTasks(limit);
        return Result.success(tasks.stream().map(TaskQueueVO::fromEntity).toList());
    }

    /**
     * 基于 getOneForUpdate 条件悲观排他锁查询单个任务（支持 VO 强类型投影）
     *
     * @param taskName 任务名称
     * @return 锁定获取到的任务视图
     */
    @GetMapping("/lock/pessimistic/one")
    public Result<TaskQueueVO> getOneTaskForUpdate(@RequestParam String taskName) {
        TaskQueueVO task = pessimisticLockService.getOneTaskForUpdate(taskName);
        return Result.success(task);
    }

    /**
     * 触发全表无条件更新以演示 BlockAttackInnerInterceptor 防全表更新拦截
     *
     * @return 防御拦截元数据结果
     */
    @PostMapping("/security/block-attack")
    public Result<Map<String, Object>> testBlockAttack() {
        return Result.success(highPerfService.triggerBlockAttackFullTableUpdate());
    }
}
