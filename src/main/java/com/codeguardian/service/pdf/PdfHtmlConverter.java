package com.codeguardian.service.pdf;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTML到PDF转换器
 * 负责HTML预处理、字体管理等PDF转换相关的底层操作
 */
@Component
@Slf4j
public class PdfHtmlConverter {

    private static final Pattern MD_PREFIX = Pattern.compile("^\\s*#{1,6}(?:[:\\s]*)");
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /**
     * 验证HTML标签是否匹配
     */
    private boolean validateHtmlTags(String html) {
        java.util.Stack<String> stack = new java.util.Stack<>();
        java.util.regex.Pattern tagPattern = java.util.regex.Pattern.compile("<(/?)([a-zA-Z][a-zA-Z0-9]*)(?:\\s[^>]*)?(/?)>");
        java.util.regex.Matcher matcher = tagPattern.matcher(html);

        while (matcher.find()) {
            String closingSlash = matcher.group(1);
            String tagName = matcher.group(2);
            String selfClosingSlash = matcher.group(3);

            // 跳过自闭合标签和不需要闭合的标签
            if (selfClosingSlash != null && !selfClosingSlash.isEmpty()) {
                continue;
            }
            if (isVoidElement(tagName)) {
                continue;
            }

            if (closingSlash != null && !closingSlash.isEmpty()) {
                // 闭合标签
                if (!stack.isEmpty() && stack.peek().equalsIgnoreCase(tagName)) {
                    stack.pop();
                }
            } else {
                // 开始标签
                stack.push(tagName);
            }
        }

        if (!stack.isEmpty()) {
            log.warn("HTML标签未正确闭合: {}", stack);
        }
        return stack.isEmpty();
    }

    /**
     * 判断是否是void元素（不需要闭合的标签）
     */
    private boolean isVoidElement(String tagName) {
        return tagName.equalsIgnoreCase("img") ||
               tagName.equalsIgnoreCase("br") ||
               tagName.equalsIgnoreCase("hr") ||
               tagName.equalsIgnoreCase("input") ||
               tagName.equalsIgnoreCase("meta") ||
               tagName.equalsIgnoreCase("link");
    }

    /**
     * 预处理HTML以适配PDF生成
     */
    public String prepareHtmlForPdf(String html) {
        // 1. 移除脚本标签
        html = html.replaceAll("(?is)<script[^>]*>.*?</script>", "");

        // 2. 移除外部样式表链接
        html = html.replaceAll("(?is)<link[^>]*/?>", "");

        // 3. 清理HTML内容中的Markdown语法
        html = cleanMarkdownInHtml(html);

        // 4. 转义未转义的&符号
        html = escapeAmpersands(html);

        // 5. 展开CSS变量为实际颜色值
        html = expandCssVariables(html);

        // 6. 移除返回审查按钮
        html = html.replaceAll("<a class=\"back\"[^>]*>.*?</a>", "");

        // 7. 完全移除问题详情表格的表头行
        html = html.replaceAll("<div class=\"table-hd\">[^\"]*\".*?</div>\\n", "");

        // 8. 内联Prism代码高亮样式
        html = inlinePrismStyles(html);

        // 9. 替换Font Awesome图标为Unicode字符
        html = replaceIcons(html);

        // 10. 规范化自闭合标签
        html = normalizeSelfClosingTags(html);

        // 11. 验证HTML标签是否正确闭合
        if (!validateHtmlTags(html)) {
            log.warn("HTML预处理后标签未正确闭合，可能导致PDF生成失败");
        }

        return html;
    }

    /**
     * 清理HTML内容中的Markdown语法
     */
    private String cleanMarkdownInHtml(String html) {
        for (int i = 0; i < 5; i++) {
            html = html.replaceAll("(>)(\\s*)#{1,6}(?:[:\\s]*)", "$1$2");
            html = html.replaceAll("(>)([^<]*?)\\*\\*([^*]+?)\\*\\*([^<]*?)(<)", "$1$2$3$4$5");
            html = html.replaceAll("(>)([^<]*?)`([^`]+?)`([^<]*?)(<)", "$1$2$3$4$5");
            html = html.replaceAll("(>)([^<]*?)\\[([^\\]]+?)\\]\\([^\\)]+\\)([^<]*?)(<)", "$1$2$3$4$5");
            html = html.replaceAll("(>)([^<]*?)([-*+])(\\s+)([^<]*?)(<)", "$1$2$5$6");
            html = html.replaceAll("(>)([^<]*?)(>)(\\s+)([^<]*?)(<)", "$1$2$5$6");
            html = html.replaceAll("(>)([^<]*?)(#{1,6})([^<]*?)(<)", "$1$2$4$5");
        }

        html = html.replaceAll("(>)\\s*#+[:\\s]*", "$1");
        html = html.replaceAll("(</span>\\s*)#+[:\\s]*", "$1");
        html = html.replaceAll("(>)([^<]*?)(#{1,})([^<]*?)(<)", "$1$2$4$5");

        // 额外清理：直接在标签后跟#号的情况
        html = html.replaceAll("()</span>#", "$1");  // </span>#
        html = html.replaceAll("()</div>#", "$1");   // </div>#
        html = html.replaceAll("()>#", "$1");          // >#

        // 清理标签开头的#号
        html = html.replaceAll("#<", "<");

        return html;
    }

    /**
     * 转义未转义的&符号
     */
    private String escapeAmpersands(String html) {
        Map<String, String> entityMap = new HashMap<>();
        int placeholderIndex = 0;

        Pattern entityPattern = Pattern.compile("&(?:[a-zA-Z]+|#[0-9]+|#x[0-9a-fA-F]+);");
        Matcher entityMatcher = entityPattern.matcher(html);
        StringBuffer protectedHtml = new StringBuffer();

        while (entityMatcher.find()) {
            String entity = entityMatcher.group();
            String placeholder = "___ENTITY_" + placeholderIndex++ + "___";
            entityMap.put(placeholder, entity);
            entityMatcher.appendReplacement(protectedHtml, Matcher.quoteReplacement(placeholder));
        }
        entityMatcher.appendTail(protectedHtml);

        String escapedHtml = protectedHtml.toString().replace("&", "&amp;");

        for (Map.Entry<String, String> entry : entityMap.entrySet()) {
            escapedHtml = escapedHtml.replace(entry.getKey(), entry.getValue());
        }

        return escapedHtml;
    }

    /**
     * 展开CSS变量为实际颜色值
     */
    private String expandCssVariables(String html) {
        // 替换基础CSS变量 - 直接使用已注册的字体名称
        html = html.replace("*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font-family:-apple-system,BlinkMacSystemFont,\"Segoe UI\",Helvetica,Arial,sans-serif}",
                "*{box-sizing:border-box}body{margin:0;background:#0d1117;color:#c9d1d9;font-family:ArialUnicode}");

        // 替换代码编辑器字体，使用已注册的字体
        html = html.replaceAll("'Monaco','Menlo','Ubuntu Mono','Consolas','source-code-pro',monospace",
                "'Monaco','Menlo','Consolas',ArialUnicode");

        // 替换diff区域的字体
        html = html.replaceAll("font-family:Monaco,Menlo,Consolas,monospace",
                "font-family:'Monaco','Menlo',Consolas',ArialUnicode");

        // 替换所有CSS变量引用
        html = html.replaceAll("var\\(--bg\\)", "#0d1117");
        html = html.replaceAll("var\\(--card\\)", "#161b22");
        html = html.replaceAll("var\\(--text\\)", "#c9d1d9");
        html = html.replaceAll("var\\(--text2\\)", "#8b949e");
        html = html.replaceAll("var\\(--border\\)", "#30363d");
        html = html.replaceAll("var\\(--primary\\)", "#58a6ff");
        html = html.replaceAll("var\\(--critical\\)", "#f44336");
        html = html.replaceAll("var\\(--high\\)", "#ff9800");
        html = html.replaceAll("var\\(--medium\\)", "#ffc107");
        html = html.replaceAll("var\\(--low\\)", "#4caf50");
        html = html.replaceAll("var\\(--editor-bg\\)", "#0d1117");
        html = html.replaceAll("var\\(--editor-line-number\\)", "#6e7681");
        html = html.replaceAll("var\\(--editor-text\\)", "#c9d1d9");
        html = html.replaceAll("var\\(--editor-padding\\)", "20px");

        return html;
    }

    /**
     * 内联Prism代码高亮样式
     */
    private String inlinePrismStyles(String html) {
        String prismStyles = buildPrismStyles();
        return html.replace("</style>", "</style>\n" + prismStyles);
    }

    /**
     * 构建Prism样式字符串
     */
    private String buildPrismStyles() {
        return "<style>\n" +
                "/* 通用样式：确保所有文本使用中文字体 */\n" +
                "body {\n" +
                "  font-family: ArialUnicode, sans-serif;\n" +
                "}\n" +
                ".row, .badge, .loc, .muted, .diff-title, .panel-bd, .panel-hd {\n" +
                "  font-family: ArialUnicode, sans-serif;\n" +
                "}\n" +
                "/* 确保badge的背景色和文字颜色正确显示 */\n" +
                ".badge.critical { background: #f44336 !important; color: #fff !important; }\n" +
                ".badge.high { background: #ff9800 !important; color: #fff !important; }\n" +
                ".badge.medium { background: #ffc107 !important; color: #fff !important; }\n" +
                ".badge.low { background: #4caf50 !important; color: #fff !important; }\n" +
                "/* 去掉badge的多余空白，调整右间距 */\n" +
                ".badge { margin: 0 !important; margin-right: 8px !important; padding: 2px 8px !important; gap: 0 !important; }\n" +
                "/* 去掉badge内图标的额外间距 */\n" +
                ".badge i { margin: 0 !important; padding: 0 !important; display: inline !important; }\n" +
                "/* 强制设置问题行的样式，确保分隔效果 */\n" +
                ".row { border-bottom: 3px solid #30363d !important; margin-bottom: 30px !important; padding-bottom: 20px !important; }\n" +
                "/* 为每个子元素添加下边距，增加行之间的垂直间距 */\n" +
                ".row .loc { margin-bottom: 12px !important; }\n" +
                ".row > div:nth-child(3) { margin-bottom: 12px !important; }\n" +
                "/* 描述和建议标签样式 */\n" +
                ".desc-label { color: #8b949e !important; font-weight: 600 !important; }\n" +
                ".suggest-label { color: #8b949e !important; font-weight: 600 !important; margin-top: 16px !important; display: inline-block !important; }\n" +
                "/* Prism Tomorrow Theme for PDF */\n" +
                "code[class*=\"language-\"], pre[class*=\"language-\"] {\n" +
                "  color: #c9d1d9;\n" +
                "  background: #0d1117;\n" +
                "  text-shadow: none;\n" +
                "  font-family: 'Monaco', 'Menlo', 'ArialUnicode', monospace;\n" +
                "  font-size: 12px;\n" +
                "  line-height: 1.6;\n" +
                "}\n" +
                ".code-editor-container {\n" +
                "  display: table;\n" +
                "  width: 100%;\n" +
                "  table-layout: fixed;\n" +
                "  background: #0d1117;\n" +
                "  border-collapse: collapse;\n" +
                "}\n" +
                ".line-numbers {\n" +
                "  display: table-cell;\n" +
                "  vertical-align: top;\n" +
                "  width: 50px;\n" +
                "  padding: 0 8px 0 0;\n" +
                "  text-align: right;\n" +
                "  border-right: 1px solid #30363d;\n" +
                "  font-family: 'Monaco', 'Menlo', 'ArialUnicode', monospace;\n" +
                "  font-size: 12px;\n" +
                "  line-height: 19.2px;\n" +
                "  color: #6e7681;\n" +
                "  background-color: #0d1117;\n" +
                "  white-space: pre;\n" +
                "  box-sizing: border-box;\n" +
                "}\n" +
                ".code-editor-pre {\n" +
                "  display: table-cell;\n" +
                "  vertical-align: top;\n" +
                "  margin: 0;\n" +
                "  padding: 0;\n" +
                "  font-family: 'Monaco', 'Menlo', 'ArialUnicode', monospace;\n" +
                "  font-size: 12px;\n" +
                "  line-height: 19.2px;\n" +
                "  background-color: transparent;\n" +
                "  white-space: pre;\n" +
                "  overflow-x: auto;\n" +
                "  overflow-y: hidden;\n" +
                "  box-sizing: border-box;\n" +
                "  width: auto;\n" +
                "  max-width: 100%;\n" +
                "}\n" +
                ".code-editor-wrapper {\n" +
                "  display: block;\n" +
                "  width: 100%;\n" +
                "  overflow-x: auto;\n" +
                "  overflow-y: visible;\n" +
                "  background: #0d1117;\n" +
                "  page-break-inside: avoid;\n" +
                "  break-inside: avoid;\n" +
                "  box-sizing: border-box;\n" +
                "}\n" +
                ".config-panel {\n" +
                "  page-break-inside: avoid;\n" +
                "  break-inside: avoid;\n" +
                "  margin-bottom: 12px;\n" +
                "}\n" +
                ".config-panel .panel-bd {\n" +
                "  max-height: none;\n" +
                "  overflow: visible;\n" +
                "  padding: 8px 18px;\n" +
                "  flex: 0 0 auto;\n" +
                "}\n" +
                ".panel.code-panel {\n" +
                "  page-break-before: always;\n" +
                "  break-before: page;\n" +
                "}\n" +
                ".panel.table {\n" +
                "  page-break-before: always;\n" +
                "  break-before: page;\n" +
                "}\n" +
                ".token.comment, .token.prolog, .token.doctype, .token.cdata {\n" +
                "  color: #8b949e;\n" +
                "}\n" +
                ".token.punctuation {\n" +
                "  color: #c9d1d9;\n" +
                "}\n" +
                ".token.property, .token.tag, .token.boolean, .token.number, .token.constant, .token.symbol, .token.deleted {\n" +
                "  color: #79c0ff;\n" +
                "}\n" +
                ".token.selector, .token.attr-name, .token.string, .token.char, .token.builtin, .token.inserted {\n" +
                "  color: #a5d6ff;\n" +
                "}\n" +
                ".token.operator, .token.entity, .token.url, .language-css .token.string, .style .token.string {\n" +
                "  color: #ff7b72;\n" +
                "}\n" +
                ".token.atrule, .token.attr-value, .token.keyword {\n" +
                "  color: #ff7b72;\n" +
                "}\n" +
                ".token.function, .token.class-name {\n" +
                "  color: #d2a8ff;\n" +
                "}\n" +
                ".token.regex, .token.important, .token.variable {\n" +
                "  color: #ffa657;\n" +
                "}\n" +
                "</style>\n";
    }

    /**
     * 替换Font Awesome图标为简单符号或删除
     * PDF渲染器无法显示emoji，使用空格替代
     */
    private String replaceIcons(String html) {
        // 先处理复杂的组合图标
        html = html.replace("<i class=\"fas fa-shield-alt finding-icon finding-icon-shield\"></i><i class=\"fas fa-check finding-icon finding-icon-check\"></i>", "");

        // 再处理简单的单个图标 - 使用空格替代，避免PDF显示#
        html = html.replace("<i class=\"fas fa-arrow-left\"></i>", "←");
        html = html.replace("<i class=\"fas fa-shield-alt\"></i>", "");
        html = html.replace("<i class=\"fas fa-check\"></i>", "");
        html = html.replace("<i class=\"fas fa-bug\"></i>", "");
        html = html.replace("<i class=\"fas fa-cog\"></i>", "");
        html = html.replace("<i class=\"fas fa-chart-bar\"></i>", "");

        // 处理带有不同属性的图标（使用更安全的正则表达式）
        html = html.replaceAll("<i class=\"fas fa-shield-alt[^\"]*\"></i>", "");
        html = html.replaceAll("<i class=\"fas fa-check[^\"]*\"></i>", "");
        html = html.replaceAll("<i class=\"fas fa-bug[^\"]*\"></i>", "");
        html = html.replaceAll("<i class=\"fas fa-cog[^\"]*\"></i>", "");
        html = html.replaceAll("<i class=\"fas fa-chart-bar[^\"]*\"></i>", "");

        return html;
    }

    /**
     * 规范化自闭合标签
     */
    private String normalizeSelfClosingTags(String html) {
        return html.replaceAll("(?i)<(meta|img|br|hr|input|area|base|col|embed|source|track|wbr)([^>]*?)(?<!\\s/)(?<!/)>", "<$1$2 />");
    }

    /**
     * 加载字体文件
     * @return 字体文件，如果加载失败返回null
     */
    public FontLoadResult loadFont() {
        try {
            Resource fontResource = new ClassPathResource("fonts/ArialUnicode.ttf");
            if (!fontResource.exists()) {
                log.error("字体文件不存在: fonts/ArialUnicode.ttf");
                return FontLoadResult.failure("字体文件不存在");
            }

            log.info("开始加载字体文件: fonts/ArialUnicode.ttf");
            File fontFile = tryGetFontFile(fontResource);
            if (fontFile == null) {
                log.error("无法获取字体文件");
                return FontLoadResult.failure("无法读取字体文件");
            }

            log.info("字体文件加载成功: path={}, size={} bytes", fontFile.getAbsolutePath(), fontFile.length());

            // 验证字体文件
            if (!validateFontFile(fontFile)) {
                log.warn("字体文件验证失败，但仍然尝试使用");
                // 不再返回失败，让PDF渲染器尝试使用
            }

            return FontLoadResult.success(fontFile);
        } catch (Exception e) {
            log.error("加载中文字体异常: {}", e.getMessage(), e);
            return FontLoadResult.failure(e.getMessage());
        }
    }

    /**
     * 尝试获取字体文件
     */
    private File tryGetFontFile(Resource fontResource) throws IOException {
        try {
            // 尝试直接获取文件（开发环境）
            File fontFile = fontResource.getFile();
            if (fontFile.exists() && fontFile.canRead() && fontFile.length() >= 1024) {
                return fontFile;
            }
            throw new IOException("字体文件不可读");
        } catch (IOException e) {
            // 如果在JAR中运行，复制到临时文件
            return copyToTempFile(fontResource);
        }
    }

    /**
     * 复制字体到临时文件
     */
    private File copyToTempFile(Resource fontResource) throws IOException {
        try (InputStream is = fontResource.getInputStream()) {
            if (is == null) {
                throw new IOException("无法读取字体文件输入流");
            }
            File fontFile = File.createTempFile("ArialUnicode", ".ttf");
            fontFile.deleteOnExit();
            long copied = Files.copy(is, fontFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

            if (!fontFile.exists() || fontFile.length() == 0 || copied == 0) {
                throw new IOException("临时字体文件创建失败，大小: " + fontFile.length());
            }
            if (fontFile.length() < 1024) {
                throw new IOException("临时字体文件大小异常: " + fontFile.length() + " bytes");
            }
            return fontFile;
        }
    }

    /**
     * 验证字体文件是否有效
     */
    private boolean validateFontFile(File fontFile) {
        if (fontFile == null || !fontFile.exists()) {
            log.warn("字体验证失败: 文件不存在");
            return false;
        }

        if (fontFile.length() < 1024) {
            log.warn("字体验证失败: 文件大小过小 ({} bytes)", fontFile.length());
            return false;
        }

        try (FileInputStream fis = new FileInputStream(fontFile)) {
            byte[] header = new byte[4];
            if (fis.read(header) == 4) {
                log.info("字体文件头: [{}, {}, {}, {}]",
                        String.format("0x%02X", header[0]),
                        String.format("0x%02X", header[1]),
                        String.format("0x%02X", header[2]),
                        String.format("0x%02X", header[3]));

                // 检查TTF/OTF文件头
                if (header[0] == 0x00 && header[1] == 0x01 && header[2] == 0x00 && header[3] == 0x00) {
                    log.info("字体文件验证通过: TTF格式");
                    return true; // TTF
                }
                if (header[0] == 'O' && header[1] == 'T' && header[2] == 'T' && header[3] == 'O') {
                    log.info("字体文件验证通过: OTF格式");
                    return true; // OTF
                }
                if (header[0] == 't' && header[1] == 't' && header[2] == 'c' && header[3] == 'f') {
                    log.info("字体文件验证通过: TTC格式");
                    return true; // TTC
                }

                log.warn("字体文件格式未知，可能不是有效的TTF/OTF文件");
            }
            return false;
        } catch (IOException e) {
            log.error("字体验证失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * 移除HTML中的字体引用（当字体加载失败时）
     * @deprecated 使用 ensureSystemFontFallback 代替
     */
    @Deprecated
    public String removeFontReferences(String html) {
        html = html.replace("font-family:ArialUnicode,", "font-family:");
        html = html.replace("font-family:ArialUnicode;", "font-family:sans-serif;");
        return html.replaceAll("ArialUnicode", "sans-serif");
    }

    /**
     * 确保使用系统字体回退（当字体加载失败时）
     * 将自定义字体名称替换为PDF渲染器能识别的系统字体
     */
    public String ensureSystemFontFallback(String html) {
        // 将 ArialUnicode 替换为 PDF 渲染器支持的中文字体序列
        // STSong-Light, STSong, MHei, MSung 等是常见的PDF中文字体
        html = html.replaceAll("ArialUnicode", "STSong-Light, STSong, MHei, MSung, sans-serif");

        // 确保代码编辑器也使用支持中文的字体
        html = html.replaceAll("'Monaco', 'Menlo', 'ArialUnicode', monospace",
                "'Monaco', 'Menlo', 'STSong-Light', 'Courier New', monospace");

        return html;
    }

    /**
     * 生成PDF文件名
     */
    public String generatePdfFileName(Long taskId) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
        return "review_report_" + taskId + "_" + timestamp + ".pdf";
    }

    /**
     * 字体加载结果
     */
    public static class FontLoadResult {
        private final boolean success;
        private final File fontFile;
        private final String errorMessage;

        private FontLoadResult(boolean success, File fontFile, String errorMessage) {
            this.success = success;
            this.fontFile = fontFile;
            this.errorMessage = errorMessage;
        }

        public static FontLoadResult success(File fontFile) {
            return new FontLoadResult(true, fontFile, null);
        }

        public static FontLoadResult failure(String errorMessage) {
            return new FontLoadResult(false, null, errorMessage);
        }

        public boolean isSuccess() {
            return success;
        }

        public File getFontFile() {
            return fontFile;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }

    /**
     * Resource接口，用于解耦Spring的Resource
     */
    public interface Resource {
        boolean exists() throws IOException;
        File getFile() throws IOException;
        InputStream getInputStream() throws IOException;
    }

    /**
     * Spring Resource适配器
     */
    private static class SpringResourceAdapter implements Resource {
        private final org.springframework.core.io.Resource delegate;

        SpringResourceAdapter(org.springframework.core.io.Resource delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean exists() throws IOException {
            return delegate.exists();
        }

        @Override
        public File getFile() throws IOException {
            return delegate.getFile();
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return delegate.getInputStream();
        }
    }

    /**
     * ClassPathResource适配器
     */
    private static class ClassPathResource implements Resource {
        private final Resource adapter;

        ClassPathResource(String location) {
            this.adapter = new SpringResourceAdapter(new org.springframework.core.io.ClassPathResource(location));
        }

        @Override
        public boolean exists() throws IOException {
            return adapter.exists();
        }

        @Override
        public File getFile() throws IOException {
            return adapter.getFile();
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return adapter.getInputStream();
        }
    }
}
