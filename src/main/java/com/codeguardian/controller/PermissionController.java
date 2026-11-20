package com.codeguardian.controller;

import com.codeguardian.annotation.OperationLogRecord;
import com.codeguardian.dto.PermissionDTO;
import com.codeguardian.dto.PermissionRequestDTO;
import com.codeguardian.entity.Permission;
import com.codeguardian.service.PermissionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;

import java.util.List;

/**
 * 权限管理控制器
 */
@Controller
@RequestMapping("/admin/permissions")
@RequiredArgsConstructor
@Slf4j
public class PermissionController {
    
    private final PermissionService permissionService;
    
    /**
     * 权限管理页面
     *
     * <p>需登录。</p>
     */
    @GetMapping
    @SaCheckLogin
    public String permissionManagementPage(Model model, jakarta.servlet.http.HttpSession session) {
        com.codeguardian.util.ViewModelUtils.populateUserInfo(model, session);
        return "admin/permissions";
    }
    
    /**
     * 查询所有权限（API）
     *
     * <p>需`CONFIG`权限。</p>
     */
    @GetMapping("/api")
    @ResponseBody
    @SaCheckPermission("CONFIG")
    public ResponseEntity<List<Permission>> getAllPermissions() {
        List<Permission> permissions = permissionService.getAllPermissions();
        return ResponseEntity.ok(permissions);
    }
    
    /**
     * 查询所有权限（DTO）（API）
     *
     * <p>需`CONFIG`权限。</p>
     */
    @GetMapping("/api/dto")
    @ResponseBody
    @SaCheckPermission("CONFIG")
    public ResponseEntity<List<PermissionDTO>> getAllPermissionDTOs() {
        List<PermissionDTO> permissions = permissionService.getAllPermissionDTOs();
        return ResponseEntity.ok(permissions);
    }

    /**
     * 创建权限（API）
     */
    @PostMapping("/api")
    @ResponseBody
    @SaCheckPermission("CONFIG")
    @OperationLogRecord(module = "权限管理", action = "创建权限")
    public ResponseEntity<Permission> create(@Valid @RequestBody PermissionRequestDTO request) {
        return ResponseEntity.ok(permissionService.create(request));
    }

    /**
     * 更新权限（API）
     */
    @PutMapping("/api/{id}")
    @ResponseBody
    @SaCheckPermission("CONFIG")
    @OperationLogRecord(module = "权限管理", action = "更新权限")
    public ResponseEntity<Permission> update(@PathVariable("id") Long id,
                                             @Valid @RequestBody PermissionRequestDTO request) {
        return ResponseEntity.ok(permissionService.update(id, request));
    }

    /**
     * 删除权限（API）
     */
    @DeleteMapping("/api/{id}")
    @ResponseBody
    @SaCheckPermission("CONFIG")
    @OperationLogRecord(module = "权限管理", action = "删除权限")
    public ResponseEntity<Void> delete(@PathVariable("id") Long id) {
        permissionService.delete(id);
        return ResponseEntity.ok().build();
    }
}
