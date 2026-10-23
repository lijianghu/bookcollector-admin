# auth 模块

> `com.bookcollector.auth` · 登录鉴权（Sa-Token + Redis）。

## 职责

账号密码来自 `sys_user`（MD5 摘要）；校验通过由 Sa-Token 签发 token、会话落 Redis；登出真失效。
`roles` 只给前端菜单渲染，不参与鉴权。

## 关键类

| 类 | 职责 |
|---|---|
| `AuthController` | login / logout / me |
| `AuthService(Impl)` | 查 `sys_user` → MD5 比对 → Sa-Token 登录 → 会话存 `LoginUser` |
| `SysUser` / `SysUserRepository` | 用户 / 按 username 查、刷新 `lastLoginAt` |
| `AuthStpUtil` | `loginType="bookcollector-admin"` 的 `StpLogic` 封装 |
| `AuthInterceptor` | 校验 `/api/**`，失败写 `code=4100` |
| `@ExtractLoginUser` + resolver | 注入当前登录用户 + 身份一致性校验 |

## 数据

`sys_user`：`uk_username` 唯一；密码存 MD5（32 位小写 hex）。

## 接口

| 方法 | 路由 | 说明 |
|---|---|---|
| POST | `/api/auth/login` | 返回 `{token, username}` |
| POST | `/api/auth/logout` | 删 Redis 会话，token 立即失效 |
| GET | `/api/auth/me` | 当前用户 |

## 不变量

1. 密码只存 MD5 摘要，不存明文；`LoginUser` 不含 password。
2. 鉴权失败返 HTTP 200 + `code=4100`（不是 401）；拦截器自己写响应体。
3. 放行 `/api/auth/login`、`/api/ping`、`/api/ping/**`。
4. `AuthProperties` 只是首次启动播种参数，不是登录校验来源；改密码改库即生效。
5. Redis 必须启动（会话在 Redis）；`spring.redis.*` 前缀。
