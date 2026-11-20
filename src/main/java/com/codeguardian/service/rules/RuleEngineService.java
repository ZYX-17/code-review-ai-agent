package com.codeguardian.service.rules;

import com.codeguardian.dto.CustomRuleDTO;
import com.codeguardian.entity.Finding;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规范规则审查引擎
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RuleEngineService {

    private final List<RuleTemplate> ruleTemplates;

    public List<Finding> reviewWithTemplate(String code, String language, String templateName) {
        String lang = normalize(language);
        String tpl = templateName != null ? templateName.toUpperCase() : "";
        List<RuleDefinition> rules = new ArrayList<>();

        boolean found = false;
        for (RuleTemplate template : ruleTemplates) {
            if (template.getName().equalsIgnoreCase(tpl) && template.supports(lang)) {
                rules.addAll(template.getRules());
                found = true;
                break;
            }
        }

        if (!found) {
            log.warn("未知或不匹配的模板: {} / {}，尝试查找通用语言支持", templateName, language);
            // 尝试只匹配语言
            for (RuleTemplate template : ruleTemplates) {
                if (template.supports(lang) && (tpl.isEmpty() || template.getName().contains(tpl))) {
                     rules.addAll(template.getRules());
                }
            }
        }

        return applyRules(code, rules);
    }

    public List<Finding> reviewWithCustom(String code, List<CustomRuleDTO> customRules) {
        List<RuleDefinition> rules = new ArrayList<>();
        if (customRules != null) {
            for (CustomRuleDTO dto : customRules) {
                if (dto.getPattern() == null || dto.getPattern().isEmpty()) continue;
                rules.add(RuleDefinition.builder()
                        .name(dto.getName() != null ? dto.getName() : "自定义规则")
                        .description(dto.getPoint() != null ? dto.getPoint() : "")
                        .pattern(dto.getPattern())
                        .severity(dto.getSeverity() != null ? dto.getSeverity().toUpperCase() : "MEDIUM")
                        .weight(dto.getWeight() != null ? dto.getWeight() : 50)
                        .suggestion("建议：遵循规范，修正上述问题。")
                        .build());
            }
        }
        return applyRules(code, rules);
    }

    private List<Finding> applyRules(String code, List<RuleDefinition> rules) {
        List<Finding> findings = new ArrayList<>();
        String[] lines = code != null ? code.split("\r?\n") : new String[0];

        for (RuleDefinition rule : rules) {
            try {
                Pattern p = Pattern.compile(rule.getPattern(), Pattern.MULTILINE);
                Matcher m = p.matcher(code);
                while (m.find()) {
                    int start = m.start();
                    int line = computeLineNumber(code, start);
                    // String matchedLine = line > 0 && line <= lines.length ? lines[line - 1] : "";
                    findings.add(Finding.builder()
                            .severity(com.codeguardian.enums.SeverityEnum.fromName(rule.getSeverity()).getValue())
                            .title(rule.getName())
                            .location("Line " + line)
                            .startLine(line)
                            .endLine(line) // 简单起见，暂定单行
                            .description(rule.getDescription())
                            .suggestion(rule.getSuggestion())
                            .category(resolveCategory(rule))
                            .diff(null)
                            .build());
                }
            } catch (Exception e) {
                log.warn("规则应用失败: {}", rule.getName(), e);
            }
        }

        return findings;
    }

    private String resolveCategory(RuleDefinition rule) {
        if (rule.getCategory() != null && !rule.getCategory().isBlank()) {
            return rule.getCategory().trim().toUpperCase();
        }

        String text = ((rule.getName() != null ? rule.getName() : "") + " "
                + (rule.getDescription() != null ? rule.getDescription() : "")).toLowerCase();

        if (containsAny(text, "sql注入", "敏感", "密码", "密钥", "token", "xss", "csrf", "越权", "加密", "安全")) {
            return "SECURITY";
        }
        if (containsAny(text, "性能", "循环", "缓存", "索引", "并发", "线程", "内存", "o(n)", "批量")) {
            return "PERFORMANCE";
        }
        if (containsAny(text, "空指针", "npe", "异常", "资源", "关闭", "死锁", "边界", "判空", "类型转换")) {
            return "BUG";
        }
        if (containsAny(text, "命名", "注释", "重复", "复杂度", "可读", "魔法值", "日志", "格式", "风格")) {
            return "MAINTAINABILITY";
        }
        return "CODE_STYLE";
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private int computeLineNumber(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }

    private String normalize(String lang) {
        if (lang == null) return "";
        String l = lang.toUpperCase();
        if (l.startsWith("JAVASCRIPT") || l.equals("JS")) return "JS";
        if (l.startsWith("TYPESCRIPT") || l.equals("TS")) return "TS";
        if (l.startsWith("JAVA")) return "JAVA";
        if (l.startsWith("PY")) return "PY";
        return l;
    }
}
