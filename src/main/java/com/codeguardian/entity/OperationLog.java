package com.codeguardian.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 操作日志实体
 */
@Entity
@Table(name = "operation_logs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OperationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(length = 32)
    private String username;

    @Column(length = 128)
    private String operation;

    @Column(length = 128)
    private String method;

    @Column(columnDefinition = "TEXT")
    private String params;

    @Column(name = "time_millis")
    private Long timeMillis;

    @Column(length = 64)
    private String ip;

    /**
     * 0=成功，1=失败
     */
    private Integer status;

    @Column(name = "error_msg", columnDefinition = "TEXT")
    private String errorMsg;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
