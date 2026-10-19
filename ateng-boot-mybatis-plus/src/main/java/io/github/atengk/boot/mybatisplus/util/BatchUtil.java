package io.github.atengk.boot.mybatisplus.util;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.spi.CurrentUserProvider;
import io.github.atengk.boot.core.util.CollectionUtil;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.mybatisplus.entity.BaseDeleteIdEntity;
import io.github.atengk.boot.mybatisplus.entity.BaseEntity;
import io.github.atengk.boot.mybatisplus.entity.BaseFullEntity;
import io.github.atengk.boot.mybatisplus.entity.BaseIdEntity;
import io.github.atengk.boot.mybatisplus.entity.BaseTimeEntity;
import io.github.atengk.boot.mybatisplus.entity.TenantEntity;
import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
     * 非基类实体主键元数据反射引用缓存（类 -> TableIdMetadata）
     */
    private static final Map<Class<?>, TableIdMetadata> TABLE_ID_METADATA_CACHE = new ConcurrentHashMap<>();

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
     * 原生分批批量插入（采用默认分批大小 1000）
     * 插入前自动对实体集合执行自愈补偿处理
     *
     * @param mapper     实现了 AtengBaseMapper 的持久层 Mapper
     * @param entityList 待批量插入的实体数据集合
     * @param <T>        实体类型
     * @return 累计成功插入影响的总行数
     */
    public static <T> int insertBatch(AtengBaseMapper<T> mapper, Collection<T> entityList) {
        return insertBatch(mapper, entityList, DEFAULT_BATCH_SIZE);
    }

    /**
     * 原生分批批量插入（支持自定义切片批次大小）
     * 在切片分批执行前执行自愈补偿处理，随后将集合按 batchSize 切片并逐批执行 insertBatchSomeColumn 原生多值 SQL 插入
     *
     * @param mapper     实现了 AtengBaseMapper 的持久层 Mapper
     * @param entityList 待批量插入的实体数据集合
     * @param batchSize  单批次最大切片数量（小于等于 0 时自动采用默认值 1000）
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
        int effectiveBatchSize = batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;
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
        return upsertBatch(mapper, entityList, DEFAULT_BATCH_SIZE);
    }

    /**
     * 原生分批批量 Upsert（支持自定义切片批次大小）
     * 在切片分批执行前执行自愈补偿处理，随后将集合按 batchSize 切片并逐批执行 upsertBatchSomeColumn 原生多值 SQL Upsert
     *
     * @param mapper     实现了 AtengBaseMapper 的持久层 Mapper
     * @param entityList 待批量 Upsert 的实体数据集合
     * @param batchSize  单批次最大切片数量（小于等于 0 时自动采用默认值 1000）
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
        int effectiveBatchSize = batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;
        List<T> list = (entityList instanceof List) ? (List<T>) entityList : new ArrayList<>(entityList);

        int affectedRows = 0;
        for (List<T> subList : CollectionUtil.partition(list, effectiveBatchSize)) {
            affectedRows += mapper.upsertBatchSomeColumn(subList);
        }

        return affectedRows;
    }

    /**
     * 原生分批批量 Upsert 别名方法（采用默认分批大小 1000）
     *
     * @param mapper     实现了 AtengBaseMapper 的持久层 Mapper
     * @param entityList 待批量 Upsert 的实体数据集合
     * @param <T>        实体类型
     * @return 累计成功操作影响的总行数
     */
    public static <T> int upsertBatchSomeColumn(AtengBaseMapper<T> mapper, Collection<T> entityList) {
        return upsertBatch(mapper, entityList, DEFAULT_BATCH_SIZE);
    }

    /**
     * 原生分批批量 Upsert 别名方法（支持自定义切片批次大小）
     *
     * @param mapper     实现了 AtengBaseMapper 的持久层 Mapper
     * @param entityList 待批量 Upsert 的实体数据集合
     * @param batchSize  单批次最大切片数量（小于等于 0 时自动采用默认值 1000）
     * @param <T>        实体类型
     * @return 累计成功操作影响的总行数
     */
    public static <T> int upsertBatchSomeColumn(AtengBaseMapper<T> mapper, Collection<T> entityList, int batchSize) {
        return upsertBatch(mapper, entityList, batchSize);
    }

    /**
     * 原生分批批量更新（采用默认分批大小 1000）
     * 执行前自动对实体集合执行更新审计自愈补偿，并在内存中无条件按主键升序排序以落实防死锁铁律
     *
     * @param mapper     实现了 AtengBaseMapper 的持久层 Mapper
     * @param entityList 待批量更新的实体数据集合
     * @param <T>        实体类型
     * @return 累计成功更新影响的总行数
     */
    public static <T> int updateBatch(AtengBaseMapper<T> mapper, Collection<T> entityList) {
        return updateBatch(mapper, entityList, DEFAULT_BATCH_SIZE);
    }

    /**
     * 原生分批批量更新（支持自定义切片批次大小）
     * 在切片分批执行前执行更新审计自愈补偿与主键正序排列，随后将集合按 batchSize 切片并逐批执行 updateBatchSomeColumn 原生 CASE WHEN 批量更新
     *
     * @param mapper     实现了 AtengBaseMapper 的持久层 Mapper
     * @param entityList 待批量更新的实体数据集合
     * @param batchSize  单批次最大切片数量（小于等于 0 时自动采用默认值 1000）
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
        int effectiveBatchSize = batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;
        int affectedRows = 0;
        for (List<T> subList : CollectionUtil.partition(sortedList, effectiveBatchSize)) {
            affectedRows += mapper.updateBatchSomeColumn(subList);
        }

        return affectedRows;
    }

    /**
     * 原生分批批量更新别名方法（采用默认分批大小 1000）
     *
     * @param mapper     实现了 AtengBaseMapper 的持久层 Mapper
     * @param entityList 待批量更新的实体数据集合
     * @param <T>        实体类型
     * @return 累计成功更新影响的总行数
     */
    public static <T> int updateBatchSomeColumn(AtengBaseMapper<T> mapper, Collection<T> entityList) {
        return updateBatch(mapper, entityList, DEFAULT_BATCH_SIZE);
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
     *   <li>主键自愈：若继承 BaseIdEntity 且 id 为空，自动分配雪花 ID；若为普通实体且标注 ASSIGN_ID，自动通过反射发号回填；</li>
     *   <li>时间自愈：若继承 BaseTimeEntity 且 createTime/updateTime 为空，自动注入当前系统时钟；</li>
     *   <li>操作人自愈：若继承 BaseEntity 且 createBy/updateBy 为空，自动从 CurrentUserProvider / UserContextHolder 注入；</li>
     *   <li>租户自愈：若实现 TenantEntity 且 tenantId 为空且上下文存在租户，自动注入当前租户 ID；</li>
     *   <li>状态流转自愈：若继承 BaseFullEntity，补全 version=1 与 deleted=0；若继承 BaseDeleteIdEntity，补全 version=1 与 deleted=0L。</li>
     * </ol>
     *
     * @param item 待自愈的实体对象
     * @param <T>  实体类型
     */
    public static <T> void autoHeal(T item) {
        if (item == null) {
            return;
        }

        // 1. 主键自愈 (Fast-Path: BaseIdEntity -> 反射元数据缓存通道)
        if (item instanceof BaseIdEntity idEntity) {
            if (idEntity.getId() == null) {
                idEntity.setId(IdUtil.getSnowflakeNextId());
            }
        } else {
            healCustomEntityId(item);
        }

        // 2. 审计时间自愈 (BaseTimeEntity)
        if (item instanceof BaseTimeEntity timeEntity) {
            LocalDateTime now = null;
            if (timeEntity.getCreateTime() == null) {
                now = LocalDateTime.now();
                timeEntity.setCreateTime(now);
            }
            if (timeEntity.getUpdateTime() == null) {
                if (now == null) {
                    now = LocalDateTime.now();
                }
                timeEntity.setUpdateTime(now);
            }
        }

        // 3. 审计操作人自愈 (BaseEntity)
        if (item instanceof BaseEntity baseEntity) {
            String operator = null;
            if (baseEntity.getCreateBy() == null || baseEntity.getCreateBy().isBlank()) {
                operator = resolveCurrentUser();
                baseEntity.setCreateBy(operator);
            }
            if (baseEntity.getUpdateBy() == null || baseEntity.getUpdateBy().isBlank()) {
                if (operator == null) {
                    operator = resolveCurrentUser();
                }
                baseEntity.setUpdateBy(operator);
            }
        }

        // 4. 多租户标识自愈 (TenantEntity)
        if (item instanceof TenantEntity tenantEntity) {
            if (tenantEntity.getTenantId() == null || tenantEntity.getTenantId().isBlank()) {
                String tenantId = TenantContextHolder.getTenantId();
                if (tenantId != null && !tenantId.isBlank()) {
                    tenantEntity.setTenantId(tenantId);
                }
            }
        }

        // 5. 乐观锁与逻辑删除标记自愈 (BaseFullEntity 与 BaseDeleteIdEntity)
        if (item instanceof BaseFullEntity fullEntity) {
            if (fullEntity.getVersion() == null) {
                fullEntity.setVersion(1);
            }
            if (fullEntity.getDeleted() == null) {
                fullEntity.setDeleted(0);
            }
        }
        if (item instanceof BaseDeleteIdEntity deleteIdEntity) {
            if (deleteIdEntity.getVersion() == null) {
                deleteIdEntity.setVersion(1);
            }
            if (deleteIdEntity.getDeleted() == null) {
                deleteIdEntity.setDeleted(0L);
            }
        }
    }

    /**
     * 针对普通实体通过反射元数据缓存通道自愈主键 ID
     *
     * @param item 普通实体对象
     */
    private static void healCustomEntityId(Object item) {
        if (item == null) {
            return;
        }
        TableIdMetadata metadata = TABLE_ID_METADATA_CACHE.computeIfAbsent(item.getClass(), BatchUtil::resolveTableIdMetadata);
        if (metadata.isAssignable()) {
            metadata.assignIdIfNull(item);
        }
    }

    /**
     * 解析普通实体的 @TableId 注解元数据并构建不可变缓存对象
     *
     * @param clazz 实体类 Class
     * @return TableIdMetadata 元数据对象
     */
    private static TableIdMetadata resolveTableIdMetadata(Class<?> clazz) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                TableId tableId = field.getAnnotation(TableId.class);
                if (tableId != null) {
                    field.setAccessible(true);
                    Class<?> fieldType = field.getType();
                    boolean isLong = Long.class.equals(fieldType) || long.class.equals(fieldType);
                    boolean isString = String.class.equals(fieldType);
                    IdType type = tableId.type();
                    boolean assignable = (type == IdType.ASSIGN_ID || type == IdType.NONE) && (isLong || isString);
                    return new TableIdMetadata(field, assignable, isLong, isString);
                }
            }
            current = current.getSuperclass();
        }
        return TableIdMetadata.NON_ASSIGNABLE;
    }

    /**
     * 获取实体的唯一主键 ID（支持继承 BaseIdEntity 与标注 @TableId 的普通实体）
     *
     * @param item 实体对象
     * @return 主键 ID（实现 Comparable 接口），获取失败或不存在时返回 null
     */
    public static Comparable<?> getEntityId(Object item) {
        if (item == null) {
            return null;
        }
        if (item instanceof BaseIdEntity idEntity) {
            return idEntity.getId();
        }
        TableIdMetadata metadata = TABLE_ID_METADATA_CACHE.computeIfAbsent(item.getClass(), BatchUtil::resolveTableIdMetadata);
        return metadata.getId(item);
    }

    /**
     * 对实体集合按主键 ID 正序排列（并发防死锁铁律 Anti-Deadlock Invariant）
     * 确保不同并发事务执行批量更新时获取行锁的顺序绝对一致，从源头杜绝行级锁死锁
     *
     * @param entityList 待排序实体集合
     * @param <T>        实体类型
     * @return 按主键升序排列的新实体列表
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static <T> List<T> sortByIdAscending(Collection<T> entityList) {
        if (entityList == null || entityList.isEmpty()) {
            return Collections.emptyList();
        }
        List<T> list = new ArrayList<>(entityList);
        list.sort((a, b) -> {
            Comparable idA = getEntityId(a);
            Comparable idB = getEntityId(b);
            if (idA == null && idB == null) return 0;
            if (idA == null) return -1;
            if (idB == null) return 1;
            try {
                return idA.compareTo(idB);
            } catch (ClassCastException ignored) {
                return idA.toString().compareTo(idB.toString());
            }
        });
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
     * 对单个实体对象执行更新前轻量自愈补偿处理（仅在 updateTime / updateBy 为空时注入当前系统时钟与操作人）
     *
     * @param item 待自愈的实体对象
     * @param <T>  实体类型
     */
    public static <T> void autoHealForUpdate(T item) {
        if (item == null) {
            return;
        }
        if (item instanceof BaseTimeEntity timeEntity) {
            if (timeEntity.getUpdateTime() == null) {
                timeEntity.setUpdateTime(LocalDateTime.now());
            }
        }
        if (item instanceof BaseEntity baseEntity) {
            if (baseEntity.getUpdateBy() == null || baseEntity.getUpdateBy().isBlank()) {
                baseEntity.setUpdateBy(resolveCurrentUser());
            }
        }
    }

    /**
     * 解析当前操作人标识，优先从 SPI 获取，空则回退至 ThreadLocal 内存上下文，最后由配置的默认值兜底
     *
     * @return 当前操作人标识
     */
    private static String resolveCurrentUser() {
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
        if (user == null || user.isBlank()) {
            user = DEFAULT_OPERATOR;
        }
        return user;
    }

    /**
     * 普通实体 @TableId 反射元数据缓存项
     */
    private static class TableIdMetadata {
        private static final TableIdMetadata NON_ASSIGNABLE = new TableIdMetadata(null, false, false, false);

        private final Field field;
        private final boolean assignable;
        private final boolean isLong;
        private final boolean isString;

        TableIdMetadata(Field field, boolean assignable, boolean isLong, boolean isString) {
            this.field = field;
            this.assignable = assignable;
            this.isLong = isLong;
            this.isString = isString;
        }

        boolean isAssignable() {
            return assignable;
        }

        Comparable<?> getId(Object target) {
            if (field == null || target == null) {
                return null;
            }
            try {
                Object currentVal = field.get(target);
                if (currentVal instanceof Comparable<?> comp) {
                    return comp;
                }
                return currentVal != null ? currentVal.toString() : null;
            } catch (IllegalAccessException ignored) {
                return null;
            }
        }

        void assignIdIfNull(Object target) {
            if (field == null || target == null) {
                return;
            }
            try {
                Object currentVal = field.get(target);
                if (currentVal == null) {
                    if (isLong) {
                        field.set(target, IdUtil.getSnowflakeNextId());
                    } else if (isString) {
                        field.set(target, IdUtil.getSnowflakeNextIdStr());
                    }
                }
            } catch (IllegalAccessException ignored) {
                // 防御安全管理器与非法访问
            }
        }
    }
}
