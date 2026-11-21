package com.codeguardian.repository;

import com.codeguardian.service.rag.KnowledgeBase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBase, Long> {

    List<KnowledgeBase> findByEnabledTrueOrderByIdAsc();

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);
}
