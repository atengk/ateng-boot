package io.github.atengk.boot.sample.openapi.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 响应状态码字典条目视图
 *
 * @author Ateng
 * @since 2026-11-16
 */
@Schema(description = "系统及业务扩展状态码字典条目视图")
public class ResultCodeItemVO {

    @Schema(description = "状态码数值", example = "8001")
    private Integer code;

    @Schema(description = "状态码语义消息", example = "文章正在审核中")
    private String msg;

    @Schema(description = "来源分组名称", example = "样例工程自定义业务状态码字典")
    private String source;

    @Schema(description = "适用业务场景说明", example = "敏感词拦截或新发布内容进入人工审核队列")
    private String scenario;

    public ResultCodeItemVO() {
    }

    public ResultCodeItemVO(Integer code, String msg, String source, String scenario) {
        this.code = code;
        this.msg = msg;
        this.source = source;
        this.scenario = scenario;
    }

    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getScenario() {
        return scenario;
    }

    public void setScenario(String scenario) {
        this.scenario = scenario;
    }
}
