# 三方 SSO（企业微信 / 飞书 / 钉钉）Owner 自验手册

> 交付轮：P60 v0.1.0-oa-completion · I5 iteration-10（执行入口 `planning-execution-prompt-stage-i5-tenant-safe-sso-09.md`）
> 适用对象：Owner（将自行注入真实凭据并验收三 Provider 真实成功链）
> 本手册不含任何秘密；所有 `__SET_ME__` 均为待 Owner 替换的占位符。
> 三 Provider 当前默认**禁用态**交付；真实成功链（G8-WECOM / G8-FEISHU / G8-DINGTALK）由 Owner 实测后裁决，执行侧未声明成功。

## 0. 从哪里开始

1. 先读 `docs/sso/provider-config-example.yml`（可直接填值的禁用态样例）。
2. 按本手册 §1 准备系统级配置 → §2 在各 Provider 官方控制台建应用 → §3 注入凭据并启用 → §4 逐 Provider 走绑定/登录/解绑自验 → §5 失败恢复与问题定位。

## 1. 系统级配置（启动边界）

| 配置键 | 用途 | 缺失后果 |
|---|---|---|
| `SW_SSO_CIPHER_KEY` | Provider secret 的 AES-256-GCM 加密密钥（Base64，32 字节） | 启动失败（fail-fast） |
| `SW_SSO_CALLBACK_BASE_URL` | 服务端对外 HTTPS 基址 | 留空=相对路径模式（仅同源代理可用） |
| `SW_SSO_CALLBACK_ALLOWLIST` | 回调 URL 前缀白名单（逗号分隔） | 配了基址但不在白名单 → 回调地址生成直接拒绝（fail closed） |

回调路径固定：`{BASE}/api/auth/sso/{provider}/callback`（provider 小写）。认证层放行已内建（`/auth/sso/*/callback` 等已在 permit 名单），无需 Owner 修改代码或安全配置。

## 2. Provider 控制台配置项（官方侧，逐 Provider 独立应用）

### 企业微信（WECOM）
- 管理后台自建应用：取 `CorpID`（→ app_id）、应用 `Secret`、`AgentID`（→ extra_config.agentId）。
- 「企业微信授权登录」中配置**授权回调域**：必须与访问链接域名完全一致（含端口，不支持泛域名，不含协议头）。
- 文档对照：`product/v0.1.0-oa-completion/receipts/evidence/i5-10/doc-comparison.md`（当前实现走旧版扫码 `qrConnect`，官方确认仍可用；回调带回 code/state）。

### 飞书（FEISHU）
- 开放平台自建应用：取 `App ID`（→ app_id）、`App Secret`。
- 「安全设置」登记重定向 URL：`{BASE}/api/auth/sso/feishu/callback`。
- 权限：获取用户身份（contact/user 相关只读授权即可满足 open_id 换取）。

### 钉钉（DINGTALK）
- 开放平台企业内部应用：取 `AppKey`（→ app_id）、`AppSecret`。
- 「登录与分享」登记回调地址：`{BASE}/api/auth/sso/dingtalk/callback`。
- 权限：通讯录个人信息只读（unionId）。

## 3. 凭据注入与启用顺序

Provider 配置行（`sys_sso_provider_config`）当前**无 HTTP 管理端点**（`SsoAuthService.saveConfig` 仅为服务层契约）；凭据注入按以下顺序操作：

1. 确认 `SW_SSO_CIPHER_KEY` 已在运行环境注入且服务可用该密钥。
2. 用应用同款 `AesGcmCipher`（sw-common）以该密钥把真实 secret 加密为 Base64 密文（在受控环境执行，密文与明文均不写入仓库、日志、命令历史）。
3. 写入/更新租户配置行（disabled → enabled 的启用顺序）：
   - 先以 `enabled=0` 落行：app_id、extra_config、redirect_path 就位，`app_secret_enc` 置密文；
   - 核对 §2 控制台回调域/重定向 URL 与 `SW_SSO_CALLBACK_ALLOWLIST` 一致；
   - 再置 `enabled=1` 启用。启用边界会拒绝空值/占位值（`placeholder/changeme/example/your-*/replace-me/__SET_ME__` 等）——这是预期保护，不是故障。
4. 同一 `(provider, app_id)` 全系统仅允许一个租户登记；跨租户重复登记会被拒绝并记审计。

**禁用/停用**：置 `enabled=0` 即可，停用不要求凭据（支持安全下线）。

## 4. 自验步骤（逐 Provider，预期结果）

前置：该租户下存在本地用户；浏览器可访问 `{BASE}` 前端。

**绑定**（已登录第一方会话）：
1. `GET /api/auth/sso/{provider}/authorize`（需认证）→ 返回 Provider 授权页 URL（含 state 摘要落库）。
2. 用户在 Provider 页面扫码/确认 → 回调 `{BASE}/api/auth/sso/{provider}/callback?code&state`。
3. 服务端用 code 换稳定外部身份（企业微信 userid / 飞书 open_id / 钉钉 unionId）→ 候选暂存。
4. `POST /api/auth/sso/bind-candidate` → `GET /api/auth/sso/bindings` 出现该 Provider 绑定。预期：200；`sys_sso_audit_record` 新增 BIND/SUCCESS；本地角色表零增量。

**已绑定登录**（未登录态）：
1. `GET /api/auth/sso/{provider}/authorize-login?tenant=...` → 授权页 URL；回调后 `POST /api/auth/sso/ticket` 一次性兑换 → 与第一方同契约的 accessToken/refresh cookie。
2. 预期：me 200；票据重放被拒（「票据无效或已过期」）。

**解绑与会话撤销**：
1. `POST /api/auth/sso/unbind {"provider":...}`（Bearer 当前会话）→ 200。
2. 预期：当前会话 me 401、refresh 拒绝；绑定视图清空；新第一方登录立即成功、不被旧撤销标记误伤（同秒亦然，jti 唯一性已锁定）。

**失败恢复**：
- Provider 侧失败（拒绝授权/无效 code）→ 服务端统一拒绝并记审计 DENIAL，本地不产生会话；重试发起新授权即可。
- 停用租户/过期租户 → 兑换拒绝（「租户无效或已停用/过期」），既有会话在下次权威装载收敛。

## 5. 清理与问题定位边界

- 审计：`GET /api/auth/sso/audit?provider=...` 查询绑定/解绑/拒绝事件。
- 会话排查键：`sw:security:login-user:{userId}`（会话缓存）、`sw:security:token-revoked:{sha256(token)}`（撤销标记）——只观察键存在性，不落 token 明文。
- 换票失败优先核对：code 是否一次性且 5 分钟内、回调域/重定向 URL 是否与控制台登记**完全一致**、凭据是否启用状态、（飞书）redirect_uri 相关错误见对照账本观察点。
- 自验完成后清理：解绑测试绑定、停用或删除测试配置行、轮换测试期间暴露给浏览器的授权 URL（state 一次性已内建）。

## 6. 安全口径（验收红线）

- secret 只进环境与加密列；不出现在命令行参数、URL、普通日志、截图或任何回执。
- Provider 一旦启用，占位凭据在启用边界 fail-fast；未启用时样例保持哨兵值。
- 三 Provider 真实成功链由 Owner 实测裁决；执行侧证据只覆盖文档一致性与本地行为链（见 `evidence/i5-10/`）。
