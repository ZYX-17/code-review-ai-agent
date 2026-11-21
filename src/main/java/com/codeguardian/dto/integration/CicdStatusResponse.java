package com.codeguardian.dto.integration;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CicdStatusResponse {
    private Long taskId;
    private String status; // RUNNING, COMPLETED, FAILED
    private Boolean passed; // 是否通过门禁；触发时为 null，状态查询时才有结果
    private String message;
    private String reportUrl;
    private Summary summary;

    @Data
    @Builder
    public static class Summary {
        private int critical;
        private int high;
        private int medium;
        private int low;
    }
}
