-- ============================================
-- CodeGuardian AI - 初始化示例数据脚本
-- 版本: v2.1.0
-- 日期: 2026-05-29
-- 说明: 与当前实体枚举值一致（ReviewType/TaskStatus/Severity 为整数）
-- ============================================

DO $$
DECLARE
  admin_user_id BIGINT;
BEGIN
  SELECT id INTO admin_user_id FROM users WHERE username = 'admin' LIMIT 1;
  IF admin_user_id IS NULL THEN
    RAISE EXCEPTION '管理员用户不存在，请先执行 init_permissions_20260529.sql';
  END IF;

  -- 1) 示例审查任务
  -- SNIPPET=3, FILE=2, DIRECTORY=1；COMPLETED=2, RUNNING=1
  INSERT INTO review_tasks (name, review_type, scope, status, created_at, completed_at)
  VALUES
    ('示例代码片段审查', 3, 'public class Test {
        public void method(String str) {
            System.out.println(str.length());
        }
    }', 2, CURRENT_TIMESTAMP - INTERVAL '2 days', CURRENT_TIMESTAMP - INTERVAL '2 days' + INTERVAL '30 seconds'),
    ('示例文件审查',      2, '/path/to/example/FileService.java', 1, CURRENT_TIMESTAMP - INTERVAL '1 hour', NULL),
    ('示例目录审查',      1, '/path/to/src/main/java', 2, CURRENT_TIMESTAMP - INTERVAL '1 day', CURRENT_TIMESTAMP - INTERVAL '1 day' + INTERVAL '2 minutes');

  -- 2) 示例审查发现
  -- severity: CRITICAL=0, HIGH=1, MEDIUM=2, LOW=3
  INSERT INTO findings (task_id, severity, title, location, start_line, end_line, description, suggestion, category, source)
  VALUES
    (1, 1, '潜在的空指针异常', '第3行', 3, 3,
     '变量str在使用前未进行空值检查，如果传入null会导致NullPointerException',
     '在使用str前添加空值检查：if (str != null) { System.out.println(str.length()); }',
     'BUG', 'AI'),
    (1, 2, '代码风格问题', '第2-4行', 2, 4,
     '方法名method不够描述性，建议使用更具描述性的名称',
     '建议将方法名改为更具描述性的名称，如：printStringLength',
     'CODE_STYLE', 'RuleEngine'),
    (1, 3, '缺少文档注释', '第2行', 2, 2,
     '公共方法缺少JavaDoc注释',
     '为方法添加JavaDoc注释，说明方法的功能、参数和返回值',
     'MAINTAINABILITY', 'AI'),
    (3, 0, 'SQL注入风险', 'UserService.java:45', 45, 45,
     '使用字符串拼接构建SQL查询，存在SQL注入风险',
     '使用PreparedStatement或参数化查询来防止SQL注入',
     'SECURITY', 'Semgrep'),
    (3, 1, '资源未关闭', 'FileService.java:78', 78, 78,
     '文件流在使用后未正确关闭，可能导致资源泄漏',
     '使用try-with-resources语句确保资源自动关闭',
     'BUG', 'AI'),
    (3, 2, '性能问题', 'DataProcessor.java:120', 120, 125,
     '在循环中进行数据库查询，存在N+1查询问题',
     '使用批量查询或JOIN查询来减少数据库访问次数',
     'PERFORMANCE', 'AI');

  -- 3) 示例审查报告
  INSERT INTO review_reports (task_id, html_content, markdown_content, statistics, created_at)
  VALUES
    (1,
     '<!DOCTYPE html><html><head><title>代码审查报告</title></head><body><h1>代码审查报告</h1><p>任务名称: 示例代码片段审查</p><p>问题总数: 3</p></body></html>',
     '# 代码审查报告

**任务名称**: 示例代码片段审查

**问题总数**: 3

## 问题详情

1. **潜在的空指针异常** (HIGH)
2. **代码风格问题** (MEDIUM)
3. **缺少文档注释** (LOW)',
     '{"total":3,"critical":0,"high":1,"medium":1,"low":1,"categories":{"BUG":1,"CODE_STYLE":1,"MAINTAINABILITY":1}}',
     CURRENT_TIMESTAMP - INTERVAL '2 days'),
    (3,
     '<!DOCTYPE html><html><head><title>代码审查报告</title></head><body><h1>代码审查报告</h1><p>任务名称: 示例目录审查</p><p>问题总数: 3</p></body></html>',
     '# 代码审查报告

**任务名称**: 示例目录审查

**问题总数**: 3

## 问题详情

1. **SQL注入风险** (CRITICAL)
2. **资源未关闭** (HIGH)
3. **性能问题** (MEDIUM)',
     '{"total":3,"critical":1,"high":1,"medium":1,"low":0,"categories":{"SECURITY":1,"BUG":1,"PERFORMANCE":1}}',
     CURRENT_TIMESTAMP - INTERVAL '1 day');
END $$;

-- 完成
