package com.codeguardian.service.ai.context;

import com.codeguardian.entity.Finding;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 审查上下文持有者
 * 用于在线程中传递工具发现的问题
 */
public class ReviewContextHolder {
    private static final ThreadLocal<List<Finding>> findingsHolder = ThreadLocal.withInitial(ArrayList::new);

    public static void addFindings(List<Finding> findings) {
        if (findings != null) {
            findingsHolder.get().addAll(findings);
        }
    }
    
    public static void addFinding(Finding finding) {
        if (finding != null) {
            findingsHolder.get().add(finding);
        }
    }

    public static List<Finding> getFindings() {
        return Collections.unmodifiableList(new ArrayList<>(findingsHolder.get()));
    }

    public static void clear() {
        findingsHolder.remove();
    }
}
