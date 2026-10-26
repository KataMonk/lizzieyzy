package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.analysis.FoxKifuClient;
import featurecat.lizzie.rules.SGFParser;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;

/**
 * 野狐（腾讯围棋）棋谱窗口。
 *
 * <p>支持三种方式：
 *
 * <ul>
 *   <li>按昵称/关键字搜索（官网棋谱页搜索框，标题/棋手名）；
 *   <li>粘贴分享链接或 chessid 导入；
 *   <li>大赛棋谱列表。
 * </ul>
 */
public class FoxKifuImport extends JDialog {

  private final JTextField txtLink = new JTextField();
  private final JTextField txtKeyword = new JTextField();
  private final JButton btnImport = new JButton();
  private final JButton btnSearch = new JButton();
  private final JButton btnNextPage = new JButton();
  private final JButton btnList = new JButton();
  private final JLabel lblStatus = new JLabel(" ");

  private final DefaultListModel<FoxKifuClient.KifuInfo> listModel =
      new DefaultListModel<FoxKifuClient.KifuInfo>();
  private final JList<FoxKifuClient.KifuInfo> list = new JList<FoxKifuClient.KifuInfo>(listModel);

  private boolean chinese;
  private int page = 1;
  private String lastKeyword = "";

  public FoxKifuImport(java.awt.Window owner) {
    super(owner);
    chinese = Lizzie.config != null && Lizzie.config.isChinese;
    setTitle(chinese ? "野狐/腾讯棋谱" : "Fox / Tencent Kifu");
    setModal(false);
    setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
    setLayout(new BorderLayout(6, 6));

    JPanel top = new JPanel(new GridLayout(2, 1, 2, 2));

    // 第一行：昵称搜索
    JPanel row1 = new JPanel(new BorderLayout(4, 4));
    row1.add(new JLabel(chinese ? "昵称/关键字：" : "Player / keyword:"), BorderLayout.WEST);
    row1.add(txtKeyword, BorderLayout.CENTER);
    JPanel sBtn = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    btnSearch.setText(chinese ? "搜索棋谱" : "Search");
    btnNextPage.setText(chinese ? "下一页" : "Next page");
    btnNextPage.setEnabled(false);
    sBtn.add(btnSearch);
    sBtn.add(btnNextPage);
    row1.add(sBtn, BorderLayout.EAST);

    // 第二行：链接导入
    JPanel row2 = new JPanel(new BorderLayout(4, 4));
    row2.add(new JLabel(chinese ? "分享链接/chessid：" : "Link / chessid:"), BorderLayout.WEST);
    row2.add(txtLink, BorderLayout.CENTER);
    btnImport.setText(chinese ? "导入链接" : "Import link");
    row2.add(btnImport, BorderLayout.EAST);

    top.add(row1);
    top.add(row2);
    add(top, BorderLayout.NORTH);

    list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    if (LizzieFrame.uiFont != null) list.setFont(LizzieFrame.uiFont);
    add(new JScrollPane(list), BorderLayout.CENTER);

    JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT));
    btnList.setText(chinese ? "大赛棋谱列表" : "Latest kifu");
    bottom.add(btnList);
    bottom.add(lblStatus);
    add(bottom, BorderLayout.SOUTH);

    btnSearch.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            doSearch(txtKeyword.getText().trim(), 1);
          }
        });
    txtKeyword.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            doSearch(txtKeyword.getText().trim(), 1);
          }
        });
    btnNextPage.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            doSearch(lastKeyword, page + 1);
          }
        });
    btnImport.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            importChessId(FoxKifuClient.parseChessId(txtLink.getText()));
          }
        });
    txtLink.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            importChessId(FoxKifuClient.parseChessId(txtLink.getText()));
          }
        });
    btnList.addActionListener(
        new ActionListener() {
          public void actionPerformed(ActionEvent e) {
            loadLatest();
          }
        });
    list.addMouseListener(
        new MouseAdapter() {
          public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() == 2) {
              FoxKifuClient.KifuInfo info = list.getSelectedValue();
              if (info != null) importChessId(info.chessid);
            }
          }
        });

    setSize(600, 500);
    setLocationRelativeTo(owner);
  }

  private void setBusy(boolean busy) {
    btnSearch.setEnabled(!busy);
    btnList.setEnabled(!busy);
    btnImport.setEnabled(!busy);
    btnNextPage.setEnabled(!busy && lastKeyword != null && !lastKeyword.isEmpty());
  }

  private void doSearch(final String keyword, final int pageNo) {
    if (keyword == null || keyword.isEmpty()) {
      lblStatus.setText(chinese ? "请输入昵称或关键字" : "Enter a name / keyword");
      return;
    }
    // 昵称框里粘贴了分享链接/chessid 时，直接按链接导入（普通网棋只能这样载入）
    if (keyword.contains("chesid")
        || keyword.contains("chessid")
        || keyword.contains("foxwq.com")
        || keyword.matches("^\\s*\\d{5,25}\\s*$")) {
      String id = FoxKifuClient.parseChessId(keyword);
      if (id != null) {
        txtKeyword.setText("");
        importChessId(id);
        return;
      }
    }
    lastKeyword = keyword;
    page = pageNo;
    lblStatus.setText(chinese ? "搜索中…" : "Searching…");
    setBusy(true);
    new Thread() {
      public void run() {
        try {
          final FoxKifuClient.SearchResult r = FoxKifuClient.searchKifu(keyword, pageNo);
          SwingUtilities.invokeLater(
              new Runnable() {
                public void run() {
                  fill(r.items, pageNo, r.total);
                  if (r.total == 0) {
                    lblStatus.setText(
                        chinese
                            ? "未找到：官网搜索仅含公开/大赛棋谱；普通网棋请把分享链接粘贴到此处或第二行导入"
                            : "Not found: only public/tournament kifu are indexed; paste a share link to import net games");
                  } else {
                    lblStatus.setText(
                        (chinese ? "共 " : "Total ") + r.total
                            + (chinese ? " 盘 · 第 " : " games · page ") + pageNo
                            + (chinese ? " 页" : "")
                            + (chinese ? "（双击载入）" : " (double-click to load)"));
                  }
                  setBusy(false);
                  btnNextPage.setEnabled(true);
                }
              });
        } catch (final IOException ex) {
          SwingUtilities.invokeLater(
              new Runnable() {
                public void run() {
                  lblStatus.setText((chinese ? "搜索失败：" : "Search failed: ") + ex.getMessage());
                  setBusy(false);
                }
              });
        }
      }
    }.start();
  }

  private void loadLatest() {
    lastKeyword = "";
    page = 1;
    lblStatus.setText(chinese ? "加载大赛棋谱…" : "Loading latest kifu…");
    setBusy(true);
    new Thread() {
      public void run() {
        try {
          final java.util.List<FoxKifuClient.KifuInfo> items = FoxKifuClient.fetchLatestList(1);
          SwingUtilities.invokeLater(
              new Runnable() {
                public void run() {
                  fill(items, 1, items.size());
                  lblStatus.setText(
                      (chinese ? "大赛棋谱 " : "Latest kifu ") + items.size()
                          + (chinese ? " 盘（双击载入）" : " games (double-click to load)"));
                  setBusy(false);
                }
              });
        } catch (final IOException ex) {
          SwingUtilities.invokeLater(
              new Runnable() {
                public void run() {
                  lblStatus.setText((chinese ? "加载失败：" : "Load failed: ") + ex.getMessage());
                  setBusy(false);
                }
              });
        }
      }
    }.start();
  }

  private void fill(java.util.List<FoxKifuClient.KifuInfo> items, int pageNo, int total) {
    listModel.clear();
    for (FoxKifuClient.KifuInfo i : items) listModel.addElement(i);
    if (!items.isEmpty()) list.setSelectedIndex(0);
  }

  private void importChessId(final String chessId) {
    if (chessId == null || chessId.isEmpty()) {
      lblStatus.setText(chinese ? "无法识别棋谱编号（请输入链接或 chessid）" : "Cannot parse chess id");
      return;
    }
    lblStatus.setText((chinese ? "下载棋谱中… " : "Downloading… ") + chessId);
    btnImport.setEnabled(false);
    new Thread() {
      public void run() {
        try {
          final String sgf = FoxKifuClient.downloadSgf(chessId);
          SwingUtilities.invokeLater(
              new Runnable() {
                public void run() {
                  try {
                    SGFParser.loadFromString(sgf);
                    Lizzie.board.setMovelistAll();
                    if (Lizzie.leelaz != null && Lizzie.leelaz.isPondering()) Lizzie.leelaz.ponder();
                    lblStatus.setText(chinese ? "已载入：" + chessId : "Loaded: " + chessId);
                    if (Lizzie.frame != null) Lizzie.frame.refresh();
                    dispose();
                  } catch (Exception e) {
                    lblStatus.setText((chinese ? "SGF 载入失败：" : "SGF load failed: ") + e.getMessage());
                    btnImport.setEnabled(true);
                  }
                }
              });
        } catch (final IOException ex) {
          SwingUtilities.invokeLater(
              new Runnable() {
                public void run() {
                  lblStatus.setText((chinese ? "下载失败：" : "Download failed: ") + ex.getMessage());
                  btnImport.setEnabled(true);
                }
              });
        }
      }
    }.start();
  }
}
