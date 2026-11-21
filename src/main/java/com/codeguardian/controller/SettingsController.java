package com.codeguardian.controller;

import com.codeguardian.annotation.OperationLogRecord;
import com.codeguardian.model.dto.SettingsDTO;
import com.codeguardian.dto.OperationResponseDTO;
import com.codeguardian.service.SystemConfigService;
import com.codeguardian.service.notification.ReviewNotificationService;
import com.codeguardian.util.ViewModelUtils;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import cn.dev33.satoken.annotation.SaCheckPermission;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

/**
 * 系统设置控制器
 */
@Controller
@RequestMapping("/admin/settings")
@RequiredArgsConstructor
@Slf4j
public class SettingsController {

    private final SystemConfigService configService;
    private final ReviewNotificationService notificationService;

    /**
     * 设置页面
     * <p>需`CONFIG`权限。</p>
     */
    @GetMapping
    @SaCheckPermission("CONFIG")
    public String settingsPage(Model model, HttpSession session) {
        ViewModelUtils.populateUserInfo(model, session);
        model.addAttribute("settings", configService.getSettings());
        return "admin/settings";
    }

    /**
     * 保存设置
     */
    @PostMapping("/save")
    @ResponseBody
    @SaCheckPermission("CONFIG")
    @OperationLogRecord(module = "系统设置", action = "保存设置")
    public ResponseEntity<OperationResponseDTO> saveSettings(@RequestBody SettingsDTO settings) {
        try {
            configService.saveSettings(settings);
            return ResponseEntity.ok(OperationResponseDTO.success("设置已保存"));
        } catch (Exception e) {
            log.error("Failed to save settings", e);
            return ResponseEntity.badRequest().body(OperationResponseDTO.error(e.getMessage()));
        }
    }

    /**
     * 恢复默认设置
     */
    @PostMapping("/reset")
    @ResponseBody
    @SaCheckPermission("CONFIG")
    @OperationLogRecord(module = "系统设置", action = "恢复默认")
    public ResponseEntity<OperationResponseDTO> resetSettings() {
        try {
            configService.resetSettings();
            return ResponseEntity.ok(OperationResponseDTO.success("已恢复默认设置"));
        } catch (Exception e) {
            log.error("Failed to reset settings", e);
            return ResponseEntity.badRequest().body(OperationResponseDTO.error(e.getMessage()));
        }
    }

    /**
     * 发送测试通知（使用已保存的通知配置）
     */
    @PostMapping("/notify/test")
    @ResponseBody
    @SaCheckPermission("CONFIG")
    @OperationLogRecord(module = "系统设置", action = "测试通知")
    public ResponseEntity<OperationResponseDTO> testNotification() {
        try {
            notificationService.sendTest();
            return ResponseEntity.ok(OperationResponseDTO.success("测试通知已发送"));
        } catch (Exception e) {
            log.warn("测试通知发送失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(OperationResponseDTO.error(e.getMessage()));
        }
    }

    /**
     * 导出设置
     */
    @GetMapping("/export")
    @ResponseBody
    @SaCheckPermission("CONFIG")
    public ResponseEntity<SettingsDTO> exportSettings() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=settings.json");
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .body(configService.getSettings());
    }

    /**
     * 导入设置
     */
    @PostMapping("/import")
    @ResponseBody
    @SaCheckPermission("CONFIG")
    @OperationLogRecord(module = "系统设置", action = "导入设置")
    public ResponseEntity<OperationResponseDTO> importSettings(@RequestBody SettingsDTO settings) {
        try {
            configService.saveSettings(settings);
            return ResponseEntity.ok(OperationResponseDTO.success("设置导入成功"));
        } catch (Exception e) {
            log.error("Import settings failed", e);
            return ResponseEntity.badRequest().body(OperationResponseDTO.error(e.getMessage()));
        }
    }
}
