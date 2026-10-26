package io.github.atengk.boot.search.entity;

import io.github.atengk.boot.search.annotation.SearchId;
import io.github.atengk.boot.search.annotation.SearchIndex;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 搜索测试文档实体
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@SearchIndex("user_idx")
public class UserDoc {

    @SearchId
    private Long id;

    private String username;

    private String email;

    private Integer age;

    private LocalDateTime createTime;
}
