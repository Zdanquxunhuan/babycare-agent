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
