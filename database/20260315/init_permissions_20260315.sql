-- ============================================
-- CodeGuardian AI - 初始化权限与管理员脚本（最新）
-- 版本: v2.1.0
-- 日期: 2026-03-15
-- 说明: 与当前实体结构一致，避免类型不匹配
-- ============================================

-- 1) 角色
INSERT INTO roles (code, name, description, status)
VALUES
  ('ADMIN', '管理员', '系统管理员，拥有所有权限', 0),
  ('REVIEWER', '审查员', '可以创建和查看审查任务', 0),
  ('VIEWER', '查看者', '只能查看审查任务和报告', 0)
ON CONFLICT (code) DO NOTHING;

-- 2) 权限（resource/action 采用整数编码：READ=1, CREATE=2, UPDATE=3, ALL=99；TASK=1, REPORT=2, CONFIG=3, ALL=99）
INSERT INTO permissions (code, name, description, resource, action, created_at)
VALUES
  ('QUERY',  '查询权限',     '可以查看审查任务、报告、历史记录等', 1, 1, CURRENT_TIMESTAMP),
  ('REVIEW', '审查权限',     '可以创建和执行代码审查任务',         1, 2, CURRENT_TIMESTAMP),
  ('CONFIG', '配置权限',     '可以修改系统配置、AI配置等',         3, 3, CURRENT_TIMESTAMP),
  ('ADMIN',  '管理员权限',   '拥有所有权限，包括用户与角色管理',    99, 99, CURRENT_TIMESTAMP)
ON CONFLICT (code) DO NOTHING;

-- 3) 角色-权限绑定
-- 管理员：全部
INSERT INTO role_permissions (role_id, permission_id, created_at)
SELECT r.id, p.id, CURRENT_TIMESTAMP
FROM roles r, permissions p
WHERE r.code = 'ADMIN'
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- 审查员：QUERY + REVIEW
INSERT INTO role_permissions (role_id, permission_id, created_at)
SELECT r.id, p.id, CURRENT_TIMESTAMP
FROM roles r
JOIN permissions p ON p.code IN ('QUERY','REVIEW')
WHERE r.code = 'REVIEWER'
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- 查看者：QUERY
INSERT INTO role_permissions (role_id, permission_id, created_at)
SELECT r.id, p.id, CURRENT_TIMESTAMP
FROM roles r
JOIN permissions p ON p.code = 'QUERY'
WHERE r.code = 'VIEWER'
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- 4) 默认管理员账号（注意：生产环境请替换密码哈希）
INSERT INTO users (username, email, password_hash, real_name, status, created_at)
VALUES ('admin', 'admin@codeguardian.com', '$2a$10$bqoXICof7SwJ86evsdP82OYrfzyTMO9iAEzaG7exhSqFMDStIbTlG', '系统管理员', 0, CURRENT_TIMESTAMP)
ON CONFLICT (username) DO NOTHING;

-- 绑定管理员角色
INSERT INTO user_roles (user_id, role_id, created_at)
SELECT u.id, r.id, CURRENT_TIMESTAMP
FROM users u, roles r
WHERE u.username = 'admin' AND r.code = 'ADMIN'
ON CONFLICT (user_id, role_id) DO NOTHING;

-- 完成
