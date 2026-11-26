# 迁移与清理记录

项目已收敛为 React + Spring Boot + Python Agent。根目录不再保留原桌面应用入口和旧依赖清单。

## 已移除

- `ui/`、`icon/`、`app.py`：PyQt 页面、资源和桌面启动入口。
- `Agent/`、`Message/`、`bridge/`：旧桌面 Agent 入口、自动回复 handler 链及内存队列；模型与多轮只读工具循环已适配到 `copilot/agent/`，直接发送能力不进入模型工具。
- `database/`、`service/`、`core/`、`config.py`、`utils/`：旧 SQLite 业务存储、全局 DI、桌面配置与路径、DPAPI 辅助层。
- `Channel/`：完成所需协议与登录代码迁移后移除重复实现。
- 原根目录 `tests/`：桌面、旧模型适配及打包测试；拼多多解析和抓包解码回归迁入 agent-service 测试目录。
- PyInstaller、Inno Setup、旧浏览器安装与 Windows 打包流程。
- Python 拼多多平台代码：删除实时连接器、Playwright 登录、凭据存储、商品/订单查询会话及平台直发客户端；相应登录、核验、查询脚本和注册工具一并删除。登录、连接、平台数据查询及消息发送均由 Spring Boot 管理。
- Spring Boot 初始化不再创建演示店铺、虚构商品/规则或模拟连接；工作台也不再提供模拟咨询入口。
- 旧的 `demo` 店铺迁移为未授权拼多多店铺，连接凭据解绑并重置连接状态；演示知识版本和商品快照会清除，已有会话历史不自动删除。
- `services/agent-service/build/`、旧 egg-info 与 pytest 缓存等生成物不纳入源码交付。

## 保留和整理

| 当前代码 | 来源与变化 |
| --- | --- |
| `services/agent-service/copilot/agent/` | Agent 意图处理、工具调用、RAG、上下文管理、回复草稿与评估；真实业务工具通过 Spring 内部接口读取结构化事实 |
| `services/agent-service/copilot/agent/` | Agent 侧使用通用图片地址校验和配送事实展示工具；平台字段解析留在 Java |
| `services/core-service/src/main/java/.../platform/pdd/` | Spring Boot 平台登录、凭据管理、WebSocket 收消息、商品/订单查询、审批后发送与重连 |
| 根 `pyproject.toml`、`uv.lock` | Python 工作区只构建 Agent 服务及其评测/测试依赖 |

运行和构建不依赖其他业务仓库；已移除原上游 Git remote。原始代码的来源及许可证材料缺口单独记录在根目录 `NOTICE`。本机清理前的源码归档位于工作区之外 `~/.local/share/pdd-copilot/history/`，不参与构建或上传。

保留现有本地 Git 历史，没有删除 `.git` 或重写提交。产品运行独立与消除代码来源是两件事，上传时应保留必要的来源和版权材料。内部 API 不是平台协议字段说明，真实账号兼容性仍需隔离验证。

Agent 的当前迁移边界与能力详见 [Agent 编排说明](agent.md)。自动化测试和离线评测保留合成事实夹具，但它们不注册为服务接口，也不会启动模拟店铺。
