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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AtengSqlInjector 全局 SQL 注入器单元测试
 * 验证 InsertBatchSomeColumn 原生批量插入方法被正确追加至默认方法列表
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
    @DisplayName("1. 挂载验证：getMethodList 返回的注入方法列表中包含 InsertBatchSomeColumn")
    void shouldIncludeInsertBatchSomeColumnInMethodList() {
        Configuration configuration = new Configuration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "resource");
        assistant.setCurrentNamespace(SampleMapper.class.getName());
        TableInfo tableInfo = TableInfoHelper.initTableInfo(assistant, SampleEntity.class);

        AtengSqlInjector injector = new AtengSqlInjector();
        List<AbstractMethod> methodList = injector.getMethodList(configuration, SampleMapper.class, tableInfo);

        assertThat(methodList).isNotNull();
        assertThat(methodList).anyMatch(m -> m instanceof InsertBatchSomeColumn);
    }
}
