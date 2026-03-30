const ADMIN_AUTH_KEY = 'super-agent-admin-auth'
const ADMIN_USER_KEY = 'super-agent-admin-user'

/**
 * 判断当前是否已进入后台管理台演示态。
 *
 * <p>这里故意不走真实登录接口，而是用本地存储模拟一个轻量登录态，
 * 方便学员直接体验管理端功能，不引入额外鉴权复杂度。</p>
 */
export function isAdminAuthenticated() {
  return window.localStorage.getItem(ADMIN_AUTH_KEY) === '1'
}

/**
 * 写入一个假的后台登录态。
 */
export function loginAdminDemo(username) {
  window.localStorage.setItem(ADMIN_AUTH_KEY, '1')
  window.localStorage.setItem(ADMIN_USER_KEY, username || 'admin')
}

/**
 * 清理假的后台登录态。
 */
export function logoutAdminDemo() {
  window.localStorage.removeItem(ADMIN_AUTH_KEY)
  window.localStorage.removeItem(ADMIN_USER_KEY)
}

/**
 * 获取当前演示用户名称。
 */
export function getAdminUsername() {
  return window.localStorage.getItem(ADMIN_USER_KEY) || 'admin'
}
