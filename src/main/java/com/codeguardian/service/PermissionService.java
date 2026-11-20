package com.codeguardian.service;

import com.codeguardian.dto.PermissionDTO;
import com.codeguardian.dto.PermissionRequestDTO;
import com.codeguardian.entity.Permission;
import com.codeguardian.repository.PermissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 权限服务
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PermissionService {

    private static final Set<String> PROTECTED_CODES = Set.of("ADMIN", "QUERY", "REVIEW", "CONFIG");

    private final PermissionRepository permissionRepository;

    /**
     * 创建权限
     */
    @Transactional
    public Permission create(PermissionRequestDTO request) {
        String code = normalizeCode(request.getCode());
        if (permissionRepository.existsByCode(code)) {
            throw new IllegalArgumentException("权限代码已存在: " + code);
        }

        Permission permission = Permission.builder()
                .code(code)
                .name(request.getName().trim())
                .description(request.getDescription())
                .resource(request.getResource() != null ? request.getResource() : 1)
                .action(request.getAction() != null ? request.getAction() : 1)
                .createdAt(LocalDateTime.now())
                .build();

        Permission saved = permissionRepository.save(permission);
        log.info("创建权限成功: code={}, id={}", saved.getCode(), saved.getId());
        return saved;
    }

    /**
     * 更新权限
     */
    @Transactional
    public Permission update(Long id, PermissionRequestDTO request) {
        Permission permission = permissionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("权限不存在"));

        String code = normalizeCode(request.getCode());
        permissionRepository.findByCode(code).ifPresent(existing -> {
            if (!existing.getId().equals(id)) {
                throw new IllegalArgumentException("权限代码已存在: " + code);
            }
        });

        if (PROTECTED_CODES.contains(permission.getCode()) && !permission.getCode().equals(code)) {
            throw new IllegalArgumentException("内置权限代码不能修改: " + permission.getCode());
        }

        permission.setCode(code);
        permission.setName(request.getName().trim());
        permission.setDescription(request.getDescription());
        if (request.getResource() != null) {
            permission.setResource(request.getResource());
        }
        if (request.getAction() != null) {
            permission.setAction(request.getAction());
        }

        Permission saved = permissionRepository.save(permission);
        log.info("更新权限成功: id={}, code={}", id, saved.getCode());
        return saved;
    }

    /**
     * 删除权限
     */
    @Transactional
    public void delete(Long id) {
        Permission permission = permissionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("权限不存在"));

        if (PROTECTED_CODES.contains(permission.getCode())) {
            throw new IllegalArgumentException("内置权限不能删除: " + permission.getCode());
        }

        permissionRepository.delete(permission);
        log.info("删除权限成功: id={}, code={}", id, permission.getCode());
    }

    private String normalizeCode(String code) {
        if (code == null || code.trim().isEmpty()) {
            throw new IllegalArgumentException("权限代码不能为空");
        }
        String normalized = code.trim().toUpperCase().replaceAll("[^A-Z0-9_]", "_");
        if (normalized.length() > 32) {
            throw new IllegalArgumentException("权限代码长度不能超过32");
        }
        return normalized;
    }
    
    /**
     * 查询所有权限
     */
    @Transactional(readOnly = true)
    public List<Permission> getAllPermissions() {
        return permissionRepository.findAll();
    }
    
    /**
     * 查询所有权限（DTO格式）
     */
    @Transactional(readOnly = true)
    public List<PermissionDTO> getAllPermissionDTOs() {
        List<Permission> permissions = permissionRepository.findAll();
        return permissions.stream().map(permission -> {
            String resource = getResourceString(permission.getResource(), permission.getCode());
            String action = getActionString(permission.getAction(), permission.getCode());
            return PermissionDTO.builder()
                .id(permission.getId())
                .code(permission.getCode())
                .name(permission.getName())
                .description(permission.getDescription())
                .resource(resource)
                .action(action)
                .build();
        }).collect(Collectors.toList());
    }
    
    /**
     * 获取资源字符串
     */
    private String getResourceString(Integer resource, String code) {
        if (code.equals("ADMIN")) {
            return "ALL";
        } else if (code.equals("QUERY")) {
            return "TASK, REPORT";
        } else if (code.equals("REVIEW")) {
            return "TASK";
        } else if (code.equals("CONFIG")) {
            return "CONFIG";
        }
        return "";
    }
    
    /**
     * 获取操作字符串
     */
    private String getActionString(Integer action, String code) {
        if (code.equals("ADMIN")) {
            return "ALL";
        } else if (code.equals("QUERY")) {
            return "READ";
        } else if (code.equals("REVIEW")) {
            return "CREATE, READ";
        } else if (code.equals("CONFIG")) {
            return "READ, UPDATE";
        }
        return "";
    }
}

