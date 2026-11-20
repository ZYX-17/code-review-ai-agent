package com.codeguardian.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.codeguardian.annotation.OperationLogRecord;
import com.codeguardian.dto.CategoryRequestDTO;
import com.codeguardian.entity.Category;
import com.codeguardian.service.CategoryService;
import com.codeguardian.util.ViewModelUtils;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 分类管理控制器
 */
@Controller
@RequestMapping("/admin/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    /**
     * 分类管理页面
     */
    @GetMapping
    @SaCheckPermission("ADMIN")
    public String page(Model model, HttpSession session) {
        ViewModelUtils.populateUserInfo(model, session);
        return "admin/categories";
    }

    /**
     * 分类列表（API）
     */
    @GetMapping("/api")
    @ResponseBody
    @SaCheckPermission("ADMIN")
    public ResponseEntity<List<Category>> list() {
        return ResponseEntity.ok(categoryService.listAll());
    }

    /**
     * 创建分类（API）
     */
    @PostMapping("/api")
    @ResponseBody
    @SaCheckPermission("ADMIN")
    @OperationLogRecord(module = "分类管理", action = "创建分类")
    public ResponseEntity<Category> create(@Valid @RequestBody CategoryRequestDTO request) {
        return ResponseEntity.ok(categoryService.create(
                request.getCode(), request.getName(), request.getDescription()));
    }

    /**
     * 更新分类（API）
     */
    @PutMapping("/api/{id}")
    @ResponseBody
    @SaCheckPermission("ADMIN")
    @OperationLogRecord(module = "分类管理", action = "更新分类")
    public ResponseEntity<Category> update(@PathVariable("id") Long id,
                                           @Valid @RequestBody CategoryRequestDTO request) {
        return ResponseEntity.ok(categoryService.update(
                id, request.getCode(), request.getName(), request.getDescription()));
    }

    /**
     * 删除分类（API）
     */
    @DeleteMapping("/api/{id}")
    @ResponseBody
    @SaCheckPermission("ADMIN")
    @OperationLogRecord(module = "分类管理", action = "删除分类")
    public ResponseEntity<Void> delete(@PathVariable("id") Long id) {
        categoryService.delete(id);
        return ResponseEntity.ok().build();
    }
}
