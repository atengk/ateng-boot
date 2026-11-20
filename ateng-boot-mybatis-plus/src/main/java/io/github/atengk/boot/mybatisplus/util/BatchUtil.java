package io.github.atengk.boot.mybatisplus.util;

import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.spi.CurrentUserProvider;
import io.github.atengk.boot.core.util.CollectionUtil;
import io.github.atengk.boot.mybatisplus.entity.Auditable;
import io.github.atengk.boot.mybatisplus.entity.Identifiable;
import io.github.atengk.boot.mybatisplus.entity.LogicDeletable;
import io.github.atengk.boot.mybatisplus.entity.TenantAware;
import io.github.atengk.boot.mybatisplus.entity.TimeAuditable;
import io.github.atengk.boot.mybatisplus.entity.Versioned;
import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 原生高性能批量操作门面工具类
 * 提供针对 AtengBaseMapper 的集合切片与智能分批批量插入能力，杜绝单次超大 SQL 导致网络包溢出，
 * 并内置轻量双轨制自愈补偿引擎（Fast-Path + 元数据缓存通道），在分批切片执行前自动为实体补齐
 * 空缺的分布式雪花主键 ID、创建/更新审计时间、操作人、当前租户 ID、乐观锁版本号以及逻辑删除标记。
 *
 * @author Ateng
 * @since 2026-10-16
 */
public final class BatchUtil {

    /**
     * 默认分片切片批次大小（1000）
     */
    public static final int DEFAULT_BATCH_SIZE = 1000;

    /**
     * 默认兜底操作人标识（SYSTEM）
     */
    public static final String DEFAULT_OPERATOR = "SYSTEM";

    /**
     * 当前登录用户 SPI 提取器（支持运行时动态注入）
     */
    private static volatile CurrentUserProvider currentUserProvider;

    /**
     * 是否启用自动审计字段（createTime / updateTime / createBy / updateBy）填充，默认 true
     */
    private static volatile boolean auditEnabled = true;

    /**
     * 全局默认兜底操作人标识（无上下文且需审计填充时使用），默认 SYSTEM
     */
    private static volatile String defaultOperator = DEFAULT_OPERATOR;

    /**
     * 全局默认批量切片批次大小，默认 1000
     */
    private static volatile int defaultBatchSize = DEFAULT_BATCH_SIZE;

    private BatchUtil() {
    }

    /**
     * 动态设置当前操作人提供者 SPI 实例
     *
     * @param provider CurrentUserProvider 实例
     */
    public static void setCurrentUserProvider(CurrentUserProvider provider) {
        currentUserProvider = provider;
    }

    /**
     * 获取当前操作人提供者 SPI 实例
     *
     * @return 当前 CurrentUserProvider 实例
     */
    public static CurrentUserProvider getCurrentUserProvider() {
        return currentUserProvider;
    }

    /**
     * 设置是否启用批量自愈与审计字段自动填充
     *
     * @param enabled 是否启用审计填充
     */
    public static void setAuditEnabled(boolean enabled) {
        auditEnabled = enabled;
    }

    /**
     * 获取当前是否启用审计字段自动填充
     *
     * @return true 表示启用，false 表示跳过审计时间与操作人填充
     */
    public static boolean isAuditEnabled() {
        return auditEnabled;
    }

    /**
     * 设置全局默认兜底操作人标识（传入 null 或空白字符串时回退为 DEFAULT_OPERATOR）
     *
     * @param operator 默认操作人标识
     */
    public static void setDefaultOperator(String operator) {
        defaultOperator = (operator == null || operator.isBlank()) ? DEFAULT_OPERATOR : operator;
    }

    /**
     * 获取当前全局默认兜底操作人标识
     *
     * @return 默认操作人标识
     */
    public static String getDefaultOperator() {
        return defaultOperator;
    }

    /**
     * 设置全局默认批量切片批次大小（传入小于等于 0 的值时回退为 DEFAULT_BATCH_SIZE）
     *
     * @param batchSize 默认批次大小
     */
    public static void setDefaultBatchSize(int batchSize) {
        defaultBatchSize = batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;
    }

    /**
     * 获取当前全局默认批量切片批次大小
     *
     * @return 默认批次大小
     */
    public static int getDefaultBatchSize() {
        return defaultBatchSize;
    }

    /**
     * 原生分批批量插入（采用默认分批大小，默认 1000）
     * 插入前自动对实体集合执行自愈补偿处理
     *
     * @param mapper     实现了 AtengBaseMapper 的持久层 Mapper
     * @param entityList 待批量插入的实体数据集合
     * @param <T>        实体类型
     * @return 累计成功插入影响的总行数
     */
    public static <T> int insertBatch(AtengBaseMapper<T> mapper, Collection<T> entityList) {
        return insertBatch(mapper, entityList, getDefaultBatchSize());
    }

    /**
     * 原生分批批量插入（支持自定义切片批次大小）
     * 在切片分批执行前执行自愈补偿处理，随后将集合按 batchSize 切片并逐批执行 insertBatchSomeColumn 原生多值 SQL 插入
     *
     * @param mapper     实现了 AtengBaseMapper 的持久层 Mapper
     * @param entityList 待批量插入的实体数据集合
     * @param batchSize  单批次最大切片数量（小于等于 0 时自动采用全局默认批次大小）
     * @param <T>        实体类型
     * @return 累计成功插入影响的总行数
     */
    public static <T> int insertBatch(AtengBaseMapper<T> mapper, Collection<T> entityList, int batchSize) {
        if (entityList == null || entityList.isEmpty()) {
            return 0;
        }
        if (mapper == null) {
            throw new IllegalArgumentException("Mapper must not be null");
        }

        // 1. 前置轻量自愈补偿处理
        autoHealAll(entityList);

        // 2. 切片分批执行
        int effectiveBatchSize = batchSize > 0 ? batchSize : getDefaultBatchSize();
        List<T> list = (entityList instanceof List) ? (List<T>) entityList : new ArrayList<>(entityList);

        int affectedRows = 0;
        for (List<T> subList : CollectionUtil.partition(list, effectiveBatchSize)) {
            affectedRows += mapper.insertBatchSomeColumn(subList);
        }

        return affectedRows;
    }

    /**
     * 原生分批批量插入别名方法（采用默认分批大小 1000）
     *
     * @param mapper     实现了 AtengBaseMapper 的持久层 Mapper
     * @param entityList 待批量插入的实体数据集合
     * @param <T>        实体类型
     * @return 累计成功插入影响的总行数
     */
    public static <T> int insertBatchSomeColumn(AtengBaseMapper<T> mapper, Collection<T> entityList) {
        return insertBatch(mapper, entityList, DEFAULT_BATCH_SIZE);
    }

    /**
     * 原生分批批量插入别名方法（支持自定义切片批次大小）
     *
     * @param mapper     实现了 AtengBaseMapper 的持久层 Mapper
     * @param entityList 待批量插入的实体数据集合
     * @param batchSize  单批次最大切片数量（小于等于 0 时自动采用默认值 1000）
     * @param <T>        实体类型
     * @return 累计成功插入影响的总行数
     */
    public static <T> int insertBatchSomeColumn(AtengBaseMapper<T> mapper, Collection<T> entityList, int batchSize) {
        return insertBatch(mapper, entityList, batchSize);
    }

    /**
     * 原生分批批量 Upsert（采用默认分批大小 1000）
     * 执行前自动对实体集合执行自愈补偿处理，底层自适应根据数据库方言生成 ON DUPLICATE KEY UPDATE 或 ON CONFLICT
     *
     * @param mapper     实现了 AtengBaseMapper 的持久层 Mapper
     * @param entityList 待批量 Upsert 的实体数据集合
     * @param <T>        实体类型
     * @return 累计成功操作影响的总行数
     */
    public static <T> int upsertBatch(AtengBaseMapper<T> mapper, Collection<T> entityList) {
        return upsertBatch(mapper, entityList, getDefaultBatchSize());
    }

    /**
     * 原生分批批量 Upsert（支持自定义切片批次大小）
     * 在切片分批执行前执行自愈补偿处理，随后将集合按 batchSize 切片并逐批执行 upsertBatchSomeColumn 原生多值 SQL Upsert
     *
     * @param mapper     实现了 AtengBaseMapper 的持久层 Mapper
     * @param entityList 待批量 Upsert 的实体数据集合
     * @param batchSize  单批次最大切片数量（小于等于 0 时自动采用全局默认批次大小）
     * @param <T>        实体类型
     * @return 累计成功操作影响的总行数
     */
    public static <T> int upsertBatch(AtengBaseMapper<T> mapper, Collection<T> entityList, int batchSize) {
        if (entityList == null || entityList.isEmpty()) {
            return 0;
        }
        if (mapper == null) {
            throw new IllegalArgumentException("Mapper must not be null");
        }

        // 1. 前置轻量自愈补偿处理
        autoHealAll(entityList);

        // 2. 切片分批执行
        int effectiveBatchSize = batchSize > 0 ? batchSize : getDefaultBatchSize();
        List<T> list = (entityList instanceof List) ? (List<T>) entityList : new ArrayList<>(entityList);

        int affectedRows = 0;
        for (List<T> subList : CollectionUtil.partition(list, effectiveBatchSize)) {
            affectedRows += mapper.upsertBatchSomeColumn(subList);
        }

        return affectedRows;
    }

    /**
     * 原生分批批量 Upsert 别名方法（采用默认分批大小）
     *
     * @param mapper     实现了 AtengBaseMapper 的持久层 Mapper
     * @param entityList 待批量 Upsert 的实体数据集合
     * @param <T>        实体类型
     * @return 累计成功操作影响的总行数
     */
    public static <T> int upsertBatchSomeColumn(AtengBaseMapper<T> mapper, Collection<T> entityList) {
        return upsertBatch(mapper, entityList, getDefaultBatchSize());
    }

    /**
     * 原生分批批量 Upsert 别名方法（支持自定义切片批次大小）
     *
     * @param mapper     实现了 AtengBaseMapper 的持久层 Mapper
     * @param entityList 待批量 Upsert 的实体数据集合
     * @param batchSize  单批次最大切片数量（小于等于 0 时自动采用全局默认批次大小）
     * @param <T>        实体类型
     * @return 累计成功操作影响的总行数
     */
    public static <T> int upsertBatchSomeColumn(AtengBaseMapper<T> mapper, Collection<T> entityList, int batchSize) {
        return upsertBatch(mapper, entityList, batchSize);
    }

    /**
     * 原生分批批量更新（采用默认分批大小）
     * 执行前自动对实体集合执行更新审计自愈补偿，并在内存中无条件按主键升序排序以落实防死锁铁律
     *
     * @param mapper     实现了 AtengBaseMapper 的持久层 Mapper
     * @param entityList 待批量更新的实体数据集合
     * @param <T>        实体类型
     * @return 累计成功更新影响的总行数
     */
    public static <T> int updateBatch(AtengBaseMapper<T> mapper, Collection<T> entityList) {
        return updateBatch(mapper, entityList, getDefaultBatchSize());
    }

    /**
     * 原生分批批量更新（支持自定义切片批次大小）
     * 在切片分批执行前执行更新审计自愈补偿与主键正序排列，随后将集合按 batchSize 切片并逐批执行 updateBatchSomeColumn 原生 CASE WHEN 批量更新
     *
     * @param mapper     实现了 AtengBaseMapper 的持久层 Mapper
     * @param entityList 待批量更新的实体数据集合
     * @param batchSize  单批次最大切片数量（小于等于 0 时自动采用全局默认批次大小）
     * @param <T>        实体类型
     * @return 累计成功更新影响的总行数
     */
    public static <T> int updateBatch(AtengBaseMapper<T> mapper, Collection<T> entityList, int batchSize) {
        if (entityList == null || entityList.isEmpty()) {
            return 0;
        }
        if (mapper == null) {
            throw new IllegalArgumentException("Mapper must not be null");
        }

        // 1. 前置更新审计自愈补偿 (updateTime / updateBy)
        autoHealForUpdateAll(entityList);

        // 2. 内存主键正序排列（并发防死锁铁律 Anti-Deadlock Invariant）
        List<T> sortedList = sortByIdAscending(entityList);

        // 3. 切片分批执行
        int effectiveBatchSize = batchSize > 0 ? batchSize : getDefaultBatchSize();
        int affectedRows = 0;
        for (List<T> subList : CollectionUtil.partition(sortedList, effectiveBatchSize)) {
            affectedRows += mapper.updateBatchSomeColumn(subList);
        }

        return affectedRows;
    }

    /**
     * 原生分批批量更新别名方法（采用默认分批大小）
     *
     * @param mapper     实现了 AtengBaseMapper 的持久层 Mapper
     * @param entityList 待批量更新的实体数据集合
     * @param <T>        实体类型
     * @return 累计成功更新影响的总行数
     */
    public static <T> int updateBatchSomeColumn(AtengBaseMapper<T> mapper, Collection<T> entityList) {
        return updateBatch(mapper, entityList, getDefaultBatchSize());
    }

    /**
     * 原生分批批量更新别名方法（支持自定义切片批次大小）
     *
     * @param mapper     实现了 AtengBaseMapper 的持久层 Mapper
     * @param entityList 待批量更新的实体数据集合
     * @param batchSize  单批次最大切片数量（小于等于 0 时自动采用默认值 1000）
     * @param <T>        实体类型
     * @return 累计成功更新影响的总行数
     */
    public static <T> int updateBatchSomeColumn(AtengBaseMapper<T> mapper, Collection<T> entityList, int batchSize) {
        return updateBatch(mapper, entityList, batchSize);
    }

    /**
     * 对实体集合全量执行轻量自愈补偿处理
     *
     * @param entityList 待自愈实体集合
     * @param <T>        实体类型
     */
    public static <T> void autoHealAll(Collection<T> entityList) {
        if (entityList == null || entityList.isEmpty()) {
            return;
        }
        for (T item : entityList) {
            autoHeal(item);
        }
    }

    /**
     * 对单个实体对象执行轻量自愈补偿处理（原地修改实体内存对象）
     * <ol>
     *   <li>主键自愈：实现 {@link Identifiable} 或标注 {@code @TableId} 时按策略自动发号（跳过 {@code AUTO}/{@code INPUT}）；</li>
     *   <li>时间自愈：实现 {@link TimeAuditable} 且 {@code createTime}/{@code updateTime} 为空时注入当前时钟；</li>
     *   <li>操作人自愈：实现 {@link Auditable} 且 {@code createBy}/{@code updateBy} 为空时从上下文注入；</li>
     *   <li>租户自愈：实现 {@link TenantAware} 且 {@code tenantId} 为空时按泛型（{@code Long}/{@code String}）Fail-Fast 注入；</li>
     *   <li>状态流转自愈：实现 {@link Versioned} 补全 {@code version=0}；实现 {@link LogicDeletable} 补全未删除默认值（{@code 0L}/{@code 0}/{@code "0"}/{@code false}）。</li>
     * </ol>
     *
     * @param item 待自愈的实体对象
     * @param <T>  实体类型
     */
    public static <T> void autoHeal(T item) {
        if (item == null) {
            return;
        }

        // 1. 主键自愈 (Fast-Path: Identifiable -> @TableId 元数据缓存通道)
        if (item instanceof Identifiable<?> identifiable) {
            EntityContractHelper.autoAssignId(identifiable);
        } else {
            EntityContractHelper.autoAssignCustomTableId(item);
        }

        // 2. 审计时间自愈 (TimeAuditable，仅在 auditEnabled=true 时执行)
        if (auditEnabled) {
            if (item instanceof TimeAuditable timeAuditable) {
                LocalDateTime now = null;
                if (timeAuditable.getCreateTime() == null) {
                    now = LocalDateTime.now();
                    timeAuditable.setCreateTime(now);
                }
                if (timeAuditable.getUpdateTime() == null) {
                    if (now == null) {
                        now = LocalDateTime.now();
                    }
                    timeAuditable.setUpdateTime(now);
                }
            }

            // 3. 审计操作人自愈 (Auditable)
            if (item instanceof Auditable auditable) {
                String operator = null;
                if (auditable.getCreateBy() == null || auditable.getCreateBy().isBlank()) {
                    operator = resolveCurrentUser();
                    auditable.setCreateBy(operator);
                }
                if (auditable.getUpdateBy() == null || auditable.getUpdateBy().isBlank()) {
                    if (operator == null) {
                        operator = resolveCurrentUser();
                    }
                    auditable.setUpdateBy(operator);
                }
            }
        }

        // 4. 多租户标识自愈 (TenantAware<T>)
        if (item instanceof TenantAware<?> tenantAware) {
            EntityContractHelper.fillTenantIdIfNull(tenantAware);
        }

        // 5. 乐观锁与逻辑删除标记自愈 (Versioned 与 LogicDeletable<T>)
        if (item instanceof Versioned versioned && versioned.getVersion() == null) {
            versioned.setVersion(0);
        }
        if (item instanceof LogicDeletable<?> logicDeletable) {
            EntityContractHelper.fillLogicNotDeletedIfNull(logicDeletable);
        }
    }

    /**
     * 获取实体的唯一主键 ID（支持实现 {@link Identifiable} 与标注 {@code @TableId} 的普通实体）
     *
     * @param item 实体对象
     * @return 主键 ID（实现 Comparable 接口），获取失败或不存在时返回 null
     */
    public static Comparable<?> getEntityId(Object item) {
        return EntityContractHelper.getEntityId(item);
    }

    /**
     * 对实体集合按主键 ID 正序排列（并发防死锁铁律 Anti-Deadlock Invariant）
     * 确保不同并发事务执行批量更新时获取行锁的顺序绝对一致，从源头杜绝行级锁死锁
     *
     * @param entityList 待排序实体集合
     * @param <T>        实体类型
     * @return 按主键升序排列的新实体列表
     */
    /**
     * 主键 ID 升序比较规则（支持 null 保底排前、Comparable 接口比较以及类型不兼容时字符串字典序降级）
     *
     * @param idA 标识 A
     * @param idB 标识 B
     * @return 比较结果
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static int compareIds(Comparable idA, Comparable idB) {
        if (idA == null && idB == null) return 0;
        if (idA == null) return -1;
        if (idB == null) return 1;
        try {
            return idA.compareTo(idB);
        } catch (ClassCastException ignored) {
            return idA.toString().compareTo(idB.toString());
        }
    }

    /**
     * 在内存中强制对批量更新实体集合按主键升序排序
     * 用于物理防御并发事务更新相同记录集不同行顺序导致的死锁（Deadlock）
     *
     * @param entityList 待排序实体集合
     * @param <T>        实体类型
     * @return 按主键升序排列的新集合
     */
    public static <T> List<T> sortByIdAscending(Collection<T> entityList) {
        if (entityList == null || entityList.isEmpty()) {
            return Collections.emptyList();
        }
        List<T> list = new ArrayList<>(entityList);
        list.sort((a, b) -> compareIds(getEntityId(a), getEntityId(b)));
        return list;
    }

    /**
     * 对实体集合全量执行更新前审计自愈补偿处理（仅填充 updateTime 与 updateBy）
     *
     * @param entityList 待自愈实体集合
     * @param <T>        实体类型
     */
    public static <T> void autoHealForUpdateAll(Collection<T> entityList) {
        if (entityList == null || entityList.isEmpty()) {
            return;
        }
        for (T item : entityList) {
            autoHealForUpdate(item);
        }
    }

    /**
     * 对单个实体对象执行更新前轻量自愈补偿处理
     * <p>
     * 仅在 {@code auditEnabled == true} 时执行：强制刷新 {@code updateTime} 为当前系统时钟；
     * 若存在有效上下文操作人（SPI 或 {@link UserContextHolder}），强制刷新 {@code updateBy}，
     * 否则保留实体已有非空 {@code updateBy} 或兜底填充 {@code defaultOperator}。
     * </p>
     *
     * @param item 待自愈的实体对象
     * @param <T>  实体类型
     */
    public static <T> void autoHealForUpdate(T item) {
        if (item == null || !auditEnabled) {
            return;
        }
        if (item instanceof TimeAuditable timeAuditable) {
            timeAuditable.setUpdateTime(LocalDateTime.now());
        }
        if (item instanceof Auditable auditable) {
            String contextUser = resolveContextUserOrNull();
            if (contextUser != null) {
                auditable.setUpdateBy(contextUser);
            } else if (auditable.getUpdateBy() == null || auditable.getUpdateBy().isBlank()) {
                auditable.setUpdateBy(defaultOperator);
            }
        }
    }

    /**
     * 解析当前操作人标识，优先从 SPI 获取，空则回退至 ThreadLocal 内存上下文，最后由配置的默认值兜底
     *
     * @return 当前操作人标识
     */
    private static String resolveCurrentUser() {
        String contextUser = resolveContextUserOrNull();
        return contextUser != null ? contextUser : defaultOperator;
    }

    private static String resolveContextUserOrNull() {
        String user = null;
        if (currentUserProvider != null) {
            try {
                user = currentUserProvider.getCurrentUser();
            } catch (Throwable ignored) {
                // 防御 SPI 抛出异常中断自愈
            }
        }
        if (user == null || user.isBlank()) {
            user = UserContextHolder.get();
        }
        return (user != null && !user.isBlank()) ? user : null;
    }
}
