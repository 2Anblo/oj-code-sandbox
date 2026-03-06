package com.lingbo.ojcodesandbox.model;

import lombok.Data;

@Data
public class ExecuteMessage {

    private String message;

    private String errorMessage;

    private Integer exitCode;

    private Long time;
}
