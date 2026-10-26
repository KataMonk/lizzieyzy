# LizzieYzy yzy2.5.4 User Manual

> Version: **2.5.4** (build 20261026)
> For the Windows x64 package rebuilt on 2026-10-26.
> Source: <https://github.com/KataMonk/lizzieyzy> (fork of <https://github.com/yzyray/lizzieyzy>)

---

## 1. What is it

LizzieYzy is a Go AI analysis GUI (a modified Lizzie). It bundles KataGo and two networks:
real-time analysis with candidate moves and win rates, territory estimation, SGF analysis,
Fox (Tencent Weiqi) kifu import, and Yike live/lobby sync.

Highlights:

- Bundled **JRE 17** — no Java installation needed;
- Bundled **KataGo 1.18.1** (OpenCL) and two networks (analysis + estimate);
- Startup/font/temp-dir/black-screen issues fixed;
- Yike live/lobby sync (lobby requires your own token, see 7.4).

---

## 2. Quick start

1. Extract the whole folder to disk (do not run inside the zip);
2. Double-click **`Lizzieyzy-2.5.4-win64.exe`**;
3. First launch creates runtime files and starts the engine; wait a few seconds.

| Situation | File to run |
|---|---|
| Normal | `Lizzieyzy-2.5.4-win64.exe` |
| exe won't open / crashes | `bat启动器(exe无法打开时使用).bat` |
| Display issues (black/garbled) | `Lizzieyzy(仅在显示异常时尝试)-win64.exe` or the matching bat |
| Want console logs | launch via the bat launcher |

> If antivirus blocks the exe, whitelist the folder (launch4j wrappers are often flagged).

---

## 3. Folder layout

| Path | Purpose |
|---|---|
| `Lizzieyzy-2.5.4-win64.exe` | main launcher (jar embedded) |
| `lizzie-yzy2.5.4-shaded.jar` | application (run with `java -jar` if needed) |
| `jre\java17\` | bundled JRE |
| `weights\` | KataGo networks |
| `katago_opencl\` | KataGo OpenCL engine (default) |
| `katago_eigen\`, `katago_eigen_avx2\` | CPU engines (fallback) |
| `katago_tensorRT\` | TensorRT engine (needs NVIDIA + TensorRT libs) |
| `katago_configs\` | `analysis.cfg` / `estimate.cfg` |
| `KataGoData\` | engine cache (safe to delete) |
| `theme\`, `sound\` | board themes and sounds |
| `temp\`, `persist`, `save\` | runtime files (safe to delete; keep `save` for autosaved games) |
| `工具\` | diagnostic scripts |
| `文档\` | detailed docs |
| `images\` | screenshots used here |

---

## 4. Engines and networks

| Network | Purpose | Size |
|---|---|---|
| `kata1-tf3-b11c768-...bin.gz` | main analysis (candidates/winrate) | ~250 MB |
| `kata1-tf2-b10c384-...bin.gz` | territory estimation (fast) | ~36 MB |

Engine entries: OpenCL b11c768 (default), OpenCL b10c384, TensorRT b11c768, TensorRT b10c384.
Switch in **Settings → Engine settings**.

---

## 5. Main window

![Main window](images/01_main.png)

1. **Menu bar**: File / View / Game / Analyze / Edit / Sync / Help / Settings;
2. **Left panel**: win-rate bar and score lead;
3. **Candidates on board**: green = black's moves, blue = white's moves; numbers are playout share;
4. **Right panel**: analysis table, variation tree, mini board;
5. **Bottom toolbar**: start/pause analysis, clear board, navigation, estimate panel, move numbers, coordinates, auto-play.

Basics: click the board to place/try moves; `<`/`>` to step through the game;
**File → Open/Save** for SGF; **Settings → Engine settings** to change engines.

---

## 6. Territory estimate (目数估计)

- Open via bottom toolbar **形势判断** or menu **Analyze → 形势判断**;
- Thresholds: low **0.2**, high **0.5** (adjustable).

![Estimate panel](images/03_estimate_panel.png)

| Row | Meaning |
|---|---|
| 领地 (territory) | empty points only |
| 死子 (dead stones) | opponent's dead stones inside your area |
| 差值 (difference) | territory + dead stones + captures |
| **盘面领先 (board lead)** | **area difference** = (alive + territory + enemy dead stones), komi excluded |

> Board lead uses area scoring; e.g. engine score −0.8 with komi 7.5 → about 7 points for Black.

---

## 7. Kifu and online games

### 7.1 Fox / Tencent kifu (menu: Sync → 腾讯棋谱)

![Fox kifu dialog](images/02_fox_kifu.png)

1. **Nickname / keyword** (top row): search public/tournament kifu (e.g. `柯洁`), 20 per page;
   double-click a row to load. Casual online games are not indexed — use row 2 instead.
2. **Share link / chessid** (second row): paste
   `https://h5.foxwq.com/yehunewshare/?chessid=...&boardsize=19` (or just the chessid),
   click **导入链接** to download SGF and load.
3. **大赛棋谱列表** loads the latest official kifu list.

### 7.2 Yike live (menu: File → Open online link)

Paste any of:

- `https://home.yikeweiqi.com/#/live/new-room/197418/0/0`
- `https://home.yikeweiqi.com/#/live/room/18328/1/15630642`
- `https://home.yikeweiqi.com/#/live/board/<id>`

The app fetches the current SGF and polls it at the dialog's refresh interval.

### 7.3 Yike lobby (menu: Sync → 弈客大厅)

![Yike lobby](images/04_yike_lobby.png)

- Browse games (playing / finished / all), refresh and page through;
- **Double-click a game** to load it and start **live sync** (checks every 3 s, auto-follows new moves);
- **停止同步** stops sync; `#/game/play/<hall>/<id>` links also work via "Open online link".

### 7.4 Yike token (required for lobby)

The code contains **no token**. Provide your own:

1. Open any Yike game page → F12 → **Network** → find
   `game-server.yikeweiqi.com/game/info?id=...` → copy the value after `authorization: Bearer `;
2. Create **`yike-token.txt`** in the program folder (UTF-8, one line),
   or add `"yike-game-token": "<token>"` to `config.txt` under `ui`,
   or start with `-Dlizzie.yike.token=<token>`.

### 7.5 Packet capture

See `文档\抓包指南.md`.

---

## 8. Useful config keys

| Key | Meaning |
|---|---|
| `ui.estimate-threshold` / `ui.estimate-high-threshold` | estimate thresholds (0.2 / 0.5) |
| `ui.estimate-calibrate` | enable estimate calibration |
| `leelaz.max-analyze-time-seconds` | per-move time cap (0 = unlimited) |
| `leelaz.limit-time` / `leelaz.limit-playout` | limit analysis (default false) |
| `ui.read-komi` | use komi from SGF |
| `ui.always-gotolast-onlive` | jump to last move on live games |

> Keep `config.txt` valid JSON; invalid files are renamed to `config_wrong.txt`.

---

## 9. FAQ

| Symptom | Fix |
|---|---|
| exe does nothing / crashes | use the bat launcher |
| black/garbled window | use the "display issues" exe |
| `Can't create cache file!` / font errors | delete `temp\` and restart |
| engine fails to load | check `weights\` and antivirus; use OpenCL entries unless TensorRT is installed |
| no estimate numbers | wait for engine, open the estimate panel, lower thresholds |
| a kifu is not found by nickname | casual games are not indexed; paste the share link |
| lobby says no token | configure `yike-token.txt` (7.4) |
| broken config | delete `config.txt` and restart |

---

## 10. Docs and feedback

`文档\` contains: integration/estimate notes, code analysis, KataGo comparison,
online-feature fix notes, and the capture guide.

Issues: <https://github.com/KataMonk/lizzieyzy/issues>

QQ groups: 584641555 (4), 246284327 (3), 867298807 (2), 786173361 (1)

---

## 11. Changelog

- **2.5.4 (20261026)**
  - Simplified update dialog (version + QQ groups only);
  - About page: build 20261026, authors yzyray/katamonk, new source URL;
  - Version 2.5.4 everywhere (jar/exe/folder);
  - Rewritten manual with screenshots.
- **2.5.3 base (rebuilt from 2023-06 source)**
  - Startup/font/temp/black-screen fixes; estimate calibration; thresholds 0.2/0.5;
  - Fox/Tencent kifu import; Yike live/lobby sync; performance tuning; no embedded secrets.
