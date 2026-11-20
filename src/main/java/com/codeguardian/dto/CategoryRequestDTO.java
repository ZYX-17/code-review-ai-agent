package com.codeguardian.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 分类创建/更新请求
 */
@Data
public class CategoryRequestDTO {

    @NotBlank(message = "分类代码不能为空")
    @Size(max = 32, message = "分类代码长度不能超过32")
    private String code;

    @NotBlank(message = "分类名称不能为空")
    @Size(max = 64, message = "分类名称长度不能超过64")
    private String name;

    private String description;
}
