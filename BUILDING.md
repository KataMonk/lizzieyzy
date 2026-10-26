# 构建说明（本 fork）

本仓库基于 [yzyray/lizzieyzy](https://github.com/yzyray/lizzieyzy) 修改，主要变更见 `docs/整合包与形势判断总结.md`。

## 环境

- JDK 17+（本项目 `pom.xml` 目标 Java 8，可用新 JDK 编译：`-Dmaven.compiler.release=8`）
- Maven 3.9+

## 构建

```powershell
mvn -B package -DskipTests -Dfmt.skip=true -Dmaven.compiler.release=8
```

产物：`target/lizzie-yzy2.5.3-shaded.jar`

运行：

```powershell
java -jar target/lizzie-yzy2.5.3-shaded.jar
```

## 打包为 exe（可选）

使用 [launch4j](https://launch4j.sourceforge.net/) 把 shaded jar 嵌入 exe：
需要 `launch4j` 目录下的 `launch4jc`，配置见官方示例。

## 弈客功能说明

本 fork 的弈客直播/大厅功能需要用户自行提供 token（代码中不含 token）：

1. 程序目录新建 `yike-token.txt`（内容为 F12 抓到的 `Authorization: Bearer` 后的 token）；
2. 或配置 `config.txt` → `ui` → `"yike-game-token": "<token>"`；
3. 或启动参数 `-Dlizzie.yike.token=<token>`。

获取方式见 `docs/抓包指南.md`。