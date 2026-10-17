package com.codeguardian.service.pdf;

import com.codeguardian.entity.ReviewReport;
import com.codeguardian.exception.PdfGenerationException;
import com.codeguardian.service.ReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ContentDisposition;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * PDF生成服务
 * 负责将HTML报告转换为PDF格式
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PdfGenerationService {

    private final ReportService reportService;
    private final PdfHtmlConverter htmlConverter;

    /**
     * 生成PDF报告
     * @param taskId 任务ID
     * @return PDF字节数组
     * @throws PdfGenerationException PDF生成失败时抛出
     */
    public PdfGenerationResult generatePdf(Long taskId) {
        log.debug("开始生成PDF报告: taskId={}", taskId);

        ReviewReport report = reportService.generateReportForceRefresh(taskId);
        validateHtmlContent(report);

        String html = report.getHtmlContent();
        PdfGenerationResult result = new PdfGenerationResult();

        try {
            // 预处理HTML
            String preparedHtml = htmlConverter.prepareHtmlForPdf(html);

            // 加载字体
            PdfHtmlConverter.FontLoadResult fontLoadResult = htmlConverter.loadFont();

            // 生成PDF
            byte[] pdfBytes = convertToPdf(preparedHtml, fontLoadResult);
            result.setSuccess(true);
            result.setPdfBytes(pdfBytes);
            result.setFileName(htmlConverter.generatePdfFileName(taskId));

            log.info("PDF报告生成成功: taskId={}, size={} bytes", taskId, pdfBytes.length);
        } catch (Exception e) {
            log.error("PDF报告生成失败: taskId={}", taskId, e);
            result.setSuccess(false);
            result.setErrorMessage(e.getMessage());
        }

        return result;
    }

    /**
     * 验证HTML内容
     */
    private void validateHtmlContent(ReviewReport report) {
        if (report.getHtmlContent() == null || report.getHtmlContent().isEmpty()) {
            throw new PdfGenerationException("HTML报告内容为空");
        }
    }

    /**
     * 将HTML转换为PDF
     */
    private byte[] convertToPdf(String html, PdfHtmlConverter.FontLoadResult fontLoadResult) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        com.openhtmltopdf.pdfboxout.PdfRendererBuilder builder = createBuilder(html, out, fontLoadResult);
        builder.run();

        return out.toByteArray();
    }

    /**
     * 创建PDF渲染器
     */
    private com.openhtmltopdf.pdfboxout.PdfRendererBuilder createBuilder(
            String html,
            ByteArrayOutputStream out,
            PdfHtmlConverter.FontLoadResult fontLoadResult) throws Exception {

        com.openhtmltopdf.pdfboxout.PdfRendererBuilder builder = new com.openhtmltopdf.pdfboxout.PdfRendererBuilder();
        builder.useFastMode();
        builder.withHtmlContent(html, null);
        builder.toStream(out);

        // 尝试加载字体
        if (fontLoadResult.isSuccess() && fontLoadResult.getFontFile() != null) {
            try {
                // 注册字体到PDF渲染器
                builder.useFont(fontLoadResult.getFontFile(), "ArialUnicode");
                log.info("PDF字体注册成功: ArialUnicode, 文件: {}",
                        fontLoadResult.getFontFile().getAbsolutePath());
            } catch (Exception e) {
                log.error("PDF字体注册失败: {}", e.getMessage(), e);
                throw e;
            }
        } else {
            log.error("字体加载失败，PDF将无法显示中文: {}", fontLoadResult.getErrorMessage());
        }

        return builder;
    }

    /**
     * 构建PDF响应头
     */
    public HttpHeaders buildPdfHeaders(String fileName) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(fileName)
                .build());
        return headers;
    }
}
