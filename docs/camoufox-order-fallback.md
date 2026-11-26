# 订单查询 Camoufox 通道

左栏订单列表查询直接使用网关分配的独立 Firefox 实例，筛选和翻页也走 Camoufox。单次调用只申请一个实例；失败后返回明确错误，不先尝试或回切本地 Chromium。

订单详情保留原有链路：本地 Chromium 订单浏览器明确收到业务码 `40002` 后关闭该会话，仅在兜底开关开启时使用 Camoufox 尝试一次。代码不判断拒绝原因，也不保证能够解除平台限制。

## 调用入口

可注入 `PddOrderQueryService`，调用：

```java
OrderResult result = orderQueries.queryPddOrder(
    "https://mms.pinduoduo.com/orders/list?msfrom=mms_sidenav&type=1");
```

没有 `main` 或新增公开 HTTP 接口。店铺来自业务层已有的 `ShopScope`，调用者应沿用现有认证、权限及店铺作用域；URL 不能选择另一个店铺。

支持已观察到的列表页和详情页：

- `/orders/list`：默认已发货订单，第 1 页。
- `/orders/list?type=1`：平台已观察到的“已发货”路由，对应请求 `orderType=2`。
- `/orders/list?orderType=2&page=2`：内部入口参数，支持 `orderType=1/2/7`，`page=1..100`；由已有页面筛选和翻页操作执行，不把内部参数传给平台。页面状态标签可能带数量，例如 `待发货(0)`。

不把 `orderType=0` 当作“全部订单”。实际页面的该类型请求每页数量为 1；已发货完整列表使用类型 2、每页数量 20，点击原生待发货标签发出类型 1。工作台默认显示已发货，不展示未经验证的“全部”筛选。

### 工作台缓存

订单列表和顶部统计使用 Redis 展示缓存，按店铺绑定身份隔离；列表进一步按状态和页码隔离。缓存 60 秒内直接返回，超过 60 秒先返回旧数据并后台更新，成功快照最多保留 24 小时。没有缓存时先返回更新状态，由工作台轮询结果；轮询不会重复发起正在执行的查询。`fetched_at` 保留实际读取时间，读缓存不会改写它。

点击刷新或重试使用 `refresh=true`，立即申请更新，不受 60 秒新鲜度限制；同一资源已有查询时合并到该任务。Redis 租约隔离多个服务实例；列表执行器最多执行两个任务，统计使用独立的单线程执行器，均不设置无限队列。更新失败保留旧快照，返回 `cache.error_code`；自动更新失败后退避 30 秒，执行器繁忙退避 5 秒。工作台遇到短暂繁忙时每 5 秒重试，最多 12 次，等待期间展示旧数据和更新状态；真实查询失败仍明确提示。统计和列表的重试按钮仅更新对应资源，顶部刷新按钮更新两者。

`GET /api/v1/shops/{id}/orders` 与 `/orders/overview` 的响应增加 `cache` 元数据：`has_value`、`state`（`empty/fresh/stale`）、`refreshing`、`error_code` 和 `expires_at`。只有 `has_value=true` 时包含列表或统计数据，缺少缓存不伪装成零条订单。读取缓存仍经过现有账号、店铺权限和绑定检查；后台任务显式继承店铺作用域。此缓存不用于 Agent 的订单明细核验。
- `/orders/detail?sn=<订单号>`：详情查询。

仅接受 `https://mms.pinduoduo.com` 的上述路径。两条链路均返回 `OrderResult(channel, kind, data)`，`data` 复用 `PddOrderParser` 的白名单字段，不返回平台原始响应。现有 `captureOrder` 和 `captureOrderList` 已接入编排层，仍返回原来的 Map 结构，避免变更工作台契约。

商品查询、订单概览及 `PddCommerceService` 中已保存会话的直接 HTTP 查询不在这个浏览器旁路的切换范围内。主连接登录和原有人工安全核验入口保留；旁路另有独立扫码授权页。

## 配置

在部署的 `infra/.env` 中设置，然后重建并更新 Core：

```dotenv
PDD_ENABLE_CAMOUFOX_FALLBACK=false
CAMOUFOX_GATEWAY_URL=http://camoufox-gateway:8000
# 代理可选；网关 Token 须配置为至少 32 字符的随机密钥
CAMOUFOX_PROXY=
CAMOUFOX_GATEWAY_TOKEN=
```

Spring 属性名为 `copilot.pdd-order.enable-camoufox-fallback`，Java 字段为 `enableCamoufoxFallback`，默认 `false`，只控制订单详情的 40002 兜底，不控制订单列表。使用左栏订单列表前必须配置网关并保存独立扫码登录态。Core 在 Docker 中运行时，网关地址必须能从 Core 容器访问；容器的 `localhost` 不是宿主机。

仓库已包含独立网关实现及 Dockerfile，默认不启动，使用 Compose 的 `camoufox` profile 部署。网关要求至少 32 字符的专用 `CAMOUFOX_GATEWAY_TOKEN`，Java 通过 `Authorization: Bearer ...` 调用；不要使用公开示例中的固定密钥。部署步骤和回收行为见[网关说明](../services/camoufox-gateway/README.md)。Agent 服务没有加入这些浏览器依赖。

## 网关要求

```http
POST /fetch
Content-Type: application/json

{"proxy":"socks5://...","loginSessionHint":"opaque-correlation-id"}
```

`proxy` 可选。Java 每次生成独立的 `loginSessionHint`，仅用于关联日志；网关只记录其摘要，不把它当作登录凭据、Cookie、实例复用键或浏览器参数。响应契约：

```json
{"instanceId":"inst_xxx","wsEndpoint":"ws://gateway:8000/relay/inst_xxx/opaque-token","protocol":"playwright-firefox","ttlSeconds":120}
```

实例 ID 限制为 1–128 位字母、数字、`_`、`-`。Java 使用 `playwright.firefox().connect()`；不启动旁路本地浏览器、不使用 CDP、不注入指纹伪装、不设置启动参数。

每次 `/fetch` 分配独占 BrowserServer 实例。标准 Playwright 远程连接初始没有 BrowserContext。Java 在旁路扫码授权时创建空上下文，管理员独立登录；查询时只恢复该旁路自己取得的 `storage_state`，不读取 Chromium 的 Cookie、Context、凭据文件。返回的 `ttlSeconds` 为必填整数，范围 30–3600 秒；Java 浏览器阶段使用 `min(75, ttlSeconds-10)` 秒预算，给释放留出时间。

### 首次独立扫码

1. 先配置并启动网关、更新 Core 和 Web，保持兜底开关关闭。
2. 在工作台选择已绑定店铺，进入「店铺与成员 → 订单旁路授权 → 独立扫码授权」，或访问当前站点 `/pdd-camoufox-login`。仅当前店铺管理员可操作。
3. 点击「开始扫码授权」。页面展示远端 Camoufox 的登录页面，可点击平台已有的二维码入口，再用商家手机扫码。没有使用主链路的 noVNC 或 Cookie。
4. 登录后核对平台店铺编号和客服账号，点击「确认并保存」。保存和审计在同一数据库事务内完成，然后释放授权实例。未确认不保存。
5. 登录状态保存到独立的 PostgreSQL 表 `camoufox_login_states`，使用 AES-GCM 和 `camoufox-login:` 作用域加密，并按业务店铺、平台店铺、账号绑定。需要执行 V25 数据库迁移，Docker 更新 Core 时自动应用。此前的手工 JSON 状态文件不会被读取或自动导入。

授权页只提供平台登录页的画面和点击，以便选择扫码入口，不提供密码代填、验证码绕过或商家后台的任意控制。识别到可见验证码页面、明确的验证令牌/验证错误、未支持的登录跳转或超时，立即结束授权并请求释放。平台登录页面和验证信号仍需用隔离账号实际验收，不能根据构建成功推定真实扫码成功。

每次订单任务先恢复独立状态并核对实际店铺/账号。没有状态、登录失败、过期、出现验证要求或页面异常时，当前任务失败并释放实例；不会在订单任务中等待人工扫码或自动重试。成功查询会更新旁路自己的 Cookie 状态；版本校验防止覆盖更新的扫码授权。登录过期/验证失败只标记本次读取的旧版本失效，不破坏并发保存的新版本。

网关的 Playwright 服务端必须与 Java **1.52.x** 的协议兼容，不能只凭 WebSocket 地址可连接就认为兼容。浏览器端点和代理不得公开暴露；WS 日志仅输出协议、主机和端口，隐藏用户信息、路径和查询参数。

```http
POST /release/inst_xxx
Content-Type: application/json

{}
```

`release` 支持重复调用并终止远端进程，已不存在的 ID 也返回 `{"ok":true}`。Java 在正常完成、业务拒绝、连接失败和其他异常后都会调用，并检查响应 `ok=true`；申请实例 HTTP 超时为 60 秒，释放超时为 15 秒。申请预算包含网关启动失败后的清理时间。失败会记录 `pdd_order_instance_release_failed`，正常查询的释放失败也会向调用方报错，不声称已经释放。网关 TTL 是请求超时、响应丢失、JVM 被强制结束时的兜底；客户端在未取得实例 ID 时无法主动释放未知实例。

公开 WS 为网关单端口上的转发地址，不是浏览器内部 WS。网关能实际观察 Java 连接断开，默认等待 2 秒后回收实例；WS 路径携带一次性能力令牌，关闭访问日志，禁止在反向代理中记录完整路径。TTL 使用单调时钟，在实例就绪后开始计算；启动另有 45 秒限制。正常关闭先向 Node 启动器发送 stdin EOF，请求 `BrowserServer.close()`；超过预算后清理记录的进程树，覆盖 Firefox 独立进程组。清理失败返回 503，并保留实例供 GC 重试。

## 查询及失败语义

1. 订单列表直接调用 Camoufox，不启动本地 Chromium；列表筛选与翻页沿用这个策略。
2. 订单详情的原浏览器链路收到 `errorCode`、`error_code`、`code` 任一字段明确为 `40002` 时，立即标记失效、关闭本地持久化上下文并移出会话缓存。同一次调用不重试主链路；仅在开关开启时进入 Camoufox。两种查询的 Camoufox 调用都只申请一个实例，不再重试。
3. 旁路在页面导航和 `networkidle` 完成后等待随机 3000–7000 毫秒，再做身份查询及显式筛选、翻页。订单页自身的首屏请求可能早于 `networkidle`，不会为等待它而阻塞所有首屏请求。
4. `page.route` 对订单列表 JSON 请求中的 `orderType=0/2` 串行放行，按单调时钟使两次放行至少间隔 1500 毫秒。翻页逐页等待对应响应，避免连续点击跳过页码。
5. `page.onResponse` 监听实际 Playwright Java 响应事件；旁路再次收到 `40002` 即关闭连接并失败，随后调用网关释放，不做原因归因。
6. 旁路浏览器阶段使用不超过 75 秒、且不超过 `ttlSeconds-10` 的逻辑预算；操作和等待按剩余时间设置。若驱动本身无响应，网关 TTL 仍是独立的硬回收边界。网关申请和释放分别有 60 秒、15 秒 HTTP 超时，整个请求可能长于 75 秒。不得用过短的上游 HTTP 超时误判其已经停止。

错误分开记录：

| 错误 | 含义 |
| --- | --- |
| `ORDER_BROWSER_REJECTED_40002` | 平台 JSON 中明确返回业务码 40002 |
| `CAMOUFOX_GATEWAY_HTTP_<状态码>` | 网关 HTTP 错误，与平台业务码无关 |
| `CAMOUFOX_GATEWAY_TIMEOUT` | 网关 HTTP 超时 |
| `CAMOUFOX_ORDER_HTTP_<状态码>` | 远端浏览器访问平台的 HTTP 错误 |
| `CAMOUFOX_ORDER_TIMEOUT` | 旁路浏览器操作超时 |
| `CAMOUFOX_SHOP_IDENTITY_MISMATCH` | 远端登录身份与当前店铺不符 |
| `CAMOUFOX_LOGIN_REQUIRED` | 尚未完成旁路独立扫码授权 |
| `CAMOUFOX_LOGIN_EXPIRED` | 旁路登录已过期 |
| `CAMOUFOX_LOGIN_CHALLENGE_REQUIRED` | 已发现明确的验证要求，本次旁路停止 |
| `CAMOUFOX_LOGIN_FAILED` | 旁路身份检查或登录失败 |
| `CAMOUFOX_RELEASE_NOT_CONFIRMED` | 释放接口没有明确返回 `ok=true` |

日志使用 `channel=original/camoufox`，不输出订单内容、Cookie、完整 WS 或代理。实际平台返回非 JSON 时不会根据 HTTP 状态猜测 `40002`。

## 验收边界

构建和容器健康检查不能代替真实店铺验收。上线前需用授权隔离账号核对：列表直接走 Camoufox、筛选翻页、列表失败不回切原链路、独立扫码、管理员/跨店铺权限、详情三种字段的 40002 切换、HTTP 失败不切换、身份隔离、响应解析、请求间隔、取消和超时释放、断连回收、释放失败告警及网关 TTL 回收。
