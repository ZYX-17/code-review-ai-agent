package com.codeguardian.service;

import com.codeguardian.entity.Category;
import com.codeguardian.enums.CategoryEnum;
import com.codeguardian.repository.CategoryRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 类别服务
 * 提供类别的缓存查询与增删改查
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final Map<String, Category> cache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        refreshCache();
    }

    public void refreshCache() {
        try {
            cache.clear();
            categoryRepository.findAll().forEach(c -> cache.put(c.getCode(), c));
            log.info("Loaded {} categories into cache.", cache.size());
        } catch (Exception e) {
            log.error("Failed to load categories into cache", e);
        }
    }

    public List<Category> listAll() {
        return categoryRepository.findAll(Sort.by(Sort.Direction.ASC, "id"));
    }

    @Transactional
    public Category create(String code, String name, String description) {
        String normalizedCode = normalizeCode(code);
        if (categoryRepository.findByCode(normalizedCode).isPresent()) {
            throw new IllegalArgumentException("分类代码已存在: " + normalizedCode);
        }

        Category category = Category.builder()
                .code(normalizedCode)
                .name(name.trim())
                .description(description)
                .createdAt(LocalDateTime.now())
                .build();

        Category saved = categoryRepository.save(category);
        refreshCache();
        return saved;
    }

    @Transactional
    public Category update(Long id, String code, String name, String description) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("分类不存在"));

        String normalizedCode = normalizeCode(code);
        categoryRepository.findByCode(normalizedCode).ifPresent(existing -> {
            if (!existing.getId().equals(id)) {
                throw new IllegalArgumentException("分类代码已存在: " + normalizedCode);
            }
        });

        category.setCode(normalizedCode);
        category.setName(name.trim());
        category.setDescription(description);

        Category saved = categoryRepository.save(category);
        refreshCache();
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("分类不存在"));

        if (CategoryEnum.CODE_STYLE.name().equals(category.getCode())) {
            throw new IllegalArgumentException("默认分类 CODE_STYLE 不能删除");
        }

        categoryRepository.delete(category);
        refreshCache();
    }

    public Category getByCode(String code) {
        return cache.get(code);
    }

    public Category getByEnum(CategoryEnum enumVal) {
        if (enumVal == null) return null;
        return cache.get(enumVal.name());
    }

    public Category getDefaultCategory() {
        Category cat = getByEnum(CategoryEnum.CODE_STYLE);
        if (cat == null && !cache.isEmpty()) {
            return cache.values().iterator().next();
        }
        return cat;
    }

    private String normalizeCode(String code) {
        if (code == null || code.trim().isEmpty()) {
            throw new IllegalArgumentException("分类代码不能为空");
        }
        String normalized = code.trim().toUpperCase().replaceAll("[^A-Z0-9_]", "_");
        if (normalized.length() > 32) {
            throw new IllegalArgumentException("分类代码长度不能超过32");
        }
        return normalized;
    }
}
