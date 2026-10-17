-- ============================================
-- CodeGuardian AI - 初始化权限数据脚本
-- 版本: v1.1.0
-- 日期: 2025-12-04
-- 说明: 初始化角色、权限和默认管理员用户
-- ============================================

-- ============================================
-- 1. 初始化角色数据
-- ============================================

INSERT INTO roles (code, name, description) VALUES
('ADMIN', '管理员', '系统管理员，拥有所有权限'),
('REVIEWER', '审查员', '可以创建和查看审查任务'),
('VIEWER', '查看者', '只能查看审查任务和报告')
ON CONFLICT (code) DO NOTHING;

-- ============================================
-- 2. 初始化权限数据
-- ============================================

INSERT INTO permissions (code, name, description, resource, action) VALUES
('QUERY', '查询权限', '可以查看审查任务、报告、历史记录等', 'TASK,REPORT', 'READ'),
('REVIEW', '审查权限', '可以创建和执行代码审查任务', 'TASK', 'CREATE,READ'),
('CONFIG', '配置权限', '可以修改系统配置、AI配置等', 'CONFIG', 'READ,UPDATE'),
('ADMIN', '管理员权限', '拥有所有权限，包括用户管理、角色管理等', 'ALL', 'ALL')
ON CONFLICT (code) DO NOTHING;

-- ============================================
-- 3. 初始化角色权限关联
-- ============================================

-- 管理员角色拥有所有权限
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r, permissions p
WHERE r.code = 'ADMIN'
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- 审查员角色拥有查询和审查权限
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r, permissions p
WHERE r.code = 'REVIEWER' AND p.code IN ('QUERY', 'REVIEW')
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- 查看者角色只拥有查询权限
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r, permissions p
WHERE r.code = 'VIEWER' AND p.code = 'QUERY'
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- ============================================
-- 4. 创建默认管理员用户
-- ============================================
-- 注意：密码为 admin123，实际使用时请修改
-- 密码哈希值使用BCrypt加密，示例：$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy
-- 实际使用时，应该使用应用层生成的BCrypt哈希值

INSERT INTO users (username, email, password_hash, real_name, status) VALUES
('admin', 'admin@codeguardian.com', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', '系统管理员', 'ACTIVE')
ON CONFLICT (username) DO NOTHING;

-- 为管理员用户分配管理员角色
INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id
FROM users u, roles r
WHERE u.username = 'admin' AND r.code = 'ADMIN'
ON CONFLICT (user_id, role_id) DO NOTHING;

-- ============================================
-- 脚本执行完成
-- ============================================
-- 默认管理员账号信息：
-- 用户名: admin
-- 密码: admin123（请在生产环境中立即修改）
-- 邮箱: admin@codeguardian.com

