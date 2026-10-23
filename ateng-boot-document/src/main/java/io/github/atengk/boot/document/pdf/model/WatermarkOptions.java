package io.github.atengk.boot.document.pdf.model;

import io.github.atengk.boot.document.enums.WatermarkLayer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * PDF 防泄密平铺水印高级选项
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WatermarkOptions {

    /**
     * 水印文本模版（支持动态宏变量，例如 #{user.nickname} #{user.phone} #{now('yyyy-MM-dd')}）
     */
    @Builder.Default
    private String text = "#{user.nickname!''} #{now('yyyy-MM-dd')}";

    /**
     * 水印不透明度（0.0f 为完全透明，1.0f 为完全实心，默认 0.2f）
     */
    @Builder.Default
    private float opacity = 0.2f;

    /**
     * 水印文字旋转角度（度，负数表示逆时针旋转，默认 -30.0f）
     */
    @Builder.Default
    private float rotation = -30.0f;

    /**
     * 水印文字字号大小（单位：pt，默认 24.0f）
     */
    @Builder.Default
    private float fontSize = 24.0f;

    /**
     * 水印十六进制 RGB 颜色值（例如 #888888、#D3D3D3，默认 #888888）
     */
    @Builder.Default
    private String color = "#888888";

    /**
     * 水印图层放置模式（OVERLAY: 前置覆盖防抹除 / UNDERLAY: 背景底层，默认 OVERLAY）
     */
    @Builder.Default
    private WatermarkLayer layer = WatermarkLayer.OVERLAY;

    /**
     * 水印平铺网格行间距（单位：pt，默认 150.0f）
     */
    @Builder.Default
    private float rowSpacing = 150.0f;

    /**
     * 水印平铺网格列间距（单位：pt，默认 200.0f）
     */
    @Builder.Default
    private float colSpacing = 200.0f;

    /**
     * 自定义上下文变量字典（用于解析宏变量，如 user.nickname, user.phone, traceId 等）
     */
    @Builder.Default
    private Map<String, Object> variables = new HashMap<>();

    /**
     * 便捷设置上下文变量
     *
     * @param key   变量名
     * @param value 变量值
     * @return 当前 WatermarkOptions 实例
     */
    public WatermarkOptions addVariable(String key, Object value) {
        if (this.variables == null) {
            this.variables = new HashMap<>();
        }
        this.variables.put(key, value);
        return this;
    }

    /**
     * 便捷注入当前操作人信息供水印宏变量解析
     *
     * @param nickname 用户昵称
     * @param username 用户账号
     * @param phone    用户手机号（将自动脱敏掩码）
     * @return 当前 WatermarkOptions 实例
     */
    public WatermarkOptions setUser(String nickname, String username, String phone) {
        Map<String, Object> userMap = new HashMap<>();
        if (nickname != null) {
            userMap.put("nickname", nickname);
        }
        if (username != null) {
            userMap.put("username", username);
        }
        if (phone != null) {
            userMap.put("phone", phone);
        }
        return addVariable("user", userMap);
    }

    /**
     * 创建默认前置半透明平铺水印选项
     *
     * @return WatermarkOptions 默认实例
     */
    public static WatermarkOptions ofDefault() {
        return WatermarkOptions.builder().build();
    }

    /**
     * 创建指定水印文本的默认平铺水印选项
     *
     * @param text 水印文本
     * @return WatermarkOptions 实例
     */
    public static WatermarkOptions ofText(String text) {
        return WatermarkOptions.builder().text(text).build();
    }
}
