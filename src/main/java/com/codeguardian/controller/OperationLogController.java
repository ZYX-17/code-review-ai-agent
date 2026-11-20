package com.codeguardian.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.codeguardian.dto.OperationLogDTO;
import com.codeguardian.dto.PageResult;
import com.codeguardian.service.OperationLogService;
import com.codeguardian.util.ViewModelUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

/**
 * 操作日志控制器
 */
@Controller
@RequestMapping("/admin/logs")
@RequiredArgsConstructor
public class OperationLogController {

    private final OperationLogService operationLogService;

    /**
     * 操作日志页面
     */
    @GetMapping
    @SaCheckPermission("ADMIN")
    public String page(Model model, HttpSession session) {
        ViewModelUtils.populateUserInfo(model, session);
        return "admin/logs";
    }

    /**
     * 分页查询操作日志（API）
     */
    @GetMapping("/api")
    @ResponseBody
    @SaCheckPermission("ADMIN")
    public ResponseEntity<PageResult<OperationLogDTO>> query(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) Integer status,
            @RequestParam(value = "page", defaultValue = "0") Integer page,
            @RequestParam(value = "size", defaultValue = "20") Integer size) {
        return ResponseEntity.ok(operationLogService.query(keyword, status, page, size));
    }
}
