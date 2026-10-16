package io.github.atengk.boot.mybatisplus.annotation;

import io.github.atengk.boot.mybatisplus.handler.EncryptTypeHandler;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 持久层字段透明加密标注注解
 * 声明实体字段在落盘写入数据库时自动加密为 AES 密文，在查询读取时自动解密为明文
 * 配合 @TableField(typeHandler = EncryptTypeHandler.class) 使用
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface FieldEncrypt {
}
