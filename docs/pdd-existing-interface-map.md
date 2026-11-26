# 现有拼多多连接方式迁移清单

目标是把仓库当前正在使用的网页登录态、商家网页 HTTP 接口和 WebSocket 迁入 Spring Boot；不把官方 OpenAPI 作为本次迁移前置条件。下表根据现有代码整理，表示“当前代码实际调用”，不代表平台公开承诺这些路径稳定。

## 当前协议实现

下表记录观察到的商家网页协议；运行时代码已迁入 `services/core-service/.../platform/pdd/`。Python Agent 仓库中不再保留登录、凭据、WebSocket、HTTP 客户端或平台发送实现。

| 能力 | 当前实现 | 已观察到的调用 |
| --- | --- | --- |
| Cookie 登录和身份确认 | `PddCredentialStore`、`PddCredentialVerifier` | `POST /janus/api/new/userinfo`、`POST /earth/api/merchant/queryMerchantInfoByMallId` |
| WebSocket 消息接收 | `PddConnectionManager`、`PddWebClient` | `wss://m-ws.pinduoduo.com/`；先调用 `POST /chats/getToken`，连接查询参数包含 `access_token`、`role=mall_cs`、`client=web` 和版本号 |
| 消息归一化与事件持久化 | `PddMessageNormalizer`、`PddEventSpool` | 认证后将平台事件转换为内部消息并写入 PostgreSQL 幂等队列 |
| 商品列表与详情/SKU/属性/配送模板 | `PddWebClient`、`PddProductParser` | `/latitude/goods/recommendGoods`、`/glide/v2/mms/query/commit/on_shop/detail`、`/latitude/goods/goodsProperties`、`/express_inf/cost_template/get_one` |
| 订单状态数量 | `PddCommerceService` | `/mars/app/order/statisticWithType`，请求 `source=MMS` 与已观察的 `subType` 15、30、29；统计查询缓存 60 秒 |
| 订单明细列表 | 已实现，待真实店铺复验 | 用户浏览器成功请求 `/mangkhut/mms/recentOrderList`：`orderType=2`、`afterSaleType=1`、`remarkStatus=-1`、`urgeShippingStatus=-1`、`groupStartTime`/`groupEndTime`、`mobile=""`、`pageNumber=1`、`pageSize=20`、`sortType=11`、`hideRegionBlackDelayShipping=false`；响应 `success=true`、`errorCode=0`、`totalItemNum=2` 且 `pageItems` 有记录。全量响应含买家及物流敏感字段，不入仓库；Java 只解析商品名、规格、数量、状态、时间、金额、受限图片 URL 和掩码订单尾号。接口已接入 `GET /api/v1/shops/{id}/orders?orderType={1|2|3|7}&page={1..100}` 和工作台筛选分页。为匹配订单页实际生成的 `anti-content`、浏览器标识和筛选参数，Spring 为每次列表任务申请独立 Camoufox 实例，通过 `playwright.firefox().connect()` 捕获响应并释放实例。工作台默认已发货；完整列表已读取到 2 条，原生待发货标签已观察到类型 1 请求；其他筛选及分页仍需分别验收。 |
| 订单明细正式接入候选 | 尚未接入 | 公开接入资料指向拼多多方舟 ERP 商家自研场景 `pdd.erp.order.list.get`；需通过开发者/应用审核、接入场景并完成店铺授权。它使用 Open API `access_token`，不能用当前网页登录 Cookie 替代。参考[拼多多开放平台入口](https://open.pinduoduo.com/application/home)、[商家自研申请及流程说明](https://open.wangdian.cn/open/guide?path=guide_pdd_application)、[方舟订单列表接口字段参考](https://open.wangdian.cn/qyb/open/apidoc/doc?path=pdd_erp_order_list_get)。具体权限和响应须用本项目获批的应用实测后再实现。 |
| 订单状态与详情 | `PddWebClient`、`PddOrderParser` | `/latitude/order/orderSimpleDetail`、`/mangkhut/mms/orderDetail`；页面资源另有 `/mangkhut/mms/routeOrderDetail`，本次只读试查返回业务码 `4002`，尚不能据此假设其成功响应。会话保存实际捕获并校验的允许路径；详情依赖商家页面短期请求头及平台安全验证 |
| 文字消息与商品卡发送 | `PddSendService` | `/plateau/chat/send_message`、`/plateau/message/send/mallGoodsCard`；仅处理 Spring 已批准的发送命令 |

## 已实现的安全约束

- 登录 Cookie 使用 Fernet 加密保存在 `credentials.enc`，并与店铺/账号 ID 绑定；Spring 登录核验会校验身份及凭据摘要。
- Agent 发送必须经 Spring 审批任务；真实平台发送由 Java 连接器执行并写入 PostgreSQL journal。Python live runtime 没有发送回退。
- 商品查询和订单查询使用隔离页面捕获的短期请求头；登录态变化时不复用旧捕获数据。
- 平台返回不明确时保留未知状态，不把超时当作发送失败后自动重发。

## 迁移顺序

1. Spring 读取并校验当前加密凭据格式，接管 WebSocket token 获取、连接、认证、心跳、消息解析和重连。
2. 入站事件直接交给 Spring 现有的 PostgreSQL 幂等持久化与任务队列；同一店铺始终只有一个 WebSocket owner。
3. 把商品、SKU、配送、订单读取逐项迁入 Java，并保留现有页面捕获的短期请求头及店铺身份核验。
4. 把审批后的文字/商品卡发送迁入 Spring Worker，保留发送 journal、审批版本、幂等及未知回执处理。
5. Python Agent 只调用 Spring 内部业务工具；旧 Python 连接代码不属于运行服务。

## 当前进度

- Spring 已新增 Fernet 凭据读取器、基于 Java `HttpClient` 的商家网页 HTTP transport、商品目录分页和商品详情/SKU/配送/属性解析，以及 WebSocket 消息 normalizer。
- Spring 的 WebSocket 连接管理、认证、心跳、重连和 PostgreSQL 事件 spool 是真实模式唯一实现；Python live runtime 不建立平台连接。历史真实消息验收曾完成，迁移后的运行版本仍需重新核对入站、长时间运行和断线恢复。
- 商品目录同步和批量商品详情同步任务已改为由 Spring commerce service 调用现有页面接口；真实店铺尚未验收 Java 查询结果。
- Java connector 不再由开关选择；所有绑定的真实店铺均由 Spring 管理连接。
- Python Agent 商品和订单工具通过 Spring 内部接口查询；商品、订单只读 HTTP 请求及过期会话捕获由 Spring Java 执行。已审批文字/商品卡发送与 PostgreSQL 分段日志由 Java 执行，Python live runtime 不含发送回退。
- 拼多多连接与业务接口由 Spring Boot 管理；Python 只调用 Spring 内部业务接口供 Agent 使用。
- 登录浏览器、Linux noVNC 登录桌面、WebSocket 身份核验与商品/订单会话捕获由 Spring Java 管理，并写入兼容的 Fernet 加密凭据文件。Agent 容器使用独立状态卷，不挂载平台凭据卷。
