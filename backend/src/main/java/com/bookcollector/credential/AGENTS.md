# credential 模块

> `com.bookcollector.credential` · 接口凭证（`weread_credential`，单文档）。

## 职责

保存 / 读取书单八项（sUrl / skey / channelid / vid / priority / acceptLanguage / basever / v）+ `listUrl` + `synckey`；
内存副本供采集链路使用。

## 关键类

| 类 | 职责 |
|---|---|
| `WereadCredentialController` | 读取（明文回显）/ 保存 |
| `WereadCredentialService(Impl)` | 校验 + 保存 |
| `WereadCredentialStore` | 内存副本 + `refreshSynckeyNow()` + `listBaseUrl()` / `booklistBaseUrl()` / `referer()` |
| `WereadCredential` | 单文档 `_id="weread"` |

## 不变量

1. 书单八项缺一 → `requireBooklistComplete()` 抛「书单配置信息不完整」，不发请求。
2. `skey` / `synckey` 不进日志（`@ToString.Exclude`），只打长度 / 前缀。
3. `synckey` 每次请求前由 `refreshSynckeyNow()` 按本地秒值生成，同一秒不重复写库。
4. `listUrl` 只从库读，缺失抛「列表接口地址未配置」；`referer` 从 `listUrl` 推导。
5. 保存表单时 `listUrl` / `synckey` 原样保留（不取请求体）。
6. GET 明文回显书单九项（用户口径）；apiKey / token 仍掩码、不回填。
