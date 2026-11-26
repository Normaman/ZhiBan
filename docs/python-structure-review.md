# Python 服务整理结果

本次清理将运行结果与跨实例租约收回 Spring/PostgreSQL，并按现有职责拆分 Python 模块。

## 请求链路

```text
api.py → RuntimePool → Runtime
  → Spring execution/claim：检查请求身份、复用结果或认领租约
  → Spring context / model-config：读取可信上下文和模型配置
  → DraftGraph → batch.generate_batch：逐咨询执行与汇总
    → 外层图：风险检查、诉求理解、路由
      → ModelAgent：模型与工具循环
        → ToolExecutor → queries / Core 客户端 / RAG
        → validation：校验、反馈修复或有限重新分析
  → Spring execution/complete：持久保存结果
  → 返回 Java Worker，按原业务校验生成待审批草稿
```

## 职责目录

| 模块 | 职责 |
| --- | --- |
| `app.py`、`api.py` | 生命周期、内部认证、HTTP 路由 |
| `runtime.py`、`runtime_pool.py` | 店铺隔离、共享并发、调用 Spring 执行协议 |
| `graph.py` | 外层风险、理解与模型/资料提取路由 |
| `agent/batch.py` | 逐咨询并发、超时收尾、汇总与回答覆盖清单 |
| `agent/runner.py` | 模型、工具、校验与有限重新分析循环 |
| `agent/context.py` | 历史选择、消息顺序与当前问题上下文 |
| `agent/prompts.py` | 回复和独立检查提示词 |
| `agent/contracts.py` | 严格的模型输出模型和请求内图状态 |
| `agent/validation.py` | 引用、事实范围、库存/订单检查和修复 |
| `agent/tool_registry.py` | 单一工具定义、参数模型和访问元数据 |
| `agent/tool_executor.py` | 工具执行边界、超时、结果复用、证据与调用记录 |
| `agent/queries/` | 商品、SKU、物流、知识、订单查询处理 |
| `clients/core.py`、`clients/llm.py` | Spring 只读业务客户端和模型 HTTP 客户端 |
| `knowledge/` | 文档解析分类、版本范围检索及派生向量索引 |

## 已清理

- 删除 `store.py`，移除 SQLite、空恢复方法、文件独占锁及状态目录配置。
- 移除 Python 状态卷挂载、镜像数据目录、旧平台依赖与登录/桌面可选依赖组，并更新 `uv.lock`。
- 平台客户端和模型客户端迁至 `clients/`，更新测试和离线评测导入路径。
- 拆分三个原大文件，保持工具定义集中，保留原提示词和回复审批边界。
- 统一三层并发配置入口，分别限制进程运行、单次运行咨询和单咨询工具数量。
- 测试夹具不再创建无用途的 SQLite；回归覆盖跨 Python 实例复用、失败释放和提交回执丢失。

## 状态与恢复边界

PostgreSQL `agent_execution_results` 保存请求身份、租约和完成结果。Spring 锁定运行行串行认领：相同请求返回原结果，不同请求身份拒绝；有效租约阻止其他实例重复计算，旧租约不能覆盖继任者结果。失败或取消会尝试释放，进程崩溃后等待租约过期。

Python 没有本地持久运行状态，可由独立实例访问同一 Core。进程内保留 HTTP 客户端、并发控制及检索模型缓存；检索模块仍可写派生向量数据库。SQLite 旧数据不会自动删除，升级说明见 [部署与维护](deployment.md#agent-状态升级)。

有限重新分析仍通过 `replan` 节点实现；没有新增持久化任务计划或图节点检查点。模型已执行但结果未提交时仍可能重算，因此不宣称模型严格只执行一次。

推荐阅读：`api.py → runtime.py → graph.py → agent/batch.py → agent/runner.py → agent/validation.py → agent/tool_registry.py → agent/tool_executor.py → agent/queries/ → clients/core.py`。

## 本轮验证

- Python 回归：320 项通过，未调用外部模型或平台发送接口。
- Java 执行协调、回复安全及平台相关选定回归：29 项通过。
- 执行协调的 6 项测试同时在真实 PostgreSQL 隔离 schema 中通过，测试结束删除 schema。
- Ruff 检查、源码格式检查和离线锁文件一致性检查通过。
- Core、Agent 镜像构建成功，容器健康，Flyway V23 应用成功；安装包不再包含旧 Store 和旧客户端模块，Agent 容器无状态卷挂载。
