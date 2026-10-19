package io.github.atengk.boot.mybatisplus.injector;

import com.baomidou.mybatisplus.core.injector.AbstractMethod;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.injector.methods.InsertBatchSomeColumn;
import io.github.atengk.boot.mybatisplus.entity.BaseIdEntity;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import com.baomidou.mybatisplus.annotation.DbType;
import io.github.atengk.boot.mybatisplus.injector.methods.UpdateBatchSomeColumn;
import io.github.atengk.boot.mybatisplus.injector.methods.UpsertBatchSomeColumn;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AtengSqlInjector 全局 SQL 注入器单元测试
 * 验证 InsertBatchSomeColumn、UpsertBatchSomeColumn 与 UpdateBatchSomeColumn 原生批量操作方法被正确追加至方法列表
 *
 * @author Ateng
 * @since 2026-10-16
 */
@DisplayName("AtengSqlInjector 增强注入器单元测试")
class AtengSqlInjectorTest {

    static class SampleEntity extends BaseIdEntity {
        private String name;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    interface SampleMapper {}

    @Test
    @DisplayName("1. 挂载验证：getMethodList 返回的注入方法列表中包含 InsertBatchSomeColumn、UpsertBatchSomeColumn 与 UpdateBatchSomeColumn")
    void shouldIncludeInsertAndUpsertBatchInMethodList() {
        Configuration configuration = new Configuration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "resource");
        assistant.setCurrentNamespace(SampleMapper.class.getName());
        TableInfo tableInfo = TableInfoHelper.initTableInfo(assistant, SampleEntity.class);

        AtengSqlInjector injector = new AtengSqlInjector();
        List<AbstractMethod> methodList = injector.getMethodList(configuration, SampleMapper.class, tableInfo);

        assertThat(methodList).isNotNull();
        assertThat(methodList).anyMatch(m -> m instanceof InsertBatchSomeColumn);
        assertThat(methodList).anyMatch(m -> m instanceof UpsertBatchSomeColumn);
        assertThat(methodList).anyMatch(m -> m instanceof UpdateBatchSomeColumn);
    }

    @Test
    @DisplayName("2. 方言透传验证：显式指定 DbType 时正确透传至 UpsertBatchSomeColumn")
    void shouldPassExplicitDbTypeToUpsertBatchSomeColumn() {
        Configuration configuration = new Configuration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "resource");
        assistant.setCurrentNamespace(SampleMapper.class.getName());
        TableInfo tableInfo = TableInfoHelper.initTableInfo(assistant, SampleEntity.class);

        AtengSqlInjector injector = new AtengSqlInjector(DbType.POSTGRE_SQL);
        List<AbstractMethod> methodList = injector.getMethodList(configuration, SampleMapper.class, tableInfo);

        UpsertBatchSomeColumn upsertMethod = (UpsertBatchSomeColumn) methodList.stream()
                .filter(m -> m instanceof UpsertBatchSomeColumn)
                .findFirst()
                .orElseThrow();
        assertThat(upsertMethod.getDbType()).isEqualTo(DbType.POSTGRE_SQL);
    }
}
