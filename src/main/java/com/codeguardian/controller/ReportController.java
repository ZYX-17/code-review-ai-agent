package com.codeguardian.controller;

import com.codeguardian.entity.ReviewReport;
import com.codeguardian.service.ReportService;
import com.codeguardian.service.pdf.PdfGenerationService;
import com.codeguardian.service.pdf.PdfGenerationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 报告控制器
 * <p>负责报告生成和导出的HTTP接口</p>
 *
 * @author CodeGuardian
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/report")
@RequiredArgsConstructor
@Slf4j
public class ReportController {

    private final ReportService reportService;
    private final PdfGenerationService pdfGenerationService;

    /**
     * 生成审查报告
     *
     * @param taskId 任务ID
     * @return 报告生成结果
     */
    @PostMapping("/{taskId}")
    public ResponseEntity<ApiResponse> generateReport(@PathVariable("taskId") Long taskId) {
        try {
            ReviewReport report = reportService.generateReport(taskId);
            String response = String.format("{\"message\":\"报告生成成功\",\"reportId\":%d}", report.getId());
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ApiResponse(true, response, null));
        } catch (Exception e) {
            log.error("生成报告失败: taskId={}", taskId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponse(false, null, e.getMessage()));
        }
    }

    /**
     * 获取HTML格式报告
     *
     * @param taskId 任务ID
     * @return HTML报告内容
     */
    @GetMapping("/{taskId}/html")
    public ResponseEntity<String> getHtmlReport(@PathVariable("taskId") Long taskId) {
        try {
            ReviewReport report = reportService.generateReport(taskId);

            if (report.getHtmlContent() == null || report.getHtmlContent().isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_HTML)
                    .body(report.getHtmlContent());
        } catch (Exception e) {
            log.error("获取HTML报告失败: taskId={}", taskId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 获取Markdown格式报告
     *
     * @param taskId 任务ID
     * @return Markdown报告内容
     */
    @GetMapping("/{taskId}/markdown")
    public ResponseEntity<String> getMarkdownReport(@PathVariable("taskId") Long taskId) {
        try {
            ReviewReport report = reportService.generateReport(taskId);

            if (report.getMarkdownContent() == null || report.getMarkdownContent().isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(report.getMarkdownContent());
        } catch (Exception e) {
            log.error("获取Markdown报告失败: taskId={}", taskId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 获取PDF格式报告
     *
     * @param taskId 任务ID
     * @return PDF报告文件
     */
    @GetMapping("/{taskId}/pdf")
    public ResponseEntity<byte[]> getPdfReport(@PathVariable("taskId") Long taskId) {
        try {
            PdfGenerationResult result = pdfGenerationService.generatePdf(taskId);

            if (!result.isSuccess()) {
                log.error("PDF生成失败: taskId={}, error={}", taskId, result.getErrorMessage());
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }

            HttpHeaders headers = pdfGenerationService.buildPdfHeaders(result.getFileName());
            return new ResponseEntity<>(result.getPdfBytes(), headers, HttpStatus.OK);
        } catch (Exception e) {
            log.error("获取PDF报告失败: taskId={}", taskId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 统一API响应格式
     */
    public static class ApiResponse {
        private final boolean success;
        private final String data;
        private final String error;

        public ApiResponse(boolean success, String data, String error) {
            this.success = success;
            this.data = data;
            this.error = error;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getData() {
            return data;
        }

        public String getError() {
            return error;
        }
    }
}
