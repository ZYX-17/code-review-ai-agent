package com.codeguardian.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 知识库创建/更新请求
 */
@Data
public class KnowledgeBaseRequestDTO {

    @NotBlank(message = "知识库名称不能为空")
    @Size(max = 128, message = "知识库名称长度不能超过128")
    private String name;

    private String description;

    private Boolean enabled;
}
