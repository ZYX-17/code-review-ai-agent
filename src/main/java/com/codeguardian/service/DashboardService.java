package com.codeguardian.service;

import com.codeguardian.dto.DashboardDTO;
import com.codeguardian.entity.Finding;
import com.codeguardian.entity.ReviewTask;
import com.codeguardian.repository.FindingRepository;
import com.codeguardian.repository.ReviewTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final ReviewTaskRepository reviewTaskRepository;
    private final FindingRepository findingRepository;
    private final SystemConfigService systemConfigService;
    private final ObjectMapper objectMapper;

    /**
     * 计算并生成最新的仪表盘数据
     *
     * @return 最新的仪表盘数据
     */
    @Transactional(readOnly = true)
    public DashboardDTO computeDashboardData() {
        DashboardDTO.SeverityDistribution distribution = DashboardDTO.SeverityDistribution.builder()
                .critical(0).high(0).medium(0).low(0)
                .build();

        // 聚合最近 20 个已完成任务的问题分布，而不是只看最后一个任务
        List<Object[]> severityCounts = findingRepository.countSeverityForRecentCompletedTasks(
                com.codeguardian.enums.TaskStatusEnum.COMPLETED.getValue(), 20);
        for (Object[] row : severityCounts) {
            if (row == null || row.length < 2 || row[0] == null) continue;
            int severity = ((Number) row[0]).intValue();
            int count = ((Number) row[1]).intValue();
            if (severity == com.codeguardian.enums.SeverityEnum.CRITICAL.getValue()) {
                distribution.setCritical(distribution.getCritical() + count);
            } else if (severity == com.codeguardian.enums.SeverityEnum.HIGH.getValue()) {
                distribution.setHigh(distribution.getHigh() + count);
            } else if (severity == com.codeguardian.enums.SeverityEnum.MEDIUM.getValue()) {
                distribution.setMedium(distribution.getMedium() + count);
            } else {
                distribution.setLow(distribution.getLow() + count);
            }
        }

        int healthScore = calculateHealthScore(distribution);

        // 最近 5 个完成的任务（不再只统计 PROJECT 类型）
        List<ReviewTask> recentTasks = reviewTaskRepository.findTop5ByStatusOrderByCreatedAtDesc(
                com.codeguardian.enums.TaskStatusEnum.COMPLETED.getValue());
        List<DashboardDTO.ProjectStatDTO> projectStats = recentTasks.stream()
                .map(this::convertToProjectStat)
                .collect(Collectors.toList());

        // 反转列表，使图表从左到右显示时间顺序（旧 -> 新）
        Collections.reverse(projectStats);

        return DashboardDTO.builder()
                .healthScore(healthScore)
                .problemDistribution(distribution)
                .projectStats(projectStats)
                .build();
    }

    /**
     * 根据严重程度分布计算代码健康分
     *
     * @param distribution 严重程度分布
     * @return 0-100 的健康分
     */
    private int calculateHealthScore(DashboardDTO.SeverityDistribution distribution) {
        int penalty = distribution.getCritical() * 10
                + distribution.getHigh() * 5
                + distribution.getMedium() * 2
                + distribution.getLow() * 1;
        return Math.max(0, 100 - penalty);
    }

    /**
     * 获取缓存的仪表盘数据；若不存在则实时计算并返回
     *
     * @return 仪表盘数据
     */
    public DashboardDTO getCachedDashboardData() {
        try {
            String json = systemConfigService.getConfigValue(SystemConfigService.KEY_METRICS_DASHBOARD);
            if (json != null && !json.isEmpty()) {
                return objectMapper.readValue(json, DashboardDTO.class);
            }
        } catch (Exception ignored) {
        }
        return computeDashboardData();
    }

    private DashboardDTO.ProjectStatDTO convertToProjectStat(ReviewTask task) {
        List<Finding> findings = findingRepository.findByTaskId(task.getId());
        
        int critical = 0, high = 0, medium = 0, low = 0;
        for (Finding f : findings) {
            Integer severity = f.getSeverity();
            if (severity == null) continue;
            if (severity.equals(com.codeguardian.enums.SeverityEnum.CRITICAL.getValue())) critical++;
            else if (severity.equals(com.codeguardian.enums.SeverityEnum.HIGH.getValue())) high++;
            else if (severity.equals(com.codeguardian.enums.SeverityEnum.MEDIUM.getValue())) medium++;
            else low++;
        }
        return DashboardDTO.ProjectStatDTO.builder()
                .projectName(task.getName())
                .criticalCount(critical)
                .highCount(high)
                .mediumCount(medium)
                .lowCount(low)
                .totalCount(findings != null ? findings.size() : 0)
                .build();
    }
}
