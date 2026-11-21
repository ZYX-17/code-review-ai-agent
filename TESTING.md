# 全功能测试用例（E2E）

本文件描述 CodeGuardian AI 的端到端测试用例，配套脚本为
[`tools/e2e/run_full_e2e.py`](tools/e2e/run_full_e2e.py)。

## 运行前提

- PostgreSQL / Redis / MinIO 已启动（默认 `192.168.150.101`，见 `config/application-local.yml`）
- 应用已启动在 `http://localhost:7003`，并设置：
  - `API_TOKEN=e2e-token`（CI/CD 与 Webhook 测试使用）
  - `PUBLIC_BASE_URL=http://localhost:7003`（报告链接）
- 管理员账号可登录（默认 `admin/admin123`）
- 本机 Python 3.10+，已安装 `requests`

```powershell
set API_TOKEN=e2e-token
set PUBLIC_BASE_URL=http://localhost:7003
mvn spring-boot:run
```

另开一个终端：

```powershell
python tools/e2e/run_full_e2e.py
```

脚本会自动启动本地假 Webhook（18081）、创建测试数据，并在结束后清理。
测试报告输出到 `target/e2e-report.json`。

## 用例清单

### 认证与用户

| ID | 场景 | 预期 |
|---|---|---|
| AUTH-01 | 管理员登录 | 返回 token，可访问受保护接口 |
| AUTH-02 | 错误密码登录 | 返回 400 |
| AUTH-03 | 未登录访问受保护接口 | 返回 401/403 |
| AUTH-04 | 登录页可访问 | 返回 200 |
| USER-01 | 创建测试用户 | 创建成功并返回用户 ID |
| USER-02 | 用户列表 | 包含新用户 |
| USER-03 | 更新用户资料 | 资料更新成功 |
| USER-04 | 用户自助修改密码 | 旧密码失效，新密码可登录 |
| USER-05 | 管理员重置密码 | 新密码可登录 |

### 角色、权限、分类

| ID | 场景 | 预期 |
|---|---|---|
| ROLE-01 | 角色与权限列表 | 包含 ADMIN、CONFIG |
| ROLE-02 | 创建角色并分配权限 | 创建、分配成功 |
| ROLE-03 | 更新并删除角色 | 操作成功 |
| PERM-01 | 创建、更新、删除自定义权限 | 操作成功 |
| CAT-01 | 分类创建、更新、查询、删除 | 操作成功 |

### 设置

| ID | 场景 | 预期 |
|---|---|---|
| SET-01 | 导出、保存、非法校验、恢复设置 | maxIssues 生效；非法 Webhook 返回 400；恢复成功 |

### 知识库与 RAG

| ID | 场景 | 预期 |
|---|---|---|
| KB-01 | 创建知识库 | 创建成功 |
| KB-02 | 知识库列表 | 包含新知识库 |
| KB-03 | 上传文档 | 上传成功，页面可见 |
| KB-04 | 按知识库过滤文档 | 仅返回目标知识库文档 |
| KB-05 | 更新知识库 | 更新成功 |
| KB-06 | 删除有文档的知识库 | 被拒绝 |
| RAG-01 | 指定知识库范围的 RAG 审查 | 任务完成 |

### 审查与报告

| ID | 场景 | 预期 |
|---|---|---|
| REV-01 | 代码片段规则审查 | 任务完成 |
| REV-02 | 任务详情与问题列表 | 数据正确 |
| REV-03 | 审查历史查询 | 包含测试任务 |
| REV-04 | 文件审查 | 任务完成 |
| REV-05 | 目录审查 | 任务完成 |
| REV-06 | 项目审查 | 任务完成 |
| REV-07 | Git 克隆与文件树 | 克隆成功，文件树正确 |
| REV-08 | Git 项目审查（分支 + commit） | 任务完成 |
| REV-09 | 增量审查（changedFiles） | 任务完成 |
| REV-10 | AI 审查（真实模型） | 任务完成 |
| REPORT-01 | 报告生成与 HTML/Markdown/PDF | 三种格式均可导出 |
| REPORT-02 | 报告页面 | 页面可访问 |

### CI/CD 与 Webhook

| ID | 场景 | 预期 |
|---|---|---|
| CICD-01 | 无令牌调用 CI/CD | 返回 401 |
| CICD-02 | 错误令牌调用 CI/CD | 返回 401 |
| CICD-03 | CI/CD 触发审查并轮询 | 任务完成，门禁结果可读 |
| CICD-04 | CI/CD 增量审查 | 任务完成 |
| WEBHOOK-01 | GitHub 签名错误 | 返回 401 |
| WEBHOOK-02 | GitHub Push（HMAC 正确） | 返回 200 并触发审查 |
| WEBHOOK-03 | GitLab 令牌错误 | 返回 401 |
| WEBHOOK-04 | GitLab MR 事件 | 返回 200 并触发审查 |
| WEBHOOK-05 | GitCode MR 事件 | 返回 200 并触发审查 |
| WEBHOOK-06 | GitLab 非 MR 事件 | 返回 200 并忽略 |

### 通知、页面与日志

| ID | 场景 | 预期 |
|---|---|---|
| NOTIFY-01 | 通知配置测试消息 | 假 Webhook 收到 `event=test` |
| NOTIFY-02 | 审查完成自动通知 | 收到 `event=review.completed` |
| PAGE-01 | 主要页面渲染 | 页面均返回 200 |
| LOG-01 | 操作日志 | 有记录且不泄露密码 |
| DASH-01 | 仪表盘页面 | 正常渲染 |

## 最近一次运行结果

运行时间：2026-11-22 01:04（本地 Windows + VM 中间件）

| 总计 | 通过 | 失败 | 耗时 |
|---|---|---|---|
| 49 | 49 | 0 | 18.9s |

同时执行了单元测试：`Tests run: 64, Failures: 0, Errors: 0, Skipped: 0`。

测试过程中发现并修复的问题：

- 角色重复分配权限时，同一事务内先删后插未 flush，触发 `uk_role_permissions_unique` 唯一约束冲突
- Ollama 可达但未拉取配置的模型时仍会被注册为默认 DeepSeek 提供方，导致 AI 审查 404；现在会校验 `/api/tags` 中的模型是否存在

完整结果见 `target/e2e-report.json`。

