# Camoufox 实例网关

独立的浏览器基础设施服务，为 Java 订单查询提供 Playwright Firefox WS。它不运行 Agent、不解析订单、不接触原 Chromium 登录凭据。只有启用 `camoufox` profile 才启动。

## 为什么不能直接使用 Camoufox 对象

`Camoufox` 是 Playwright 上下文管理器，不是带 `ws_endpoint` 的 BrowserServer。这里用 Camoufox `launch_options()` 生成浏览器配置，再由固定版本 Playwright 自带 Node 驱动调用公开的 `firefox.launchServer()`。Java 必须使用 `playwright.firefox().connect(ws)`。

依赖固定为 Camoufox Python 0.4.11、Playwright 1.52.0，浏览器固定为 135.0.1-beta.24，不自动跟随最新版本。浏览器二进制和系统依赖在镜像构建时安装；`install_browser.py` 按固定标签选择对应平台的官方发布包。浏览器及协议兼容性仍需通过隔离店铺验收，升级时必须整体验证，不能独立升级 Java 或 Python Playwright。[上游远程说明](https://camoufox.com/python/remote-server/)

## Compose 部署

先完成项目常规 Docker 初始化。在 `infra/.env` 增加以下配置：

```dotenv
PDD_ENABLE_CAMOUFOX_FALLBACK=false
CAMOUFOX_GATEWAY_URL=http://camoufox-gateway:8000
CAMOUFOX_PUBLIC_WS_BASE=ws://camoufox-gateway:8000
CAMOUFOX_GATEWAY_TOKEN=替换为至少32字符的随机专用密钥
CAMOUFOX_INSTANCE_TTL=120
CAMOUFOX_MAX_INSTANCES=2
CAMOUFOX_DISCONNECT_GRACE=2
```

可用临时容器生成随机密钥，写入本机部署配置，勿提交：

```sh
docker run --rm python:3.11-slim python -c "import secrets; print(secrets.token_urlsafe(32))"
```

构建并启动：

```sh
docker compose --env-file infra/.env -f infra/compose.yml --profile camoufox up --build -d camoufox-gateway core-service web
```

首次构建需访问 Docker Hub、PyPI、Debian 软件源及 GitHub Releases。Docker 拉取镜像使用的代理不会自动提供给镜像内的下载命令；如需代理，应另行传入 Docker 标准代理构建参数，并确保代理地址能从构建环境访问。依赖安装和浏览器下载使用独立缓存层。

Compose 配置了 `init: true`、`shm_size: 2gb`、非 root 用户和 30 秒关机宽限。HTTP、WS 使用同一端口，仅在 Compose 私有网络开放，不发布宿主机端口。不要把示例 `127.0.0.1` WS 返回给容器中的 Java；它会指向 Java 容器自身。

如需独立部署，在本目录构建镜像后为容器设置上述网关环境变量，并按客户端实际可达的地址配置 `CAMOUFOX_PUBLIC_WS_BASE`。生产跨主机连接应通过受控网络和 WSS 代理；代理需支持 WebSocket、足够的空闲超时，并隐藏 WS 路径日志。

## 接口

所有 HTTP 管理请求携带 `Authorization: Bearer <CAMOUFOX_GATEWAY_TOKEN>`。`GET /health` 无需鉴权，仅返回进程健康状态，不启动浏览器。

### `POST /fetch`

```json
{"proxy":"socks5://user:pass@host:port","loginSessionHint":"opaque-correlation-id"}
```

两个字段均可选；不使用代理时可以省略 `proxy`，不自动读取部署机的系统代理。`loginSessionHint` 最长 128 字符，只记录其 SHA-256 摘要，绝不用于登录、传递 Cookie、选择历史实例或生成浏览器配置。Java 查询和授权分别生成随机关联标记。

支持 `http`、`https`、`socks5` 代理 URL，转换为 Playwright 的 `server/username/password` 对象；实际代理认证支持仍取决于 Firefox 和代理服务，不能仅凭 URL 被接受判断可用。配置 `CAMOUFOX_PROXY` 时由 Java 放入该字段。

成功 HTTP 200：

```json
{
  "instanceId":"inst_随机ID",
  "wsEndpoint":"ws://camoufox-gateway:8000/relay/inst_随机ID/一次性随机令牌",
  "protocol":"playwright-firefox",
  "ttlSeconds":120
}
```

失败使用真实 HTTP 状态和固定诊断信息，例如：

```json
{"code":"START_FAILED","msg":"start firefox failed"}
```

启动失败 500、启动超时 504、容量耗尽 503、请求格式错误 422、鉴权失败 401。错误不反射完整代理、启动配置或底层异常文本。启动预算 45 秒；Java 申请预算 60 秒，包含启动失败后的清理时间。

### `POST /release/{instanceId}`

返回 `{"ok":true}`。重复释放或 ID 已被 GC 回收仍返回成功。进程树清理失败返回 503，不删除注册项，保留容量占用并由 GC 重试。Java 必须检查真实响应，不能仅根据 HTTP 200 打印“释放成功”。

## 回收机制与边界

- 每实例独立启动器/Firefox 和私有临时目录，不跨任务复用；启动器临时目录指向实例目录，释放时一并删除浏览器临时配置、Cookie 和缓存。
- `release` 请求先通过管道 EOF 关闭远端 `BrowserServer`，等待 7 秒；再停止并清理记录的进程树。记录 PID 及创建时间，避免杀到 PID 被复用后的其他进程。
- Firefox 在 Linux 可能自行创建进程组，不能只 `kill` Python/Node 父 PID。清理会在关闭前记录后代，异常路径冻结父进程、补查后代后强制终止；启动器在就绪前被强杀时，按进程环境中的唯一实例标签补查已成为孤儿的浏览器，不按进程名批量杀 Firefox。
- TTL 默认从就绪后计算 120 秒；启动另有 45 秒上限。GC 每秒扫描，主动释放与 GC 共用同一清理任务。
- WS 通过 FastAPI 实际转发，首次连接独占该实例，禁止第二个连接及重连。连接断开后默认等待 2 秒再回收。
- Java 未连接、进程异常退出、HTTP 客户端在启动时断开、服务正常关机均有对应回收路径。
- 网关进程异常退出会使启动器 stdin EOF，触发关闭；Docker 的 init 负责回收孤儿/僵尸进程。强制结束整个容器最终由容器运行时清理其进程。不能把应用级 `finally` 或 TTL 宣称为宿主机崩溃时仍可执行。
- 不使用多个 Uvicorn worker；内存注册表属于单个网关进程。扩容使用独立容器，并保证同一实例的 HTTP 和 WS 路由到所属容器。

访问日志必须关闭，WS 路径是连接凭证。容器启动命令已带 `--no-access-log`；反向代理也应关闭该路径的记录。日志只包含实例 ID、生命周期状态和固定错误类别。

## 拼多多登录态是独立前置条件

`/fetch` 只创建浏览器，不继承任何主链路登录态。独立扫码由 Java 管理，页面入口为工作台「店铺与成员 → 订单旁路授权」，也可直接打开当前站点 `/pdd-camoufox-login`。开始授权时，Java 用一个全新网关实例和空上下文打开拼多多登录页；手机扫码、核对绑定身份、管理员确认后，才保存旁路自己的登录态并释放实例。

登录态单独存于 PostgreSQL 的 `camoufox_login_states`，使用部署密钥及独立作用域加密，并绑定业务店铺、平台店铺和客服账号。主连接的凭据目录不参与该过程，不再使用 `CAMOUFOX_STORAGE_STATE_DIR` 或手工 JSON 文件。

查询任务仅恢复独立授权结果，不停下来等扫码。缺少授权、登录失败、验证要求或页面异常都返回失败，并调用 `release`；网关健康不代表店铺已登录。当前没有实现验证码处理或自动登录密码填充。

左栏订单列表直接使用 Camoufox，保存独立授权后即可查询。`PDD_ENABLE_CAMOUFOX_FALLBACK` 只控制订单详情被原链路拒绝后的兜底，默认关闭；完成隔离账号验收后可设为 `true` 并更新 Core。

## 文件职责

| 文件 | 职责 |
| --- | --- |
| `server.py` | HTTP 鉴权、契约、WS 转发和断连检测 |
| `lifecycle.py` | 实例注册、容量、超时、幂等释放及进程树清理 |
| `worker.py` | 在隔离进程生成 Camoufox 配置，替换为 Node 启动器 |
| `browser-server.cjs` | 持有真正的 BrowserServer，响应 EOF 和终止信号 |

完整业务入口及尚需验收的场景见[订单旁路说明](../../docs/camoufox-order-fallback.md)。
