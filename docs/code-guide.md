# 代码导览

当前产品没有可运行的演示店铺、模拟买家消息或模拟发送入口。拼多多连接和业务数据由 Spring Boot 管理，Python 只运行 Agent，React 提供工作台。合成数据仅用于自动化测试与离线评测。

可选的 `services/camoufox-gateway/` 是独立浏览器基础设施，不属于 Agent 服务。它仅管理一次性远端 Firefox 实例和连接回收；订单查询、身份核对、解析及业务权限仍由 Java 执行。默认不启动，见[网关部署说明](../services/camoufox-gateway/README.md)。

## Java：业务、平台连接与持久化

主包位于 `services/core-service/src/main/java/com/pddcopilot/core/`。

| 目录 | 职责 |
| --- | --- |
| `api/`、`api/dto/` | 登录、工作台、知识库、审批、内部 Agent 与店铺接口 |
| `service/` | 会话、审批、知识库、商品同步、店铺/成员权限等业务事务 |
| `repository/` | 持久化操作封装、店铺范围校验、幂等记录和发送日志 |
| `mapper/` | MyBatis Mapper 接口，通过参数绑定访问 PostgreSQL |
| `src/main/resources/mapper/` | 与 Mapper 对应的 SQL XML、动态锁查询及公共 SQL 片段 |
| `platform/pdd/` | 拼多多网页登录、凭据保护、WebSocket、商品/订单读取和已审批消息发送 |
| `client/AgentClient` | 向 Python Agent 发起内部请求 |
| `job/` | 持久任务领取与后台执行 |
| `bootstrap/` | 首次启动创建管理员工作空间和未授权店铺，不导入样例商品或知识 |
| `config/`、`support/` | Cookie/Redis 会话、CSRF、敏感操作门禁、可信代理、线程池和通用值处理 |

主要消息链路：Java 连接器收到平台事件后由 `InboxService` 去重、保存并创建 Agent 任务；`AgentRunService` 固定会话上下文和知识版本；`TaskWorker` 在数据库事务外调用 Python；结果回到 Java 后形成待审核草稿；人工批准后由 `ReplySendService`、`PddSendService` 发送并记录平台回执。

平台发送在 Core 侧检查会话、草稿版本、审批状态、连接状态和授权范围。每个发送部分先写 PostgreSQL 发送记录；平台结果不明时保持待核实，不自动重复发送。`SendPolicy` 不存在绕过审批的模拟分支。

### 数据库访问

数据源位于 `src/main/resources/application.yml` 的 `spring.datasource`，读取 `DATABASE_URL`、`DATABASE_USER` 和 `DATABASE_PASSWORD`。Spring Boot 创建连接池，MyBatis Spring Boot Starter 3.0.5 复用该数据源及 Spring 事务管理器；业务数据库仍是 PostgreSQL，表结构由 Flyway 维护。

通常按 `api → service → repository → mapper → PostgreSQL` 阅读。Service 管理业务事务，Repository 保留店铺校验、JSON 转换及缺失记录的处理，Mapper XML 保存 SQL。认证审计、店铺删除等少量 Service 直接调用对应的专用 Mapper，Service 中不再嵌入 SQL。

阅读示例：`UserRepository → UserMapper → resources/mapper/UserMapper.xml`；任务队列示例：`JobRepository / JobOutboxRepository → JobMapper / JobOutboxMapper`。所有请求值使用 `#{...}` 绑定，动态锁通过 XML `<if>` 控制，不接受调用方传入整段 SQL。

任务领取的 `FOR UPDATE SKIP LOCKED`、Outbox 的 `UPDATE RETURNING` 和发送唯一约束保持原语义。MyBatis 关闭二级缓存，并将一级缓存范围限制为单条语句，确保事务内重复锁查询实际访问数据库。查询映射保留原 SQL 列名、NULL 值及大小写不敏感的列名访问。用户账户使用明确的 `UserAccount` 构造映射；多数已有业务查询暂保留 Map 返回契约，并未全部转换为实体或 DTO。

本次迁移不涉及数据库搬迁，也不修改业务表结构。原有测试的建库与造数仍可使用 JdbcTemplate，与生产 MyBatis 数据访问分开。

## Python：Agent 编排

代码位于 `services/agent-service/copilot/`。

| 文件或目录 | 职责 |
| --- | --- |
| `app.py`、`api.py`、`contracts.py` | FastAPI 生命周期、内部认证、Agent 请求与响应契约 |
| `runtime.py`、`runtime_pool.py` | 店铺隔离、并发控制；向 Spring 认领执行租约并提交结果，无本地持久状态 |
| `graph.py`、`agent/batch.py`、`agent/runner.py` | 风险分流、逐咨询并行与汇总、模型与工具循环 |
| `agent/context.py`、`agent/prompts.py`、`agent/contracts.py` | 历史上下文、系统提示词、模型输出与图状态契约 |
| `agent/validation.py` | 引用、库存、订单及回复范围校验，反馈修复 |
| `agent/tool_registry.py`、`agent/tool_executor.py` | 单一工具定义目录、参数校验、权限约束和调用记录 |
| `agent/catalog.py`、`agent/catalog_terms.py` | 商品检索、关键词归一化与跨商品比价 |
| `clients/core.py`、`clients/llm.py` | Spring 只读业务接口、模型 HTTP 客户端 |
| `agent/queries/` | 商品、SKU、物流、订单与知识查询处理；工具定义仍集中在 `tool_registry.py` |
| `knowledge/` | 知识整理、检索、RAG、回复规范与文档解析 |
| `metrics.py` | 无买家信息标签的指标 |

运行结果、请求幂等与跨实例执行租约由 Spring 的 `AgentExecutionService` 和 PostgreSQL `agent_execution_results` 管理。Python 不创建 SQLite 或目录占用锁，不挂载 Agent 状态数据卷。派生向量仍可由检索模块写入配置的 pgvector 数据库。

测试夹具放在测试目录和 `evals/fixtures.py`，不能作为服务路由、部署模式或默认数据使用。

## React：工作台

代码位于 `apps/web/src/`。`App.tsx` 负责导航；`components/` 包含会话列表、消息、草稿审批、人工回复等组件；`pages/` 包含知识库、操作记录、模型和店铺管理页面；`hooks/useWorkbench.ts` 管理登录、轮询、店铺切换和当前会话状态。产品页面不提供模拟咨询入口。

## 启动与验证

Docker 部署说明见 [README](../README.md)，本地环境变量和服务启动说明见 [运行手册](copilot-runbook.md)。Java 数据库变更使用 Flyway；Agent 单元测试和离线评测可使用隔离的合成事实，不会连接拼多多或发送消息。
