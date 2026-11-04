package io.github.atengk.boot.milvus.model;

import io.github.atengk.boot.core.util.StringUtil;
import io.github.atengk.boot.milvus.annotation.MilvusCollection;
import io.github.atengk.boot.milvus.annotation.MilvusField;
import io.github.atengk.boot.milvus.annotation.MilvusId;
import io.github.atengk.boot.milvus.annotation.MilvusPartitionKey;
import io.github.atengk.boot.milvus.annotation.MilvusVector;
import io.github.atengk.boot.milvus.exception.MilvusException;
import io.github.atengk.boot.milvus.exception.MilvusResultCode;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import lombok.Builder;
import lombok.Getter;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Milvus 实体模型元数据定义与解析器
 *
 * @author Ateng
 * @since 2026-11-04
 */
@Getter
@Builder
public class MilvusEntityMetadata {

    private static final Map<Class<?>, MilvusEntityMetadata> METADATA_CACHE = new ConcurrentHashMap<>();

    private final Class<?> entityClass;
    private final String collectionName;
    private final String description;
    private final boolean enableDynamicField;
    private final int numShards;

    private final FieldMetadata idField;
    private final FieldMetadata vectorField;
    private final List<FieldMetadata> vectorFields;
    private final FieldMetadata partitionKeyField;
    private final List<FieldMetadata> scalarFields;
    private final List<FieldMetadata> allFields;

    /**
     * 单个字段属性元数据
     */
    @Getter
    @Builder
    public static class FieldMetadata {
        private final Field field;
        private final String fieldName;
        private final DataType dataType;
        private final boolean isPrimaryKey;
        private final boolean isPartitionKey;
        private final boolean isVector;
        private final boolean autoId;
        private final int maxLength;
        private final int dimension;
        private final IndexParam.MetricType metricType;
        private final IndexParam.IndexType indexType;
        private final String indexName;
        private final String description;

        public Object getFieldValue(Object target) {
            try {
                field.setAccessible(true);
                return field.get(target);
            } catch (IllegalAccessException e) {
                throw new MilvusException("读取实体属性值失败: " + field.getName(), e);
            }
        }

        public void setFieldValue(Object target, Object value) {
            try {
                field.setAccessible(true);
                field.set(target, value);
            } catch (IllegalAccessException e) {
                throw new MilvusException("写入实体属性值失败: " + field.getName(), e);
            }
        }

        /**
         * 将字段元数据转换为 MilvusClientV2 标准 FieldSchema
         *
         * @return FieldSchema 实例
         */
        public CreateCollectionReq.FieldSchema toFieldSchema() {
            CreateCollectionReq.FieldSchema.FieldSchemaBuilder<?, ?> fsBuilder = CreateCollectionReq.FieldSchema.builder()
                    .name(fieldName)
                    .dataType(dataType)
                    .description(description)
                    .isPrimaryKey(isPrimaryKey)
                    .isPartitionKey(isPartitionKey)
                    .autoID(autoId);

            if (isVector && dataType != DataType.SparseFloatVector && dimension > 0) {
                fsBuilder.dimension(dimension);
            }
            if (dataType == DataType.VarChar) {
                fsBuilder.maxLength(maxLength > 0 ? maxLength : 65535);
            }

            return fsBuilder.build();
        }
    }

    /**
     * 解析并缓存目标实体类元数据
     *
     * @param entityClass 实体类
     * @return 实体元数据实例
     */
    public static MilvusEntityMetadata of(Class<?> entityClass) {
        Assert.notNull(entityClass, "entityClass must not be null");
        return METADATA_CACHE.computeIfAbsent(entityClass, MilvusEntityMetadata::parseInternal);
    }

    private static MilvusEntityMetadata parseInternal(Class<?> clazz) {
        MilvusCollection collAnn = clazz.getAnnotation(MilvusCollection.class);
        String collectionName = (collAnn != null && !collAnn.name().isBlank())
                ? collAnn.name().trim()
                : StringUtil.toUnderlineCase(clazz.getSimpleName());
        String description = (collAnn != null) ? collAnn.description() : "";
        boolean enableDynamicField = (collAnn == null) || collAnn.enableDynamicField();
        int numShards = (collAnn != null) ? collAnn.numShards() : 1;

        FieldMetadata idMetadata = null;
        FieldMetadata vectorMetadata = null;
        List<FieldMetadata> vectorFields = new ArrayList<>();
        FieldMetadata partitionKeyMetadata = null;
        List<FieldMetadata> scalars = new ArrayList<>();
        List<FieldMetadata> all = new ArrayList<>();

        for (Field field : getDeclaredFieldsRecursively(clazz)) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) {
                continue;
            }

            MilvusId idAnn = field.getAnnotation(MilvusId.class);
            MilvusVector vectorAnn = field.getAnnotation(MilvusVector.class);
            MilvusPartitionKey partitionKeyAnn = field.getAnnotation(MilvusPartitionKey.class);
            MilvusField fieldAnn = field.getAnnotation(MilvusField.class);

            if (idAnn != null) {
                if (idMetadata != null) {
                    throw new MilvusException("实体类 [" + clazz.getName() + "] 存在多个 @MilvusId 主键字段");
                }
                String name = !idAnn.name().isBlank() ? idAnn.name().trim() : field.getName();
                DataType dataType = inferDataType(field.getType(), DataType.None);
                if (dataType != DataType.Int64 && dataType != DataType.VarChar) {
                    throw new MilvusException("实体主键字段 [" + field.getName() + "] 类型只支持 Long (Int64) 或 String (VarChar)");
                }
                idMetadata = FieldMetadata.builder()
                        .field(field)
                        .fieldName(name)
                        .dataType(dataType)
                        .isPrimaryKey(true)
                        .isPartitionKey(false)
                        .isVector(false)
                        .autoId(idAnn.autoId())
                        .maxLength(idAnn.maxLength())
                        .dimension(0)
                        .description(idAnn.description())
                        .build();
                all.add(idMetadata);
            } else if (vectorAnn != null) {
                String name = !vectorAnn.name().isBlank() ? vectorAnn.name().trim() : field.getName();
                DataType dataType = inferVectorDataType(field.getType());
                IndexParam.MetricType metricType = vectorAnn.metricType();
                IndexParam.IndexType indexType = vectorAnn.indexType();
                int dim = vectorAnn.dimension();

                if (dataType == DataType.SparseFloatVector) {
                    metricType = IndexParam.MetricType.IP;
                    if (indexType == IndexParam.IndexType.AUTOINDEX || indexType == IndexParam.IndexType.None) {
                        indexType = IndexParam.IndexType.SPARSE_INVERTED_INDEX;
                    }
                    dim = -1;
                } else {
                    if (dim <= 0) {
                        throw new MilvusException("实体类 [" + clazz.getName() + "] 稠密向量字段 [" + field.getName() + "] 必须配置有效维度 dimension > 0");
                    }
                }

                FieldMetadata vm = FieldMetadata.builder()
                        .field(field)
                        .fieldName(name)
                        .dataType(dataType)
                        .isPrimaryKey(false)
                        .isPartitionKey(false)
                        .isVector(true)
                        .autoId(false)
                        .maxLength(0)
                        .dimension(dim)
                        .metricType(metricType)
                        .indexType(indexType)
                        .indexName("idx_" + name)
                        .description(vectorAnn.description())
                        .build();
                vectorFields.add(vm);
                if (vectorMetadata == null) {
                    vectorMetadata = vm;
                }
                all.add(vm);
            } else if (partitionKeyAnn != null) {
                if (partitionKeyMetadata != null) {
                    throw new MilvusException("实体类 [" + clazz.getName() + "] 仅支持配置一个分区键字段 @MilvusPartitionKey");
                }
                String name = !partitionKeyAnn.name().isBlank() ? partitionKeyAnn.name().trim() : field.getName();
                DataType dataType = inferDataType(field.getType(), DataType.None);
                partitionKeyMetadata = FieldMetadata.builder()
                        .field(field)
                        .fieldName(name)
                        .dataType(dataType)
                        .isPrimaryKey(false)
                        .isPartitionKey(true)
                        .isVector(false)
                        .autoId(false)
                        .maxLength(partitionKeyAnn.maxLength())
                        .dimension(0)
                        .description(partitionKeyAnn.description())
                        .build();
                all.add(partitionKeyMetadata);
            } else {
                String name = (fieldAnn != null && !fieldAnn.name().isBlank()) ? fieldAnn.name().trim() : field.getName();
                DataType dataType = inferDataType(field.getType(), fieldAnn != null ? fieldAnn.dataType() : DataType.None);
                int maxLength = (fieldAnn != null) ? fieldAnn.maxLength() : 65535;
                String desc = (fieldAnn != null) ? fieldAnn.description() : "";
                IndexParam.IndexType indexType = (fieldAnn != null) ? fieldAnn.indexType() : IndexParam.IndexType.None;
                String indexName = (fieldAnn != null && StringUtils.hasText(fieldAnn.indexName()))
                        ? fieldAnn.indexName().trim()
                        : (indexType != IndexParam.IndexType.None ? "idx_" + name : "");

                FieldMetadata scalar = FieldMetadata.builder()
                        .field(field)
                        .fieldName(name)
                        .dataType(dataType)
                        .isPrimaryKey(false)
                        .isPartitionKey(false)
                        .isVector(false)
                        .autoId(false)
                        .maxLength(maxLength)
                        .dimension(0)
                        .indexType(indexType)
                        .indexName(indexName)
                        .description(desc)
                        .build();
                scalars.add(scalar);
                all.add(scalar);
            }
        }

        if (idMetadata == null) {
            throw new MilvusException(MilvusResultCode.OPERATION_FAILED,
                    "实体类 [" + clazz.getName() + "] 必须通过 @MilvusId 显式指定主键字段");
        }
        if (vectorFields.isEmpty()) {
            throw new MilvusException(MilvusResultCode.OPERATION_FAILED,
                    "实体类 [" + clazz.getName() + "] 必须通过 @MilvusVector 显式指定向量字段");
        }

        return MilvusEntityMetadata.builder()
                .entityClass(clazz)
                .collectionName(collectionName)
                .description(description)
                .enableDynamicField(enableDynamicField)
                .numShards(numShards)
                .idField(idMetadata)
                .vectorField(vectorMetadata)
                .vectorFields(Collections.unmodifiableList(vectorFields))
                .partitionKeyField(partitionKeyMetadata)
                .scalarFields(Collections.unmodifiableList(scalars))
                .allFields(Collections.unmodifiableList(all))
                .build();
    }

    /**
     * 获取当前实体映射的所有 Milvus 列名列表 (用于检索与点查出参 outputFields 投影)
     *
     * @return 字段列名列表
     */
    public List<String> getFieldNames() {
        return allFields.stream()
                .map(FieldMetadata::getFieldName)
                .collect(Collectors.toList());
    }

    /**
     * 将本实体元数据转换为 MilvusClientV2 标准创建集合请求对象
     *
     * @return CreateCollectionReq 请求建造者产物
     */
    public CreateCollectionReq toCreateCollectionReq() {
        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                .build();

        List<CreateCollectionReq.FieldSchema> fieldSchemaList = allFields.stream()
                .map(FieldMetadata::toFieldSchema)
                .collect(Collectors.toList());

        schema.setFieldSchemaList(fieldSchemaList);

        CreateCollectionReq.CreateCollectionReqBuilder<?, ?> reqBuilder = CreateCollectionReq.builder()
                .collectionName(collectionName)
                .description(description)
                .numShards(numShards)
                .enableDynamicField(enableDynamicField)
                .collectionSchema(schema);

        return reqBuilder.build();
    }

    private static DataType inferDataType(Class<?> type, DataType explicit) {
        if (explicit != null && explicit != DataType.None) {
            return explicit;
        }
        if (type == Long.class || type == long.class) {
            return DataType.Int64;
        }
        if (type == Integer.class || type == int.class) {
            return DataType.Int32;
        }
        if (type == Short.class || type == short.class) {
            return DataType.Int16;
        }
        if (type == Byte.class || type == byte.class) {
            return DataType.Int8;
        }
        if (type == Boolean.class || type == boolean.class) {
            return DataType.Bool;
        }
        if (type == Float.class || type == float.class) {
            return DataType.Float;
        }
        if (type == Double.class || type == double.class) {
            return DataType.Double;
        }
        if (type == String.class) {
            return DataType.VarChar;
        }
        if (Map.class.isAssignableFrom(type)) {
            return DataType.JSON;
        }
        if (List.class.isAssignableFrom(type) || type.isArray()) {
            return DataType.Array;
        }
        return DataType.VarChar;
    }

    private static DataType inferVectorDataType(Class<?> type) {
        if (type == ByteBuffer.class) {
            return DataType.BinaryVector;
        }
        if (Map.class.isAssignableFrom(type)) {
            return DataType.SparseFloatVector;
        }
        return DataType.FloatVector;
    }

    private static List<Field> getDeclaredFieldsRecursively(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            Collections.addAll(fields, current.getDeclaredFields());
            current = current.getSuperclass();
        }
        return fields;
    }
}
