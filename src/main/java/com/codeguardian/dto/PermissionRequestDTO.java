package com.codeguardian.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 权限创建/更新请求
 */
@Data
public class PermissionRequestDTO {

    @NotBlank(message = "权限代码不能为空")
    @Size(max = 32, message = "权限代码长度不能超过32")
    private String code;

    @NotBlank(message = "权限名称不能为空")
    @Size(max = 64, message = "权限名称长度不能超过64")
    private String name;

    private String description;

    /**
     * 资源编码：1=TASK, 2=REPORT, 3=CONFIG, 99=ALL
     */
    private Integer resource;

    /**
     * 操作编码：1=READ, 2=CREATE, 3=UPDATE, 99=ALL
     */
    private Integer action;
}
