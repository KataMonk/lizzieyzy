package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.YikeGameClient;
import featurecat.lizzie.rules.SGFParser;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.List;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;

/**
 * 弈客大厅（对局列表）。
 *
 * <p>数据来源：{@code https://game-server.yikeweiqi.com/game/list}（Bearer token），
 * 双击任意一局 → 拉取 {@code game/info}（含 SGF）→ 载入棋盘。
 */
public class YikeLobbyDialog extends JDialog {

  private final DefaultListModel<YikeGameClient.GameItem> model =
      new DefaultListModel<YikeGameClient.GameItem>();
  private final JList<YikeGameClient.GameItem> list = new JList<YikeGameClient.GameItem>(model);
  private final JLabel lblStatus = new JLabel(" ");
  private final JLabel lblPage = new JLabel("第 1 页");
  private final JComboBox<String> cmbStatus = new JComboBox<String>();
  private final JButton btnPrev = new JButton("上一页");
  private final JButton btnNext = new JButton("下一页");
  private final JButton btnRefresh = new JButton("刷新");
  private final JButton btnStopSync = new JButton("停止同步");

  private boolean chinese;
  private int page = 1;
  private int totalPage = 1;

  public YikeLobbyDialog(java.awt.Window owner) {
    super(owner);
    chinese = Lizzie.config != null && Lizzie.config.isChinese;
    setTitle(chinese ? "弈客大厅（对局列表）" : "Yike Lobby");
    setModal(false);
    setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
    setLayout(new BorderLayout(6, 6));

    JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
    top.add(new JLabel(chinese ? "状态：" : "Status:"));
    cmbStatus.addItem(chinese ? "进行中" : "Playing");
    cmbStatus.addItem(chinese ? "已结束" : "Finished");
    cmbStatus.addItem(chinese ? "全部" : "All");
    top.add(cmbStatus);
    top.add(btnRefresh);
    top.add(btnPrev);
    top.add(btnNext);
    top.add(lblPage);
    add(top, BorderLayout.NORTH);

    list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    if (LizzieFrame.uiFont != null) list.setFont(LizzieFrame.uiFont);
    add(new JScrollPane(list), BorderLayout.CENTER);

    JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT));
    btnStopSync.setEnabled(false);
    bottom.add(btnStopSync);
    bottom.add(lblStatus);
    add(bottom, BorderLayout.SOUTH);

    btnStopSync.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            YikeGameSync.stop();
            btnStopSync.setEnabled(false);
            lblStatus.setText(chinese ? "已停止实时同步" : "Live sync stopped");
          }
        });
    btnRefresh.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            load(1);
          }
        });
    btnPrev.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            if (page > 1) load(page - 1);
          }
        });
    btnNext.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            if (page < totalPage) load(page + 1);
          }
        });
    cmbStatus.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            load(1);
          }
        });
    list.addMouseListener(
        new MouseAdapter() {
          public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() == 2) {
              YikeGameClient.GameItem it = list.getSelectedValue();
              if (it != null) importGame(it.id);
            }
          }
        });

    setSize(720, 480);
    setLocationRelativeTo(owner);
    if (YikeGameClient.token().isEmpty()) {
      lblStatus.setText(
          chinese
              ? "未配置弈客 token：请把 yike-token.txt（内容为 F12 抓到的 Bearer token）放到程序目录"
              : "Yike token missing: put yike-token.txt next to the program");
    }
    load(1);
  }

  private String statusParam() {
    int i = cmbStatus.getSelectedIndex();
    if (i == 0) return "processing";
    if (i == 1) return "finished";
    return "";
  }

  private void load(final int pageNo) {
    page = pageNo;
    lblStatus.setText(chinese ? "加载中…" : "Loading…");
    btnRefresh.setEnabled(false);
    new Thread() {
      public void run() {
        try {
          final String json =
              YikeGameClient.fetch(YikeGameClient.listUrl(pageNo, 20, statusParam()));
          final List<YikeGameClient.GameItem> items = YikeGameClient.parseList(json);
          SwingUtilities.invokeLater(
              new Runnable() {
                public void run() {
                  model.clear();
                  for (YikeGameClient.GameItem it : items) model.addElement(it);
                  if (!items.isEmpty()) list.setSelectedIndex(0);
                  totalPage = Math.max(1, pageNo + 50);
                  lblPage.setText((chinese ? "第 " : "Page ") + pageNo + (chinese ? " 页" : ""));
                  lblStatus.setText(
                      (chinese ? "共 " : "Total ")
                          + items.size()
                          + (chinese ? " 局（双击进入对局）" : " games (double-click)"));
                  btnRefresh.setEnabled(true);
                }
              });
        } catch (final IOException ex) {
          SwingUtilities.invokeLater(
              new Runnable() {
                public void run() {
                  lblStatus.setText((chinese ? "加载失败：" : "Load failed: ") + ex.getMessage());
                  btnRefresh.setEnabled(true);
                }
              });
        }
      }
    }.start();
  }

  private void importGame(final String id) {
    lblStatus.setText((chinese ? "载入对局 " : "Loading game ") + id + " …");
    new Thread() {
      public void run() {
        try {
          String json = YikeGameClient.fetch(YikeGameClient.infoUrl(id));
          final YikeGameClient.GameItem it = YikeGameClient.parseInfo(json);
          if (it == null || it.sgf == null || it.sgf.isEmpty()) {
            SwingUtilities.invokeLater(
                new Runnable() {
                  public void run() {
                    lblStatus.setText(chinese ? "该局暂无棋谱数据" : "No SGF for this game");
                  }
                });
            return;
          }
          final String sgf = YikeGameClient.sgfWithPlayers(it);
          SwingUtilities.invokeLater(
              new Runnable() {
                public void run() {
                  try {
                    SGFParser.loadFromString(sgf);
                    Lizzie.board.setMovelistAll();
                    if (Lizzie.leelaz != null && Lizzie.leelaz.isPondering()) Lizzie.leelaz.ponder();
                    if (Lizzie.frame != null) Lizzie.frame.refresh();
                    // 启动实时同步：检测到新落子自动跟一步
                    YikeGameSync.start(it.id);
                    btnStopSync.setEnabled(true);
                    lblStatus.setText(
                        (chinese ? "实时同步中：" : "Live sync: ")
                            + it.black + " vs " + it.white
                            + (chinese ? "（" : " (") + it.hands + (chinese ? " 手）" : " moves)"));
                  } catch (Exception e) {
                    lblStatus.setText(
                        (chinese ? "载入失败：" : "Load failed: ") + e.getMessage());
                  }
                }
              });
        } catch (final IOException ex) {
          SwingUtilities.invokeLater(
              new Runnable() {
                public void run() {
                  lblStatus.setText((chinese ? "载入失败：" : "Load failed: ") + ex.getMessage());
                }
              });
        }
      }
    }.start();
  }
}
