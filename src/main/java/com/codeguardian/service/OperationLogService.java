package com.codeguardian.service;

import com.codeguardian.dto.OperationLogDTO;
import com.codeguardian.dto.PageResult;
import com.codeguardian.entity.OperationLog;
import com.codeguardian.repository.OperationLogRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 操作日志服务
 */
@Service
@RequiredArgsConstructor
public class OperationLogService {

    private final OperationLogRepository operationLogRepository;

    /**
     * 分页查询操作日志
     */
    @Transactional(readOnly = true)
    public PageResult<OperationLogDTO> query(String keyword, Integer status, Integer page, Integer size) {
        Specification<OperationLog> specification = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (StringUtils.hasText(keyword)) {
                String like = "%" + keyword.trim() + "%";
                predicates.add(cb.or(
                        cb.like(root.get("username"), like),
                        cb.like(root.get("operation"), like),
                        cb.like(root.get("ip"), like)
                ));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<OperationLog> result = operationLogRepository.findAll(specification, pageable);

        List<OperationLogDTO> content = result.getContent().stream()
                .map(this::toDTO)
                .toList();

        return PageResult.<OperationLogDTO>builder()
                .content(content)
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .page(page)
                .size(size)
                .hasPrevious(result.hasPrevious())
                .hasNext(result.hasNext())
                .build();
    }

    private OperationLogDTO toDTO(OperationLog log) {
        return OperationLogDTO.builder()
                .id(log.getId())
                .userId(log.getUserId())
                .username(log.getUsername())
                .operation(log.getOperation())
                .method(log.getMethod())
                .params(log.getParams())
                .timeMillis(log.getTimeMillis())
                .ip(log.getIp())
                .status(log.getStatus())
                .errorMsg(log.getErrorMsg())
                .createdAt(log.getCreatedAt())
                .build();
    }
}
