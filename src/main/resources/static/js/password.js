(() => {
  function injectChangePasswordButton() {
    const userSection = document.querySelector('.user-section');
    if (!userSection || document.getElementById('changePasswordBtn')) {
      return;
    }

    const button = document.createElement('button');
    button.id = 'changePasswordBtn';
    button.type = 'button';
    button.className = 'logout-btn';
    button.style.marginRight = '8px';
    button.innerHTML = '<i class="fas fa-key"></i> 修改密码';
    button.addEventListener('click', openChangePasswordModal);

    const logoutButton = userSection.querySelector('.logout-btn');
    if (logoutButton) {
      userSection.insertBefore(button, logoutButton);
    } else {
      userSection.appendChild(button);
    }
  }

  function openChangePasswordModal() {
    const existing = document.getElementById('changePasswordModal');
    if (existing) {
      existing.style.display = 'flex';
      return;
    }

    const overlay = document.createElement('div');
    overlay.id = 'changePasswordModal';
    overlay.style.cssText = 'position:fixed;inset:0;background:rgba(0,0,0,.45);display:flex;align-items:center;justify-content:center;z-index:9999;';
    overlay.innerHTML = `
      <div style="background:#fff;border-radius:8px;padding:24px;width:360px;max-width:90vw;box-shadow:0 10px 30px rgba(0,0,0,.2);">
        <h3 style="margin:0 0 16px;font-size:18px;">修改密码</h3>
        <label style="display:block;margin-bottom:12px;font-size:14px;">原密码
          <input id="changePasswordOld" type="password" autocomplete="current-password" style="width:100%;margin-top:6px;padding:8px;border:1px solid #ccc;border-radius:4px;box-sizing:border-box;">
        </label>
        <label style="display:block;margin-bottom:12px;font-size:14px;">新密码（6-32 位）
          <input id="changePasswordNew" type="password" autocomplete="new-password" style="width:100%;margin-top:6px;padding:8px;border:1px solid #ccc;border-radius:4px;box-sizing:border-box;">
        </label>
        <label style="display:block;margin-bottom:20px;font-size:14px;">确认新密码
          <input id="changePasswordConfirm" type="password" autocomplete="new-password" style="width:100%;margin-top:6px;padding:8px;border:1px solid #ccc;border-radius:4px;box-sizing:border-box;">
        </label>
        <div style="display:flex;justify-content:flex-end;gap:10px;">
          <button type="button" id="changePasswordCancel" style="padding:8px 16px;border:1px solid #ccc;background:#fff;border-radius:4px;cursor:pointer;">取消</button>
          <button type="button" id="changePasswordSubmit" style="padding:8px 16px;border:none;background:#1677ff;color:#fff;border-radius:4px;cursor:pointer;">确定</button>
        </div>
      </div>`;
    document.body.appendChild(overlay);

    const close = () => overlay.remove();
    overlay.addEventListener('click', event => {
      if (event.target === overlay) {
        close();
      }
    });
    document.getElementById('changePasswordCancel').addEventListener('click', close);
    document.getElementById('changePasswordSubmit').addEventListener('click', async () => {
      const oldPassword = document.getElementById('changePasswordOld').value;
      const newPassword = document.getElementById('changePasswordNew').value;
      const confirmPassword = document.getElementById('changePasswordConfirm').value;

      if (!oldPassword || !newPassword) {
        alert('请填写原密码和新密码');
        return;
      }
      if (newPassword.length < 6 || newPassword.length > 32) {
        alert('新密码长度必须在 6-32 位之间');
        return;
      }
      if (newPassword !== confirmPassword) {
        alert('两次输入的新密码不一致');
        return;
      }

      const submitButton = document.getElementById('changePasswordSubmit');
      submitButton.disabled = true;
      try {
        const response = await fetch('/api/auth/change-password', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json'
          },
          body: JSON.stringify({ oldPassword, newPassword })
        });
        const data = await response.json().catch(() => ({}));

        if (response.ok && data.success) {
          alert('密码修改成功');
          close();
        } else {
          alert(data.message || data.error || '密码修改失败');
        }
      } catch (error) {
        console.error('修改密码失败:', error);
        alert('密码修改失败，请稍后重试');
      } finally {
        submitButton.disabled = false;
      }
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', injectChangePasswordButton);
  } else {
    injectChangePasswordButton();
  }
})();
