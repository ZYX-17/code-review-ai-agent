#!/usr/bin/env python3
"""
CodeGuardian AI 全功能端到端测试
覆盖：认证、用户、角色、权限、分类、设置、通知、知识库、RAG、
审查（片段/文件/目录/项目/Git/增量）、报告、CI/CD、Webhook、页面与操作日志。

运行前提：
  1. 中间件（PostgreSQL/Redis/MinIO）可用
  2. 应用已在 http://localhost:7003 启动
  3. 启动应用时设置 API_TOKEN（默认按 e2e-token 测试）：
       set API_TOKEN=e2e-token
       set PUBLIC_BASE_URL=http://localhost:7003
  4. 默认管理员 admin/admin123（可用环境变量覆盖）

用法：
  python tools/e2e/run_full_e2e.py
可选环境变量：
  CG_BASE_URL, CG_API_TOKEN, CG_ADMIN_USER, CG_ADMIN_PASSWORD
"""
import hashlib
import hmac
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

import requests

BASE = os.environ.get("CG_BASE_URL", "http://localhost:7003").rstrip("/")
API_TOKEN = os.environ.get("CG_API_TOKEN", "e2e-token")
ADMIN_USER = os.environ.get("CG_ADMIN_USER", "admin")
ADMIN_PASSWORD = os.environ.get("CG_ADMIN_PASSWORD", "admin123")

PROJECT_ROOT = Path(__file__).resolve().parents[2]
SUFFIX = str(int(time.time()))
PREFIX = f"E2E-{SUFFIX}"
RUNTIME = PROJECT_ROOT / "target" / f"e2e-runtime-{SUFFIX}"

RESULTS = []
CREATED_TASK_IDS = []
CREATED_USER_IDS = []
CREATED_ROLE_IDS = []
CREATED_PERMISSION_IDS = []
CREATED_CATEGORY_IDS = []
CREATED_KB_IDS = []
CREATED_DOC_IDS = []
ORIGINAL_SETTINGS = None
SESSION = requests.Session()
FAKE_WEBHOOK = None


# --------------------------------------------------------------------------- #
# 测试框架
# --------------------------------------------------------------------------- #
def case(case_id, name):
    def decorator(fn):
        def wrapper():
            started = time.time()
            try:
                fn()
                RESULTS.append((case_id, name, "PASS", "", round(time.time() - started, 2)))
                print(f"[PASS] {case_id} {name}")
            except Exception as exc:  # noqa: BLE001 - 用例需要捕获所有断言/异常
                RESULTS.append((case_id, name, "FAIL", str(exc), round(time.time() - started, 2)))
                print(f"[FAIL] {case_id} {name}: {exc}")
        wrapper.__e2e_case__ = True
        wrapper.__case_id__ = case_id
        wrapper.__case_name__ = name
        wrapper.__order__ = fn.__code__.co_firstlineno
        return wrapper
    return decorator


def api(method, path, expected=200, timeout=60, **kwargs):
    response = SESSION.request(method, BASE + path, timeout=timeout, **kwargs)
    if response.status_code != expected:
        raise AssertionError(
            f"{method} {path} 期望 {expected}，实际 {response.status_code}: {response.text[:300]}")
    return response


def expect(condition, message):
    if not condition:
        raise AssertionError(message)


def find_by(items, key, value):
    for item in items:
        if str(item.get(key)) == str(value):
            return item
    return None


def wait_task(task_id, timeout=180):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        response = SESSION.get(f"{BASE}/api/review/task/{task_id}", timeout=30)
        if response.status_code == 200:
            last = response.json()
            if last.get("status") in ("COMPLETED", "FAILED"):
                return last
        time.sleep(0.5)
    raise AssertionError(f"任务 {task_id} 未在 {timeout}s 内完成，最后状态: {last}")


def create_review(payload, timeout=180):
    response = api("POST", "/api/review/" + payload.pop("_endpoint"), json=payload, timeout=30)
    data = response.json()
    CREATED_TASK_IDS.append(data["taskId"])
    return wait_task(data["taskId"], timeout=timeout)


def history_ids():
    response = SESSION.get(f"{BASE}/api/review/history", params={"size": 200}, timeout=30)
    if response.status_code != 200:
        return set()
    return {item["taskId"] for item in response.json().get("content", [])}


# --------------------------------------------------------------------------- #
# 假 Webhook 服务
# --------------------------------------------------------------------------- #
class FakeWebhook:
    def __init__(self, port=18081):
        self.port = port
        self.events = []
        self.lock = threading.Lock()
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                length = int(self.headers.get("Content-Length", "0"))
                raw = self.rfile.read(length)
                try:
                    body = json.loads(raw.decode("utf-8"))
                except Exception:  # noqa: BLE001
                    body = raw.decode("utf-8", errors="replace")
                with outer.lock:
                    outer.events.append(body)
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b'{"errcode":0,"errmsg":"ok"}')

            def log_message(self, *args):
                pass

        self.server = HTTPServer(("127.0.0.1", port), Handler)

    def start(self):
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def stop(self):
        self.server.shutdown()

    def wait_event(self, predicate, timeout=15):
        deadline = time.time() + timeout
        while time.time() < deadline:
            with self.lock:
                for event in self.events:
                    if predicate(event):
                        return event
            time.sleep(0.2)
        raise AssertionError(f"未在 {timeout}s 内收到匹配的 Webhook 事件: {self.events}")


# --------------------------------------------------------------------------- #
# 测试数据准备
# --------------------------------------------------------------------------- #
def prepare_git_repo():
    repo = RUNTIME / "git-repo"
    if repo.exists():
        force_rmtree(repo)
    sample_dir = repo / "src" / "main" / "java" / "com" / "e2e"
    sample_dir.mkdir(parents=True)
    (sample_dir / "Sample.java").write_text(
        "package com.e2e;\n"
        "public class Sample {\n"
        "    public String read(String input) throws Exception {\n"
        "        java.sql.Statement st = null;\n"
        "        st.execute(\"SELECT * FROM users WHERE name='\" + input + \"'\");\n"
        "        return input;\n"
        "    }\n"
        "}\n", encoding="utf-8")
    (sample_dir / "Other.java").write_text(
        "package com.e2e;\n"
        "public class Other {\n"
        "    public int add(int a, int b) { return a + b; }\n"
        "}\n", encoding="utf-8")
    git = ["git", "-C", str(repo)]
    subprocess.run(["git", "init", "-b", "main", str(repo)], check=True, capture_output=True)
    subprocess.run(git + ["add", "."], check=True, capture_output=True)
    subprocess.run(git + ["-c", "user.name=E2E", "-c", "user.email=e2e@example.com",
                          "commit", "-m", "init"], check=True, capture_output=True)
    sha = subprocess.run(git + ["rev-parse", "HEAD"], check=True, capture_output=True,
                         text=True).stdout.strip()
    url = "file:///" + str(repo).replace("\\", "/")
    return repo, url, sha


def prepare_source_tree():
    directory = RUNTIME / "source-tree"
    source_dir = directory / "src" / "main" / "java"
    source_dir.mkdir(parents=True, exist_ok=True)
    (source_dir / "Risk.java").write_text(
        "public class Risk {\n"
        "    public void run(String cmd) throws Exception {\n"
        "        Runtime.getRuntime().exec(cmd);\n"
        "    }\n"
        "}\n", encoding="utf-8")
    (source_dir / "Clean.java").write_text(
        "public class Clean {\n"
        "    public int add(int a, int b) { return a + b; }\n"
        "}\n", encoding="utf-8")
    return directory


def delete_task(task_id):
    try:
        SESSION.delete(f"{BASE}/api/review/task/{task_id}", timeout=30)
    except Exception:  # noqa: BLE001
        pass


# --------------------------------------------------------------------------- #
# 用例
# --------------------------------------------------------------------------- #
@case("AUTH-01", "管理员登录")
def auth_login():
    global SESSION
    response = requests.post(f"{BASE}/api/auth/login", timeout=30,
                             json={"usernameOrEmail": ADMIN_USER, "password": ADMIN_PASSWORD})
    expect(response.status_code == 200, f"登录失败: {response.status_code} {response.text[:200]}")
    data = response.json()
    expect(data.get("success") is True and data.get("token"), "登录响应缺少 token")
    SESSION = requests.Session()
    SESSION.headers.update({"satoken": data["token"]})
    api("GET", "/api/review/history", params={"size": 1})


@case("AUTH-02", "错误密码登录被拒绝")
def auth_bad_password():
    response = requests.post(f"{BASE}/api/auth/login", timeout=30,
                             json={"usernameOrEmail": ADMIN_USER, "password": "wrong-password"})
    expect(response.status_code == 400, f"错误密码期望 400，实际 {response.status_code}")


@case("AUTH-03", "未登录访问受保护接口返回 401")
def auth_unauthorized():
    response = requests.get(f"{BASE}/api/review/history", timeout=30)
    expect(response.status_code in (401, 403),
           f"未登录期望 401/403，实际 {response.status_code}")


@case("AUTH-04", "页面登录入口可访问")
def auth_login_page():
    response = requests.get(f"{BASE}/login", timeout=30)
    expect(response.status_code == 200, f"登录页状态 {response.status_code}")
    expect("CodeGuardian" in response.text, "登录页内容异常")


@case("USER-01", "创建测试用户")
def user_create():
    roles = api("GET", "/admin/roles/api").json()
    codes = [role.get("code") for role in roles if role.get("code")]
    role_code = "VIEWER" if "VIEWER" in codes else (codes[0] if codes else None)
    payload = {
        "username": f"e2e_{SUFFIX}",
        "email": f"e2e_{SUFFIX}@example.com",
        "password": "E2ePass123",
        "realName": "E2E Test"
    }
    if role_code:
        payload["roleCodes"] = [role_code]
    response = api("POST", "/admin/users/api", json=payload)
    data = response.json()
    CREATED_USER_IDS.append(data["id"])
    expect(data["username"] == f"e2e_{SUFFIX}", "创建用户返回异常")


@case("USER-02", "用户列表包含新用户")
def user_list():
    response = api("GET", "/admin/users/api", params={"page": 0, "size": 100})
    data = response.json()
    items = data.get("content", data if isinstance(data, list) else [])
    expect(find_by(items, "username", f"e2e_{SUFFIX}") is not None, "用户列表未找到新用户")


@case("USER-03", "更新用户资料")
def user_update():
    user_id = CREATED_USER_IDS[0]
    response = api("PUT", f"/admin/users/api/{user_id}",
                   json={"realName": "E2E Updated", "phone": "13800000000"})
    expect(response.json().get("realName") == "E2E Updated", "更新用户资料失败")


@case("USER-04", "用户自助修改密码并重新登录")
def user_change_password():
    login = requests.post(f"{BASE}/api/auth/login", timeout=30,
                          json={"usernameOrEmail": f"e2e_{SUFFIX}", "password": "E2ePass123"})
    expect(login.status_code == 200, f"测试用户登录失败: {login.text[:200]}")
    token = login.json()["token"]

    changed = requests.post(f"{BASE}/api/auth/change-password", timeout=30,
                            headers={"satoken": token},
                            json={"oldPassword": "E2ePass123", "newPassword": "E2ePass456"})
    expect(changed.status_code == 200, f"修改密码失败: {changed.text[:200]}")

    old = requests.post(f"{BASE}/api/auth/login", timeout=30,
                        json={"usernameOrEmail": f"e2e_{SUFFIX}", "password": "E2ePass123"})
    expect(old.status_code == 400, "旧密码仍然可以登录")

    new = requests.post(f"{BASE}/api/auth/login", timeout=30,
                        json={"usernameOrEmail": f"e2e_{SUFFIX}", "password": "E2ePass456"})
    expect(new.status_code == 200, "新密码登录失败")


@case("USER-05", "管理员重置用户密码")
def user_reset_password():
    user_id = CREATED_USER_IDS[0]
    api("PUT", f"/admin/users/api/{user_id}/password", json={"newPassword": "E2ePass789"})
    login = requests.post(f"{BASE}/api/auth/login", timeout=30,
                          json={"usernameOrEmail": f"e2e_{SUFFIX}", "password": "E2ePass789"})
    expect(login.status_code == 200, "重置后的密码登录失败")


@case("ROLE-01", "角色列表与权限列表")
def role_permission_list():
    roles = api("GET", "/admin/roles/api").json()
    expect(any(role.get("code") == "ADMIN" for role in roles), "角色列表缺少 ADMIN")
    permissions = api("GET", "/admin/permissions/api/dto").json()
    expect(any(item.get("code") == "CONFIG" for item in permissions), "权限列表缺少 CONFIG")


@case("ROLE-02", "创建角色并分配权限")
def role_create():
    response = api("POST", "/admin/roles/api", json={
        "code": f"E2E_ROLE_{SUFFIX}",
        "name": "E2E 测试角色",
        "description": "自动化测试",
        "permissionCodes": ["QUERY"]
    })
    role = response.json()
    CREATED_ROLE_IDS.append(role["id"])
    api("POST", f"/admin/roles/api/{role['id']}/permissions",
        json=["QUERY", "REVIEW"])


@case("ROLE-03", "更新并删除角色")
def role_update_delete():
    role_id = CREATED_ROLE_IDS[0]
    api("PUT", f"/admin/roles/api/{role_id}", json={"name": "E2E 更新角色"})
    api("DELETE", f"/admin/roles/api/{role_id}")
    CREATED_ROLE_IDS.clear()


@case("PERM-01", "创建、更新并删除自定义权限")
def permission_crud():
    response = api("POST", "/admin/permissions/api", json={
        "code": f"E2E_PERM_{SUFFIX}",
        "name": "E2E 权限",
        "description": "自动化测试",
        "resource": 1,
        "action": 1
    })
    permission = response.json()
    CREATED_PERMISSION_IDS.append(permission["id"])
    api("PUT", f"/admin/permissions/api/{permission['id']}",
        json={"code": f"E2E_PERM_{SUFFIX}", "name": "E2E 权限更新"})
    api("DELETE", f"/admin/permissions/api/{permission['id']}")
    CREATED_PERMISSION_IDS.clear()


@case("CAT-01", "分类创建、更新、查询与删除")
def category_crud():
    response = api("POST", "/admin/categories/api", json={
        "code": f"E2E_CAT_{SUFFIX}",
        "name": "E2E 分类",
        "description": "自动化测试"
    })
    category = response.json()
    CREATED_CATEGORY_IDS.append(category["id"])
    api("PUT", f"/admin/categories/api/{category['id']}",
        json={"code": f"E2E_CAT_{SUFFIX}", "name": "E2E 分类更新"})
    items = api("GET", "/admin/categories/api").json()
    expect(find_by(items, "code", f"E2E_CAT_{SUFFIX}") is not None, "分类列表未找到新分类")
    api("DELETE", f"/admin/categories/api/{category['id']}")
    CREATED_CATEGORY_IDS.clear()


@case("SET-01", "设置导出、保存、校验与恢复")
def settings_roundtrip():
    global ORIGINAL_SETTINGS
    original = api("GET", "/admin/settings/export").json()
    ORIGINAL_SETTINGS = original

    modified = dict(original)
    modified["maxIssues"] = 77
    api("POST", "/admin/settings/save", json=modified)
    current = api("GET", "/admin/settings/export").json()
    expect(current.get("maxIssues") == 77, "maxIssues 保存未生效")

    invalid = dict(original)
    invalid["notifyEnabled"] = True
    invalid["notifyWebhookUrl"] = "ftp://bad-url"
    response = SESSION.post(f"{BASE}/admin/settings/save", json=invalid, timeout=30)
    expect(response.status_code == 400, f"非法通知地址期望 400，实际 {response.status_code}")

    api("POST", "/admin/settings/import", json=original)


@case("KB-01", "创建知识库")
def kb_create():
    response = api("POST", "/admin/knowledge/bases/api", json={
        "name": f"E2E 知识库 {SUFFIX}",
        "description": "自动化测试知识库",
        "enabled": True
    })
    kb = response.json()
    CREATED_KB_IDS.append(kb["id"])
    expect(kb["name"] == f"E2E 知识库 {SUFFIX}", "创建知识库返回异常")


@case("KB-02", "知识库列表包含新知识库")
def kb_list():
    items = api("GET", "/admin/knowledge/bases/api").json()
    expect(find_by(items, "id", CREATED_KB_IDS[0]) is not None, "知识库列表未找到新知识库")


@case("KB-03", "上传知识库文档")
def kb_upload():
    filename = f"e2e-kb-{SUFFIX}.txt"
    content = ("CodeGuardian 安全规范：禁止拼接 SQL，必须使用参数化查询。"
               "所有外部输入都要校验，避免命令注入和路径穿越。")
    response = SESSION.post(
        f"{BASE}/admin/knowledge/upload",
        data={"category": "SECURITY", "kbId": str(CREATED_KB_IDS[0])},
        files={"file": (filename, content.encode("utf-8"), "text/plain")},
        timeout=120, allow_redirects=False)
    expect(response.status_code in (301, 302),
           f"上传文档期望重定向，实际 {response.status_code}: {response.text[:200]}")

    page = api("GET", "/admin/knowledge", params={"kbId": CREATED_KB_IDS[0], "size": 50}).text
    expect(filename in page, "知识库页面未显示上传的文档")
    match = re.search(r"/admin/knowledge/detail/([^\"'?]+)", page)
    expect(match is not None, "未能从页面解析文档 ID")
    CREATED_DOC_IDS.append(match.group(1))


@case("KB-04", "按知识库过滤文档")
def kb_filter():
    page = api("GET", "/admin/knowledge", params={"kbId": CREATED_KB_IDS[0], "size": 50}).text
    expect(f"e2e-kb-{SUFFIX}.txt" in page, "按知识库过滤未返回文档")


@case("KB-05", "更新知识库")
def kb_update():
    response = api("PUT", f"/admin/knowledge/bases/api/{CREATED_KB_IDS[0]}", json={
        "name": f"E2E 知识库更新 {SUFFIX}",
        "description": "更新后的描述",
        "enabled": True
    })
    expect("更新" in response.json()["name"], "更新知识库失败")


@case("KB-06", "有文档的知识库禁止删除")
def kb_delete_protected():
    response = SESSION.delete(f"{BASE}/admin/knowledge/bases/api/{CREATED_KB_IDS[0]}", timeout=30)
    expect(response.status_code >= 400,
           f"有文档的知识库删除应失败，实际 {response.status_code}")


@case("RAG-01", "RAG 审查按知识库范围检索")
def rag_review():
    task = create_review({
        "_endpoint": "snippet",
        "codeSnippet": "public class RagCheck { void run(String id) { String sql=\"select * from t where id=\"+id; } }",
        "language": "java",
        "taskName": f"{PREFIX}-RAG",
        "rulesOnly": True,
        "enableRag": True,
        "knowledgeBaseIds": [CREATED_KB_IDS[0]]
    }, timeout=240)
    expect(task["status"] == "COMPLETED", f"RAG 审查失败: {task.get('errorMessage')}")


@case("REV-01", "代码片段规则审查")
def review_snippet():
    task = create_review({
        "_endpoint": "snippet",
        "codeSnippet": "public class Demo { void run(String cmd) throws Exception { Runtime.getRuntime().exec(cmd); } }",
        "language": "java",
        "taskName": f"{PREFIX}-SNIPPET",
        "rulesOnly": True
    })
    expect(task["status"] == "COMPLETED", f"片段审查失败: {task.get('errorMessage')}")
    expect(task["totalFindings"] >= 0, "问题数量异常")


@case("REV-02", "任务详情与问题列表")
def review_task_detail():
    task_id = CREATED_TASK_IDS[0]
    detail = api("GET", f"/api/review/task/{task_id}").json()
    expect(detail["taskId"] == task_id, "任务详情 ID 不匹配")
    findings = api("GET", f"/api/review/task/{task_id}/findings").json()
    expect(isinstance(findings, list), "问题列表格式异常")


@case("REV-03", "审查历史查询")
def review_history():
    data = api("GET", "/api/review/history",
               params={"name": "E2E", "size": 50}).json()
    expect(any(item["taskId"] in CREATED_TASK_IDS for item in data.get("content", [])),
           "历史记录未包含测试任务")


@case("REV-04", "文件审查")
def review_file():
    file_path = RUNTIME / "single" / "Risk.java"
    file_path.parent.mkdir(parents=True, exist_ok=True)
    file_path.write_text(
        "public class Risk { void run(String p) throws Exception { new ProcessBuilder(p).start(); } }",
        encoding="utf-8")
    task = create_review({
        "_endpoint": "file",
        "filePath": str(file_path),
        "taskName": f"{PREFIX}-FILE",
        "rulesOnly": True
    })
    expect(task["status"] == "COMPLETED", f"文件审查失败: {task.get('errorMessage')}")


@case("REV-05", "目录审查")
def review_directory():
    task = create_review({
        "_endpoint": "directory",
        "directoryPath": str(RUNTIME / "source-tree"),
        "taskName": f"{PREFIX}-DIR",
        "rulesOnly": True
    })
    expect(task["status"] == "COMPLETED", f"目录审查失败: {task.get('errorMessage')}")


@case("REV-06", "项目审查")
def review_project():
    task = create_review({
        "_endpoint": "project",
        "projectPath": str(RUNTIME / "source-tree"),
        "taskName": f"{PREFIX}-PROJECT",
        "rulesOnly": True
    })
    expect(task["status"] == "COMPLETED", f"项目审查失败: {task.get('errorMessage')}")


@case("REV-07", "Git 克隆与文件树")
def git_clone():
    repo, url, _ = prepare_git_repo()
    data = api("POST", "/api/review/git/clone", json={"gitUrl": url}, timeout=120).json()
    expect(data.get("success") is True, f"Git 克隆失败: {data}")
    expect(any("Sample.java" in path for path in data.get("fileList", [])), "文件树缺少 Sample.java")


@case("REV-08", "Git 项目审查")
def review_git():
    _, url, sha = prepare_git_repo()
    task = create_review({
        "_endpoint": "git",
        "gitUrl": url,
        "gitBranch": "main",
        "gitCommit": sha,
        "taskName": f"{PREFIX}-GIT",
        "rulesOnly": True
    })
    expect(task["status"] == "COMPLETED", f"Git 审查失败: {task.get('errorMessage')}")


@case("REV-09", "增量审查（changedFiles）")
def review_incremental():
    _, url, sha = prepare_git_repo()
    task = create_review({
        "_endpoint": "git",
        "gitUrl": url,
        "gitBranch": "main",
        "gitCommit": sha,
        "taskName": f"{PREFIX}-GIT-INC",
        "rulesOnly": True,
        "changedFiles": ["src/main/java/com/e2e/Sample.java"]
    })
    expect(task["status"] == "COMPLETED", f"增量审查失败: {task.get('errorMessage')}")


@case("REV-10", "AI 审查（真实模型调用）")
def review_ai():
    task = create_review({
        "_endpoint": "snippet",
        "codeSnippet": "public class AiCheck { public int div(int a, int b) { return a / b; } }",
        "language": "java",
        "taskName": f"{PREFIX}-AI",
        "rulesOnly": False
    }, timeout=300)
    expect(task["status"] == "COMPLETED", f"AI 审查失败: {task.get('errorMessage')}")


@case("REPORT-01", "报告生成与 HTML/Markdown/PDF 导出")
def report_export():
    task_id = CREATED_TASK_IDS[0]
    api("POST", f"/api/report/{task_id}")
    html = api("GET", f"/api/report/{task_id}/html")
    expect("html" in html.text.lower(), "HTML 报告内容异常")
    markdown = api("GET", f"/api/report/{task_id}/markdown")
    expect(len(markdown.text) > 20, "Markdown 报告内容为空")
    pdf = api("GET", f"/api/report/{task_id}/pdf")
    expect(pdf.content[:4] == b"%PDF", "PDF 报告格式异常")


@case("REPORT-02", "报告页面可访问")
def report_page():
    response = api("GET", f"/report/{CREATED_TASK_IDS[0]}")
    expect("CodeGuardian" in response.text, "报告页面内容异常")


@case("CICD-01", "CI/CD 无令牌被拒绝")
def cicd_unauthorized():
    response = requests.post(f"{BASE}/api/v1/cicd/trigger", timeout=30, json={})
    expect(response.status_code == 401, f"期望 401，实际 {response.status_code}")


@case("CICD-02", "CI/CD 错误令牌被拒绝")
def cicd_wrong_token():
    response = requests.post(f"{BASE}/api/v1/cicd/trigger", timeout=30, json={},
                             headers={"X-API-Token": "wrong-token"})
    expect(response.status_code == 401, f"期望 401，实际 {response.status_code}")


@case("CICD-03", "CI/CD 触发审查并轮询门禁结果")
def cicd_trigger():
    _, url, sha = prepare_git_repo()
    response = requests.post(
        f"{BASE}/api/v1/cicd/trigger", timeout=30,
        headers={"X-API-Token": API_TOKEN},
        json={"gitUrl": url, "branch": "main", "commitHash": sha,
              "triggerBy": "E2E", "rulesOnly": True})
    expect(response.status_code == 200, f"触发失败: {response.text[:200]}")
    task_id = response.json()["taskId"]
    CREATED_TASK_IDS.append(task_id)
    deadline = time.time() + 180
    status = None
    while time.time() < deadline:
        status = requests.get(f"{BASE}/api/v1/cicd/status/{task_id}", timeout=30,
                              headers={"X-API-Token": API_TOKEN}).json()
        if status.get("status") in ("COMPLETED", "FAILED"):
            break
        time.sleep(1)
    expect(status and status.get("status") == "COMPLETED", f"CI/CD 审查未完成: {status}")


@case("CICD-04", "CI/CD 增量审查")
def cicd_incremental():
    _, url, sha = prepare_git_repo()
    response = requests.post(
        f"{BASE}/api/v1/cicd/trigger", timeout=30,
        headers={"X-API-Token": API_TOKEN},
        json={"gitUrl": url, "branch": "main", "commitHash": sha,
              "triggerBy": "E2E", "rulesOnly": True,
              "changedFiles": ["src/main/java/com/e2e/Sample.java"]})
    task_id = response.json()["taskId"]
    CREATED_TASK_IDS.append(task_id)
    task = wait_task(task_id)
    expect(task["status"] == "COMPLETED", f"CI/CD 增量审查失败: {task.get('errorMessage')}")


@case("WEBHOOK-01", "GitHub 签名错误被拒绝")
def webhook_github_bad_signature():
    body = json.dumps({"ref": "refs/heads/main"}).encode("utf-8")
    response = requests.post(f"{BASE}/api/v1/webhook/github", data=body, timeout=30,
                             headers={"X-GitHub-Event": "push",
                                      "X-Hub-Signature-256": "sha256=bad"})
    expect(response.status_code == 401, f"期望 401，实际 {response.status_code}")


@case("WEBHOOK-02", "GitHub Push 事件触发增量审查")
def webhook_github_push():
    _, url, sha = prepare_git_repo()
    body = json.dumps({
        "ref": "refs/heads/main",
        "after": sha,
        "repository": {"full_name": "e2e-owner/e2e-repo", "clone_url": url},
        "commits": [{"added": [], "modified": ["src/main/java/com/e2e/Sample.java"]}]
    }).encode("utf-8")
    signature = "sha256=" + hmac.new(API_TOKEN.encode("utf-8"), body, hashlib.sha256).hexdigest()
    response = requests.post(f"{BASE}/api/v1/webhook/github", data=body, timeout=30,
                             headers={"X-GitHub-Event": "push",
                                      "X-Hub-Signature-256": signature,
                                      "Content-Type": "application/json"})
    expect(response.status_code == 200, f"GitHub Webhook 失败: {response.text[:200]}")


@case("WEBHOOK-03", "GitLab 错误令牌被拒绝")
def webhook_gitlab_unauthorized():
    response = requests.post(f"{BASE}/api/v1/webhook/gitlab", timeout=30,
                             headers={"X-Gitlab-Token": "wrong"}, json={})
    expect(response.status_code == 401, f"期望 401，实际 {response.status_code}")


@case("WEBHOOK-04", "GitLab MR 事件触发审查")
def webhook_gitlab_mr():
    _, url, sha = prepare_git_repo()
    payload = {
        "object_kind": "merge_request",
        "project": {"git_http_url": url, "web_url": "file://" + url},
        "object_attributes": {
            "action": "open", "iid": 9901, "title": f"{PREFIX}-WH-MR",
            "source_branch": "main", "last_commit": {"id": sha}
        }
    }
    response = requests.post(f"{BASE}/api/v1/webhook/gitlab", timeout=30,
                             headers={"X-Gitlab-Token": API_TOKEN,
                                      "X-Gitlab-Event": "Merge Request Hook"},
                             json=payload)
    expect(response.status_code == 200, f"GitLab Webhook 失败: {response.text[:200]}")


@case("WEBHOOK-05", "GitCode MR 事件触发审查")
def webhook_gitcode_mr():
    _, url, sha = prepare_git_repo()
    payload = {
        "object_kind": "merge_request",
        "project": {"git_http_url": url, "web_url": "file://" + url},
        "object_attributes": {
            "action": "reopen", "iid": 9902, "title": f"{PREFIX}-WH-GITCODE",
            "source_branch": "main", "last_commit": {"id": sha}
        }
    }
    response = requests.post(f"{BASE}/api/v1/webhook/gitcode", timeout=30,
                             headers={"X-Gitlab-Token": API_TOKEN,
                                      "X-Gitlab-Event": "Merge Request Hook"},
                             json=payload)
    expect(response.status_code == 200, f"GitCode Webhook 失败: {response.text[:200]}")


@case("WEBHOOK-06", "GitLab 非 MR 事件被忽略")
def webhook_gitlab_ignore():
    response = requests.post(f"{BASE}/api/v1/webhook/gitlab", timeout=30,
                             headers={"X-Gitlab-Token": API_TOKEN,
                                      "X-Gitlab-Event": "Ping"},
                             json={"object_kind": "ping"})
    expect(response.status_code == 200, f"Ping 事件状态 {response.status_code}")


@case("NOTIFY-01", "通知配置测试消息")
def notify_test():
    settings = api("GET", "/admin/settings/export").json()
    settings.update({
        "notifyEnabled": True,
        "notifyChannel": "custom",
        "notifyWebhookUrl": "http://127.0.0.1:18081/hook",
        "notifySecret": "",
        "notifyOnSuccess": True,
        "notifyOnFailure": True
    })
    api("POST", "/admin/settings/save", json=settings)
    api("POST", "/admin/settings/notify/test")
    FAKE_WEBHOOK.wait_event(lambda event: event.get("event") == "test")


@case("NOTIFY-02", "审查完成自动推送通知")
def notify_review():
    task = create_review({
        "_endpoint": "snippet",
        "codeSnippet": "public class NotifyCheck { int f(int a) { return 1 / a; } }",
        "language": "java",
        "taskName": f"{PREFIX}-NOTIFY",
        "rulesOnly": True
    })
    expect(task["status"] == "COMPLETED", f"通知审查失败: {task.get('errorMessage')}")
    event = FAKE_WEBHOOK.wait_event(
        lambda item: item.get("event") == "review.completed"
        and item.get("taskName") == f"{PREFIX}-NOTIFY",
        timeout=20)
    expect(event.get("taskId") == task["taskId"], "通知中的任务 ID 不匹配")


@case("PAGE-01", "主要页面渲染")
def pages_render():
    for path in [
        "/dashboard",
        "/review",
        "/review/reports",
        "/admin/users",
        "/admin/settings",
        "/admin/knowledge",
        "/admin/knowledge/bases",
    ]:
        response = SESSION.get(BASE + path, timeout=60)
        expect(response.status_code == 200, f"{path} 状态 {response.status_code}")
        expect(len(response.text) > 800, f"{path} 页面内容过短")
        expect("CodeGuardian" in response.text, f"{path} 页面缺少标题")


@case("LOG-01", "操作日志记录并脱敏")
def operation_logs():
    response = api("GET", "/admin/logs/api", params={"page": 0, "size": 50})
    data = response.json()
    items = data.get("content", data if isinstance(data, list) else [])
    expect(len(items) > 0, "操作日志为空")
    raw = json.dumps(items, ensure_ascii=False)
    expect("E2ePass" not in raw, "操作日志泄露了密码")


@case("DASH-01", "仪表盘统计页面数据可用")
def dashboard_page():
    response = api("GET", "/dashboard")
    expect("CodeGuardian" in response.text and len(response.text) > 800, "仪表盘页面异常")


# --------------------------------------------------------------------------- #
# 执行与清理
# --------------------------------------------------------------------------- #
def force_rmtree(path):
    """Windows 下 git 对象可能是只读的，rmtree 前需去掉只读属性。"""
    def on_error(func, target, exc_info):
        try:
            os.chmod(target, 0o700)
            func(target)
        except Exception:  # noqa: BLE001
            pass
    shutil.rmtree(path, onerror=on_error)


def cleanup(fake_webhook, webhook_new_task_ids):
    if SESSION.headers.get("satoken") and ORIGINAL_SETTINGS is not None:
        try:
            SESSION.post(f"{BASE}/admin/settings/import", json=ORIGINAL_SETTINGS, timeout=30)
        except Exception:  # noqa: BLE001
            pass

    for task_id in set(CREATED_TASK_IDS + list(webhook_new_task_ids)):
        delete_task(task_id)

    for doc_id in CREATED_DOC_IDS:
        try:
            SESSION.post(f"{BASE}/admin/knowledge/delete/{doc_id}", timeout=30)
        except Exception:  # noqa: BLE001
            pass
    for kb_id in CREATED_KB_IDS:
        try:
            SESSION.delete(f"{BASE}/admin/knowledge/bases/api/{kb_id}", timeout=30)
        except Exception:  # noqa: BLE001
            pass
    for category_id in CREATED_CATEGORY_IDS:
        try:
            SESSION.delete(f"{BASE}/admin/categories/api/{category_id}", timeout=30)
        except Exception:  # noqa: BLE001
            pass
    for permission_id in CREATED_PERMISSION_IDS:
        try:
            SESSION.delete(f"{BASE}/admin/permissions/api/{permission_id}", timeout=30)
        except Exception:  # noqa: BLE001
            pass
    for role_id in CREATED_ROLE_IDS:
        try:
            SESSION.delete(f"{BASE}/admin/roles/api/{role_id}", timeout=30)
        except Exception:  # noqa: BLE001
            pass
    for user_id in CREATED_USER_IDS:
        try:
            SESSION.delete(f"{BASE}/admin/users/api/{user_id}", timeout=30)
        except Exception:  # noqa: BLE001
            pass

    try:
        fake_webhook.stop()
    except Exception:  # noqa: BLE001
        pass
    force_rmtree(RUNTIME)


def main():
    global FAKE_WEBHOOK, RESULTS
    if not BASE:
        print("请设置 CG_BASE_URL")
        return 1
    RUNTIME.mkdir(parents=True, exist_ok=True)
    prepare_git_repo()
    prepare_source_tree()

    FAKE_WEBHOOK = FakeWebhook()
    FAKE_WEBHOOK.start()

    RESULTS = []
    all_cases = []
    for name, value in list(globals().items()):
        if callable(value) and getattr(value, "__e2e_case__", False):
            all_cases.append(value)
    all_cases.sort(key=lambda fn: fn.__order__)

    start = time.time()
    before_webhooks = set()

    try:
        for fn in all_cases:
            if fn.__case_id__ == "WEBHOOK-01":
                before_webhooks = history_ids()
            fn()

        # 给 Webhook 异步任务一点时间创建出来，再纳入清理范围
        time.sleep(5)
        webhook_task_ids = history_ids() - before_webhooks
    finally:
        cleanup(FAKE_WEBHOOK, webhook_task_ids if 'webhook_task_ids' in locals() else set())

    elapsed = round(time.time() - start, 1)
    passed = sum(1 for item in RESULTS if item[2] == "PASS")
    failed = [item for item in RESULTS if item[2] != "PASS"]

    report = {
        "baseUrl": BASE,
        "startedAt": time.strftime("%Y-%m-%d %H:%M:%S"),
        "elapsedSeconds": elapsed,
        "total": len(RESULTS),
        "passed": passed,
        "failed": len(failed),
        "results": [
            {"id": cid, "name": name, "status": status, "detail": detail, "seconds": seconds}
            for cid, name, status, detail, seconds in RESULTS
        ],
    }
    report_path = PROJECT_ROOT / "target" / "e2e-report.json"
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    print("\n========== E2E 结果 ==========")
    print(f"总计 {len(RESULTS)}，通过 {passed}，失败 {len(failed)}，耗时 {elapsed}s")
    if failed:
        print("失败用例：")
        for item in failed:
            print(f"  - {item[0]} {item[1]}: {item[3]}")
    print(f"报告: {report_path}")
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
