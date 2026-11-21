# 服务器部署说明（Docker + GHCR + GitHub Actions）

## 1. 服务器准备

要求：

- Linux（Ubuntu 22.04+ / Debian 12+ 等）
- Docker Engine 24+
- Docker Compose v2
- 可访问 `ghcr.io`
- 开放应用端口（默认 `7003`），MinIO 控制台端口 `9001` 按需开放

```bash
# 创建部署目录
sudo mkdir -p /opt/codeguardian
sudo chown -R "$USER":"$USER" /opt/codeguardian
cd /opt/codeguardian
```

## 2. 准备部署文件

把仓库中的以下内容放到 `/opt/codeguardian`：

```text
/opt/codeguardian/
├── docker-compose.prod.yml     # 来自 deploy/docker-compose.prod.yml
├── .env                        # 由 deploy/.env.example 复制并修改
└── database/
    └── 20260529/
        ├── schema_20260529.sql
        ├── init_permissions_20260529.sql
        └── init_data_20260529.sql
```

```bash
cp deploy/.env.example .env
# 编辑 .env，至少修改：
#   DB_PASSWORD
#   REDIS_PASSWORD
#   MINIO_ACCESS_KEY / MINIO_SECRET_KEY
#   QWEN_API_KEY 或 OPENAI_API_KEY
chmod 600 .env
```

> 需要 Webhook 评论回写时，再填写 `.env` 中的 `API_TOKEN`、`GITHUB_TOKEN`、
> `GITLAB_TOKEN`/`GITLAB_API_URL`；`PUBLIC_BASE_URL` 用于在 commit status 中链接报告页。

## 3. 登录 GHCR

GitHub Actions 推送的镜像在私有 GHCR 中，服务器需要能拉取。

1. GitHub → Settings → Developer settings → Personal access tokens
2. 创建 Classic token，勾选 `read:packages`
3. 在服务器登录：

```bash
echo "<你的 PAT>" | docker login ghcr.io -u ZYX-17 --password-stdin
```

也可以在项目 Settings → Secrets and variables → Actions 中添加 `GHCR_TOKEN`，由部署流水线自动登录。

## 4. 首次启动

```bash
cd /opt/codeguardian
docker compose -f docker-compose.prod.yml up -d

# 查看状态
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs -f app
```

首次启动时，PostgreSQL 会自动执行 `database/20260529/` 下的三个脚本，创建表、权限和示例数据。

默认管理员：

```text
admin / admin123（首次登录后请立即修改）
```

**登录后请立即修改密码。**

> 注意：数据库初始化脚本只在 PostgreSQL 数据卷第一次创建时执行。如果修改了脚本并希望重新初始化，需要先删除数据卷（会丢数据）：
>
> ```bash
> docker compose -f docker-compose.prod.yml down -v
> docker compose -f docker-compose.prod.yml up -d
> ```

## 5. 访问

- 应用：`http://服务器IP:7003/login`
- 健康检查：`http://服务器IP:7003/actuator/health`
- MinIO 控制台：`http://服务器IP:9001`

生产环境建议在前面加 Nginx 并配置 HTTPS：

```nginx
server {
    listen 80;
    server_name your-domain.com;
    client_max_body_size 100m;

    location / {
        proxy_pass http://127.0.0.1:7003;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

## 6. GitHub Actions 配置

仓库 Settings → Secrets and variables → Actions：

### Secrets

| 名称 | 说明 |
|---|---|
| `SSH_HOST` | 服务器 IP 或域名 |
| `SSH_USER` | SSH 用户名 |
| `SSH_KEY` | SSH 私钥（对应公钥已加入服务器 `~/.ssh/authorized_keys`） |
| `SSH_PORT` | SSH 端口，默认 22，可不填 |
| `GHCR_TOKEN` | 有 `read:packages` 权限的 PAT，服务器拉取镜像用 |

### Variables

| 名称 | 值 | 说明 |
|---|---|---|
| `DEPLOY_ENABLED` | `true` | 为 `true` 时才执行自动部署 |

设置完成后：

- 推送到 `main`：自动构建 jar → 构建并推送镜像到 GHCR → SSH 部署
- 提交 PR：只执行构建，不推送镜像、不部署
- 手动触发：Actions → CI/CD → Run workflow

## 7. 常见问题

**镜像拉取失败 401/403**

服务器没有登录 GHCR，或 PAT 没有 `read:packages` 权限。

**数据库连接失败**

确认 `.env` 中的 `DB_PASSWORD` 与首次启动时一致。改过密码但数据卷已存在时，PostgreSQL 不会自动修改密码，需要进入容器修改或重建数据卷。

**登录提示账号或密码错误**

确认使用的是 `database/20260529/init_permissions_20260529.sql` 初始化；旧脚本 `database/init_permissions.sql` 的管理员密码哈希不正确。

**AI 审查不可用**

至少配置 `QWEN_API_KEY` 或 `OPENAI_API_KEY`。只用规则审查时可以不配置。

**RAG / 知识库不可用**

需要 MinIO 正常运行，并且 PostgreSQL 已启用 `vector` 扩展（`pgvector/pgvector:pg17` 镜像自带）。
