# CodeGuardian AI - 代码审查AI Agent

## 项目简介

CodeGuardian AI 是一个基于 Spring Boot 3.x 和 Java 21 的智能代码审查系统。它能够自动化地对代码进行深度审查，识别潜在的bug、安全漏洞、性能问题和代码异味，并生成详细的审查报告。

## 技术栈

- **Java**: 21
- **Spring Boot**: 3.2.0
- **数据库**: PostgreSQL
- **构建工具**: Maven
- **其他**: Lombok, Jackson, OkHttp, JavaParser, Thymeleaf

## 核心功能

- ✅ 代码片段审查
- ✅ 单个文件审查
- ✅ 目录审查
- ✅ 项目审查
- ✅ Git项目审查
- ✅ AI模型集成
- ✅ 审查报告生成（HTML/Markdown）
- ✅ 审查历史查询

## 快速开始

### 1. 环境要求

- JDK 21+
- Maven 3.6+

> 如果命令行默认 Java 是 17，请先把 `JAVA_HOME` 指向 JDK 21，否则编译会报“不支持发行版本 21”。
>
> Windows 示例：`set JAVA_HOME=C:\JAVA\Java\jdk-21`

### 2. 本地配置

复制本地配置模板，填写数据库、Redis、MinIO 和 AI API Key：

```bash
cp config/application-local.yml.example \
   config/application-local.yml
```

`application-local.yml` 已被 `.gitignore` 忽略，不会提交到仓库。默认激活的 profile 是 `local`。

### 3. 配置数据库

确保 PostgreSQL 已安装并运行，然后在 `application-local.yml` 中填写连接信息：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/code_guardian
    username: postgres
    password: your_password
```

数据库初始化脚本在 [database/](database/) 目录。生产环境使用 `prod` profile，敏感配置全部通过环境变量注入，参考 [deploy/README.md](deploy/README.md)。

### 4. 运行项目

```bash
# 编译项目
mvn clean compile

# 运行项目
mvn spring-boot:run
```

### 5. 访问应用

- 登录页面: http://localhost:7003/login
- 应用首页: http://localhost:7003
- Actuator健康检查: http://localhost:7003/actuator/health

**默认账号**: `admin` / `admin123`（首次登录后请立即修改）

## API文档

### 代码审查接口

#### 1. 审查代码片段

```bash
POST /api/review/snippet
Content-Type: application/json

{
  "codeSnippet": "public class Test { ... }",
  "language": "java",
  "taskName": "测试代码片段"
}
```

#### 2. 审查单个文件

```bash
POST /api/review/file
Content-Type: application/json

{
  "filePath": "/path/to/file.java",
  "taskName": "文件审查"
}
```

#### 3. 审查目录

```bash
POST /api/review/directory
Content-Type: application/json

{
  "directoryPath": "/path/to/directory",
  "taskName": "目录审查"
}
```

#### 4. 审查项目

```bash
POST /api/review/project
Content-Type: application/json

{
  "projectPath": "/path/to/project",
  "taskName": "项目审查"
}
```

#### 5. 获取审查任务详情

```bash
GET /api/review/task/{taskId}
```

#### 6. 获取审查发现的问题

```bash
GET /api/review/task/{taskId}/findings
```

#### 7. 查询审查历史

```bash
GET /api/review/history?name=关键词&reviewType=FILE&page=0&size=20
```

### 报告接口

#### 1. 生成报告

```bash
POST /api/report/{taskId}
```

#### 2. 获取HTML报告

```bash
GET /api/report/{taskId}/html
```

#### 3. 获取Markdown报告

```bash
GET /api/report/{taskId}/markdown
```

## 项目文档

设计文档（系统设计、架构图、数据库设计）保存在本地 `docs/` 目录，未随仓库发布。

## 数据库初始化

当前版本使用 `database/20260529/` 下的脚本：

```bash
# 1. 创建数据库
createdb -U postgres code_guardian

# 2. 执行建表脚本
psql -U postgres -d code_guardian -f database/20260529/schema_20260529.sql

# 3. 初始化权限与管理员
psql -U postgres -d code_guardian -f database/20260529/init_permissions_20260529.sql

# 4. （可选）插入示例数据
psql -U postgres -d code_guardian -f database/20260529/init_data_20260529.sql
```

也可以直接用仓库根目录的 `docker-compose.yml` 启动 PostgreSQL、Redis 和 MinIO。

## 启用向量检索（RAG）

默认使用 BM25 关键词检索。配置阿里云 DashScope embedding Key 后会自动启用 Qwen embedding + pgvector：

1. 在 `config/application-local.yml` 的 `app.rag.embedding.api-key` 填入 DashScope API Key（与对话模型的 Key 独立）
2. 默认 embedding 模型为 `text-embedding-v3`，维度 1024
3. `app.rag.vectorize-on-startup: true` 时，启动会为已有文档生成向量
4. 更换 embedding 模型或维度后，`vector_store` 表会重建（旧向量被清空），需要重新向量化
5. 生产环境通过 `RAG_EMBEDDING_API_KEY`、`RAG_EMBEDDING_BASE_URL`、`RAG_EMBEDDING_MODEL`、`RAG_EMBEDDING_DIMENSIONS` 环境变量配置

## GitHub Webhook 与评论回写

1. 创建 GitHub PAT：
   - Classic：勾选 `repo`
   - Fine-grained：`Pull requests: Read and write`、`Commit statuses: Read and write`、`Contents: Read`
2. 本地填入 `config/application-local.yml` 的 `github.api.token`；生产环境设置 `GITHUB_TOKEN`
3. GitHub 仓库 Settings → Webhooks 添加：
   - Payload URL：`https://你的域名/api/v1/webhook/github`
   - Content type：`application/json`
   - Secret：与 `API_TOKEN` 相同
   - 事件：`Pull requests`、`Pushes`
4. 审查完成后会自动在 PR 下评论，并更新 commit status（context 为 `codeguardian-ai`）
5. 如需 commit status 跳转到报告页，设置 `PUBLIC_BASE_URL`（如 `https://your-domain.com`）

## GitLab Webhook 与评论回写

1. 创建 GitLab Personal Access Token，scope 勾选 `api`
2. 本地填入 `config/application-local.yml` 的 `gitlab.api.token`；生产环境设置 `GITLAB_TOKEN`。
   自建 GitLab 通过 `gitlab.api.base-url`（或 `GITLAB_API_URL`）指定，例如
   `https://gitlab.example.com/api/v4`
3. GitLab 项目 Settings → Webhooks 添加：
   - URL：`https://你的域名/api/v1/webhook/gitlab`
   - Secret token：与 `API_TOKEN` 相同（请求头 `X-Gitlab-Token`）
   - 事件：`Merge request events`、`Push events`
4. MR 审查完成后会回写评论并更新 commit status（name 为 `codeguardian-ai`）；
   Push 事件只更新 commit status
5. 未配置 `GITLAB_TOKEN` 时，`/api/v1/webhook/gitlab` 会回退使用 GitCode Token

GitCode 的 Webhook 地址为 `/api/v1/webhook/gitcode`，同样支持 MR 与 Push 事件。

### 增量审查

Webhook 触发的审查默认只审查本次变更的文件：

- GitHub PR：通过 Pull Request files API 获取变更文件
- GitLab MR：通过 Merge Request diffs API 获取变更文件
- Push 事件：直接使用 payload 中 `commits[].added/modified`

获取不到变更列表（未配置 Token、API 失败或事件不含文件信息）时自动回退为全量审查。
手动发起 `GIT` 类型审查时仍为全量审查，也可在请求体中传 `changedFiles` 指定范围。

## 审查通知（可选）

在「设置 → 通知」中配置，审查完成或失败时推送到群机器人：

- 企业微信机器人：填写群机器人 Webhook
- 钉钉机器人：填写 Webhook，可选填加签密钥
- 飞书机器人：填写 Webhook
- 自定义 Webhook：接收 JSON，包含 `event`、`taskId`、`taskName`、`status`、
  `critical`/`high`/`medium`/`low`、`reportUrl` 和 `text`

可分别控制完成/失败是否通知，点击「保存并发送测试」验证配置是否生效。
设置报告的公网地址 `PUBLIC_BASE_URL` 后，通知中会附带报告链接。

## 项目结构

```
code-review-ai-agent/
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/codeguardian/
│   │   │       ├── CodeReviewApplication.java    # 主启动类
│   │   │       ├── config/                       # 配置类
│   │   │       ├── constants/                    # 常量类
│   │   │       ├── controller/                   # 控制器
│   │   │       ├── dto/                          # 数据传输对象
│   │   │       ├── entity/                       # 实体类
│   │   │       ├── exception/                    # 异常处理
│   │   │       ├── repository/                  # 数据访问层
│   │   │       └── service/                      # 服务层
│   │   └── resources/
│   │       ├── application.yml                   # 公共配置
│   │       └── application-prod.yml              # 生产环境配置（环境变量）
│   └── test/                                      # 测试代码
├── config/                                        # 本地配置（真实值不提交）
│   └── application-local.yml.example              # 本地配置模板
├── deploy/                                        # 部署编排与说明
├── docker-compose.yml                             # 本地中间件（PostgreSQL/Redis/MinIO）
├── pom.xml                                        # Maven配置
└── README.md                                      # 项目说明
```

## 开发计划

- [x] 项目骨架搭建
- [x] 基础实体和DTO
- [x] 核心服务实现
- [x] REST API接口
- [x] 报告生成功能
- [x] Git集成
- [x] 异步任务处理
- [x] 规则引擎集成
- [x] RAG增强
- [x] Function Calling

## 许可证

MIT License

