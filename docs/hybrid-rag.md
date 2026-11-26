# 本地混合 RAG 与咨询批次

## 检索链路

启用本地 RAG 后：指定店铺和知识版本 → 商品/规则类别过滤 → BM25 与向量候选 → RRF 融合 → 本地 CrossEncoder 重排 → 最多五条引用 → 模型生成与依据校验 → 人工审批。

- Embedding：`BAAI/bge-small-zh-v1.5`，512 维，当前缓存模型的 commit 加分段版本组成索引版本。
- 存储：独立索引 PostgreSQL 的 pgvector，`rag_chunks` 保存派生向量，`rag_manifests` 保存版本指纹。Spring 的知识快照仍是事实来源。
- 长资料按 400 字符、60 字符重叠生成向量窗口，避免只向量化开头。引用保留完整来源及原文；重排读取命中的窗口。
- BM25 最多 20 条，向量检索取 40 个窗口并去重至最多 20 个来源；RRF 使用 `1/(60+rank)` 融合，最多重排 20 个来源。
- 重排：本地 `BAAI/bge-reranker-base`。分数只用于相关性排序，不当作事实可信概率；生成后仍检查证据与问题是否匹配。
- SQL 同时限制店铺、知识版本、Embedding 版本和已授权来源 ID。先限定候选，再排序；不能靠模型自己遵守店铺隔离。

这条混合链路检索的是知识库条目，不会自动用于拼多多商品目录。商品目录仍以当前平台标题和实时 SKU 数据为事实来源；商品检索先做标题精确匹配，若一个查询词是多个标题片段连写，只有当所有片段都能从当前商品标题完整拆出时才拆词匹配。未知片段不会被语义相近的商品替代。商品向量召回暂不启用：试验中“比利时硬币”也会把比利时纸币排到高位，说明相似度分数不能单独作为商品身份确认。后续要接入，须用包含同地区不同品类的正反例评估召回与误命中，再决定置信和过滤策略。

当前按过滤后的知识规模做精确余弦排序，未启用 HNSW 近似索引。这避免小店铺/商品过滤后候选不足；大规模、多租户压力测试尚未完成。

每个不可变知识版本在首次检索时构建向量，成功后持久保存，后续重启复用；不是每次重新向量化。索引通过事务与 advisory lock 发布，避免读到半份数据。尚未增加独立的上传后索引任务队列，首次大文档查询可能较慢。失败或超时返回明确的 `bm25_degraded`，工具记录显示降级原因；下一次可重试。20 秒超时停止等待，不保证已开始的本地推理线程立即终止；模型锁会串行保护共享模型。

知识库页面显示检索服务状态，具体问题的执行记录显示实际检索模式。未启用 RAG 时仍用 BM25。无模型的单条规则摘录也可使用混合检索；无模型的多条咨询保持人工处理。

## Docker 部署（CPU）

业务 PostgreSQL 保持原样；`compose.rag.yml` 新增独立的 pgvector 索引数据库、受限的索引应用账号和持久模型缓存卷。默认 PostgreSQL 不需要替换镜像或搬迁业务数据。模型及 CPU PyTorch 不进入默认轻量 Agent 镜像；RAG 部署显式选择 `rag-dependencies` 构建阶段。

Linux / macOS 从仓库根目录执行：

```bash
sh scripts/docker-init.sh
sh scripts/docker-rag-init.sh
bash scripts/docker-rag.sh build
bash scripts/docker-rag.sh up
bash scripts/docker-rag.sh status
```

已有部署执行 `docker-rag-init.sh` 只补齐缺少的两份索引数据库密码，不覆盖业务凭据。公开模型由一次性 `rag-models` 服务下载到专用卷；Agent 只读挂载该卷、离线加载固定模型提交版本。首次运行需要访问 Hugging Face、PyPI/PyTorch 和镜像仓库；磁盘需求取决于镜像及缓存复用，不能保证不下载。CPU PyTorch 采用独立哈希锁文件，避免拉取整套 CUDA 依赖。模型下载失败时修复网络后重新执行 `up`，不要删除业务卷。

Windows PowerShell 无需 WSL，执行初始化与等效 Compose 命令：

```powershell
.\scripts\docker-init.ps1
.\scripts\docker-rag-init.ps1
docker compose --env-file infra/.env -f infra/compose.yml -f infra/compose.rag.yml build core-service agent-service web
docker compose --env-file infra/.env -f infra/compose.yml -f infra/compose.rag.yml up -d --no-build --wait
```

成功后在私有 `infra/.env` 设置 `RAG_ENABLED=1`；Linux 的 `docker-rag.sh up` 会自动记录该标志。它让 `docker-update.sh` 后续更新时继续使用 RAG 覆盖配置；手动使用 Compose 时仍必须保留两个 `-f` 参数。轻量 BM25 部署继续使用原 `compose.yml`。

知识库页面及检索调试显示实际运行模式。模型/数据库故障时显示 `bm25_degraded`，不会把关键词结果冒充语义检索结果。HTTP `/health` 健康代表服务能处理请求，不单独保证向量检索就绪，应同时检查 `/knowledge/retrieval` 和实际检索模式。

## 本地开发

在已有 Python 环境安装 `pip install -e 'services/agent-service[rag]'`，用 `python scripts/setup_rag.py --download-models` 准备本地 PostgreSQL 扩展和模型。该脚本读取工作区环境配置，不要求固定的用户路径。设置 `RAG_ENABLED=1` 和本地 `RAG_DB_*` 后再启动 Agent。GPU 环境需要按 PyTorch 官方说明选择对应驱动；Docker 默认方案使用 CPU。

向量化和重排不调用外部模型 API、不需要额外 API Key。DeepSeek 继续负责草稿生成，会收到检索出的正文和必要会话文本；上传原始文件不发送给生成模型。

## 检索专项 A/B（无生成模型调用）

RAG Docker 部署完成后，在仓库根目录执行：

```bash
python evals/retrieval_compare.py --docker --split test --output .runtime/evals/retrieval-comparison/report.json
```

脚本使用带 `retrieval_sources` 标注的合成问题，两侧使用同一知识候选和问题，比较 BM25 与真实混合排序的 Recall@5、Precision@5、MRR、nDCG@5 及延迟；不调用 DeepSeek、不读取客户会话。模型预热时间不计入单条查询，首次索引耗时计入。临时数据库以 `copilot_eval_` 开头，运行结束删除，不复用线上向量库。输出路径必须尚不存在；结果不能替代完整 Agent 回答质量或线上客服准确率评估。

CPU-only 安装方式参考 [uv PyTorch 集成](https://docs.astral.sh/uv/guides/integration/pytorch/)，索引镜像参考 [pgvector 官方项目](https://github.com/pgvector/pgvector)。

## 2026-11-20 CPU 部署检索对照

部署实际返回 `hybrid`、BGE Embedding/Reranker 和 pgvector；不是仅设置开关。新模型缓存与索引库独立持久化，业务 PostgreSQL 未替换。

同一数据集、同一候选知识、固定模型版本、不调用生成模型的离线检索结果：

| 集合 | BM25 Recall@5 | 混合 Recall@5 | BM25 MRR | 混合 MRR | BM25 nDCG@5 | 混合 nDCG@5 |
| --- | --- | --- | --- | --- | --- | --- |
| 开发集，64 条多候选合成用例 | 0.9219 | 1.0000 | 0.8307 | 0.9766 | 0.8543 | 0.9827 |
| 测试集，16 条合成用例 | 1.0000 | 1.0000 | 1.0000 | 1.0000 | 1.0000 | 1.0000 |

开发集混合检索平均约 222ms，BM25 约 0.03ms；模型初次加载不计入，首次索引计入，不能直接当作生产 p95。测试集这 16 条实际都只有一个候选来源，排名区分度不足；不能用其满分宣称泛化达标。下一轮需扩充独立测试集的多候选、同义表达、无依据和相似商品干扰样本。此处结果仅是检索专项，不代表客服回答正确率。临时数据库均已删除，生成模型调用为 0。

原始报告保存在部署工作区 `.runtime/evals/retrieval-enterprise-20261120/`，包括数据集哈希、来源排名、固定模型提交和单条延迟；新运行必须使用不同输出路径以保留历史结果。

## 本轮问题与历史记忆

自动批次只包括锚点消息之前八秒内、最多八条未回答咨询，并以已发送回复为界；历史积压留在待回复列表，不自动加入。每条消息独立查依据、生成和验证，最多两条同时处理，共用整体时间预算。商品上下文按每条咨询之前的商品来源确定。

可以回答的部分形成待审批草稿，其他问题分别标记缺资料或需人工核实。成功发送仅结束 `answered_message_ids` 指定的消息；旧问题、失败和未知回执都不被误判为已回答。点击待回复数量可单独重试一条旧咨询。已有发送命令的草稿不会被改写，这类剩余问题需人工补充回复。

这仍是按买家消息记录的处理，不是语义分题：一条消息里的多个问题仍须一起回答。旧草稿保持原样；用户重新生成或收到新咨询时才使用新逻辑。

## 验证

`python scripts/verify_hybrid.py` 在一次性数据库中，用合成事实验证：无关键词命中时的语义召回、商品/店铺/版本隔离、持久索引复用；结束删除测试库。它需要本机模型缓存和 PostgreSQL 管理 socket，不写真实店铺资料、不调用外部生成模型。

这些是链路与隔离验证，不代表电商真实问答准确率评测已完成。后续应使用授权且脱敏的问答集衡量 Recall@K、重排效果、无依据拒答率与延迟。

模型与存储参考：[BGE Embedding](https://huggingface.co/BAAI/bge-small-zh-v1.5)、[BGE Reranker](https://huggingface.co/BAAI/bge-reranker-base)、[pgvector](https://github.com/pgvector/pgvector)。
