package featurecat.lizzie.analysis;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * 野狐（腾讯围棋）棋谱客户端。
 *
 * <p>旧版通过外部 helper {@code foxReq/FoxRequest.jar} 请求已停用的腾讯欢乐围棋 CGI；
 * 现在改为直接访问野狐现行接口：
 *
 * <ul>
 *   <li>棋谱列表：{@code https://www.foxwq.com/qipu.html}（第 1 页）与
 *       {@code /qipu/index/p/N.html}（服务端渲染，可直接解析）；
 *   <li>棋谱数据：{@code https://api.foxwq.com/kifu?chessid=<id>} 等端点，返回 SGF 数据
 *       （可能是 gzip/二进制，本类自动解压并按 UTF-8/GBK 解码）。
 * </ul>
 */
public class FoxKifuClient {

  /** 单条棋谱信息 */
  public static class KifuInfo {
    public String title = "";
    public String chessid = "";
    public String date = "";

    @Override
    public String toString() {
      return (date.isEmpty() ? "" : date + "  ") + title;
    }
  }

  /** 测试时可把 base 指到本地 mock 服务器（system property: lizzie.fox.base） */
  private static String base() {
    String b = System.getProperty("lizzie.fox.base", "https://");
    if (!b.endsWith("/")) b = b + "/";
    return b;
  }

  private static String listBase() {
    String b = System.getProperty("lizzie.fox.listbase", "https://www.foxwq.com/");
    if (!b.endsWith("/")) b = b + "/";
    return b;
  }

  /**
   * 棋谱数据端点（按顺序尝试）。%s = chessid。
   *
   * <p>主端点来自野狐 H5 分享页的 JS（pages-Desk-index.*.js）：
   * {@code https://h5.foxwq.com/yehuDiamond/chessbook_local/YHWQFetchChess}，
   * 返回 JSON，其中 "chess" 字段就是 SGF 文本。
   */
  private static final String[] KIFU_ENDPOINTS = {
    "https://h5.foxwq.com/yehuDiamond/chessbook_local/YHWQFetchChess?chessid=%s",
    "https://api.foxwq.com/kifu?chessid=%s",
    "https://api.foxwq.com/kifu/%s"
  };

  private static final String UA =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36";

  /** 从分享链接/纯数字中提取 chessid */
  public static String parseChessId(String raw) {
    if (raw == null) return null;
    String s = raw.trim();
    if (s.isEmpty()) return null;
    Matcher m = Pattern.compile("(?i)chessid=(\\d{5,25})").matcher(s);
    if (m.find()) return m.group(1);
    m = Pattern.compile("^\\s*(\\d{5,25})\\s*$").matcher(s);
    if (m.matches()) return m.group(1);
    m = Pattern.compile("(\\d{15,25})").matcher(s);
    if (m.find()) return m.group(1);
    return null;
  }

  /** 搜索结果（棋谱列表 + 总盘数） */
  public static class SearchResult {
    public List<KifuInfo> items = new ArrayList<KifuInfo>();
    public int total = 0;
  }

  /** 抓取"大赛棋谱"列表（第 page 页，从 1 开始） */
  public static List<KifuInfo> fetchLatestList(int page) throws IOException {
    String url =
        page <= 1 ? listBase() + "qipu.html" : listBase() + "qipu/index/p/" + page + ".html";
    return parseListHtml(decode(httpGet(url, listBase())));
  }

  /**
   * 按昵称/关键字搜索棋谱（对应官网棋谱页的搜索框：标题/棋手名）。
   *
   * @param keyword 昵称或关键字（如"柯洁"）
   * @param page 页码，从 1 开始
   */
  public static SearchResult searchKifu(String keyword, int page) throws IOException {
    String enc = java.net.URLEncoder.encode(keyword, "UTF-8");
    String url =
        page <= 1
            ? listBase() + "qipu.html?keyword=" + enc
            : listBase() + "qipu/index/keywords/" + enc + "/p/" + page + ".html";
    String html = decode(httpGet(url, listBase()));
    SearchResult r = new SearchResult();
    r.items = parseListHtml(html);
    java.util.regex.Matcher tm = Pattern.compile("共\\s*(\\d+)\\s*盘").matcher(html);
    if (tm.find()) {
      try {
        r.total = Integer.parseInt(tm.group(1));
      } catch (NumberFormatException ignore) {
      }
    }
    return r;
  }

  private static List<KifuInfo> parseListHtml(String html) {
    List<KifuInfo> result = new ArrayList<KifuInfo>();
    Pattern p =
        Pattern.compile(
            "<a href=\"(https?://h5\\.foxwq\\.com/yehunewshare/\\?[^\"]*?chessid=(\\d{5,25})[^\"]*)\"[^>]*>([^<]+)</a>");
    Matcher m = p.matcher(html);
    while (m.find()) {
      KifuInfo info = new KifuInfo();
      info.chessid = m.group(2);
      info.title = unescape(m.group(3));
      result.add(info);
    }
    // 时间列与条目一一对应（若数量一致就填上）
    Pattern tp = Pattern.compile("qipu-time[^>]*>([^<]+)<");
    Matcher tm = tp.matcher(html);
    ArrayList<String> times = new ArrayList<String>();
    while (tm.find()) times.add(tm.group(1).trim());
    if (times.size() == result.size()) {
      for (int i = 0; i < result.size(); i++) result.get(i).date = times.get(i);
    }
    return result;
  }

  /**
   * 下载棋谱并返回 SGF 文本。
   *
   * @param chessId 棋谱 id
   * @return SGF 文本（以 "(;" 开头）
   */
  public static String downloadSgf(String chessId) throws IOException {
    // 测试/自定义端点优先（lizzie.fox.kifuUrl，%s 会被替换成 chessid）
    String mock = System.getProperty("lizzie.fox.kifuUrl", null);
    if (mock != null) {
      String sgf = tryDownload(mock.replace("%s", chessId));
      if (sgf != null) return sgf;
    }
    IOException last = null;
    for (String tpl : KIFU_ENDPOINTS) {
      String url = String.format(tpl, chessId);
      try {
        String sgf = tryDownload(url);
        if (sgf != null) return sgf;
      } catch (IOException e) {
        last = e;
      }
    }
    throw last != null

        ? last
        : new IOException("无法获取棋谱数据（可能接口已变更，请更新端点）");
  }

  private static String tryDownload(String url) throws IOException {
    byte[] bytes = httpGet(url, "https://www.foxwq.com/");
    if (bytes == null || bytes.length == 0) return null;
    byte[] plain = maybeGunzip(bytes);
    String text = decode(plain);
    // 1) 接口返回 JSON：{"result":0,"chessid":"…","chess":"(;GM[1]…"}
    String fromJson = extractJsonString(text, "chess");
    if (fromJson != null) {
      String sgf = extractSgf(fromJson);
      if (sgf != null) return sgf;
    }
    // 2) 直接返回 SGF / 夹杂 SGF 的其他格式
    return extractSgf(text);
  }

  /** 从 JSON 文本中取某个字符串字段（支持 \\" \\\\ \\/ \\n \\uXXXX 转义） */
  static String extractJsonString(String json, String field) {
    if (json == null || field == null) return null;
    String key = "\"" + field + "\"";
    int i = json.indexOf(key);
    if (i < 0) return null;
    int colon = json.indexOf(':', i + key.length());
    if (colon < 0) return null;
    int p = colon + 1;
    while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
    if (p >= json.length() || json.charAt(p) != '"') return null;
    p++;
    StringBuilder sb = new StringBuilder();
    while (p < json.length()) {
      char ch = json.charAt(p++);
      if (ch == '"') return sb.toString();
      if (ch == '\\' && p < json.length()) {
        char e = json.charAt(p++);
        switch (e) {
          case 'n': sb.append('\n'); break;
          case 'r': sb.append('\r'); break;
          case 't': sb.append('\t'); break;
          case 'b': sb.append('\b'); break;
          case 'f': sb.append('\f'); break;
          case 'u':
            if (p + 4 <= json.length()) {
              try {
                sb.append((char) Integer.parseInt(json.substring(p, p + 4), 16));
                p += 4;
              } catch (NumberFormatException ignore) {
                sb.append('u');
              }
            }
            break;
          default: sb.append(e);
        }
      } else sb.append(ch);
    }
    return null;
  }

  /** 在返回内容里定位 SGF 起点 */
  static String extractSgf(String text) {
    if (text == null) return null;
    int i = text.indexOf("(;");
    if (i < 0) return null;
    String s = text.substring(i).trim();
    // 去掉尾部的 JSON/HTML 残留（SGF 以 ')' 结束）
    int end = s.lastIndexOf(')');
    if (end >= 0 && end < s.length() - 1) s = s.substring(0, end + 1);
    return s.startsWith("(;") ? s : null;
  }

  private static byte[] maybeGunzip(byte[] b) throws IOException {
    if (b.length > 2 && (b[0] & 0xff) == 0x1f && (b[1] & 0xff) == 0x8b) {
      GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(b));
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      int n;
      while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
      in.close();
      return out.toByteArray();
    }
    return b;
  }

  static String decode(byte[] bytes) {
    String utf8 = new String(bytes, Charset.forName("UTF-8"));
    // 野狐接口有时以 GBK 返回中文（标题/棋手名），UTF-8 解码会出现替换字符
    if (utf8.indexOf('\uFFFD') >= 0) {
      try {
        return new String(bytes, Charset.forName("GB18030"));
      } catch (Throwable ignore) {
        try {
          return new String(bytes, Charset.forName("GBK"));
        } catch (Throwable ignore2) {
        }
      }
    }
    return utf8;
  }

  private static String unescape(String s) {
    if (s == null) return "";
    try {
      return URLDecoder.decode(s.replace("&amp;", "&"), "UTF-8");
    } catch (Throwable ignore) {
      return s;
    }
  }

  private static byte[] httpGet(String url, String referer) throws IOException {
    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
    conn.setRequestMethod("GET");
    conn.setConnectTimeout(15000);
    conn.setReadTimeout(20000);
    conn.setInstanceFollowRedirects(true);
    conn.setRequestProperty("User-Agent", UA);
    conn.setRequestProperty("Accept", "*/*");
    if (referer != null) conn.setRequestProperty("Referer", referer);
    int code = conn.getResponseCode();
    if (code != 200) throw new IOException("HTTP " + code + " for " + url);
    InputStream in = conn.getInputStream();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    int n;
    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    in.close();
    conn.disconnect();
    return out.toByteArray();
  }
}
