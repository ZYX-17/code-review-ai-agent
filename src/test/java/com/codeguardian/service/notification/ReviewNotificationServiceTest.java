package com.codeguardian.service.notification;

import com.codeguardian.entity.ReviewTask;
import com.codeguardian.enums.SeverityEnum;
import com.codeguardian.enums.TaskStatusEnum;
import com.codeguardian.repository.FindingRepository;
import com.codeguardian.service.SystemConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ReviewNotificationServiceTest {

    private ReviewNotificationService service;

    @BeforeEach
    void setUp() {
        service = new ReviewNotificationService(
                mock(SystemConfigService.class),
                mock(FindingRepository.class),
                new ObjectMapper());
        ReflectionTestUtils.setField(service, "publicBaseUrl", "https://cg.example.com/");
    }

    @Test
    void should_build_completed_text_with_counts_and_report_url() {
        ReviewTask task = ReviewTask.builder()
                .id(42L)
                .name("PR-1-demo")
                .status(TaskStatusEnum.COMPLETED.getValue())
                .build();
        Map<Integer, Integer> counts = Map.of(
                SeverityEnum.CRITICAL.getValue(), 1,
                SeverityEnum.HIGH.getValue(), 2,
                SeverityEnum.LOW.getValue(), 3);

        String text = service.buildText(task, counts);

        assertTrue(text.contains("CodeGuardian AI 审查完成"));
        assertTrue(text.contains("PR-1-demo (#42)"));
        assertTrue(text.contains("严重 1 / 高危 2 / 中危 0 / 低危 3"));
        assertTrue(text.contains("https://cg.example.com/report/42"));
    }

    @Test
    void should_build_failed_text_with_reason() {
        ReviewTask task = ReviewTask.builder()
                .id(7L)
                .name("PUSH-abc")
                .status(TaskStatusEnum.FAILED.getValue())
                .errorMessage("AI timeout")
                .build();

        String text = service.buildText(task, Map.of());

        assertTrue(text.contains("CodeGuardian AI 审查失败"));
        assertTrue(text.contains("AI timeout"));
        assertFalse(text.contains("问题: 严重"));
    }

    @Test
    void should_not_append_report_url_when_public_base_url_missing() {
        ReflectionTestUtils.setField(service, "publicBaseUrl", "");
        ReviewTask task = ReviewTask.builder()
                .id(1L)
                .name("local")
                .status(TaskStatusEnum.COMPLETED.getValue())
                .build();

        String text = service.buildText(task, Map.of());

        assertFalse(text.contains("报告:"));
    }

    @Test
    void should_sign_dingtalk_url_when_secret_present() {
        String url = "https://oapi.dingtalk.com/robot/send?access_token=abc";
        String signed = ReviewNotificationService.buildDingTalkUrl(url, "SEC-test", 1700000000000L);

        assertTrue(signed.startsWith(url + "&timestamp=1700000000000&sign="));
        assertNotEquals(url, signed);
    }

    @Test
    void should_add_timestamp_query_when_url_has_no_query() {
        String url = "https://oapi.dingtalk.com/robot/send";
        String signed = ReviewNotificationService.buildDingTalkUrl(url, "SEC-test", 1700000000000L);

        assertTrue(signed.startsWith(url + "?timestamp=1700000000000&sign="));
    }

    @Test
    void should_not_sign_dingtalk_url_without_secret() {
        String url = "https://oapi.dingtalk.com/robot/send?access_token=abc";

        assertEquals(url, ReviewNotificationService.buildDingTalkUrl(url, "", 1700000000000L));
    }
}
