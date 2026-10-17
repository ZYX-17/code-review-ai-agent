# CodeGuardian AI - 数据库脚本说明

## 文件说明

- **schema.sql**: 数据库表结构创建脚本，包含表、索引、触发器、视图、存储过程等
- **init_permissions.sql**: 初始化权限数据脚本，包含角色、权限和默认管理员用户
- **init_data.sql**: 初始化示例数据脚本，用于测试和演示

## 使用方法

### 1. 创建数据库

```bash
# 使用psql连接PostgreSQL
psql -U postgres

# 创建数据库
CREATE DATABASE codeguardian
    WITH 
    OWNER = postgres
    ENCODING = 'UTF8'
    LC_COLLATE = 'en_US.utf8'
    LC_CTYPE = 'en_US.utf8'
    TABLESPACE = pg_default
    CONNECTION LIMIT = -1;

# 退出
\q
```

### 2. 执行建表脚本

```bash
# 执行schema.sql创建表结构
psql -U postgres -d codeguardian -f schema.sql

# 或者使用psql交互式执行
psql -U postgres -d codeguardian
\i schema.sql
```

### 3. 执行权限初始化脚本（必需）

```bash
# 执行init_permissions.sql初始化角色、权限和默认管理员
psql -U postgres -d codeguardian -f init_permissions.sql
```

**重要提示**: 
- 默认管理员账号：`admin` / `admin123`
- 生产环境请立即修改默认密码！

### 4. 执行示例数据脚本（可选）

```bash
# 执行init_data.sql插入示例数据
psql -U postgres -d codeguardian -f init_data.sql
```

## 验证安装

```sql
-- 检查表是否创建成功
\dt

-- 检查索引
\di

-- 检查视图
\dv

-- 检查函数
\df

-- 查看角色和权限
SELECT * FROM roles;
SELECT * FROM permissions;
SELECT * FROM role_permissions;

-- 查看用户权限视图
SELECT * FROM v_user_permissions WHERE username = 'admin';

-- 测试权限检查函数
SELECT check_user_permission(1, 'ADMIN'); -- 应该返回 true

-- 查看任务统计视图
SELECT * FROM v_task_statistics LIMIT 10;

-- 查看问题分类统计视图
SELECT * FROM v_finding_category_statistics;
```

## 清理数据（测试用）

```sql
-- 清理所有数据（谨慎使用）
TRUNCATE TABLE review_reports CASCADE;
TRUNCATE TABLE findings CASCADE;
TRUNCATE TABLE review_tasks CASCADE;

-- 或者使用存储过程清理过期数据
SELECT * FROM cleanup_old_tasks(0); -- 清理所有已完成的任务
```

## 注意事项

1. **生产环境**: 在生产环境执行前，请先备份现有数据
2. **权限**: 确保数据库用户有足够的权限创建表、索引等对象
3. **扩展**: 脚本会自动创建 `pg_trgm` 和 `btree_gin` 扩展，如果不存在会报错，需要先安装这些扩展
4. **版本**: 建议使用 PostgreSQL 15+ 版本

## 常见问题

### Q: 执行脚本时提示扩展不存在？
A: 需要先安装PostgreSQL扩展：
```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS btree_gin;
```

### Q: 如何重置数据库？
A: 删除并重新创建数据库：
```sql
DROP DATABASE IF EXISTS codeguardian;
CREATE DATABASE codeguardian;
-- 然后重新执行schema.sql
```

### Q: 如何备份数据库？
A: 使用pg_dump命令：
```bash
pg_dump -U postgres -d codeguardian -F c -f codeguardian_backup.dump
```

