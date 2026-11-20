package com.codeguardian.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 操作日志DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OperationLogDTO {

    private Long id;
    private Long userId;
    private String username;
    private String operation;
    private String method;
    private String params;
    private Long timeMillis;
    private String ip;
    private Integer status;
    private String errorMsg;
    private LocalDateTime createdAt;
}
