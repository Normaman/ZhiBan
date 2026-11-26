# 拼多多连接器迁移基线

**目标：Spring Boot 负责拼多多连接与业务接口；Python 只负责 Agent 编排。**

本文记录迁移目标、实施状态和验收边界。每个真实店铺只能有一个连接 owner，当前固定为 Spring Boot Java 连接器。

## 目标职责

### Spring Boot

- 店铺授权流程、平台身份核验、凭据加密保存和凭据轮换。
- WebSocket 连接、认证、心跳、重连及连接状态。
- 消息解析、事件幂等、持久化、任务排队和恢复。
- 商品、SKU、商品配送设置、订单及后续确认可用的物流/售后只读查询。
- 审批后的文字或商品卡发送、回执确认和未知结果处理。
- 给 React 和 Python Agent 提供经过店铺权限校验的业务 API。

### Python Agent

- 意图识别、LangGraph 编排、会话上下文、知识检索和回复草稿。
- 通过受限内部 API 请求商品、订单、政策等业务事实。
- 不保存拼多多 Cookie、Token、反爬请求头或发送 journal；不直接建立平台连接或发送消息。

### 数据权威

- PostgreSQL 保存店铺绑定、业务消息、连接状态、任务、订单查询结果（按明确留存策略）、审批和发送回执。
- 凭据由 Spring Boot 管理并加密；日志、指标和 Agent 上下文不含 Cookie、地址、电话等非必要字段。
- Python 只保存 Agent 运行所需状态和评测/生成状态，不保存平台会话凭据或连接生命周期状态。

## 当前实现盘点

| 能力 | 当前归属 | 证据/说明 |
| --- | --- | --- |
| 拼多多 WebSocket、认证、重连及事件接收 | Spring Boot | `PddConnectionManager`、`PddEventSpool` |
| 商品与订单只读 HTTP 请求和结果解析 | Spring Boot PDD adapter | `PddCommerceService`、`PddProductParser`、`PddOrderParser`、`PddWebClient` |
| 登录凭据及商品/订单查询会话解密读取 | Spring Boot | `PddCredentialStore`，兼容现有 Fernet 文件格式 |
| 商品/订单查询会话浏览器捕获 | Spring Boot Java Playwright | `PddBrowserSessionService`；只读捕获、身份校验、验证请求后 Fernet 加密保存 |
| Agent 工具读取平台业务事实 | Python Agent → Spring 内部 API | `AgentBusinessClient` → `/internal/v1/agent-tools/*` |
| 已审批消息发送和平台回执 | Spring Boot | `PddSendService`；分段意图写入 PostgreSQL `pdd_send_parts`；Python 不提供平台发送能力 |
| 店铺、消息、审批、业务幂等与任务队列 | Spring Boot | `InboxService`、`ReplyApprovalService`、`JobService`、PostgreSQL |
| 授权页面入口、店铺绑定及连接状态 API | Spring Boot | `PlatformLoginService`、`PddLoginBrowserService`、`PddLoginDesktopService`、`PddCredentialVerifier`、`WorkspaceService` |
| Spring 调用 Python 执行 Agent | 双服务 | `TaskWorker`、`AgentClient`、Python `Runtime`；真实平台连接和发送不经过 Python |

当前订单只读请求使用已观察到的商家页面接口，并逐笔核对订单号及买家 UID；当前实现不含订单列表、物流轨迹或售后申请列表能力。迁移不得扩写或猜测平台字段。

## 迁移阶段

### 0. 锁定边界和契约

- 明确入站事件、商品/订单只读结果、发送命令、回执和连接状态的版本化 DTO。
- 每个 DTO 标明字段来源、是否必需、敏感级别和未知值处理方式；缺字段保持未知，不从页面文案猜测。
- 定义店铺范围、幂等键、连接 epoch/sequence、审批版本和 `send_unknown` 恢复规则。

现有路径和迁移次序记录在[`现有拼多多接口清单`](pdd-existing-interface-map.md)。本次按仓库当前实际运行方式迁移，不将官方 OpenAPI 作为前置条件；仍以代码已观察到的商家页面接口和 WebSocket 消息为边界，不猜测新的请求字段或平台能力。

### 1. Spring 建立平台连接模块

- 在 `services/core-service` 内建立平台适配层，隔离 HTTP/WebSocket、协议解析、凭据仓库和店铺连接生命周期；Controller 不直接操作协议或 SQL。
- 适配层先兼容现有加密 Cookie、身份核验和已观察到的 HTTP/WebSocket 行为；所有请求和解析集中在 Spring PDD adapter，Controller 不操作平台协议。
- 将授权浏览器助手及凭据导入/更新纳入 Spring 连接生命周期；Java Playwright 管理浏览器和桌面进程；Python Agent 镜像不含平台浏览器。
- 保留平台未知字段的白名单解析；Java 连接模块以独立错误码报告登录过期、协议变化、身份不符和平台超时。

### 2. 迁移只读业务请求

- 先迁商品目录、商品详情/SKU/配送设置和已核验订单详情；只添加代码已观察到且能确认来源的字段。
- Spring 对外提供同店权限受控的内部 API；Python Agent 通过 API client 获取数据，不再接收 connector 对象或自行访问平台。
- 对物流轨迹和售后查询，只有在确认可用接口、字段和权限后再列入本阶段；接口未知时明确返回未支持。

### 3. 迁移入站消息和连接恢复

- Spring 单独建立 WebSocket owner；解析消息后沿用 PostgreSQL 入站幂等、消息队列和 Agent 任务流程。
- 使用连接 epoch/sequence 阻止旧连接状态覆盖新连接；断线恢复不重复建会话、不丢弃持久化待投递事件。
- 切换时保证一个店铺任一时刻只有一个消费者；通过影子解析对比事件结果，但影子进程不写业务库、不触发 Agent。

### 4. 迁移已审批发送

- Spring Worker 在审批、店铺、会话版本和发送策略通过后直接调用 Java 连接器。
- 单一发送锁、持久发送意图、稳定幂等键和成功部分记录继续有效；平台结果不确定时进入 `send_unknown`，不得自动重发。
- 明确失败才允许人工重试；Agent 进程不能持有发送凭据或调用平台写接口。

### 5. Python Agent 运行边界（已完成）

- Python live runtime 不再创建 `LiveConnector`、读取平台凭据或执行平台发送；平台凭据卷只挂载给 Core。
- Agent 服务只保留图编排、工具路由、模型客户端和通过 Core API 取数的客户端。
- Docker、Compose 配置和运维文档已更新；Agent 重启不会影响 Spring 持有的平台连接或发送任务。

## 切换门槛

- 授权身份与绑定店铺一致；重启后凭据仍有效，不能输出明文或写入日志。
- 单店连续运行能够接收真实测试消息；断网后显示离线/重连，恢复后只产生一条业务消息和一个 Agent 任务。
- 已知商品、规格、订单状态和缺失字段与平台返回一致；不支持的字段明确标未知。
- 仅人工批准的当前版本能够进入发送队列；离线、撤回、过期审批、重复命令和未知回执都按既有保护处理。
- Spring Java 是真实店铺唯一连接 owner；Python live runtime 没有连接器和发送回退。
- Docker Compose 创建未授权店铺，不注入模拟平台数据；真实发送授权由部署者显式配置。

## 当前阶段

Spring Boot 现在是所有真实拼多多连接的唯一 owner：负责登录桌面、Playwright、身份与 WebSocket 核验、连接、心跳、重连、消息接收、事件 spool、商品/订单查询和审批后发送。Python 实时模式不创建连接器，也没有平台凭据卷；它只处理 Agent 任务并通过 Core 内部接口取业务事实。Java 收到的事件直接进入 Core 持久队列，Agent 继续负责生成草稿。2026-11-10 曾完成一次真实消息入站验收；这不代表迁移后的运行版本已重新验收，长时间运行、断线恢复和并发仍待验证。

登录和商品/订单短期查询会话由 Spring Java Playwright 管理。Java 负责浏览器生命周期、Linux 虚拟桌面及 noVNC 代理、店铺身份和 WebSocket 核验、只读请求捕获、会话加密保存。Core 镜像不再复制 Agent Python 源码或调用 Python 登录脚本。Compose 将 Core 的平台凭据卷与 Agent 的运行状态卷分开。Java 实时发送路径仅接受 Core 已标记为批准并进入发送状态的命令。

### 当前实施决定

- 已确认目标边界：Spring Boot 持有现有拼多多连接和业务操作；Python 只保留 Agent 编排。
- 迁移对象是仓库当前网页登录态、商家网页 HTTP 和 WebSocket 实现；不新增猜测的 PDD API。
- Spring 已负责凭据读取、商家 HTTP transport、登录与会话捕获、WebSocket 连接和持久事件 spool；不再通过开关选择 Python 平台连接器。
- Python Agent 的 live runtime 只保留 Agent 数据、生成、工具编排与 Core API 客户端；不能读取凭据文件或直接调用平台发送接口。不保留模拟连接器。
- 商品目录、详情同步任务、订单只读查询及 Agent 商品/订单工具由 Spring 平台层与内部业务接口提供；审批后文字/商品卡由 Spring 发送服务执行，发送分段日志写 PostgreSQL。未知回执或中断不会自动重发。
- Maven 构建与 Core 镜像依赖已移除 Python 登录运行时；noVNC/Websockify 仍是 Java 启动的桌面显示依赖。当前代码构建通过，真实店铺需重新核对在线、入站和发送前审批链路；本次未发送真实回复。
