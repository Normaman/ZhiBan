# 拼多多客服副驾驶

为拼多多商家打造的 AI 客服工作台。集中处理买家咨询，结合商品资料、订单信息与店铺知识生成回复草稿，由客服审核后发送。

**买家咨询 → Agent 查询与检索 → 回复草稿与依据 → 人工编辑、审批 → 平台发送回执**

## 产品功能

- **会话工作台**：查看买家消息、商品与订单卡片，支持人工接管和恢复 Agent。
- **客服 Agent**：围绕商品规格、价格、库存、发货及售后规则组织回答，支持工具查询、多轮上下文和草稿重试。
- **店铺知识库**：上传与维护商品知识、店铺政策、常见问答和客服回答规范，通过混合检索为回复提供依据。
- **人工审批**：查看引用、编辑草稿再发送；记录发送结果，结果不确定时保留待核实状态。
- **多店铺管理**：按工作空间隔离店铺数据，支持店铺切换、成员权限和邀请码注册。
- **运行追踪与评测**：查看模型及工具执行记录，使用评测集和人工复核改进回复质量。

## 开始使用

安装并启动 Docker，在项目根目录执行：

**macOS / Linux**

```sh
sh scripts/docker-init.sh
docker compose --env-file infra/.env -f infra/compose.yml up --build -d
```

**Windows PowerShell**

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\docker-init.ps1
docker compose --env-file infra/.env -f infra/compose.yml up --build -d
```

打开 <http://localhost:5173>，初始管理员账号、密码和注册邀请码见 `infra/.env`。

登录后，按以下步骤配置：

1. 在「店铺与成员」连接拼多多并核对店铺身份。
2. 在「模型配置」填写模型服务地址、模型名称与 API Key，测试并启用。
3. 同步商品，上传店铺规则及客服回答规范。
4. 接收咨询，核对草稿依据，编辑并审批回复。

真实发送默认关闭，启用方式及完整部署步骤见 [部署与维护](docs/deployment.md)。首次构建需要网络访问镜像与依赖仓库。

## 技术架构

| 组件 | 职责 |
| --- | --- |
| React | 会话、知识库、审批与店铺管理工作台 |
| Spring Boot / MyBatis | 拼多多连接、商品与订单查询、权限、审批、消息发送与业务持久化 |
| FastAPI + LangGraph | Agent 编排、工具调用、RAG 与草稿生成 |
| PostgreSQL | 业务数据、知识版本、任务状态与审计记录 |
| Redis Streams | 后台任务分发与消费者组协作 |

## 文档

- [部署与维护](docs/deployment.md)
- [Agent 编排与模型配置](docs/agent.md)
- [知识库资料管理](docs/knowledge-import.md) · [混合检索与 RAG](docs/hybrid-rag.md)
- [多店铺与成员权限](docs/multishop.md) · [账号安全](docs/account-security.md)
- [任务队列与恢复机制](docs/job-queue.md)
- [评测与人工复核](docs/agent-evaluation.md)
- [代码导览](docs/code-guide.md) · [开发运行手册](docs/copilot-runbook.md)

## 接入与许可说明

当前通过拼多多商家网页登录态和网页通信协议接入，平台变更可能影响功能；部署后需自行验证店铺连接与消息链路。

仓库尚未补齐项目 `LICENSE`，上游来源和许可核对情况见 [NOTICE](NOTICE)。
