package io.github.atengk.boot.mybatisplus.dynamicsql;

import java.util.Objects;

/**
 * 供 DynamicSqlExecutor 测试使用的用户视图对象
 *
 * @author Ateng
 * @since 2026-10-18
 */
public class TestUserVO {
    private Long id;
    private String name;
    private Integer status;

    public TestUserVO() {
    }

    public TestUserVO(Long id, String name, Integer status) {
        this.id = id;
        this.name = name;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TestUserVO that = (TestUserVO) o;
        return Objects.equals(id, that.id) && Objects.equals(name, that.name) && Objects.equals(status, that.status);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, status);
    }
}
