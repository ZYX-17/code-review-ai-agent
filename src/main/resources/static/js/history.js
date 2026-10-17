// 历史报告页面脚本

let currentPage = 0;
const pageSize = 10;
let totalPages = 0;

document.addEventListener('DOMContentLoaded', function() {
    loadHistory();
    
    // 监听回车键查询
    document.getElementById('nameFilter').addEventListener('keypress', function(e) {
        if (e.key === 'Enter') {
            searchHistory();
        }
    });
});

/**
 * 加载历史记录
 */
function loadHistory(page = 0) {
    currentPage = page;
    
    // 构建查询参数
    const params = new URLSearchParams();
    params.append('page', currentPage);
    params.append('size', pageSize);
    
    const name = document.getElementById('nameFilter').value;
    if (name) params.append('name', name);
    
    const scope = document.getElementById('scopeFilter').value;
    if (scope) params.append('reviewType', scope);
    
    // 处理日期
    const startDate = document.getElementById('startDateFilter').value;
    const endDate = document.getElementById('endDateFilter').value;
    
    // 如果选择了快捷时间范围
    const timeRange = document.getElementById('timeRangeFilter').value;
    if (timeRange) {
        const now = new Date();
        let start = new Date();
        
        if (timeRange === 'today') {
            start.setHours(0, 0, 0, 0);
        } else if (timeRange === 'week') {
            const day = now.getDay();
            const diff = now.getDate() - day + (day === 0 ? -6 : 1); // adjust when day is sunday
            start.setDate(diff);
            start.setHours(0, 0, 0, 0);
        } else if (timeRange === 'month') {
            start.setDate(1);
            start.setHours(0, 0, 0, 0);
        }
        
        params.append('startTime', formatDateForApi(start));
        params.append('endTime', formatDateForApi(now));
    } else {
        if (startDate) params.append('startTime', startDate + 'T00:00:00');
        if (endDate) params.append('endTime', endDate + 'T23:59:59');
    }
    
    // 发起请求
    fetch(`/api/review/history?${params.toString()}`)
        .then(response => response.json())
        .then(data => {
            renderTable(data.content);
            updatePagination(data);
        })
        .catch(error => {
            console.error('加载历史记录失败:', error);
            alert('加载历史记录失败，请重试');
        });
}

/**
 * 渲染表格
 */
function renderTable(items) {
    const tbody = document.getElementById('historyTableBody');
    tbody.innerHTML = '';
    
    if (!items || items.length === 0) {
        tbody.innerHTML = '<tr><td colspan="6" style="text-align: center; padding: 20px;">暂无记录</td></tr>';
        return;
    }
    
    items.forEach((item, index) => {
        const tr = document.createElement('tr');
        
        // 序号
        const serialNum = index + 1 + (currentPage * pageSize);
        
        // 格式化时间
        const reviewTime = formatDateTime(item.createdAt);
        
        // 构建问题统计
        const statsHtml = `
            <div class="issue-stats">
                <span class="stat-item stat-critical" title="严重">严重:${item.criticalCount || 0}</span>
                <span class="stat-item stat-high" title="高危">高危:${item.highCount || 0}</span>
                <span class="stat-item stat-medium" title="中危">中危:${item.mediumCount || 0}</span>
                <span class="stat-item stat-low" title="低危">低危:${item.lowCount || 0}</span>
            </div>
        `;
        
    // 范围显示：统一使用审查类型的固定标签
        let scopeDisplay = mapReviewTypeToZh(item.reviewType);
        
        let actionButtons = `
            <a href="#" class="action-btn" onclick="viewReport(${item.taskId})">
                <i class="fas fa-eye"></i> 查看
            </a>
            <a href="#" class="action-btn" onclick="downloadReport(${item.taskId})">
                <i class="fas fa-download"></i> 下载
            </a>
        `;

        if (typeof canAdmin !== 'undefined' && canAdmin) {
            actionButtons += `
                <a href="#" class="action-btn" onclick="deleteTask(${item.taskId})">
                    <i class="fas fa-trash"></i> 删除
                </a>
            `;
        }

        tr.innerHTML = `
            <td>${serialNum}</td>
            <td>${item.taskName || '未命名片段'}</td>
            <td>${scopeDisplay}</td>
            <td>${reviewTime}</td>
            <td>${statsHtml}</td>
            <td>
                ${actionButtons}
            </td>
        `;
        
        tbody.appendChild(tr);
    });
}

/**
 * 更新分页控件
 */
function updatePagination(data) {
    totalPages = data.totalPages;
    const totalElements = data.totalElements;
    
    const paginationInfo = document.getElementById('paginationInfo');
    paginationInfo.textContent = `共 ${totalElements} 条记录，当前第 ${data.number + 1}/${data.totalPages} 页`;
    
    const pageDisplay = document.getElementById('pageDisplay');
    pageDisplay.textContent = `第 ${data.number + 1}/${data.totalPages} 页`;
    
    const prevBtn = document.querySelector('.prev-btn');
    const nextBtn = document.querySelector('.next-btn');
    
    prevBtn.disabled = data.first;
    nextBtn.disabled = data.last;
}

/**
 * 搜索
 */
function searchHistory() {
    loadHistory(0);
}

/**
 * 重置筛选
 */
function resetFilters() {
    document.getElementById('nameFilter').value = '';
    document.getElementById('scopeFilter').value = '';
    document.getElementById('timeRangeFilter').value = '';
    document.getElementById('startDateFilter').value = '';
    document.getElementById('endDateFilter').value = '';
    loadHistory(0);
}

/**
 * 上一页
 */
function prevPage() {
    if (currentPage > 0) {
        loadHistory(currentPage - 1);
    }
}

/**
 * 下一页
 */
function nextPage() {
    if (currentPage < totalPages - 1) {
        loadHistory(currentPage + 1);
    }
}

/**
 * 查看报告
 */
function viewReport(taskId) {
    window.open(`/api/report/${taskId}/html`, '_blank');
}

/**
 * 下载报告
 */
function downloadReport(taskId) {
    // 触发下载PDF
    const a = document.createElement('a');
    a.href = `/api/report/${taskId}/pdf`;
    a.download = `review_report_${taskId}.pdf`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
}

function deleteTask(taskId) {
    if (!confirm('确认删除该历史记录？删除后不可恢复')) {
        return;
    }

    // 显示加载提示
    const loadingMsg = '正在删除...';

    fetch(`/api/review/task/${taskId}`, { method: 'DELETE' })
        .then(resp => {
            if (!resp.ok) throw new Error('删除失败');
            // 删除成功，显示提示并刷新列表
            alert('删除成功！');
            loadHistory(currentPage);
        })
        .catch(err => {
            alert('删除失败：' + err.message);
            console.error(err);
        });
}

/**
 * 格式化日期时间
 */
function formatDateTime(isoString) {
    if (!isoString) return '';
    const date = new Date(isoString);
    return date.toLocaleString('zh-CN', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit'
    }).replace(/\//g, '-');
}

/**
 * 格式化日期为API需要的格式 (yyyy-MM-ddTHH:mm:ss)
 */
function formatDateForApi(date) {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    const hours = String(date.getHours()).padStart(2, '0');
    const minutes = String(date.getMinutes()).padStart(2, '0');
    const seconds = String(date.getSeconds()).padStart(2, '0');
    return `${year}-${month}-${day}T${hours}:${minutes}:${seconds}`;
}

/**
 * 类型映射为中文
 */
function mapReviewTypeToZh(type) {
    if (!type) return '片段';
    const t = String(type).toUpperCase();
    if (t === 'PROJECT') return '整个项目';
    if (t === 'DIRECTORY') return '指定目录';
    if (t === 'FILE') return '指定文件';
    if (t === 'SNIPPET') return '代码片段';
    if (t === 'GIT') return 'git项目';
    return '代码片段';
}
