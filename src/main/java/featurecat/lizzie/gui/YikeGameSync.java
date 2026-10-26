package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.YikeGameClient;
import featurecat.lizzie.rules.SGFParser;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * 弈客大厅对局实时同步器。
 *
 * <p>每隔几秒拉一次 {@code game/info}；检测到 {@code hands_count} 增加时
 * 自动重载最新 SGF，实现"对方一落子，棋盘自动跟一步"。
 */
public class YikeGameSync {

  private static Timer timer;
  private static String gameId;
  private static int lastHands = -1;
  private static volatile boolean running;

  public static boolean isRunning() {
    return running;
  }

  public static String currentGame() {
    return gameId;
  }

  public static int lastHands() {
    return lastHands;
  }

  public static void start(String id) {
    stop();
    gameId = id;
    lastHands = -1;
    running = true;
    int sec = 3;
    try {
      sec = Math.max(2, Integer.parseInt(System.getProperty("lizzie.yike.syncInterval", "3")));
    } catch (Throwable ignore) {
    }
    timer =
        new Timer(
            sec * 1000,
            evt -> {
              if (!running) return;
              tick();
            });
    timer.start();
    tick();
  }

  public static void stop() {
    running = false;
    if (timer != null) {
      timer.stop();
      timer = null;
    }
    lastHands = -1;
  }

  private static void tick() {
    final String id = gameId;
    if (id == null || id.isEmpty()) return;
    new Thread() {
      public void run() {
        try {
          String json = YikeGameClient.fetch(YikeGameClient.infoUrl(id));
          final YikeGameClient.GameItem it = YikeGameClient.parseInfo(json);
          if (it == null || it.sgf == null || it.sgf.isEmpty()) return;
          if (lastHands >= 0 && it.hands <= lastHands) return;
          final String sgf = YikeGameClient.sgfWithPlayers(it);
          lastHands = it.hands;
          SwingUtilities.invokeLater(
              new Runnable() {
                public void run() {
                  try {
                    SGFParser.loadFromString(sgf);
                    Lizzie.board.setMovelistAll();
                    if (Lizzie.leelaz != null && Lizzie.leelaz.isPondering()) {
                      Lizzie.leelaz.ponder();
                    }
                    if (Lizzie.frame != null) Lizzie.frame.refresh();
                  } catch (Throwable ignore) {
                  }
                }
              });
        } catch (Throwable ignore) {
        }
      }
    }.start();
  }
}
