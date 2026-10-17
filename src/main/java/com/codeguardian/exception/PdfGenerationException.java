package com.codeguardian.exception;

/**
 * PDF生成异常
 * <p>PDF生成过程中抛出的异常</p>
 *
 * @author CodeGuardian
 * @since 1.0.6
 */
public class PdfGenerationException extends RuntimeException {

    private final Long taskId;

    public PdfGenerationException(String message) {
        super(message);
        this.taskId = null;
    }

    public PdfGenerationException(String message, Throwable cause) {
        super(message, cause);
        this.taskId = null;
    }

    public PdfGenerationException(Long taskId, String message) {
        super(String.format("[taskId=%d] %s", taskId, message));
        this.taskId = taskId;
    }

    public PdfGenerationException(Long taskId, String message, Throwable cause) {
        super(String.format("[taskId=%d] %s", taskId, message), cause);
        this.taskId = taskId;
    }

    public Long getTaskId() {
        return taskId;
    }
}
