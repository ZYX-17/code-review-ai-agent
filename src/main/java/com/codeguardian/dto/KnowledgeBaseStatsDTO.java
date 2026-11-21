package com.codeguardian.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 知识库统计DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeBaseStatsDTO {
    private Long id;
    private String name;
    private String description;
    private Boolean enabled;
    private Long documentCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
