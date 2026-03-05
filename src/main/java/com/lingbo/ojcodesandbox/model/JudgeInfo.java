package com.lingbo.ojcodesandbox.model;

import lombok.Data;

/**
 * 判题配置
 */
@Data
public class JudgeInfo {

    /**
     * 消耗时间（ms）
     */
    private Long time;

    /**
     * 消耗内存（KB）
     */
    private Long memory;

    /**
     * 判题消息
     */
    private String message;

}
