package featurecat.lizzie.gui;

import featurecat.lizzie.Lizzie;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.util.ResourceBundle;
import javax.imageio.ImageIO;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;

/** 检查更新对话框：只显示当前版本号 + QQ 群下载提示。 */
public class CheckVersion extends JDialog {
  private final ResourceBundle resourceBundle = Lizzie.resourceBundle;

  public CheckVersion(boolean hasNewVersion, String remoteVersion, String newVersionDis) {
    this.setModal(true);
    setTitle(resourceBundle.getString("CheckVersion.titile"));
    setAlwaysOnTop(true);
    setResizable(false);
    if (Lizzie.frame != null) setLocationRelativeTo(Lizzie.frame);

    Lizzie.setFrameSize(this, 520, 160);

    try {
      this.setIconImage(ImageIO.read(MoreEngines.class.getResourceAsStream("/assets/logo.png")));
      Dimension screensize = Toolkit.getDefaultToolkit().getScreenSize();
      int x = (int) screensize.getWidth() / 2 - this.getWidth() / 2;
      int y = (int) screensize.getHeight() / 2 - this.getHeight() / 2;
      setLocation(x, y);
    } catch (Exception ignore) {
    }

    getContentPane().setLayout(null);

    // 只保留版本号
    JLabel label = new JLabel(Lizzie.lizzieVersion);
    label.setBounds(10, 10, 200, 24);
    getContentPane().add(label);

    // 下边：QQ 群下载提示
    JTextPane textPane = new JTextPane();
    textPane.setBackground(Color.WHITE);
    textPane.setEditable(false);
    textPane.setText(
        "可加QQ群在群文件中下载\r\n"
            + "QQ群号: 584641555(四群),246284327(三群),867298807(二,),786173361(一群)");
    JScrollPane scrollPane = new JScrollPane(textPane);
    scrollPane.setBounds(10, 44, 490, 68);
    getContentPane().add(scrollPane);
    textPane.setCaretPosition(0);
  }
}
