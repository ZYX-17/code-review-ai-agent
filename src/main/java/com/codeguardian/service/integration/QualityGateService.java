package com.codeguardian.service.integration;

import com.codeguardian.entity.Finding;
import com.codeguardian.repository.FindingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 质量门禁服务
 * 用于判断审查结果是否满足通过条件
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class QualityGateService {

    private final FindingRepository findingRepository;

    /**
     * 检查是否通过质量门禁
     *
     * @param task 审查任务
     * @param blockOn 阻断级别 (CRITICAL, HIGH, MEDIUM, LOW)
     * @return true if passed, false if blocked
     */
    public boolean checkQualityGate(Long taskId, String blockOn) {
        if (taskId == null) {
            return true;
        }

        List<Finding> findings = findingRepository.findByTaskId(taskId);
        return checkQualityGate(findings, blockOn, taskId);
    }

    boolean checkQualityGate(List<Finding> findings, String blockOn) {
        return checkQualityGate(findings, blockOn, null);
    }

    private boolean checkQualityGate(List<Finding> findings, String blockOn, Long taskId) {
        Map<Integer, Long> counts = findings.stream()
                .filter(f -> f.getSeverity() != null)
                .collect(Collectors.groupingBy(Finding::getSeverity, Collectors.counting()));

        long critical = counts.getOrDefault(com.codeguardian.enums.SeverityEnum.CRITICAL.getValue(), 0L);
        long high = counts.getOrDefault(com.codeguardian.enums.SeverityEnum.HIGH.getValue(), 0L);
        long medium = counts.getOrDefault(com.codeguardian.enums.SeverityEnum.MEDIUM.getValue(), 0L);
        long low = counts.getOrDefault(com.codeguardian.enums.SeverityEnum.LOW.getValue(), 0L);

        log.info("质量门禁检查: taskId={}, blockOn={}, counts={C:{}, H:{}, M:{}, L:{}}", 
                taskId, blockOn, critical, high, medium, low);

        if (blockOn == null || blockOn.isBlank()) {
            return true; // 默认不阻断
        }

        switch (blockOn.toUpperCase()) {
            case "LOW":
                if (low > 0) return false;
                // fallthrough
            case "MEDIUM":
                if (medium > 0) return false;
                // fallthrough
            case "HIGH":
                if (high > 0) return false;
                // fallthrough
            case "CRITICAL":
                if (critical > 0) return false;
                break;
            default:
                log.warn("未知的阻断级别: {}, 忽略阻断策略", blockOn);
                return true;
        }

        return true;
    }
}
