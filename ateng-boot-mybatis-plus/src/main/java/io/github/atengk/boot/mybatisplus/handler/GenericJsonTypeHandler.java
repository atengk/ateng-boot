package io.github.atengk.boot.mybatisplus.handler;

import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * 泛型增强型 JSON 字段类型转换抽象基类
 * 支持通过子类继承携带具体泛型参数（如 extends GenericJsonTypeHandler&lt;List&lt;OrderItemVO&gt;&gt;）
 * 或传入 TypeReference 精确解析复杂嵌套泛型类型，内置空集合保底与 Spring ObjectMapper 自动绑定。
 *
 * @param <T> 实体属性目标泛型类型
 * @author Ateng
 * @since 2026-10-27
 */
@MappedJdbcTypes(value = {JdbcType.VARCHAR, JdbcType.OTHER}, includeNullJdbcType = true)
@MappedTypes({Object.class})
public abstract class GenericJsonTypeHandler<T> extends AtengJacksonTypeHandler<T> {

    public GenericJsonTypeHandler() {
        super(Object.class);
        Type genType = findGenericType(getClass());
        this.targetType = genType;
        this.genericType = genType;
    }

    public GenericJsonTypeHandler(Class<?> type) {
        super(type);
    }

    public GenericJsonTypeHandler(Class<?> type, Field field) {
        super(type, field);
    }

    public GenericJsonTypeHandler(TypeReference<T> typeReference) {
        super(Object.class);
        Type genType = (typeReference != null) ? typeReference.getType() : Object.class;
        this.targetType = genType;
        this.genericType = genType;
    }

    /**
     * 递归向上溯源解析当前类继承层次中声明的具体泛型实参
     *
     * @param clazz 待解析的具体子类 Class
     * @return 泛型实参 Type，未找到时返回 Object.class
     */
    private static Type findGenericType(Class<?> clazz) {
        Class<?> current = clazz;
        while (current != null && current != GenericJsonTypeHandler.class && current != Object.class) {
            Type genericSuper = current.getGenericSuperclass();
            if (genericSuper instanceof ParameterizedType pt) {
                if (pt.getRawType() instanceof Class<?> raw && GenericJsonTypeHandler.class.isAssignableFrom(raw)) {
                    Type[] typeArgs = pt.getActualTypeArguments();
                    if (typeArgs != null && typeArgs.length > 0) {
                        return typeArgs[0];
                    }
                }
            }
            current = current.getSuperclass();
        }
        return Object.class;
    }
}
