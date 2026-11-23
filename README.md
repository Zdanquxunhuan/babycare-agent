# BabyCare Agent 导购工作台

本地模拟网页版提供两个导购账号、50 个商品 SKU、门店报价及库存，以及带来源的知识问答。运行需要 JDK 21 和可访问的阿里云百炼模型。打开网页后按[验收标准](docs/网页首版验收标准.md)逐项操作。

## 启动与登录

在本项目目录的 PowerShell 中选择 JDK 21，执行以下命令。首次构建需能访问 Maven 仓库。

```powershell
.\mvnw.cmd package
.\start-demo.ps1
```

脚本默认使用 JAVA_HOME，可通过 -JavaHome 指定 JDK 21 目录。启动时按隐藏输入提示提供百炼密钥，或预先在后端进程中配置 DASHSCOPE_API_KEY。打开 http://127.0.0.1:18136/ ，选择直营店账号 direct 或加盟店账号 franchise，默认模拟登录密码为 BabyCare-demo。可用 BABYCARE_DEMO_PASSWORD 配置新的模拟密码。

百炼默认模型为 qwen-plus，默认使用北京地域接口。可通过 BAILIAN_MODEL 和 BAILIAN_CHAT_URL 改用相应模型及业务空间的完整 HTTPS Chat Completions 地址。网页中的模拟问答会经服务端发给百炼，测试时使用虚构顾客信息。参见[百炼兼容 Chat 接口](https://help.aliyun.com/zh/model-studio/qwen-api-via-openai-chat-completions)。

## 验证

Maven 的 test 阶段运行启动测试与真实 HTTP 会话集成测试。后者使用模型替身，覆盖门店权限、多轮筛选、证据过滤和异常分流。手工验收使用真实模型。测试 AC-10 时可在工作台运行期间创建空文件 target/stock-offline.flag 以模拟库存不可用，移除该文件后再次提问即可恢复。会话在 30 分钟无操作、退出或应用重启时失效。

实现边界及后续内部数据源的接入位置见[前后端架构设计](docs/网页首版前后端架构设计.md)。
