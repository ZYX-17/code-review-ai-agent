package com.codeguardian.service.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GitLabFeedbackServiceTest {

    @Test
    void should_extract_project_path_from_https_url() {
        assertEquals("owner/repo",
                GitLabFeedbackService.extractProjectPath("https://gitlab.com/owner/repo.git"));
        assertEquals("owner/repo",
                GitLabFeedbackService.extractProjectPath("https://gitlab.com/owner/repo"));
    }

    @Test
    void should_extract_nested_group_path() {
        assertEquals("group/sub/repo",
                GitLabFeedbackService.extractProjectPath("https://gitlab.example.com/group/sub/repo.git"));
    }

    @Test
    void should_extract_project_path_from_ssh_url() {
        assertEquals("group/repo",
                GitLabFeedbackService.extractProjectPath("git@gitlab.com:group/repo.git"));
    }

    @Test
    void should_return_null_for_invalid_url() {
        assertNull(GitLabFeedbackService.extractProjectPath(null));
        assertNull(GitLabFeedbackService.extractProjectPath(""));
        assertNull(GitLabFeedbackService.extractProjectPath("not-a-url"));
        assertNull(GitLabFeedbackService.extractProjectPath("https://gitlab.com"));
    }

    @Test
    void should_encode_project_path_with_percent_2f() {
        assertEquals("group%2Frepo", GitLabFeedbackService.encodeProjectPath("group/repo"));
        assertEquals("group%2Fsub%2Frepo", GitLabFeedbackService.encodeProjectPath("group/sub/repo"));
        assertNull(GitLabFeedbackService.encodeProjectPath(null));
    }

    @Test
    void should_map_internal_states_to_gitlab_states() {
        assertEquals("pending", GitLabFeedbackService.mapState("pending"));
        assertEquals("pending", GitLabFeedbackService.mapState("running"));
        assertEquals("success", GitLabFeedbackService.mapState("success"));
        assertEquals("success", GitLabFeedbackService.mapState("passed"));
        assertEquals("failed", GitLabFeedbackService.mapState("failure"));
        assertEquals("failed", GitLabFeedbackService.mapState("error"));
        assertEquals("canceled", GitLabFeedbackService.mapState("canceled"));
        assertEquals("pending", GitLabFeedbackService.mapState(null));
        assertEquals("pending", GitLabFeedbackService.mapState("unknown"));
    }
}
