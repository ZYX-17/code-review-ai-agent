package com.codeguardian.service.rag;

import com.codeguardian.dto.KnowledgeBaseRequestDTO;
import com.codeguardian.dto.KnowledgeBaseStatsDTO;
import com.codeguardian.repository.KnowledgeBaseRepository;
import com.codeguardian.repository.KnowledgeDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 知识库管理服务
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeBaseManagementService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeDocumentRepository knowledgeDocumentRepository;

    /**
     * 所有知识库及文档数量
     */
    public List<KnowledgeBaseStatsDTO> listStats() {
        return knowledgeBaseRepository.findAll(Sort.by(Sort.Direction.ASC, "id")).stream()
                .map(this::toStats)
                .toList();
    }

    /**
     * 启用中的知识库（用于上传/审查选择）
     */
    public List<KnowledgeBase> getEnabledBases() {
        return knowledgeBaseRepository.findByEnabledTrueOrderByIdAsc();
    }

    @Transactional
    public KnowledgeBase create(KnowledgeBaseRequestDTO request) {
        String name = request.getName().trim();
        if (knowledgeBaseRepository.existsByName(name)) {
            throw new IllegalArgumentException("知识库名称已存在: " + name);
        }

        KnowledgeBase knowledgeBase = KnowledgeBase.builder()
                .name(name)
                .description(request.getDescription())
                .enabled(request.getEnabled() == null || request.getEnabled())
                .build();

        KnowledgeBase saved = knowledgeBaseRepository.save(knowledgeBase);
        log.info("创建知识库: id={}, name={}", saved.getId(), saved.getName());
        return saved;
    }

    @Transactional
    public KnowledgeBase update(Long id, KnowledgeBaseRequestDTO request) {
        KnowledgeBase knowledgeBase = knowledgeBaseRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("知识库不存在"));

        String name = request.getName().trim();
        if (knowledgeBaseRepository.existsByNameAndIdNot(name, id)) {
            throw new IllegalArgumentException("知识库名称已存在: " + name);
        }

        knowledgeBase.setName(name);
        knowledgeBase.setDescription(request.getDescription());
        if (request.getEnabled() != null) {
            knowledgeBase.setEnabled(request.getEnabled());
        }

        KnowledgeBase saved = knowledgeBaseRepository.save(knowledgeBase);
        log.info("更新知识库: id={}, name={}", saved.getId(), saved.getName());
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        KnowledgeBase knowledgeBase = knowledgeBaseRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("知识库不存在"));

        long documentCount = knowledgeDocumentRepository.countByKbId(id);
        if (documentCount > 0) {
            throw new IllegalArgumentException(
                    "知识库下还有 " + documentCount + " 篇文档，请先删除文档后再删除知识库");
        }

        if (knowledgeBaseRepository.count() <= 1) {
            throw new IllegalArgumentException("至少保留一个知识库");
        }

        knowledgeBaseRepository.delete(knowledgeBase);
        log.info("删除知识库: id={}, name={}", id, knowledgeBase.getName());
    }

    /**
     * 默认知识库：最早创建的一个；如果不存在则创建
     */
    @Transactional
    public KnowledgeBase getDefaultBase() {
        List<KnowledgeBase> bases = knowledgeBaseRepository.findAll(Sort.by(Sort.Direction.ASC, "id"));
        if (!bases.isEmpty()) {
            return bases.get(0);
        }

        KnowledgeBase created = knowledgeBaseRepository.save(KnowledgeBase.builder()
                .name("默认知识库")
                .description("系统默认知识库，存储所有上传的代码规范和技术文档")
                .enabled(true)
                .build());
        log.info("已创建默认知识库: id={}", created.getId());
        return created;
    }

    public boolean exists(Long id) {
        return id != null && knowledgeBaseRepository.existsById(id);
    }

    private KnowledgeBaseStatsDTO toStats(KnowledgeBase knowledgeBase) {
        return KnowledgeBaseStatsDTO.builder()
                .id(knowledgeBase.getId())
                .name(knowledgeBase.getName())
                .description(knowledgeBase.getDescription())
                .enabled(knowledgeBase.getEnabled())
                .documentCount(knowledgeDocumentRepository.countByKbId(knowledgeBase.getId()))
                .createdAt(knowledgeBase.getCreatedAt())
                .updatedAt(knowledgeBase.getUpdatedAt())
                .build();
    }
}
