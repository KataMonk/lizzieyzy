package featurecat.lizzie.analysis;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.zip.GZIPInputStream;

/**
 * 弈客直播 HTTP 客户端。
 *
 * <p>旧版用 socket.io（rtgame.yikeweiqi.com），现已不可用；实测
 * {@code https://api.yikeweiqi.com/golive/dtl?id=<room>&flag=1} 仍可直接返回 JSON，
 * 其中 {@code Result.live.Content} 就是当前局面的 SGF。
 */
public class YikeLiveClient {

  private static final String UA =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36";

  /** 直播房间接口（旧版仍可用） */
  public static String liveUrl(String roomId) {
    return "https://api.yikeweiqi.com/golive/dtl?id=" + roomId + "&flag=1";
  }

  /**
   * 从弈客直播链接解析房间 id。
   *
   * <p>支持：
   *   - https://home.yikeweiqi.com/#/live/new-room/197418/0/0
   *   - https://home.yikeweiqi.com/#/live/room/18328/1/15630642
   *   - https://home.yikeweiqi.com/#/live/board/197418
   */
  public static String parseRoomId(String url) {
    if (url == null) return null;
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile(
                "yikeweiqi\\.com/(?:#/)?(?:live|golive)/(?:new-room|room|board)/(\\d+)")
            .matcher(url);
    if (m.find()) return m.group(1);
    return null;
  }

  /** 由直播链接直接得到 golive/dtl 接口地址（无法解析时返回 null） */
  public static String ajaxUrlFor(String url) {
    String id = parseRoomId(url);
    return id == null ? null : liveUrl(id);
  }
  /** 取回接口原始文本（JSON），供 OnlineDialog.parseSgf 使用。 */
  public static String fetch(String ajaxUrl) throws IOException {
    HttpURLConnection c = (HttpURLConnection) new URL(ajaxUrl).openConnection();
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
    int code = c.getResponseCode();
    if (code != 200) throw new IOException("HTTP " + code + " for " + ajaxUrl);
    String enc = c.getContentEncoding();
    InputStream in = c.getInputStream();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    int n;
    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    in.close();
    byte[] bytes = out.toByteArray();
    if (bytes.length > 2 && (bytes[0] & 0xff) == 0x1f && (bytes[1] & 0xff) == 0x8b) {
      GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(bytes));
      ByteArrayOutputStream out2 = new ByteArrayOutputStream();
      while ((n = gz.read(buf)) > 0) out2.write(buf, 0, n);
      gz.close();
      bytes = out2.toByteArray();
    } else if (enc != null && enc.toLowerCase(java.util.Locale.ROOT).contains("deflate")) {
      java.util.zip.InflaterInputStream inflate =
          new java.util.zip.InflaterInputStream(new ByteArrayInputStream(bytes));
      ByteArrayOutputStream out2 = new ByteArrayOutputStream();
      while ((n = inflate.read(buf)) > 0) out2.write(buf, 0, n);
      inflate.close();
      bytes = out2.toByteArray();
    }
    c.disconnect();
    String s = new String(bytes, Charset.forName("UTF-8"));
    if (s.indexOf('\uFFFD') >= 0) {
      s = new String(bytes, Charset.forName("GB18030"));
    }
    return s;
  }
}
