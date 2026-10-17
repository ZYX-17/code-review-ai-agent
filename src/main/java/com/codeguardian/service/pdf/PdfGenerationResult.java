package com.codeguardian.service.pdf;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PDF生成结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PdfGenerationResult {
    /**
     * 是否成功
     */
    private boolean success;

    /**
     * PDF字节数组
     */
    private byte[] pdfBytes;

    /**
     * 文件名
     */
    private String fileName;

    /**
     * 错误信息
     */
    private String errorMessage;
}
