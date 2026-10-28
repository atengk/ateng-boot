package io.github.atengk.boot.sample.openapi.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 移动端文章资讯视图响应对象
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Schema(description = "移动端文章资讯视图对象")
public class AppArticleVO {

    @Schema(description = "文章唯一标识", example = "20001")
    private Long id;

    @Schema(description = "文章标题", example = "Spring Boot 3.3 与 OpenAPI 生产实践")
    private String title;

    @Schema(description = "文章摘要简述", example = "全面拥抱 SpringDoc 官方原生生态，打造零停更接口文档治理底座")
    private String summary;

    @Schema(description = "文章分类标识", example = "technology")
    private String category;

    @Schema(description = "发布时间", example = "2026-10-22T12:00:00")
    private LocalDateTime publishTime;

    public AppArticleVO() {
    }

    public AppArticleVO(Long id, String title, String summary, String category, LocalDateTime publishTime) {
        this.id = id;
        this.title = title;
        this.summary = summary;
        this.category = category;
        this.publishTime = publishTime;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public LocalDateTime getPublishTime() {
        return publishTime;
    }

    public void setPublishTime(LocalDateTime publishTime) {
        this.publishTime = publishTime;
    }
}
