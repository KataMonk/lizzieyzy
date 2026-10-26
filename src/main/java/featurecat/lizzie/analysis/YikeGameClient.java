package featurecat.lizzie.analysis;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 弈客大厅（game-server）客户端。
 *
 * <p>对局数据接口（需 Bearer token，实测游客 token 可用）：
 * <pre>
 * https://game-server.yikeweiqi.com/game/info?id=&lt;id&gt;&amp;sgf_option=true&amp;players_option=true...
 * https://game-server.yikeweiqi.com/game/list?page_index=1&amp;page_size=20&amp;status=processing
 * </pre>
 */
public class YikeGameClient {

  /**
   * 弈客 token 读取顺序：
   * 1) 系统属性 -Dlizzie.yike.token=...
   * 2) 环境变量 LIZZIE_YIKE_TOKEN
   * 3) 配置项 ui.yike-game-token
   * 4) 程序目录下的 yike-token.txt（内容为 Bearer token，见 docs 说明）
   */

  private static final String UA =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36";

  public static class GameItem {
    public String id = "";
    public String name = "";
    public String beginTime = "";
    public String phase = "";
    public String status = "";
    public String black = "";
    public String white = "";
    public String blackGrade = "";
    public String whiteGrade = "";
    public String result = "";
    public String sgf = "";
    public int hands = 0;
    public int boardSize = 19;
    public double komi = 0;

    @Override
    public String toString() {
      return (beginTime.isEmpty() ? "" : beginTime + "  ")
          + black
          + (blackGrade.isEmpty() ? "" : "[" + blackGrade + "]")
          + " vs "
          + white
          + (whiteGrade.isEmpty() ? "" : "[" + whiteGrade + "]")
          + "  "
          + hands
          + "手"
          + (phase.isEmpty() ? "" : "  " + phase);
    }
  }

  public static String token() {
    String t = System.getProperty("lizzie.yike.token", "");
    if (t != null && !t.isEmpty()) return t.trim();
    t = System.getenv("LIZZIE_YIKE_TOKEN");
    if (t != null && !t.isEmpty()) return t.trim();
    try {
      if (featurecat.lizzie.Lizzie.config != null
          && featurecat.lizzie.Lizzie.config.uiConfig != null) {
        String c = featurecat.lizzie.Lizzie.config.uiConfig.optString("yike-game-token", "");
        if (c != null && !c.isEmpty()) return c.trim();
      }
    } catch (Throwable ignore) {
    }
    try {
      java.io.File file = new java.io.File("yike-token.txt");
      if (file.isFile()) {
        String s =
            new String(
                java.nio.file.Files.readAllBytes(file.toPath()),
                java.nio.charset.Charset.forName("UTF-8"));
        s = s.trim();
        if (!s.isEmpty()) return s;
      }
    } catch (Throwable ignore) {
    }
    return "";
  }

  private static String base(String prop) {
    String b = System.getProperty(prop, "https://game-server.yikeweiqi.com");
    if (!b.endsWith("/")) b = b + "/";
    return b;
  }

  public static String infoUrl(String id) {
    return base("lizzie.yike.infobase") + "game/info?id="
        + id
        + "&sgf_option=true&players_option=true&setting_option=true&clock_option=true&is_void=true";
  }

  public static String listUrl(int page, int pageSize, String status) {
    String u =
        base("lizzie.yike.listbase") + "game/list?page_index="
            + page
            + "&page_size="
            + pageSize;
    if (status != null && !status.isEmpty()) u += "&status=" + status;
    return u;
  }

  /** 从首页链接/对局链接提取局 id */
  public static String parseGameId(String url) {
    if (url == null) return null;
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("[?&]id=(\\d{5,20})").matcher(url);
    if (m.find() && (url.contains("game/info") || url.contains("game-server"))) return m.group(1);
    m = java.util.regex.Pattern.compile("game/(?:play|detail|replay)/\\d+/(\\d{5,20})").matcher(url);
    if (m.find()) return m.group(1);
    m = java.util.regex.Pattern.compile("#/game/(?:detail|play)/(?:\\d+/)?(\\d{5,20})").matcher(url);
    if (m.find()) return m.group(1);
    return null;
  }

  public static String fetch(String url) throws IOException {
    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
    c.setRequestMethod("GET");
    c.setConnectTimeout(12000);
    c.setReadTimeout(15000);
    c.setInstanceFollowRedirects(true);
    c.setRequestProperty("User-Agent", UA);
    c.setRequestProperty("Accept", "application/json, text/plain, */*");
    c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
    c.setRequestProperty("Accept-Encoding", "gzip, deflate");
    c.setRequestProperty("Origin", "https://home.yikeweiqi.com");
    c.setRequestProperty("Referer", "https://home.yikeweiqi.com/");
    c.setRequestProperty("Cache-Control", "no-cache");
    c.setRequestProperty("Pragma", "no-cache");
    String tk = token();
    if (tk != null && !tk.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + tk);
    int code = c.getResponseCode();
    if (code != 200) throw new IOException("HTTP " + code + " for " + url);
    String enc = c.getContentEncoding();
    InputStream in = c.getInputStream();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    int n;
    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    in.close();
    byte[] bytes = out.toByteArray();
    try {
      if (bytes.length > 2 && (bytes[0] & 0xff) == 0x1f && (bytes[1] & 0xff) == 0x8b) {
        GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(bytes));
        ByteArrayOutputStream o2 = new ByteArrayOutputStream();
        while ((n = gz.read(buf)) > 0) o2.write(buf, 0, n);
        gz.close();
        bytes = o2.toByteArray();
      } else if (enc != null && enc.toLowerCase(java.util.Locale.ROOT).contains("deflate")) {
        InflaterInputStream inf = new InflaterInputStream(new ByteArrayInputStream(bytes));
        ByteArrayOutputStream o2 = new ByteArrayOutputStream();
        while ((n = inf.read(buf)) > 0) o2.write(buf, 0, n);
        inf.close();
        bytes = o2.toByteArray();
      }
    } catch (IOException ignore) {
    }
    c.disconnect();
    String s = new String(bytes, Charset.forName("UTF-8"));
    if (s.indexOf('\uFFFD') >= 0) s = new String(bytes, Charset.forName("GB18030"));
    return s;
  }

  /** 解析大厅列表 */
  public static List<GameItem> parseList(String json) {
    List<GameItem> result = new ArrayList<GameItem>();
    try {
      JSONObject o = new JSONObject(json);
      JSONArray arr = o.optJSONArray("data");
      if (arr == null) return result;
      for (int i = 0; i < arr.length(); i++) {
        GameItem it = parseItem(arr.optJSONObject(i));
        if (it != null && !it.id.isEmpty()) result.add(it);
      }
    } catch (Exception ignore) {
    }
    return result;
  }

  /** 解析单局信息（含 sgf） */
  public static GameItem parseInfo(String json) {
    try {
      JSONObject o = new JSONObject(json);
      JSONObject data = o.optJSONObject("data");
      if (data == null) return null;
      return parseItem(data);
    } catch (Exception e) {
      return null;
    }
  }

  private static GameItem parseItem(JSONObject data) {
    if (data == null) return null;
    GameItem it = new GameItem();
    it.id = data.optString("id", "");
    it.name = data.optString("game_name", "");
    it.beginTime = data.optString("begin_time", "");
    it.phase = data.optString("phase", "");
    it.status = data.optString("status", "");
    it.result = data.optString("result_desc", "");
    it.hands = data.optInt("hands_count", 0);
    it.boardSize = data.optInt("board_size", 19);
    it.komi = data.optDouble("komi", 0);
    it.sgf = data.optString("sgf", "");
    JSONObject players = data.optJSONObject("players");
    if (players != null) {
      JSONArray b = players.optJSONArray("blacks");
      JSONArray w = players.optJSONArray("whites");
      if (b != null && b.length() > 0) {
        it.black = b.optJSONObject(0).optString("name", "");
        it.blackGrade = b.optJSONObject(0).optString("grade", "");
      }
      if (w != null && w.length() > 0) {
        it.white = w.optJSONObject(0).optString("name", "");
        it.whiteGrade = w.optJSONObject(0).optString("grade", "");
      }
    }
    return it;
  }

  /** 在 SGF 根节点注入 PB/PW/KM（原 SGF 没有棋手名时） */
  public static String sgfWithPlayers(GameItem it) {
    if (it == null || it.sgf == null || !it.sgf.startsWith("(;")) return it == null ? "" : it.sgf;
    String body = it.sgf.substring(2);
    StringBuilder sb = new StringBuilder("(;");
    if (!it.black.isEmpty()) sb.append("PB[").append(esc(it.black)).append("]");
    if (!it.white.isEmpty()) sb.append("PW[").append(esc(it.white)).append("]");
    if (!it.sgf.contains("KM[")) sb.append("KM[").append(it.komi).append("]");
    if (!it.sgf.contains("SZ[")) sb.append("SZ[").append(it.boardSize).append("]");
    sb.append(body);
    return sb.toString();
  }

  private static String esc(String s) {
    return s.replace("\\", "\\\\").replace("]", "\\]");
  }
}
