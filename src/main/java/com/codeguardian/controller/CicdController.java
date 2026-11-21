package com.codeguardian.controller;

import com.codeguardian.dto.ReviewRequestDTO;
import com.codeguardian.dto.ReviewResponseDTO;
import com.codeguardian.dto.integration.CicdStatusResponse;
import com.codeguardian.dto.integration.CicdTriggerRequest;
import com.codeguardian.entity.ReviewTask;
import com.codeguardian.repository.ReviewTaskRepository;
import com.codeguardian.service.ReviewService;
import com.codeguardian.service.integration.QualityGateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * CI/CD 集成控制器
 * 提供给 Jenkins, GitLab CI, GitHub Actions 等调用
 */
@RestController
@RequestMapping("/api/v1/cicd")
@RequiredArgsConstructor
@Slf4j
public class CicdController {

    private final ReviewService reviewService;
    private final ReviewTaskRepository taskRepository;
    private final QualityGateService qualityGateService;

    /**
     * 触发审查 (CI/CD Pipeline 调用)
     */
    @PostMapping("/trigger")
    public ResponseEntity<CicdStatusResponse> triggerReview(@RequestBody CicdTriggerRequest request) {
        log.info("收到CI/CD触发请求: {}", request);

        ReviewRequestDTO reviewRequest = ReviewRequestDTO.builder()
                .reviewType("GIT")
                .gitUrl(request.getGitUrl())
                .gitBranch(request.getBranch())
                .gitCommit(request.getCommitHash())
                .rulesOnly(request.getRulesOnly())
                .ruleTemplate(request.getRuleTemplate())
                .taskName("CI-" + (request.getTriggerBy() != null ? request.getTriggerBy() : "AUTO") + "-" + System.currentTimeMillis())
                .build();

        // 如果指定了项目子路径
        if (request.getProjectPath() != null) {
            reviewRequest.setProjectPath(request.getProjectPath());
        }

        // 创建并启动任务
        ReviewResponseDTO taskResponse = reviewService.createReviewTask(reviewRequest);
        
        // 立即返回任务ID，CI工具可以通过轮询checkStatus来获取结果
        return ResponseEntity.ok(CicdStatusResponse.builder()
                .taskId(taskResponse.getTaskId())
                .status(com.codeguardian.enums.TaskStatusEnum.RUNNING.name())
                .passed(null) // 结果未知，CI 工具需轮询 status 接口
                .message("任务已提交，请轮询状态接口获取门禁结果")
                .build());
    }

    /**
     * 检查审查状态与结果 (CI/CD Pipeline 轮询)
     */
    @GetMapping("/status/{taskId}")
    public ResponseEntity<CicdStatusResponse> checkStatus(
            @PathVariable Long taskId,
            @RequestParam(required = false, defaultValue = "CRITICAL") String blockOn) {
        
        Optional<ReviewTask> taskOpt = taskRepository.findById(taskId);
        if (taskOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        ReviewTask task = taskOpt.get();
        boolean isCompleted = com.codeguardian.enums.TaskStatusEnum.COMPLETED.getValue().equals(task.getStatus());
        boolean isFailed = com.codeguardian.enums.TaskStatusEnum.FAILED.getValue().equals(task.getStatus());
        
        boolean passed = true;
        String message = "审查进行中...";
        CicdStatusResponse.Summary summary = null;

        if (isCompleted) {
            passed = qualityGateService.checkQualityGate(taskId, blockOn);
            message = passed ? "审查通过" : "审查未通过：存在 " + blockOn + " 级别及以上的问题";
            
            // 统计摘要
            ReviewResponseDTO dto = reviewService.getReviewTask(taskId); // Re-use logic to calculate counts
            summary = CicdStatusResponse.Summary.builder()
                    .critical(dto.getCriticalCount())
                    .high(dto.getHighCount())
                    .medium(dto.getMediumCount())
                    .low(dto.getLowCount())
                    .build();
        } else if (isFailed) {
            passed = false;
            message = "审查任务执行失败: " + task.getErrorMessage();
        }

        return ResponseEntity.ok(CicdStatusResponse.builder()
                .taskId(task.getId())
                .status(com.codeguardian.enums.TaskStatusEnum.fromValue(task.getStatus()).name())
                .passed(passed)
                .message(message)
                .reportUrl("/report/" + task.getId())
                .summary(summary)
                .build());
    }
}
