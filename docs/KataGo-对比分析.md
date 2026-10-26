# KataGo 源码分析与 LizzieYzy 对比

> 对比对象：
> - **KataGo**：<https://github.com/lightvector/KataGo> 默认分支 `master` 快照，版本 **v1.18.2**（`cpp/main.cpp:245` `return string("1.18.2")`）
> - **LizzieYzy**：yzy2.5.3（约 2023-06，对应 KataGo 1.10~1.13 时代），分析见 [LizzieYzy-代码分析.md](<LizzieYzy-代码分析.md>)
> 所有结论附 `文件:行号` 证据（KataGo 行号以 master 快照为准）。

---

## 1. KataGo 项目概览

| 项 | 内容 |
|---|---|
| 定位 | 围棋 AI 引擎（MCTS + 深度神经网络），提供 GTP 与 JSON analysis 两种对外接口，是 LizzieYzy 的"发动机" |
| 版本 | master 快照 `v1.18.2`；LizzieYzy 开发时对应的是 1.10~1.13 时代（2023 年中） |
| 规模 | `cpp/` 926 个 C++ 文件、约 41.4 万行（含第三方）；`python/` 67 个训练脚本 |
| 双栈结构 | `cpp/` = 推理引擎本体（GTP/analysis/selfplay/contribute 等入口 + 搜索 + 神经网络后端）；`python/` = 训练管线（自对弈数据训练、模型导出） |
| 命令入口 | `cpp/main.cpp:86-108` 分发到 `analysis / benchmark / benchmarknn / contribute / evalsgf / gatekeeper / genconfig / gtp / tuner / match / selfplay / testgpuerror`（均在 `cpp/command/`） |
| 神经网络后端 | CUDA / ROCm(HIP) / TensorRT / OpenCL / Eigen(CPU) / Metal(MPSGraph+CoreML) / ONNX Runtime，编译期单选（`CMakeLists.txt:585`）；支持模型版本 3–17 |
| 许可 | 见仓库 LICENSE |

### 内置配置文件（cpp/configs/）

`gtp_example.cfg`、`analysis_example.cfg`、`match_example.cfg`、`contribute_example.cfg`、`gtp_human5k_example.cfg`、`gtp_human9d_search_example.cfg`（b18 human-SL 模型相关）、`task_example.cfg`。

---

## 2. KataGo 架构梳理

### 2.1 顶层结构

- **`cpp/`** = C++ 引擎本体；**`python/`** = PyTorch 训练管线（`train.py` 训练、`shuffle.py` 数据混洗、`upload_model.py`/`export_model_pytorch.py` 导出上传；`selfplay/` 分布式训练脚本；`muon/` 实验性优化器）。
- cpp 子目录职责：
  - **`command/`**：全部子命令入口（`main.cpp:70-160` 统一分发）——`gtp.cpp`（标准 GTP 对弈引擎）、`analysis.cpp`（JSON 行协议并行分析）、`contribute.cpp`（对接 katagotraining.org 持续贡献自对弈）、`selfplay.cpp`（生成训练数据）、`match.cpp`（配置驱动对局）、`gatekeeper.cpp`（新网络门禁赛）、`benchmark.cpp`（测速 + 交互式 `genconfig` 生成配置）及工具类命令。
  - **`core/`**：与围棋无关的通用设施（配置解析 `config_parser.cpp`、日志、多线程、随机数）。
  - **`dataio/`**：SGF 解析（`sgf.cpp`）、训练数据落盘、模型加载（`loadmodel.cpp`）。
  - **`distributed/`**：分布式训练 HTTP 客户端（`client.cpp`，领任务/拉模型/上传数据）。
  - **`game/`**：棋盘与规则核心——`Board`、`BoardHistory`、`Rules`（命名规则预设与 ko/scoring/tax 自由组合）。
  - **`neuralnet/`**：NN 输入特征编码（`nninputs.cpp`）、批量评估与置换表缓存（`nneval.cpp`）+ 各硬件后端。
  - **`program/`**：各模式共享初始化（`setup.cpp` 的 `Setup` 命名空间：加载模型/参数/规则/棋盘尺寸）、自对弈管理（`selfplaymanager.cpp`）、内置配置模板（`gtpconfig.cpp`）。
  - **`search/`**：MCTS 全部实现；**`book/`**：开局书；**`configs/`**：默认配置；**`external/`**：第三方库（clblast、cutlass、cudnn-frontend、nlohmann_json、httplib 等）。

### 2.2 搜索与神经网络

- **搜索**：`Search`（`search.h:83`）持有根局面/参数与搜索树，多线程 MCTS 拆分在 `searchpuct.cpp`、`searchupdatehelpers.cpp`、`searchmultithreadhelpers.cpp`、`searchexplorehelpers.cpp`；`SearchThread` 为每线程工作上下文；`SearchNode`（`searchnode.h:192`）树节点带原子状态机（`STATE_UNEVALUATED→…→STATE_EXPANDED2`，`searchnode.h:202-208`）；`SearchParams` 超参容器；`AsyncBot` 异步封装；`NNEvaluator` + `NNCacheTable` 负责批量调度与置换表。
- **后端**（编译期单选 `-DUSE_BACKEND`，`CMakeLists.txt:585`）：CUDA（`cudabackend.cpp`）、ROCm/HIP（`rocmbackend.cpp`）、TensorRT（`trtbackend.cpp`）、OpenCL（`openclbackend.cpp`，仍在维护）、Eigen CPU（`eigenbackend.cpp`）、Metal（`metalbackend.cpp` + Swift，MPSGraph+CoreML 混合）、ONNX Runtime（`onnxbackend.cpp`）、dummy。支持模型版本 3–17（`modelversion.cpp:30-33`）。**无** DirectML/SDL 后端。

### 2.3 配置体系

- 内置配置 `cpp/configs/`：`gtp_example.cfg`、`analysis_example.cfg`、`match_example.cfg`、`contribute_example.cfg`、`gtp_human5k_example.cfg`、`gtp_human9d_search_example.cfg`（需 human-SL 模型 `b18c384nbt-humanv0`）、`task_example.cfg`；`training/` 下为自对弈/门禁配置（b18c384nbt 架构）。
- 加载路径：`program/setup.cpp`（`Setup::loadParams` `setup.cpp:428-570`）+ `core/config_parser.cpp`；支持 `-override-config`（`gtp.cpp:2070`）。关键项：`numSearchThreads`（`setup.cpp:519-520`）、`maxVisits`（`setup.cpp:495-496`）、`playoutDoublingAdvantage`（`setup.cpp:746-747`）、`analysisWideRootNoise`（仅分析引擎读取，默认 0.04，`gtp.cpp:2129-2130`、`setup.h:107`）。

### 2.4 版本与规则

- 版本：`1.18.2`（`main.cpp:244-245`）；`version` 子命令打印版本 + Git revision + 编译后端等（`main.cpp:252-296`）；**GTP `version` 返回 `1.18.2+<模型缩写>`**（`gtp.cpp:2406-2416`，可用 `--override-version` 覆盖）。
- 规则预设（`game/rules.cpp`）：`japanese/korean`、`chinese`（含 `chineseogs/kgs` 变体）、`ancientarea/stonescoring`、`ancientterritory`、`agabutton`、`aga/bga/french`、`nz/newzealand`、`tromp-taylor`、`goe/ing`；底层为 koRule（simple/positional/situational/spight）× scoring（area/territory）× tax（none/seki/all）自由组合（`rules.cpp:129-143`）。
- 棋盘：默认最大 19（`board.h:14-15`），编译选项 `USE_BIGGER_BOARDS_EXPENSIVE` 可提至 50（`CMakeLists.txt:1753`）；支持非正方形 `boardsize X:Y`（`gtp.cpp:31-34`）。

### 2.5 分布式训练

`contribute.cpp` 装配 `distributed/client.h:79` 的 `Connection`：`getNextTask` 领任务（selfplay / rating 两类，`client.h:108-109`）、`downloadModelIfNotPresent` 拉模型、上传训练数据；服务器 `https://katagotraining.org/`（`configs/contribute_example.cfg:4`）。

---

## 3. LizzieYzy ↔ KataGo 接口逐项对照

> 本节为两仓库交叉核对结果：左列是 LizzieYzy 实际发出的命令/字段（`<工作目录>\lizzieyzy` 可复核），右列是 KataGo v1.18.2 的支持情况。

### 3.1 GTP 命令对照

| LizzieYzy 用法 | LizzieYzy 位置 | KataGo v1.18.2 状态 | 证据 |
|---|---|---|---|
| `name` → 识别引擎（`startsWith("kata")`） | `Leelaz.java:999` | ✅ 返回 `"KataGo"`，识别逻辑成立 | `gtp.cpp` `command == "name"` → `response = "KataGo"` |
| `version` → 解析 `params[1]`，`主版本>1 || 次版本>10` 判定 movesOwnership | `Leelaz.java:1071-1082` | ✅ 返回 `1.18.2`；模型加载后输出 `1.18.2+<模型缩写>`（`+` 拼接），按 `.` 切分后 `ver[1]=18>10` 仍成立 | `gtp.cpp:2406-2418` |
| `kata-analyze <interval> [ownership true] [pvVisits ...] [movesOwnership ...]` | `Leelaz.java:2993,3069-3077,3112-3113` | ✅ 支持；新增 `movesOwnershipStdev`、`pvVisits` 为布尔开关 | `gtp.cpp:92,1945-1951,946-963` |
| `lz-analyze <interval>`（LZ 兼容） | `Leelaz.java:2994,3076,3116` | ✅ 仍支持（与 `analyze`/`kata-analyze` 同入口分发，按 `isLZ` 输出 LZ 简化格式，见下方格式差异） | `gtp.cpp:91,1757,3612` |
| `analyze`（Sayuri 等） | `Leelaz.java:2994` | ✅ 支持（第三种格式分支，不接受 kata 专属 key；LizzieYzy 该模式只拼 `avoid` 参数，可用） | `gtp.cpp:3612,1961` |
| `genmove` / `lz-genmove_analyze` / `genmove_analyze` / `kata-genmove_analyze` | `Leelaz.java:2843-2855` | ✅ 支持（`isLZ`/`isKata` 区分输出格式） | `gtp.cpp:1757-1758` |
| `kata-raw-nn <随机0-7>`（热力图） | `Leelaz.java:3528,2516` | ✅ 参数为 symmetry `'all'` 或 `[0-7]`，兼容；另有 LizzieYzy 未用的新命令 `kata-raw-human-nn`（human-SL 模型） | `gtp.cpp:3633-3647,3649-3673` |
| `kata-set-param analysisWideRootNoise <x>` | `Leelaz.java:3432` | ✅ 支持（`kata-set-param`/`kata-set-params`） | `gtp.cpp:2729-2731` |
| `kata-set-param playoutDoublingAdvantage <x>`（PDA） | `Leelaz.java`（PDA 设置路径） | ✅ 官方支持该参数（非官方的是 getpda/dympdacap，见下） | `gtp.cpp:2729` 起的 kata-set-param 分支 |
| `kata-get-param analysisWideRootNoise` | `Leelaz.java:3537` | ✅ 支持 | `gtp.cpp:2652` |
| `kata-get-rules` / `kata-set-rules chinese|japanese|...` | `Leelaz.java:3538`、`KataEstimate.java:139-142` | ✅ 支持；规则预设定义于 `game/rules.cpp`（japanese/korean、chinese（含 ogs/kgs 变体）、aga/bga/french、nz、tromp-taylor、ancientarea/stonescoring、ancientterritory、agabutton、goe/ing，另支持 JSON 细化与 `komi` 后缀语法） | `gtp.cpp:2526-2535`、`rules.cpp:276-380` |
| `komi` / `boardsize`（矩形）/ `play` / `undo` / `clear_board` / 让子 | 各处 | ✅ 支持（`boardsize` 支持 `X:Y` 矩形）；⚠️ 棋盘上限为**编译期常量**，默认 19，>19 需重编译（`board.h:14-15`、`gtp.cpp:2479-2482`）——LizzieYzy 若配 >19 的引擎尺寸会报错 | `gtp.cpp:2447-2486,2498-2520,3094,3161,2488,3302-3350` |
| `time_settings` / `kata-time_settings`（含 byoyomi/absolute/canadian/fischer/fischer-capped） | `EngineCountDown.java:46-145` | ✅ 支持（7 种计时制，fischer 系列仅 `kata-time_settings` 认） | `gtp.cpp:2879-3015` |
| `getpda` / `getdympdacap` / `dympdacap` / `startGame` / `stopGame` | `Leelaz.java:1014-1019,2481-2495` | ❌ **官方 KataGo 不存在**（命令表 `gtp.cpp:22-109` 与 cpp 全树均无匹配）——yzy 自改 "KataGoPda/KataGoCustom" 分支的专属命令；**触发条件是引擎 `name` 以这两个前缀开头**（`Leelaz.java:1014,1040`），官方引擎（name="KataGo"）不会收到，手工发送则得 `? unknown command` | `gtp.cpp:22-109`；KataGo cpp 全树 grep 无命中 |
| `heatmap`（LZ 旧命令） | `Leelaz.java:3529`（仅 LZ 引擎） | ❌ 官方 KataGo 无此命令（同上，LizzieYzy 只对 LZ 引擎发送） | gtp.cpp 无匹配 |
| `lz-setoption name Visits/Lagbuffer/Maximum Memory Use (MiB)/Resign Percentage value X` | `Leelaz.java:3401-3415`（仅 LZ/SAI 引擎调用 `setLeelaSaiEnginePara`） | ❌ 官方无 `setoption` 命令；官方等价物是 `kata-set-param numSearchThreads/maxVisits/maxPlayouts/maxTime/lagBuffer`（`searchparams.cpp:497-507`）——LizzieYzy 对 KataGo 路径改用 `kata-set-param`，不受影响 | gtp.cpp 全文无 `setoption` 匹配 |
| （LizzieYzy 未用的新命令） | — | 🆕 `kata-search_analyze`、`kata-search_analyze_cancellable`（不落子的局部搜索/可中断搜索）、`kata-raw-human-nn`（human-SL 模型）、`genconfig` 等 | `gtp.cpp:88-89,3649`、`main.cpp:98` |

**官方 `kata-*` 命令全集**（`knownCommands` 表，`gtp.cpp:22-109`）：`kata-get-rules`、`kata-set-rule`、`kata-set-rules`、`kata-get-models`、`kata-get-param`、`kata-set-param`、`kata-list-params`、`kata-search`、`kata-search_cancellable`、`kata-search_debug`、`kata-list_time_settings`、`kata-time_settings`、`kata-genmove_analyze`、`kata-search_analyze`、`kata-search_analyze_cancellable`、`kata-analyze`、`kata-raw-nn`、`kata-raw-human-nn`、`kata-benchmark`、`kata-debug-print-tc`。另有 `analyze`、`genmove_analyze`、`kata-get-params`、`kata-set-params` 被 dispatch 接受但未列入 `list_commands` 输出（`gtp.cpp:85,90,3612,3231,2714,2729`）。

**输出格式差异（影响 GUI 解析）**：`lz-analyze` 走 LZ 简化格式——`winrate` 为整数 ×10000、无 `scoreLead/utility`；`ownership/pvVisits/movesOwnership` 等 kata 专属标签被 `isKata` 门控（`gtp.cpp:1936-1958`），拼到 `lz-analyze` 上会解析失败。`kata-analyze` 输出小数 `winrate` + 完整字段（`scoreLead/scoreStdev/pvVisits/movesOwnership/ownership` 等，`gtp.cpp:836-1029`）。`analyze`（Sayuri 式）走第三种分支：格式同 kata 但不可带 kata 专属 key（`gtp.cpp:1961`）。LizzieYzy 按引擎类型正确分流（`isLZ`/`isKata` 判断在 `Leelaz.java:1757-1758` 同名机制），两侧行为对得上。

### 3.2 analysis JSON 模式对照（闪电/批量分析）

| LizzieYzy 请求 | LizzieYzy 位置 | KataGo v1.18.2 状态 | 证据 |
|---|---|---|---|
| 启动：`katago analysis -model ... -config analysis.cfg -quit-without-waiting` | `Config.java:436` | ✅ `-quit-without-waiting` 开关存在（stdin 关闭后快速退出） | `analysis.cpp:90-99,1429` |
| `-override-config "numSearchThreadsPerAnalysisThread=N"` | `AnalysisEngine.java:69-75` | ✅ 该键被注册为 `numSearchThreads` 的别名 | `analysis.cpp:107` `cfg.applyAlias("numSearchThreadsPerAnalysisThread","numSearchThreads")` |
| 请求字段 `id` / `moves` / `analyzeTurns` / `rules` / `komi` / `overrideSettings` | `AnalysisEngine.java:350-421` | ✅ 全部支持 | `analysis.cpp:227-249` |
| `includeOwnership` / `includeMovesOwnership` | `AnalysisEngine.java` | ✅ 支持；另新增 `includeOwnershipStdev`、`includeMovesOwnershipStdev`（LizzieYzy 未用） | `analysis.cpp:33-36,246-249` |
| `overrideSettings.reportAnalysisWinratesAs = "SIDETOMOVE"` | `AnalysisEngine.java:416` | ✅ 合法值（大小写不敏感）：`black`/`white`/`sidetomove`，其他值抛错 | `setup.cpp` `reportAnalysisWinratesAs` 解析（`"sidetomove"` 分支） |
| 响应 `moveInfos`（move/order/visits/winrate/lcb/prior/scoreLead/scoreStdev/pv/pvVisits + 逐手 `ownership`） | `AnalysisEngine.java:221-250`、`Utils.java:952-992` | ✅ 全部支持；逐手所有权在响应里名为 moveInfos 内的 `ownership` 字段，顶层另有整盘 `ownership` 与 `policy`、`rootInfo`（`searchresults.cpp:2008-2236`）；新增 `pvEdgeVisits`、`ownershipStdev` 系列字段 LizzieYzy 未用，多余字段不影响 JSON 解析 | `analysis.cpp:303-342`、`searchresults.cpp:2066-2233` |

> 注：子代理初版报告曾把 LizzieYzy 的取值写成 `SIDETOTOMOVE`，经我直接核对 `AnalysisEngine.java:416`，实际发送值是 **`SIDETOMOVE`**，与 KataGo 接受值一致，不构成兼容性问题。

---

## 4. 兼容性结论与风险

**总体结论：LizzieYzy（yzy2.5.3）与 KataGo v1.18.2 的核心通道全部兼容。** LizzieYzy 停更于 2023-06（KataGo 约 1.13 时代），但这两年间 KataGo 的 GTP/JSON 协议是"只增不改"的向后兼容演进——`lz-analyze` 别名保留、分析输出只加字段不改语义，因此 GUI 侧的解析逻辑至今成立（本文 §3 的每一项都经两仓库交叉核对验证）。

### 兼容项（验证通过）

实时分析（`kata-analyze` + ownership/pvVisits/movesOwnership）、闪电/批量分析（`analysis` JSON 模式全部字段，含 `reportAnalysisWinratesAs=SIDETOMOVE`、`numSearchThreadsPerAnalysisThread` 别名、`-quit-without-waiting`）、热力图与形势判断（`kata-raw-nn [0-7]`）、引擎参数（`kata-set-param`/`kata-get-param`）、规则（`kata-set-rules`/`kata-get-rules`）、计时（`kata-time_settings` 7 种制式）、引擎识别（`name`="KataGo"、`version` 按 `.` 切分判定 movesOwnership ≥1.11 对 `1.18.2` 成立）。

### 风险与落差（按影响排序）

1. **动态 PDA 功能依赖 yzy 定制分支（★★）**：`getpda`/`getdympdacap`/`dympdacap`/`startGame`/`stopGame` 官方 v1.18.2 全部不存在。LizzieYzy 的防护是按引擎 `name` 前缀（`KataGoPda`/`KataGoCustom`）触发（`Leelaz.java:1014,1040`），官方引擎不会收到；但动态 PDA/动态让子平衡等宣传功能只有在用户自行找到并使用 yzy 定制编译版 KataGo 时才可用，官方等价物只有静态 `kata-set-param playoutDoublingAdvantage`。
2. **棋盘尺寸上限不匹配（★）**：LizzieYzy 支持变长/矩形棋盘（9/13/19 及自定义宽高，SGF 侧甚至兼容非标 `x_y` 坐标），而官方 KataGo 的棋盘上限是**编译期常量**（默认 19，`board.h:14-15`），>19 的配置会被引擎直接拒绝（`gtp.cpp:2479-2482`）。
3. **`kata-set-param` 已变为带校验的通用覆盖机制（★）**：v1.18.2 对未识别/不可覆盖的键直接报错（`gtp.cpp:2788-2791`），且显式禁止覆盖 `dynamicPlayoutDoublingAdvantageCapPerOppLead`（`gtp.cpp:2781`）。当前 LizzieYzy 发送的键（`analysisWideRootNoise`、`playoutDoublingAdvantage`）都合法，但未来 KataGo 若调整 config 键名，GUI 侧会直接报错。
4. **`version` 输出格式变化（★）**：模型加载后返回 `1.18.2+<模型缩写>`（`gtp.cpp:2412-2416`）。已验证 LizzieYzy 的 `split(".")` 解析不受影响（`ver[1]=18>10` 判定 movesOwnership 成立）；但用 `--override-version` 伪装成非 `x.y` 格式会使其误判（`gtp.cpp:2072`）。
5. **kata-raw-nn 的边缘行为（★）**：LizzieYzy 发送 `kata-raw-nn <随机 0-7>`，输出行名（`policy`/`whiteOwnership` 等）与其 `parseHeatMap`（`Leelaz.java:2240-2280`）完全匹配；仅当使用对称参数 `all` 时输出块间含空行会截断 GTP 响应（`gtp.cpp:1714`）——LizzieYzy 不用 `all`，无实际影响。
6. **KataGo 新能力 LizzieYzy 均未利用（★）**：`kata-search_analyze`/`kata-search_analyze_cancellable`（不落子、可中断的局部搜索，正适合 GUI 的实时探询）、`movesOwnershipStdev`/`includeOwnershipStdev`（不确定度）、human-SL 模型（`gtp_human5k/9d` 配置与 `kata-raw-human-nn`，拟人化对弈）、`genconfig`（交互式配置生成）、TensorRT/Metal/ONNX 等新后端——这些只能等 GUI 跟进。
7. **模型版本无风险**：KataGo 支持模型版本 3–17（`modelversion.cpp:30-33`），LizzieYzy 不解析模型格式，不受影响。

---

## 5. 两个项目的关系小结

LizzieYzy 是"引擎无关"的 GTP 前端（同时支持 KataGo/LeelaZero/Zen/SAI 等），KataGo 是可独立运行的引擎——两者以 **GTP + JSON 行协议松耦合**，没有编译期依赖。LizzieYzy 对 KataGo 的功能映射：

| LizzieYzy 功能 | 使用的 KataGo 接口 |
|---|---|
| 实时分析选点/胜率/PV | `kata-analyze`（GTP） |
| 闪电分析、批量分析 | `katago analysis` JSON 行协议 |
| 热力图/"纯网络"按钮 | `kata-raw-nn <symmetry>` |
| 形势判断（点目） | `kata-raw-nn 0` + ownership（Zen 引擎则用 `territory`） |
| KataGo 规则设置对话框 | `kata-set-rules`/`kata-get-rules` |
| WRN 拉宽搜索 | `kata-set-param analysisWideRootNoise` |
| PDA 让子平衡 | `kata-set-param playoutDoublingAdvantage`（官方）+ `getpda`/`dympdacap`（yzy 定制版专属） |
| 引擎对局计时 | `kata-time_settings` |
| 跑谱贡献（分布式训练可视化） | `katago contribute` + stdout JSON 对局流 |
| `lz-analyze`/`lz-setoption`/`heatmap`/`genmove_analyze` | 仅服务 LeelaZero/SAI/Sayuri/Zen 等旧引擎，与 KataGo 无关 |

**维护状态对比**：KataGo 活跃演进（v1.18.2），LizzieYzy 自 2023-06 停更——GUI 侧适配已滞后约五个 minor 版本，但得益于协议向后兼容，本地分析主链路今天仍然可用；真正的断点集中在 yzy 定制分支的私有命令（§4.1）和早已失效的自建云服务（见 LizzieYzy 分析文档 §4.1）。
