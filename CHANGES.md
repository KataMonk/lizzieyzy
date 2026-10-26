# 本 fork 的修改摘要

基于上游 `yzyray/lizzieyzy`（yzy2.5.3），主要修改：

## 稳定性
- ImageIO 关闭缓存 / 临时目录回退 / 字体加载回退（修复 `Can't create cache file!`）
- 窗口关闭挂起、加载提示、左侧/右侧面板黑屏（d3d/opengl 兼容）修复

## 形势判断（Estimate）
- 重写 `EstimateCalibrator`：己方棋子不再计入己方目数；对方死子计入并显示
- 修复 stones/ownership 索引（列优先 vs 行优先）
- 盘面领先改为"面积差"（活子+空+对方死子）
- 默认阈值 0.2 / 0.5，可配置
- 取消单步最大分析限制；性能优化（内存/CPU/重绘）

## 棋谱与在线
- 野狐/腾讯棋谱：`FoxKifuClient` + 导入窗口（昵称搜索、大赛列表、分享链接/chessid）
- 弈客直播：`YikeLiveClient`（`api.yikeweiqi.com/golive/dtl`，HTTP 轮询）
- 弈客大厅：`YikeGameClient` + `YikeLobbyDialog`（game-server 列表/对局 + 实时同步）
- 拉取/轮询相关 token 均由用户配置，代码不含任何私密信息

## 打包
- 权重精简为 b11c768(tf3) + b10c384(tf2)；TensorRT 引擎条目
- 整合包与源码分仓管理（本仓库仅源码与文档，不含模型/JRE/引擎）