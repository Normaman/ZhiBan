# 后台任务队列

## 结构与交付语义

PostgreSQL 是任务状态的权威来源，Redis Streams 负责分发和并发唤醒 Worker。创建业务任务时，任务行和 outbox 事件在同一 PostgreSQL 事务中提交；发布器再把事件写入 Stream。Worker 使用 Redis 消费者组领取事件，先在 PostgreSQL 取得任务租约，调用外部服务后将结果写回数据库，最后 ACK Stream 消息。

这是**至少一次投递**设计，不承诺网络故障下外部调用严格只执行一次。Stream 可能出现重复事件；任务行锁、claim ID、业务幂等和发送回执约束重复执行。Worker 崩溃后，空闲超过三分钟的 pending entry 会重新分配。任务最多执行四次；失败状态保留在 PostgreSQL，发消息结果不确定时标记为待核实，不会自动重复发送。

Redis 中的消息不是唯一副本：发布器用有租约的 outbox 投递；后台对账器每 30 秒扫描到期且未完成的 PostgreSQL 任务，并为超过五分钟没有近期投递记录的任务补发 outbox 事件。这样 Redis Stream 丢失事件后可从数据库恢复。每个任务同一时间最多有一个活动 outbox 事件，数据库部分唯一索引处理并发插入竞态。

## 可观测性

Core 的 `/actuator/prometheus` 需要独立 `METRICS_TOKEN` 的 Bearer 认证，暴露队列指标：

| 指标 | 含义 |
| --- | --- |
| `copilot_jobs_pending` / `copilot_jobs_running` / `copilot_jobs_failed` | PostgreSQL 中各任务状态数量 |
| `copilot_job_outbox_active` | 等待发布或正在发布的 outbox 数量 |
| `copilot_job_stream_entries` | Redis Stream 当前条目数 |
| `copilot_job_stream_pending` | 消费者组已领取、尚未 ACK 的条目数 |
| `copilot_job_outbox_total` | outbox 发布成功与重试计数 |
| `copilot_jobs_total` | Worker 成功与失败计数，按任务类型和结果区分 |
| `copilot_job_reconcile_total` | 对账补发与失败计数 |

消息在数据库提交后 ACK，并立即从 Stream 删除，避免成功任务的历史条目无限累积。若 Redis 在 ACK 后删除失败，会留下不可再次投递的孤立条目；需通过 Stream 长度与 pending 数量差异监控并在维护时清理。删除失败不会影响已提交的业务结果。

## 配置

Compose 默认启用 Redis，相关变量可在 `infra/.env` 中调整：

- `JOB_STREAM_KEY`：任务 Stream 名称。
- `JOB_CONSUMER_GROUP`：Worker 消费者组。
- `JOB_OUTBOX_POLL_MS`：outbox 发布轮询间隔，默认 250 毫秒。
- `JOB_RECONCILE_MS`：数据库任务对账间隔，默认 30 秒。
- `WORKER_CONCURRENCY`：单个 Core 实例的并发任务上限，默认 4。

扩容 Core Worker 时使用相同的 Stream 与消费者组；数据库租约和任务行锁用于跨实例协调。不要为每个实例创建不同消费者组，否则每组都会收到一份任务副本。
