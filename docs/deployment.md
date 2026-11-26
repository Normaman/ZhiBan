# 部署与维护

本文包含安装、账号初始化、店铺连接、服务更新及故障排查。产品介绍见 [README](../README.md)。

## 快速部署

### 1. 准备项目文件

从项目发布页下载源码 ZIP 并解压，或在 GitHub 项目页通过 **Code → HTTPS** 复制地址后克隆仓库。然后在项目根目录打开终端；根目录应能看到 `infra/compose.yml`、`scripts/`、`apps/` 和 `services/`。发布源码时不要打包 `infra/.env`、`.runtime/` 或店铺登录资料；每套部署都应创建独立密钥和数据。

### 2. 安装 Docker

- **Windows：** 安装并启动 Docker Desktop，然后在项目目录打开 PowerShell。无需在 WSL/Ubuntu 终端中运行项目。Docker Desktop 的默认后端使用 WSL 2；若系统不能启用 WSL，须使用 Docker 官方支持的、适用于该 Windows 版本的 Hyper-V 后端安装方式。[Windows 安装要求](https://docs.docker.com/desktop/setup/install/windows-install/) · [后端说明](https://docs.docker.com/desktop/features/wsl/)
- **macOS：** 安装并启动 Docker Desktop，然后打开 Terminal。
- **Linux：** 安装 Docker Engine 和 Docker Compose 插件。

确认 Docker 服务已启动：

```sh
docker version
docker compose version
docker buildx version
```

首次构建需要互联网访问容器镜像仓库、Maven 仓库和 npm/Python 包源；耗时取决于网络。若报 `auth.docker.io` 超时，是镜像仓库网络问题，修复网络后重试部署命令即可。Dockerfile 使用 BuildKit 缓存挂载；若 `docker buildx version` 提示缺少插件，Docker Desktop 用户应更新安装，使用 Docker 官方软件源的 Linux 用户可安装 `docker-buildx-plugin`。不要通过关闭 BuildKit 来规避插件缺失。

### 3. 初始化并启动

Windows PowerShell：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\docker-init.ps1
docker compose --env-file infra/.env -f infra/compose.yml up --build -d
```

macOS 或 Linux 终端：

```sh
sh scripts/docker-init.sh
docker compose --env-file infra/.env -f infra/compose.yml up --build -d
```

初始化脚本通过临时 Python 容器生成随机管理员密码、注册邀请码、数据库密码和加密密钥；不会要求宿主机安装 Python，也不会覆盖已有 `infra/.env`。如果构建因网络超时失败，修复 Docker 拉取镜像的网络后，重复执行 `docker compose ... up --build -d`。

### 4. 登录和配置

默认打开 <http://localhost:5173>；访问端口由 `infra/.env` 中的 `WEB_PORT` 决定，例如设置 `WEB_PORT=5178` 时打开 <http://localhost:5178>。初始管理员用户名、随机密码和注册邀请码保存在部署目录的 `infra/.env`，不要公开或提交此文件。

Windows PowerShell 查看初始登录信息：

```powershell
Select-String -Path .\infra\.env -Pattern '^(REVIEWER_USER|REVIEWER_PASSWORD|REGISTRATION_INVITE)='
```

macOS/Linux 查看：

```sh
grep -E '^(REVIEWER_USER|REVIEWER_PASSWORD|REGISTRATION_INVITE)=' infra/.env
```

管理员登录后，在「模型配置」填写服务商提供的 Base URL、模型名称和 API Key，测试并启用。其他用户可用部署者提供的邀请码注册独立空间；管理员不要把整个 `.env` 发给注册用户。

### 连接真实拼多多店铺

首次登录进入系统自动创建的未授权店铺；打开「店铺与成员」可修改店铺显示名称，再点击「连接拼多多」，在授权页完成商家登录，核对页面显示的店铺身份后确认保存。登录凭据会加密保存在 Docker 持久卷中，连接器随后自动启动；真实发送仍保持关闭。完成连接后，在该店铺同步商品并单独配置模型。移除非当前店铺前会二次确认，并永久删除这家店铺的会话、消息、知识库、商品资料、模型配置和登录凭据；不能移除最后一家店铺。

已绑定店铺的商品列表会在服务启动约 1 分钟后开始自动检查，默认每 5 分钟轮询一次；工作台打开时最多约 30 秒刷新一次展示。当前连接器使用分页商品列表，没有可确认的增量游标，因此每轮会重新读取平台列表，再按商品指纹比较；列表未变化只更新时间，有变化才保存新快照。详情会优先更新受影响商品，并按缓存有效期补齐过期详情。仍可手动点击「同步商品」立即检查。可通过 `PRODUCT_CATALOG_POLL_INTERVAL_MS`（毫秒，默认 `300000`）和 `PRODUCT_CATALOG_POLL_INITIAL_DELAY_MS`（默认 `60000`）调整间隔。平台请求失败会记录安全错误类型，并在下一轮重试。

订单卡片支持「查询状态」。若平台要求安全验证，管理员点击「安全核验」，在弹出的拼多多订单页完成滑块，再回到卡片点击「已完成验证，重新查询」。查询成功会关闭核验窗口，后台保留查询浏览器以供复用；已有失败草稿需要重新生成。操作步骤与浏览器释放条件见[订单查询与安全核验说明](wsl-pdd-login.md#订单卡片提示安全验证时)。

使用左栏订单列表前，按[订单查询通道说明](camoufox-order-fallback.md)部署独立 Camoufox 网关；列表查询、筛选和翻页直接走该通道。订单详情仍保留原链路，40002 后的 Camoufox 兜底默认关闭。管理员在「店铺与成员 → 订单旁路授权」独立扫码，核对店铺后加密保存；查询失败不会停下来等待扫码。

## 日常管理

后台业务任务由 PostgreSQL 持久化、Redis Streams 分发，并通过 outbox 和定时对账恢复未投递任务。部署、重试和监控语义见[后台任务队列说明](job-queue.md)。

### 本地评测复核

用于补充和修订测试集、核对前后回答及查询依据、校正误判。打开 **<http://localhost:5190/review.html>**。只需本地 Python 服务，无需启动客服工作台、模型或数据库；编辑和离线重算均不产生模型调用。

复核服务停止后，在已安装项目 Python 依赖的环境中，从仓库根目录执行：

```bash
python -m evals.workbench
```

保持终端运行，按 `Ctrl+C` 可停止；服务已启动时不必重复执行。用例修订、人工评分和判分校正保存到 `.runtime/eval-curation/main/`，重启及换浏览器后仍可读取；旧页面的浏览器评分可通过「导入旧页面评分」转存。请备份该目录和原始报告，`.runtime/` 不随 Git 上传。

操作顺序：**选择用例 → 编辑 / 复制新增 → 填写事实、预期和修改依据 → 导出新版本**。误判用「校正判分」逐项记录；原始报告及分数保留。只改预期可重算两侧已有输出，新增问题或修改事实必须重新运行模型，不会把旧回答算作新测试通过。详细流程、数据字段和重跑命令见 [评测数据校对指南](agent-evaluation.md#本地数据集校对工作台)。

### 知识库导出与迁移

管理员可在「知识库 → 备份与迁移」一键导出当前知识库，或选择备份迁移到当前店铺。迁移保留分类、来源和商品绑定，默认合并并跳过重复资料；不会清空目标知识库。

把已有开发数据迁入当前 Docker 店铺，在已安装项目 Python 依赖的环境中，从项目根目录执行：

```sh
python scripts/knowledge_transfer.py migrate --yes
```

脚本先在 `.runtime/exports/` 保存备份，再按平台店铺编号核对目标并发布。只导出可执行 `python scripts/knowledge_transfer.py export`。商品知识要求目标已同步对应商品；详细步骤和选项见 [知识备份与迁移](knowledge-import.md#备份与迁移)。

### 容器管理

以下命令均在项目根目录执行。PowerShell 与 POSIX shell 的 Compose 命令相同：

```sh
docker compose --env-file infra/.env -f infra/compose.yml ps
docker compose --env-file infra/.env -f infra/compose.yml logs -f
docker compose --env-file infra/.env -f infra/compose.yml down
```

`down` 停止容器但保留 PostgreSQL、Redis 和连接器数据。**不要运行 `down -v`，除非确实要永久删除这套部署的全部数据。**

### 日常启动与代码更新

首次构建成功后，日常启动已有版本不需要重新构建：

```sh
docker compose --env-file infra/.env -f infra/compose.yml up -d --no-build
```

该命令使用本机已有的应用镜像，适合关机后再次启动或执行 `down` 后恢复。如果应用镜像不存在，需要先执行首次构建命令。Docker Desktop 或 Docker Engine 本身也需要处于运行状态。

修改源码后，容器不会自动加载宿主机代码，需要重建对应服务。以下命令在构建成功后更新并启动该服务：

| 修改内容 | 更新命令 |
| --- | --- |
| React 页面、样式或前端依赖 | `bash scripts/docker-update.sh web` |
| Spring Boot Java 代码或依赖 | `bash scripts/docker-update.sh core-service` |
| Python Agent 代码或依赖 | `bash scripts/docker-update.sh agent-service` |
| Camoufox 网关或浏览器镜像 | `bash scripts/docker-update.sh camoufox-gateway` |

表中的脚本在 WSL / Linux 使用，按所选服务构建、限额回收构建缓存，然后以 `up -d --no-build --no-deps` 更新容器，其余依赖应已在运行。也可执行 `make docker-update SERVICE=web`。Windows PowerShell 用户可在 WSL 执行脚本，或手动执行 `docker compose --env-file infra/.env -f infra/compose.yml up -d --build --no-deps 服务名`。

拼多多登录、商品/订单查询会话、WebSocket 连接和发送由 Spring Java 管理；Python Agent 不挂载平台凭据卷。登录桌面由 Java 启动 TigerVNC 与 noVNC/WebSocket 转发依赖。`docker compose restart` 只重启原容器，不会编译源码或换成新镜像。前端更新后，已打开的网页需要刷新。

升级或同时修改多个服务时，先备份数据，再更新源码并重建全部应用服务：

```sh
docker compose --env-file infra/.env -f infra/compose.yml up --build -d
```

### 为什么有时会重新下载依赖

Docker 会缓存构建步骤。依赖安装层命中缓存时，修改业务代码可以复用已安装的依赖；基础镜像已经在本机时，普通重建通常也不需要重新下载它。首次构建、依赖清单或锁文件变化、清理缓存、使用 `--no-cache`，以及主动更新基础镜像时，可能需要重新下载。

三个应用的 Dockerfile 已将依赖安装和业务源码分开，并增加下载缓存：

| 服务 | 只改源码时的行为 | 下载缓存 |
| --- | --- | --- |
| Core Java | 复用按 `pom.xml` 预取的依赖，重新编译 Java | Maven `.m2` |
| Core 浏览器支持 | 在复制 Java 源码前安装；只改源码时复用浏览器层 | Maven、Chromium 下载 |
| Python Agent | 复用按 `uv.lock` 安装的第三方依赖，重新安装本项目代码 | uv、pip |
| React | 复用按 `package-lock.json` 安装的依赖，重新构建前端资源 | npm |

Core 运行镜像为 Ubuntu 24.04/JRE 21。noVNC 发行包依赖系统 WebSocket 转发工具；这不包含 Agent 运行时或拼多多平台业务代码。Python Agent 依赖由根目录 `uv.lock` 管理。

Agent 源码变化时会重新构建并安装本项目的 Python 包，第三方依赖仍复用缓存。镜像构建还会逐文件核对实际导入的包与复制进去的源码，避免本地 wheel 缓存使新镜像运行旧代码。

容器内 Maven 使用 `services/core-service/maven-settings.xml` 统一从 Maven Central 下载，避免继承依赖声明的额外仓库并逐个重试。如果后续增加私有 Java 依赖，应调整该文件中的镜像源；不要将仓库密码写入源码。

应用 Dockerfile 的基础镜像和 Dockerfile 构建前端均固定到已核验的 SHA-256 摘要。Compose 设置 `build.pull: true` 核验镜像，规避本机默认本地镜像解析导致的缓存不命中；相同摘要的镜像层可以继续复用，不会因此自动切换到新版本。更新基础镜像时，需要显式更新 Dockerfile 中的摘要。手工使用 `docker buildx build` 时也应加 `--pull`。

**首次使用新版 Dockerfile 仍需建立缓存。**之后只改源码可复用第三方依赖和浏览器安装层；依赖清单变化时，下载缓存可以复用已下载的包，只补齐缺失内容。缓存属于当前 Docker 构建器，更换电脑、构建器或回收缓存后可能需要重新准备；它不保证完全离线构建，基础镜像检查、缺失的包和浏览器仍可能需要网络。日常更新不使用 `--no-cache`，缓存通过限额回收，避免长期累积。

可用下面的命令查看构建输出，依赖安装步骤显示 `CACHED` 表示整层复用；依赖步骤因清单变化重新执行时，也会使用下载缓存：

```sh
docker compose --env-file infra/.env -f infra/compose.yml --progress plain build core-service agent-service web
```

缓存机制参考 [Docker 构建缓存说明](https://docs.docker.com/build/cache/optimize/) 和 [uv 容器构建说明](https://docs.astral.sh/uv/guides/integration/docker/#intermediate-layers)。

### 可选混合检索部署

需要语义检索时，使用 [混合 RAG 部署说明](hybrid-rag.md) 的 `compose.rag.yml` 覆盖配置。它增加独立的派生向量数据库和模型缓存，不替换业务 PostgreSQL。启用后手工 Compose 命令必须保留两个配置文件；`docker-update.sh` 在私有环境标志 `RAG_ENABLED=1` 时自动保留这份覆盖配置。

### 构建缓存占用与 WSL 磁盘

查看占用和按预算回收：

```sh
bash scripts/docker-cache.sh status
bash scripts/docker-cache.sh plan
bash scripts/docker-cache.sh clean
```

维护脚本需要 Python 3，只处理能从构建描述确认属于本项目的重复编译结果和浏览器快照，每种相同构建步骤保留最新两份；已淘汰的 Python 桌面登录构建结果可回收。`plan` 只生成清单，`clean` 先将清单写入 `.runtime/docker-maintenance/`，再按明确的缓存 ID 删除。默认使用 `default` 构建器，可用 `--builder 名称` 指定其他构建器；需确认 Docker context 正确。

下载缓存挂载（`exec.cachemount`）、用途不明的构建层、镜像、容器和数据卷均不清理。npm/pip 等通用依赖步骤不通过名称猜测归属。脚本不会调用构建历史删除接口；旧版本 Docker / BuildKit 的历史查询和删除可能存在兼容性问题。清理后返回旧源码或旧依赖版本时，可能需要重新编译、安装或补充下载，不能保证离线构建。

更新脚本构建前把当前应用镜像保存到固定 `:rollback` 标签，构建成功后执行定向缓存回收，再更新所选容器。固定标签下次更新时替换，避免每次创建新的日期标签；脚本不自动删除其他已有版本。原 `DOCKER_CACHE_LIMIT` / `DOCKER_CACHE_RESERVE` 不再生效，避免将共享构建器按全局预算清理。

`docker system df` 中镜像与缓存有共享层，不能把两项相加当作可释放空间；当前镜像仍引用的层、正在使用的缓存及保留的下载缓存，也会使显示总量高于目标。避免使用 `docker system prune -a --volumes`，该命令会涉及业务持久数据。Core 已将 Chromium 安装提前到 Java 源码之前，并在复制浏览器时直接设置文件归属，减少源码更新时重复写入浏览器及递归改归属产生的大层。

绕过更新脚本直接运行 Compose 不会触发脚本清理。[infra/docker-gc.example.json](../infra/docker-gc.example.json) 提供默认 `docker` 驱动的可选 GC 规则：先回收 48 小时未使用的项目构建结果，再按 12GB 目标回收项目可识别构建层，排除下载缓存和其他项目描述。这不是 Docker 总空间配额；当前镜像、未知层及下载缓存可能使总占用超过目标。参见 [Docker 官方 GC 配置](https://docs.docker.com/build/cache/garbage-collection/#docker-daemon-configuration-file)。

部署者需将规则合并到现有 `/etc/docker/daemon.json`，保留代理、镜像仓库等其他设置。先执行 `dockerd --validate --config-file 合并后的配置文件` 检查，再安排停机应用并重启 Docker。重启可能中断本机所有容器，包括其他项目；本仓库脚本不会自动安装这份配置或重启 Docker。

Camoufox 镜像在独立阶段安装浏览器，再用 `COPY --chown` 复制到运行阶段，避免后续递归改权限额外生成一份浏览器层。首次使用此 Dockerfile 需要重新建立部分安装层，但保留的浏览器 ZIP 可继续复用；运行中的镜像不会因此自动变小，需在计划更新时构建替换。

WSL 的 Linux 可用空间和 Windows 所在盘剩余空间不同。删除 Docker 缓存后，Linux 中空间可用，但 Windows 的 VHDX 文件可能仍占原来的空间。参考 [WSL 磁盘说明](https://learn.microsoft.com/en-us/windows/wsl/disk-space) 与 [微软 compact vdisk 文档](https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/compact-vdisk)，在需要让 Windows 回收空间时：

1. 在 WSL 执行 `sudo fstrim -v /`，需要本机 sudo 密码。本机 Docker Engine 的数据在 `/var/lib/docker`，应处理 Ubuntu 的 VHDX，而不是假定为 Docker Desktop 的磁盘。
2. 保存工作并关闭 WSL 程序，在 Windows PowerShell 执行 `wsl --shutdown`；此操作会停止本机 Docker 和所有 WSL 服务。
3. 从 WSL 注册表的发行版 `BasePath` 确认实际 `ext4.vhdx` 路径；备份重要数据后，在管理员终端运行 `diskpart`，依次执行 `select vdisk file="实际的 ext4.vhdx 路径"`、`compact vdisk`、`exit`。磁盘必须未挂载或以只读方式附加；提示被占用时先解决占用。
4. 重新打开 WSL，再执行上面的日常启动命令。实际返还空间取决于 VHDX 的可压缩块，不保证等于 Docker 报告的清理量。

本项目不会自动关闭 WSL 或执行虚拟磁盘压缩。

项目发布源码和 Dockerfile，不提供预构建应用镜像。每套部署从源码构建三个应用镜像；数据库和 Redis 使用官方容器镜像。各部署的数据、账号、模型配置及拼多多登录态彼此独立。

## 从源码开发

Docker 部署是面向用户的标准启动方式。本地源码开发需要分别准备 JDK 21、Maven、Node.js、Python 及 PostgreSQL/Redis；启动方式、环境变量和服务管理命令见[开发运行手册](copilot-runbook.md#本地开发)。本地开发数据与 Docker 部署数据相互独立。

## 访问范围

默认只监听部署端的回环地址 `127.0.0.1:5173`，适合在部署主机上访问。局域网访问可在 `infra/.env` 中增加：

```dotenv
WEB_BIND_ADDRESS=0.0.0.0
```

然后重启 Compose，并按需配置操作系统防火墙。局域网模式使用 HTTP，只应在可信网络内使用。

### 公网 HTTPS 部署

项目包含可选 Caddy 反向代理。准备一台有公网 IP 的 Linux 主机、指向该 IP 的域名 DNS 记录，并确保外部能访问 TCP 80/443。编辑 `infra/.env`，设置：

```dotenv
DOMAIN=support.example.com
SESSION_COOKIE_SECURE=true
```

将域名替换为自己的域名，然后启动含 HTTPS 入口的部署：

```sh
docker compose --env-file infra/.env -f infra/compose.yml --profile public up --build -d
```

Caddy 会为域名申请和续期 HTTPS 证书，并转发工作台与登录桌面所需的 WebSocket。访问 `https://support.example.com`。公网部署不要把 PostgreSQL、Redis、Core、Agent 或 noVNC 端口映射到公网；Compose 默认不发布这些端口。上线前仍须落实数据备份、访问控制、日志留存和安全更新。

## 真实拼多多接入边界

Docker 化不会自动授权拼多多店铺。管理员可以在「店铺与成员」新增真实店铺并在线完成网页登录和店铺身份核对；Core 容器内运行 Chromium 和虚拟桌面，登录资料加密保存在持久卷中。商品同步和消息链路仍须在目标部署的测试店铺单独验证。当前没有公开的官方通用买家聊天消息 API 可供本项目替代接入，因此项目不能承诺平台长期兼容性或官方授权状态。


## Agent 状态升级

Python Agent 不再使用 SQLite 或 `AGENT_DATA_DIR`。部署升级后，Spring/Flyway 自动创建 PostgreSQL `agent_execution_results` 表，保存运行请求、执行租约和已完成结果。现有业务运行结果、会话和审批仍在原 PostgreSQL 中。

Compose 已移除 Agent 状态数据卷挂载；旧 `agent-state` 卷和本地旧缓存不会自动删除或导入。它们可能含历史买家内容，应由部署者按自己的留存策略处理。平台凭据卷和业务数据库卷继续使用。Core 与 Agent 的执行协议有变更，升级时应一起重建两项服务，避免新 Agent 请求旧 Core 接口。
