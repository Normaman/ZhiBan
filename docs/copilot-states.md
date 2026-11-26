# 状态与契约约束

连接状态：`disconnected → connecting → authenticating → online`。断线进入 `reconnecting`，认证重新确认后才 `online`；五次失败进入 `failed`，人工停止为 `stopped`。Spring 超过 20 秒没有收到状态更新时对前端显示断开；审批也检查该时间。

会话支持 `open → transferred → open`。恢复请求携带会话 revision；转人工与恢复都会推进版本，避免转人工前的迟到生成结果在恢复后复活。重复恢复同一版本不会创建新任务或发送。恢复只影响后续新咨询，旧草稿、旧审批与人工接管记录保留；有待确认的发送结果时禁止恢复，旧版本的明确失败命令也不能在恢复后重试。

消息记录状态描述其当前处理进展，Agent run、草稿、发送命令各自保留独立状态：

| 对象 | 状态 |
| --- | --- |
| Agent run | `queued / running / completed / superseded / generation_failed` |
| 草稿 | `waiting_human / outdated / approved / rejected / transferred / sending / sent / send_failed / send_unknown` |
| 发送命令 | `approved / sending / sent / send_failed / send_unknown` |
| 会话 | `open / transferred` |

`draft_ready` 到 `waiting_human` 是同一个入库事务：不暴露一个可审批但未持久的中间态。新会话消息使所有未审批草稿过期，迟到的 Agent 结果标为 `superseded`。审批失败返回 409，前端刷新后再审核。

`system_hint` 是平台来源通知，保留展示但不增加会话版本、不使草稿过期、不进入模型对话历史，也不能单独重新生成。真实发送默认关闭，只能在两个服务共同校验的指定测试会话授权范围内开放。

入站幂等键 `pdd:{shop_id}:{platform_message_id}`；无平台 ID 时只能按连接器 event ID 记录事件并停止自动生成。相同平台消息允许不同 event ID 和 received_at，内容/身份发生变化则冲突。唯一性范围需真实账号确认。

批准的最终内容保存在 decisions 表，发送命令引用 approval ID；Python 通过认证内部接口回查，不接受调用方自报批准。发送请求幂等键为 `reply:{draft_id}:{revision}`；明确失败的人工重试使用相同命令、递增 attempt。结果不确定不允许重试。

直接退款、赔偿、质量纠纷、投诉和实际改地址诉求在生成前交人工；普通退款到账规则、退换条件和入口允许基于知识库解释。当前还保守检查最近买家上下文，相关后续咨询可能一并要求人工。订单卡片的元数据不作为买家的风险诉求。

已核验的 `43001` 登录过期响应使连接进入 `failed`，旧连接关闭；保存新的加密凭据后重新建连、认证。HTTP 身份每 60 秒检查一次，前端不以 WebSocket 尚存活代替登录态有效。重新连接不推进草稿或发送命令状态。

工作台人工接管是业务状态；不会擅自调用平台的转客服接口。人工接管前若有批准/发送中的命令，返回冲突；待该命令结果确认后再接管。

工作台已读与平台买家阅读分别保存。`message_reads` 按当前账号和消息 ID 记录已查看，只确认客户端实际展示的 ID，因此迟到消息不会被旧的查看请求误清除，也不推进会话版本或触发 Agent。后台标签页不自动清除工作台未读。

平台 type 20 回执是独立事件，经连接器持久队列投递到 `/internal/v1/pdd/read-receipts`，不建立会话或消息。`user_last_read` 与 `min_supported_msg_id` 按当前官方商家页面的数字消息 ID 比较规则处理；不把这两个值猜成时间戳。支持范围内、消息 ID 不大于已读边界时为已读，之后为未读；范围之外、缺失平台 ID 或无回执为未知。回执乱序合并不把已读边界回退。本地查看不调用平台 `mark_read`，此方向尚待真实确认响应验证。
