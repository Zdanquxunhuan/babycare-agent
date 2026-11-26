# BabyCare Agent 导购工作台

本地模拟网页版提供两个导购账号、50 个商品 SKU、门店报价及库存，以及带来源的知识问答。运行需要 JDK 21 和可访问的阿里云百炼模型。打开网页后按[验收标准](docs/网页首版验收标准.md)逐项操作。

### V1.3 同款多包导购

用直营店账号问“买两包 L 码纸尿裤，总共最多 220 元”，匹配结果应为芽禾，106 × 2 = 212 元，库存 12 包。展开“查看核验步骤”可核对搜索、价格、库存和停止原因。若只说“两包，预算 220 元”，可在系统追问后补充“每包”或“总共”。多 SKU 混搭和促销留待后续版本。

多包规划从商品搜索开始，根据候选数和购买条件选择先核价或先核库存；候选为空就结束。默认每次规划最多 4 步，规划阶段共 45 秒（意图解析另有单次请求超时）。可通过 `babycare.purchase.max-steps`、`babycare.purchase.timeout` 调整。项目未设置百炼累计调用次数限制。

## 启动与登录

在项目根目录打开 PowerShell。本机的 JDK 21 安装在 `F:\ayd\jdk-21`。其他电脑请将路径换成本机 JDK 21 的根目录。此设置对当前 PowerShell 会话生效。

```powershell
$env:JAVA_HOME = 'F:\ayd\jdk-21'
.\mvnw.cmd -v
```

确认输出中有 `Java version: 21`，再在同一窗口打包。首次构建需能访问 Maven 仓库。

```powershell
.\mvnw.cmd package
```

出现 `BUILD SUCCESS` 后，在同一窗口启动工作台：

```powershell
.\start-demo.ps1
```

脚本默认使用 JAVA_HOME，可通过 -JavaHome 指定 JDK 21 目录。百炼密钥按以下顺序获取：进程环境变量 DASHSCOPE_API_KEY、项目根目录的 start-demo.key（本机专用，已被 git 忽略）、启动时按隐藏输入提示。密钥只注入当前进程。打开 http://127.0.0.1:18136/ ，选择直营店账号 direct 或加盟店账号 franchise，默认模拟登录密码为 BabyCare-demo。可用 BABYCARE_DEMO_PASSWORD 配置新的模拟密码。

百炼默认模型为 qwen-plus，默认使用北京地域接口。可通过 BAILIAN_MODEL 和 BAILIAN_CHAT_URL 改用相应模型及业务空间的完整 HTTPS Chat Completions 地址。网页中的模拟问答会经服务端发给百炼，测试时使用虚构顾客信息。参见[百炼兼容 Chat 接口](https://help.aliyun.com/zh/model-studio/qwen-api-via-openai-chat-completions)。

## 验证

Maven 的 test 阶段运行启动测试与真实 HTTP 会话集成测试。后者使用模型替身，覆盖门店权限、多轮筛选、证据过滤和异常分流。手工验收使用真实模型。测试 AC-10 时可在工作台运行期间创建空文件 target/stock-offline.flag 以模拟库存不可用，移除该文件后再次提问即可恢复。会话在 30 分钟无操作、退出或应用重启时失效。

实现边界及后续内部数据源的接入位置见[前后端架构设计](docs/网页首版前后端架构设计.md)。

### 网页回归

从仓库根目录运行，需 Node.js、可导入的 Playwright 和 Chromium。使用系统 Chrome 时可设置 `CHROME_PATH`。本地回归时设置 `BABYCARE_BROWSER_TEST=1` 并执行 `.\mvnw.cmd test`，测试会启动临时 Web 服务并以模型替身执行首版及多包用例。

真实模型验收时先用启动脚本运行服务，设置 `BABYCARE_URL`（默认 http://127.0.0.1:18136），再执行 `node src/test/browser/guide-acceptance.cjs`。使用 `--purchase-only` 可单独跑 MP-01～08。测试会临时切换演示库存故障标志，请在独立验收环境运行。结果及未验事项见[进度记录](plantodo.md)。

## V1.0a 文本检索 RAG

Windows PowerShell 从项目根目录运行。使用独立的 PostgreSQL 16 数据卷，默认宿主机端口为 55432。已有 PostgreSQL 可用独立的 babycare 库和账号，并设置 BABYCARE_DB_URL、BABYCARE_DB_USER、BABYCARE_DB_PASSWORD。

```powershell
$env:JAVA_HOME = 'F:\ayd\jdk-21'
$env:BABYCARE_DB_PASSWORD = [System.Net.NetworkCredential]::new('',(Read-Host '本地 BabyCare 数据库密码' -AsSecureString)).Password
$env:BABYCARE_OPS_PASSWORD = [System.Net.NetworkCredential]::new('',(Read-Host '知识运营账号密码' -AsSecureString)).Password
docker compose up -d --wait
.\mvnw.cmd package
$env:SPRING_PROFILES_ACTIVE = 'rag'
.\start-demo.ps1
```

使用 rag 配置时，原文、门店范围、有效期、条款、索引和反馈写入 PostgreSQL。普通演示配置用临时 H2 库，重启会清空新增资料。运营账号 ops 仅在设置独立运营密码后启用。以下资料均为模拟政策。

1. 在一个浏览器登录 ops，另开无痕窗口以 direct 登录导购。运营选 [returns-v1.md](fixtures/policies/returns-v1.md)，编号填 returns，标题填“模拟售后政策”，范围选直营店。导入后导购问“顾客拆包了还能退吗？”应提示资料不足。运营查看送审原文，点击“审核并发布”，再问应看到“由店长确认”和第4条的引用。
2. 用同一个编号、标题和范围导入 [returns-v2.md](fixtures/policies/returns-v2.md)。发布前继续答 V1，发布后再问应出现“照片”和“售后专员”。点击引用可查看对应条款，旧链接失效；加盟店账号无法访问。导购可对该轮引用答案标记有用/无用。
3. 运营下线 V2，再问同一句话应提示资料不足。

索引与查询共享中文连续双字、字母数字词的切分规则。按命中词的权重之和排序（标题/章节 3，正文 1），最多返回 5 条。门店、当前版本和有效期在选取 Top 5 前过滤。知识回答只接受候选中的证据 ID，展示对应原文；价格、库存沿用原查询工具。

**V1.0b 对照基线。** 固定 V1、直营店、同一份样本和以下问法。`GuideAcceptanceTests#policyLifecycleAndBaseline` 输出候选章节排名、检索耗时及模型替身的最终引用。真实模型引用以导购网页复测结果为准。可在独立测试数据库或默认临时库运行 `.\mvnw.cmd "-Dtest=GuideAcceptanceTests#policyLifecycleAndBaseline" test`。

| 固定问法 | 标注目标 |
|---|---|
| 政策第4条怎么处理 | 第4条 已拆包 |
| 顾客拆包了还能退吗？ | 第4条 已拆包 |
| 包装打开了，客人又不想要了，怎么办？ | 第4条 已拆包；记录词法检索漏召回或被“原包装”误导的情况 |
| 发票抬头怎么改？ | 无答案 |

## V1.0b 本地混合检索与精排

从 V1.0a 升级时沿用同一 Compose 项目和 PostgreSQL 16 数据卷。数据库镜像包含 pgvector；外部数据库需要安装 vector 扩展，并由有权限的账号在 BabyCare 数据库启用它。Windows PowerShell 在项目根目录运行，先按上一节设置 JDK 21、数据库和运营密码：

```powershell
docker compose --profile hybrid up -d --build --wait
Invoke-RestMethod http://localhost:58081/health
.\mvnw.cmd "-DskipTests" package
$env:SPRING_PROFILES_ACTIVE = 'rag,hybrid'
.\start-demo.ps1
```

首次启动要下载两个固定版本的 INT8 ONNX 模型（权重合计约 1.14 GB），后续复用 Docker 缓存卷。健康检查返回 `ready: true` 后再启动 Java。Windows 上需确保 Docker Desktop 有足够内存；下载或启动缓慢时可用 `docker compose logs -f bge` 查看进度。Java 默认访问本机 58081，入库和召回共用 1024 维 BGE-M3，精排使用 bge-reranker-v2-m3。两者均在本地 CPU 推理。导购的意图判断及最终证据选择继续使用原有百炼适配器。

**用已有数据验收。** 用 V1.0a 的运营网页发布模拟 V1 政策，以直营店导购提问“包装打开了，客人又不想要了，怎么办？”可与下方的固定快照实验对照。应用启动时为已发布的旧片段补向量，重复启动复用已有索引。混合模式下，发布新版及向量写入在同一事务，编码失败可重试，旧版继续生效。运营端和导购端应连接同一个部署。下线、到期及门店范围对两路召回都生效。内置公开洁手摘要保留原词法入口；政策 Markdown 同时走词法和向量。

**复跑四题对照。** 使用独立评测数据库。下例适用于上面的默认 Compose 端口和账号。创建库的命令只需执行一次；评测事务结束会回滚模拟政策，可以重复跑同一组问题。

```powershell
docker compose exec -T postgres createdb -U babycare babycare_eval
$previousUrl = $env:BABYCARE_DB_URL
try {
    $env:BABYCARE_DB_URL = 'jdbc:postgresql://localhost:55432/babycare_eval'
    $env:BABYCARE_COMPARE = '1'
    .\mvnw.cmd "-Dtest=HybridComparisonTests" test
    if ($LASTEXITCODE -ne 0) { throw '混合检索对照未通过，请查看测试日志' }
    Get-Content target/hybrid-comparison.md -Encoding UTF8
} finally {
    $env:BABYCARE_DB_URL = $previousUrl
    Remove-Item Env:BABYCARE_COMPARE -ErrorAction SilentlyContinue
}
```

有 `DASHSCOPE_API_KEY` 时，评测会把模拟样本的候选送到当前百炼模型，记录实际选出的条款或无依据结果；没有密钥时只测真实检索，有候选的行标记“未调用百炼”。报告包含词法（LEXICAL）、RRF 融合（HYBRID）、融合后精排（RERANK）三种策略。两路各取最多 5 条，按片段 ID 去重，RRF 的 k=60；精排最多 10 条，最终展示上限统一为 5。混合配置默认 HYBRID，四题实测中精排改善一个口语样本，同时令另一题退位，完整排名与耗时见 [V1.0b 验收](plantodo.md#v10b-四题实测)。要在导购网页切换策略，设置 `BABYCARE_RETRIEVAL_MODE` 为上述名称之一并重启 Java。普通 `rag` 配置继续提供 V1.0a 词法服务。模型工件固定版本见 [本地推理服务](models/service.py)。

## V1.1 导购任务中断续办

按 V1.0a 的 Windows 步骤启用 PostgreSQL、设置百炼密钥并启动 Java 服务。使用 `rag` 或 `rag,hybrid` 配置都可续办。默认演示配置使用内存 H2，只能验证同一进程内重新登录。

1. 用直营导购账号 `direct` 登录，点击“新对话”，输入“买两包 L 码，预算 220 元”。看到关于每包上限或总预算的追问。
2. 停止 Java 服务，保留 PostgreSQL 数据卷，从同一目录以相同配置重新运行 `.\start-demo.ps1`。用新浏览器窗口登录原账号，会看到刚才的追问。加盟导购 `franchise` 登录后只会看到自己的任务。
3. 原导购只输入“总价”。默认样本下应看到芽禾单包 106 元、两包共 212 元。“查看核验步骤”可确认本轮重新查了价格和库存。自动化另用一份模拟目录在重启时修改报价和库存，验证页面展示更新值。

每个导购在每家门店保留一个当前任务。“新对话”会替换它。检查点默认自创建或最近成功提交起有效 24 小时，可用 `BABYCARE_CHECKPOINT_TTL` 调整。历史报价带查询时间；续答只将确认条件和待答问题交给模型。版本冲突或其他窗口正在处理时，页面会刷新当前进度并保留输入。服务进程被强制终止时，尚未提交的轮次可在三分钟占用期结束后重试。

## V1.2 商品与本店活动联查

沿用上面的 Windows 启动步骤，用直营导购 `direct` 登录并点击“新对话”。

1. 问“两包 L 码总共不超 220 元”。普通价下芽禾两包 212 元符合预算。
2. 接着问“那本店今天有活动吗？”。绒朵单包 119 元、库存 3 包，两包原价 238 元。本店模拟活动每次购买同款满两包减 20 元，优惠后 218 元，进入符合预算的商品表。“查看核验步骤”展示本轮工具选择。
3. Java 服务运行期间，从项目根目录另开 PowerShell，运行 `New-Item target/promotion-offline.flag -ItemType File`，再问“再查一下活动”。页面提示“优惠待确认”，仍显示芽禾普通价 212 元；绒朵普通价合计 238 元，单独列为超预算。运行 `Remove-Item target/promotion-offline.flag` 恢复模拟活动服务。

[活动样本](fixtures/promotion-demo.json)仅适用于 STORE-01 的 DEMO-008，按北京时间核验起止日期（含首尾），同笔购买只减一次。每包限价按优惠后总额与“每包上限 × 数量”比较。每次查询读取当前活动样本，资料缺失或暂不可读时会提示优惠待确认。报价用于模拟导购查询，活动数据和库存均来自本地样本。

## V1.4 库存超时演练

在项目根目录用 JDK 21 打包后，打开 PowerShell。复制一份模拟库存，在当前窗口配置好运营密码、库存源与延迟开关后启动。这里的库存源只影响库存数量，报价和活动沿用现有样本。

```powershell
Copy-Item fixtures/catalog-demo.json target/drill-stock.json
$env:BABYCARE_STOCK_SOURCE = (Resolve-Path target/drill-stock.json).Path
$env:BABYCARE_STOCK_DELAY_FILE = "$PWD/target/stock-slow.flag"
$env:BABYCARE_OPS_PASSWORD = Read-Host '设置本次模拟运营登录密码'
.\start-demo.ps1
```

1. 用 `direct` 登录，新建对话，问“本店绒朵 L 码两包，总预算 218 元，有库存和活动吗？”。在模拟活动有效期内可看到库存 3 包、两包 218 元。
2. 从另一个 PowerShell 窗口在项目根目录执行 `New-Item target/stock-slow.flag -ItemType File`。在原对话问“帮我再核一下库存”。模拟源延迟默认 10 秒，库存工具默认最多等待 2 秒；页面显示“库存暂无法确认”和查询编号。这里计时的是库存调用，模型理解问题和选择工具另计。
3. 在浏览器的隐私窗口用 `ops` 登录，点击“库存超时告警”，按页面查询编号查找相同的 `messageId`。记录包含 `stock.lookup`、门店、实际耗时和本次超时阈值。服务端同一编号出现在 WARN 的 `messageId` 和回合日志的 `turn`。运营接口保留本实例最近 50 条超时，重启后清空。
4. 在第二个 PowerShell 窗口执行以下命令，将副本中直营店的绒朵从 3 包改为 1 包，再移除延迟标记。回原对话问“再查一下”，应得到“需要同款 2 包，候选商品最多仅有 1 包库存”。

```powershell
$stockPath = 'target/drill-stock.json'
$stockData = Get-Content $stockPath -Encoding UTF8 -Raw | ConvertFrom-Json
$item = $stockData.products | Where-Object sku -eq 'DEMO-008'
$offer = $item.store_offers | Where-Object store_id -eq 'STORE-01'
$offer.available_packs = 1
$stockData | ConvertTo-Json -Depth 10 | Set-Content $stockPath -Encoding UTF8
Remove-Item target/stock-slow.flag
```

可通过 `BABYCARE_STOCK_TIMEOUT` 和 `BABYCARE_STOCK_SIMULATED_DELAY` 配置持续时间。未配置延迟标记文件时正常读取库存。多包规划取本轮剩余时间和库存超时阈值中的较小值。普通离线标记仍用于立即失败测试。需要重演时，将原始样本重新复制到库存副本。

## V1.4 单店库存推荐切回人工

启动前设置 `BABYCARE_OPS_PASSWORD` 以启用运营账号。用两个不同的浏览器会话分别登录 `ops` 和直营导购 `direct`。需要对照另一家门店时，用第三个会话登录加盟导购 `franchise`。

1. 运营在“门店库存核验”选择模拟直营店，填入“库存接口持续故障”，点击“暂停库存推荐”。直营导购问“本店绒朵 L 码买两包，总预算 300 元，库存够吗？”，会看到“本店库存核验已暂停，请到 POS 核实”。实际库存由导购到 POS 人工确认。加盟店仍可核验自己的库存；直营店仍可查询知识资料。
2. 数据源恢复后，运营先核实库存，选择同一家店，填写恢复原因并点击“恢复库存推荐”。直营导购留在原会话问“再查一下”，系统沿用两包和预算条件并重新读取库存。浏览器自动化使用独立样本，在暂停期间把直营店绒朵库存改为 1 包，恢复后应提示“两包需求，最多仅有 1 包库存”。
3. 点击“查看操作记录”核对两次变更的版本、状态、操作人、原因和时间。每家店初始为自动核验，状态与操作记录使用同一条数据库记录提交。运营界面显示最近 50 次变更。使用持久 PostgreSQL 配置时可跨应用重启保留；默认演示的内存数据库随进程重置。

开关控制后续库存推荐和库存详情读取。已在会话中展示的历史回答保留查询时记录；恢复时重新提问会产生新的核验结果。运营根据告警决定何时暂停或恢复。

## 按列表继续询问商品

用模拟直营导购新建对话，连续询问以下三句。示例使用默认样本和活动有效期内的报价。

1. “本店有哪些 L 码纸尿裤？只看芽禾和绒朵。”页面按实际展示顺序标出 1. 芽禾、2. 绒朵。
2. “第二款买两包，今天有活动吗？”核验绒朵 DEMO-008：直营店库存 3 包，两包 238 元减 20 元，共 218 元。
3. 可刷新页面后继续问“那第一款呢？也是两包。”核验原列表第一款芽禾 DEMO-003，两包 212 元。刚才核算的单款保留在历史回答里。

已选中商品或列表只有一款时可以问“这款”；多款且尚未选中时会请导购指出序号。重新列出的结果更新候选顺序。跨轮只记录 SKU 顺序、选中款和购买条件，展示过的价格、库存和活动结果留作历史，续问时从本店工具重新核验。升级前创建的检查点缺少候选顺序时，导购可重新列出商品。

## 同一轮核价与查售后

1. 运营将 `fixtures/policies/returns-v2.md` 导入为直营店可见政策并发布。用 `direct` 新建对话，问“本店有哪些 L 码纸尿裤？只看芽禾和绒朵。”
2. 问“第二款买两包，今天有活动吗？如果拆了一包还能退吗？”在示例活动有效期内，页面列出绒朵两包 238 元、减 20 元、合计 218 元，另列本店有效政策第 4 条及原文链接。该模拟条款要求提供批次与照片，交售后专员复核。
3. 下线该政策，刷新导购页面。历史报价保留当时核验的金额，原条款和引用消失。再次提问会重新核价，缺少有效条款时提示人工确认。

## 售后人工复核

启动前在当前 PowerShell 设置 `$env:BABYCARE_DIRECT_REVIEW_PASSWORD`，用于模拟直营售后账号 `direct-reviewer`。加盟售后账号 `franchise-reviewer` 使用独立的 `$env:BABYCARE_FRANCHISE_REVIEW_PASSWORD`。对应密码留空时，该账号关闭。需要重启后保留复核单时，使用上述 PostgreSQL 的 `rag` 配置。

1. 完成上一节的绒朵报价和售后联查，保留已发布的第 4 条。用直营导购说“帮我把第二款的拆包问题交给售后”。核对草稿中的顾客原问、SKU、当时报价、待确认事项和引用，再点“提交复核”取得单号。
2. 在独立浏览器会话登录 `direct-reviewer`，打开本店待复核事项，填写“请补充包装批次和照片”并提交答复。加盟售后只能处理加盟店的复核单。
3. 刷新原导购对话查看状态和答复。新建对话或检查点过期后，可通过“我的复核单”查看自己已提交的记录。政策撤回后，草稿和已提交记录会隐藏失效条款；历史报价保留查询时的值。

复核只记录人工作出的答复。实际退款、退货受理和外部客服系统仍需由门店按现有流程处理。

## 导购多轮对话评测

在项目根目录运行以下命令。使用 Python 3.9+、项目 JDK 21 和当前会话的 `DASHSCOPE_API_KEY`。脚本先用 Maven Wrapper 打包，再启动独立的本机 Java 服务，调用百炼（默认 `qwen-plus`），结束后关闭服务。

```powershell
python scripts/eval-guide.py
```

每组重新开对话。四类任务各测两种表达，覆盖改选商品、报价与拆包条款联查、商品指代不清和生成草稿后取消转交。使用独立 H2、模拟直营店、固定活动与一份已发布的模拟政策。逐轮核对实际 SKU、总价、工具轨迹、条款、草稿及会话复核单数量。任一步失败即保留该轮差异，继续下一组。原 8 组与独立的犹豫对照均通过时退出码为 0；报告位于 `target/guide-eval.json`。已有构建可用 `--jar 路径` 跳过打包。评测会产生真实百炼调用费用。

## V1.5a 单店 POS 模拟对账

导购问“本店绒朵 L 码两包够吗，按普通价一共多少？”时，商品目录负责定位 SKU，`PosReader` 按登录门店和候选 SKU 读取普通售价（元/包）、可售库存（包）、来源和快照业务日期。可售库存直接取 `available_packs`；0 表示无货，字段缺失时提示库存暂无法确认。默认模拟直营店 DEMO-008 每包 119 元、库存 3 包，两包按普通价合计 238 元。查询时间另外记录为 `queriedAt`。

启动演示服务（默认 18136）后，在项目根目录运行以下命令。脚本使用标准库、直营导购账号及 `BABYCARE_DEMO_PASSWORD`（未配置时用演示默认密码），通过门店商品只读接口对账，无需调用百炼。

```powershell
python scripts/reconcile-pos.py
```

参照文件 `fixtures/pos/direct-reference.csv` 单独保存了同一冻结批次的两个 SKU。输出逐行列出参照值、查询值和差异（查询值减参照值）。复制 CSV，将 DEMO-008 的库存从 3 改为 2，再运行 `python scripts/reconcile-pos.py 副本路径.csv`，应报告直营店该 SKU 的库存差为 +1 包。库存为零的 DEMO-007 应继续匹配。存在差异、接口不可用或快照日期不一致时，退出码非零。正式联调时需映射真实字段与单位，并以同一业务时点或批次比较；当前样本的时间精度为日期。
