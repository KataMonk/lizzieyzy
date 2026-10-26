package featurecat.lizzie.analysis;

import featurecat.lizzie.Lizzie;
import featurecat.lizzie.gui.EngineFailedMessage;
import featurecat.lizzie.gui.LizzieFrame;
import featurecat.lizzie.gui.RemoteEngineData;
import featurecat.lizzie.gui.SetEstimateParam;
import featurecat.lizzie.rules.Board;
import featurecat.lizzie.rules.Movelist;
import featurecat.lizzie.rules.Stone;
import featurecat.lizzie.util.Utils;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.jdesktop.swingx.util.OS;

public class KataEstimate {
  public Process process;
  private final ResourceBundle resourceBundle = Lizzie.resourceBundle;
  // private boolean isShuttingdown = false;
  private BufferedReader inputStream;
  private BufferedOutputStream outputStream;
  private BufferedReader errorStream;

  // public boolean gtpConsole;
  private boolean hasResult = false;
  // private boolean isLoaded = false;
  // private Double threshold = Lizzie.config.estimateThreshold;
  private String engineCommand;
  // private List<String> commands;
  private int cmdNumber;
  // private int currentCmdNum;
  private ArrayDeque<String> cmdQueue;
  private ScheduledExecutorService executor;
  private ScheduledExecutorService executorErr;
  public String currentEnginename = "";
  ArrayList<Double> tempcount = new ArrayList<Double>();
  public int blackEatCount = 0;
  public int whiteEatCount = 0;
  public int blackPrisonerCount = 0;
  public int whitePrisonerCount = 0;
  boolean firstcount = true;
  private int numberofcount = 0;
  private int territoryCount = 0;
  // private boolean hasTempTerritory = false;
  private boolean canGetOwnership = false;
  private List<String> commands;
  //  private boolean first = true;
  private boolean isPreLoad;
  // 性能优化：节流与去重（同一局面不重复请求 raw-nn）
  private long lastRawNnSentTime = 0;
  private int lastRawNnMoveNumber = -1;
  private long lastRawNnStoneHash = 0;
  private double lastRawNnLow = -1;
  private double lastRawNnHigh = -1;

  private long currentStoneHash() {
    Stone[] st = Lizzie.board.getStones();
    long h = 1469598103934665603L;
    for (int i = 0; i < st.length; i++) {
      int v = st[i] == Stone.BLACK ? 1 : (st[i] == Stone.WHITE ? 2 : 0);
      h = (h ^ v) * 1099511628211L;
    }
    return h;
  }

  private boolean shouldSkipRawNn() {
    int mn = Lizzie.board.getData().moveNumber;
    long h = currentStoneHash();
    boolean samePos = (mn == lastRawNnMoveNumber && h == lastRawNnStoneHash);
    boolean sameTh =
        (Lizzie.config.estimateThreshold == lastRawNnLow
            && Lizzie.config.estimateHighThreshold == lastRawNnHigh);
    long now = System.currentTimeMillis();
    if (samePos && sameTh && hasResult) return true;
    if (samePos && sameTh && now - lastRawNnSentTime < 800) return true;
    lastRawNnSentTime = now;
    lastRawNnMoveNumber = mn;
    lastRawNnStoneHash = h;
    lastRawNnLow = Lizzie.config.estimateThreshold;
    lastRawNnHigh = Lizzie.config.estimateHighThreshold;
    return false;
  }

  private void repaintBoardOnly() {
    try {
      if (Lizzie.frame != null && Lizzie.frame.mainPanel != null) {
        Lizzie.frame.mainPanel.repaint();
        return;
      }
    } catch (Throwable ignore) {
    }
    Lizzie.frame.refresh();
  }

  public boolean useJavaSSH = false;
  public String ip;
  public String port;
  public String userName;
  public String password;
  public boolean useKeyGen;
  public String keyGenPath;
  public EstimateEngineSSHController javaSSH;
  public boolean javaSSHClosed = false;
  public boolean isNormalEnd = false;
  private boolean noGtp = false;
  // public boolean noread = false;

  public KataEstimate(boolean isPreLoad) throws IOException {
    cmdNumber = 1;
    // currentCmdNum = 0;
    cmdQueue = new ArrayDeque<>();
    noGtp = isPreLoad;
    // gtpConsole = true;
    engineCommand =
        Lizzie.config.useZenEstimate
            ? Lizzie.config.zenEstimateCommand
            : Lizzie.config.estimateCommand;
    this.isPreLoad = isPreLoad;
    RemoteEngineData remoteData = Utils.getEstimateEngineRemoteEngineData();
    this.useJavaSSH = remoteData.useJavaSSH;
    this.ip = remoteData.ip;
    this.port = remoteData.port;
    this.userName = remoteData.userName;
    this.password = remoteData.password;
    this.useKeyGen = remoteData.useKeyGen;
    this.keyGenPath = remoteData.keyGenPath;
    startEngine(engineCommand);
  }

  public void startEngine(String engineCommand) {
    currentEnginename = engineCommand;
    lastRawNnMoveNumber = -1;
    lastRawNnStoneHash = 0;
    hasResult = false;
    // isShuttingdown = false;
    commands = Utils.splitCommand(engineCommand);

    if (this.useJavaSSH) {
      this.javaSSH = new EstimateEngineSSHController(this, this.ip, this.port, this.isPreLoad);
      boolean loginStatus = false;
      if (this.useKeyGen) {
        loginStatus =
            this.javaSSH
                .loginByFileKey(this.engineCommand, this.userName, new File(this.keyGenPath))
                .booleanValue();
      } else {
        loginStatus =
            this.javaSSH.login(this.engineCommand, this.userName, this.password).booleanValue();
      }
      if (loginStatus) {
        this.inputStream = new BufferedReader(new InputStreamReader(this.javaSSH.getStdout()));
        this.outputStream = new BufferedOutputStream(this.javaSSH.getStdin());
        this.errorStream = new BufferedReader(new InputStreamReader(this.javaSSH.getSterr()));
        javaSSHClosed = false;
      } else {
        javaSSHClosed = true;
        return;
      }
    } else {
      ProcessBuilder processBuilder = new ProcessBuilder(commands);
      processBuilder.redirectErrorStream(true);
      try {
        process = processBuilder.start();
      } catch (IOException e) {
        // TODO Auto-generated catch block
        showErrMsg(e);
        process = null;
        return;
      }
      initializeStreams();
    }
    executor = Executors.newSingleThreadScheduledExecutor();
    executor.execute(this::read);
    executorErr = Executors.newSingleThreadScheduledExecutor();
    executorErr.execute(this::readError);
    if (!Lizzie.config.useZenEstimate) {
      if (Lizzie.config.estimateArea) {
        sendCommand("kata-set-rules chinese");
      } else {
        sendCommand("kata-set-rules japanese");
      }

      //      sendCommand("clear_board");
      //      boardSize(Lizzie.board.boardWidth, Lizzie.board.boardHeight);
      //      sendCommand("kata-raw-nn 0");
    }
  }

  private void showErrMsg(Exception e) {
    if (isPreLoad) return;
    String errMas = "";
    if (Lizzie.config.useZenEstimate) {
      errMas = resourceBundle.getString("KataEstimate.errorHint2");
    } else {
      errMas = resourceBundle.getString("KataEstimate.errorHint"); // "加载形势判断引擎失败,请确认形势判断引擎设置正确");
    }
    String err = e.getLocalizedMessage();
    tryToDignostic(
        errMas
            + ": "
            + ((err == null)
                ? resourceBundle.getString("Leelaz.engineStartNoExceptionMessage")
                : err));
    if (Lizzie.frame.setEstimateParam == null || !Lizzie.frame.setEstimateParam.isVisible()) {
      Lizzie.frame.setEstimateParam = new SetEstimateParam();
      Lizzie.frame.setEstimateParam.setVisible(true);
    }
  }

  private void initializeStreams() {
    inputStream = new BufferedReader(new InputStreamReader(process.getInputStream()));
    outputStream = new BufferedOutputStream(process.getOutputStream());
    errorStream = new BufferedReader(new InputStreamReader(process.getErrorStream()));
  }

  private void readError() {
    String line = "";
    try {
      while ((line = errorStream.readLine()) != null) {
        try {
          parseLineForError(line);
        } catch (Exception e) {
          e.printStackTrace();
        }
      }
    } catch (IOException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }

  private void parseLineForError(String line) {
    if (!noGtp) Lizzie.gtpConsole.addErrorLine(line + "\n");
  }

  private void read() {
    try {
      int c;
      StringBuilder line = new StringBuilder();
      // while ((c = inputStream.read()) != -1) {
      while ((c = inputStream.read()) != -1) {
        line.append((char) c);
        if ((c == '\n')) {
          try {
            parseLine(line.toString());
          } catch (Exception ex) {
          }
          line = new StringBuilder();
        }
      }
      // this line will be reached when engine shuts down
      System.out.println("estimate process ended.");
      if (this.useJavaSSH) javaSSHClosed = true;
      if (!isNormalEnd) tryToDignostic(resourceBundle.getString("KataEstimate.errorHint"));
      shutdown();
      // Do no exit for switching weights
      // System.exit(-1);
    } catch (IOException e) {
      if (this.useJavaSSH) javaSSHClosed = true;
      showErrMsg(e);
      process = null;
      return;
    }
  }

  private void parseLine(String line) {
    synchronized (this) {
      //      if (line.startsWith("=  ")) {
      //        String[] params = line.trim().split(" ");
      //        // Lizzie.gtpConsole.addLineforce("这是详细点目第一行,21分参数");
      //        for (int i = 2; i < params.length; i++) tempcount.add(Integer.parseInt(params[i]));
      //      }
      // # 5 5 5 0 0 0 0 0 0 0 0 0 0 0 0 -4 -5 -5 -5
      if (Lizzie.config.useZenEstimate) {
        if (line.startsWith("#")) {
          //  if (first) first = false;
          String[] params = line.substring(1).trim().split("\\s+");
          if (params.length == Board.boardWidth) {
            for (int i = 0; i < params.length; i++) {
              try {
                Double temp = -Double.parseDouble(params[i]);
                if (temp >= 300 || temp <= -300) tempcount.add(-temp);
                else tempcount.add(0.0);
                // tempcount.add(-Double.parseDouble(params[i]));
              } catch (NumberFormatException ex) {
                tempcount.add(0.0);
              }
            }
          }
          if (tempcount.size() == Board.boardHeight * Board.boardWidth) {
            //   canGetOwnership = false;
            // if (first) first = false;
            // else
            territory();
            tempcount = new ArrayList<Double>();
            // 结束并显示
          }
        }
        if (!noGtp) Lizzie.gtpConsole.addLineEstimate(line);
      } else {
        if (line.startsWith("whiteOwnership")) {
          canGetOwnership = true;
          tempcount = new ArrayList<Double>();
        }

        if (canGetOwnership) {
          String[] params = line.trim().split("\\s+");
          if (params.length == Board.boardWidth) {
            for (int i = 0; i < params.length; i++) {
              try {
                Double temp = -Double.parseDouble(params[i]);
                if (temp >= Lizzie.config.estimateThreshold
                    || temp <= -Lizzie.config.estimateThreshold) tempcount.add(temp);
                else tempcount.add(0.0);
                // tempcount.add(-Double.parseDouble(params[i]));
              } catch (NumberFormatException ex) {
                tempcount.add(0.0);
              }
            }
          }
          if (tempcount.size() == Board.boardHeight * Board.boardWidth) {
            canGetOwnership = false;
            territory();
            // 结束并显示
          }
        }
        if (!noGtp) Lizzie.gtpConsole.addLineEstimate(line);
      }
      //      if (line.startsWith("#")) {
      //
      //        String[] params = line.substring(2).trim().split(" ");
      //        if (params.length == Lizzie.board.boardWidth) {
      //          for (int i = 0; i < params.length; i++) {
      //            int temp = Integer.parseInt(params[i]);
      //            if (temp >= threshold || temp <= -threshold) tempcount.add(temp);
      //            else tempcount.add(0);
      //          }
      //          // Lizzie.gtpConsole.addLineforce("这是详细点目");
      //        }
      //      }
      //      //  }
      //      if (line.startsWith("= territory")) {
      //        // String[] params = line.trim().split(" ");
      //        // if (params.length == 14) {
      //        // Lizzie.gtpConsole.addLineforce("这是点目结果");
      //        // if (noread) {
      //        //   numberofcount = 0;
      //        // } else {
      //        territory();
      //        // }
      //        //  }
      //      }
    }
  }

  private Double getRealCountWhite(int[] coords, Double count) {
    if (coords[0] >= 1) {
      int left = (coords[0] - 1) + coords[1] * Board.boardWidth;
      if (tempcount.get(left) >= 0) {
        return 0.0;
      }
    }
    if (coords[0] <= (Board.boardWidth - 2)) {
      int right = (coords[0] + 1) + coords[1] * Board.boardWidth;
      if (tempcount.get(right) >= 0) {
        return 0.0;
      }
    }
    if (coords[1] >= 1) {
      int top = coords[0] + (coords[1] - 1) * Board.boardWidth;
      if (tempcount.get(top) >= 0) {
        return 0.0;
      }
    }
    if (coords[1] <= (Board.boardHeight - 2)) {
      int bottom = coords[0] + (coords[1] + 1) * Board.boardWidth;
      if (tempcount.get(bottom) >= 0) {
        return 0.0;
      }
    }
    return count;
  }

  private Double getRealCountBlack(int[] coords, Double count) {
    if (coords[0] >= 1) {
      int left = (coords[0] - 1) + coords[1] * Board.boardWidth;
      if (tempcount.get(left) <= 0) {
        return 0.0;
      }
    }
    if (coords[0] <= (Board.boardWidth - 2)) {
      int right = (coords[0] + 1) + coords[1] * Board.boardWidth;
      if (tempcount.get(right) <= 0) {
        return 0.0;
      }
    }
    if (coords[1] >= 1) {
      int top = coords[0] + (coords[1] - 1) * Board.boardWidth;
      if (tempcount.get(top) <= 0) {
        return 0.0;
      }
    }
    if (coords[1] <= (Board.boardHeight - 2)) {
      int bottom = coords[0] + (coords[1] + 1) * Board.boardWidth;
      if (tempcount.get(bottom) <= 0) {
        return 0.0;
      }
    }
    return count;
  }

  private long lastCalibrateLogTime = 0;

  /**
   * 按“第一选点目差”校准归属统计：
   *
   * <p>|归属|>=estimateHighThreshold 的点直接计为 1 目；
   * estimateLowThreshold<=|归属|<estimateHighThreshold 的点按 |归属| 从大到小依次计入（每点 1 目），
   * 直到累计差值等于第一选点四舍五入后的目差即停止，剩余点视为单官/未定。
   *
   * @return true 表示已完成校准统计（不再走旧算法）
   */
  /**
   * 按“第一选点目差”校准归属统计（空/目口径）：
   *
   * <p>有己方棋子的点位不统计进己方目数、也不显示为己方领地（即统计的是“空”）；
   * 对方死子仍计入并显示为提子方的领地。空点中 |归属|>=高阈值 直接计 1 目，
   * 低阈值~高阈值之间按 |归属| 从大到小依次计入，直到累计目差等于目标即停止。
   */
  private boolean calibratedTerritory() {
    List<MoveData> bestMoves = Lizzie.board.getData().bestMoves;
    if (bestMoves == null || bestMoves.isEmpty()) return false;
    boolean blackToPlay = Lizzie.board.getHistory().isBlacksTurn();
    double scoreLead = bestMoves.get(0).scoreMean; // KataGo scoreLead（当前行棋方视角，含贴目）
    double blackLead = blackToPlay ? scoreLead : -scoreLead;
    double komi = Lizzie.board.getHistory().getGameInfo().getKomi();
    // 低阈值 = 设置对话框里的“KataGo阈值”（estimate-threshold，默认 0.4）；
    // 高阈值 = 设置对话框/config.txt 的 estimate-high-threshold（默认 0.6）。
    double lowThreshold = Lizzie.config.estimateThreshold;
    double highThreshold = Lizzie.config.estimateHighThreshold;
    if (highThreshold <= lowThreshold) highThreshold = Math.min(0.95, lowThreshold + 0.05);

    // 棋子颜色：0=空、1=黑子、2=白子（用于“己方棋子不计入己方目数”）
    int total = Board.boardWidth * Board.boardHeight;
    int[] stoneColors = new int[total];
    Stone[] stones = Lizzie.board.getStones();
    // 注意：Board 的 stones 数组是列优先（index = x * height + y），
    // 而归属图是行优先（index = x + y * width），必须用 Board.getCoord 转换，
    // 否则棋子颜色会错位（把己方棋子/空点误判为对方死子）。
    for (int i = 0; i < stones.length; i++) {
      int[] coords = Board.getCoord(i);
      int idx = coords[0] + coords[1] * Board.boardWidth;
      if (idx >= 0 && idx < total) {
        stoneColors[idx] = stones[i] == Stone.BLACK ? 1 : (stones[i] == Stone.WHITE ? 2 : 0);
      }
    }

    EstimateCalibrator.Result r =
        EstimateCalibrator.calibrate(
            tempcount,
            Board.boardWidth,
            Board.boardHeight,
            blackLead,
            komi,
            highThreshold,
            lowThreshold,
            Lizzie.config.estimateCalibrateIncludeKomi,
            stoneColors);
    if (r.map == null || r.map.isEmpty() || "NO_DATA".equals(r.stop)) return false;

    // 棋盘归属图（空点的最终归属；己方棋子点为 0 不显示；对方死子仍显示为提子方领地）
    LizzieFrame.boardRenderer.drawEstimateImage(r.map);
    if (Lizzie.frame.floatBoard != null && Lizzie.frame.floatBoard.isVisible())
      Lizzie.frame.floatBoard.boardRenderer.drawEstimateImage(r.map);
    repaintBoardOnly();
    hasResult = true;

    // 面板统计（空 + 死子 + 提子）：
    //   领地 = 空点（不含任何棋子）
    //   死子 = 己方区域内对方死子（黑方显示白死子，白方显示黑死子）
    //   差值 = 空 + 对方死子 + 提子
    int blackEat = Lizzie.board.getData().blackCaptures;
    int whiteEat = Lizzie.board.getData().whiteCaptures;
    int blackPointsParam;
    int whitePointsParam;
    int blackPrisonerParam;
    int whitePrisonerParam;
    if (Lizzie.config.estimateArea) {
      // 数子模式：领地=空+对方死子，活子单列；合计=活子+领地=面积
      blackPointsParam = r.blackEmpty + r.blackDeadEnemy;
      whitePointsParam = r.whiteEmpty + r.whiteDeadEnemy;
      blackPrisonerParam = 0;
      whitePrisonerParam = 0;
    } else {
      blackPointsParam = r.blackEmpty;
      whitePointsParam = r.whiteEmpty;
      blackPrisonerParam = r.whiteDeadEnemy; // 黑方死子（被白吃）
      whitePrisonerParam = r.blackDeadEnemy; // 白方死子（被黑吃）
    }
    estimatedCounts(
        r,
        blackPointsParam,
        whitePointsParam,
        r.blackAlive,
        r.whiteAlive,
        blackEat,
        whiteEat,
        blackPrisonerParam,
        whitePrisonerParam);
    return true;
  }

  private void estimatedCounts(
      EstimateCalibrator.Result r,
      int blackPoints,
      int whitePoints,
      int blackAlive,
      int whiteAlive,
      int blackEat,
      int whiteEat,
      int blackPrisoner,
      int whitePrisoner) {
    Lizzie.frame.estimateResults.calibratedMode = true;
    if (firstcount) {
      Lizzie.frame.estimateResults.Counts(
          blackEat,
          whiteEat,
          blackPrisoner,
          whitePrisoner,
          blackPoints,
          whitePoints,
          blackAlive,
          whiteAlive);
      if (!Lizzie.frame.estimateResults.isVisible()) {
        Lizzie.frame.estimateResults.showEstimate();
        if (Lizzie.frame.isAutocounting) Lizzie.frame.setVisible(true);
      } else if (!Lizzie.frame.isAutocounting) Lizzie.frame.estimateResults.setVisible(true);
      firstcount = false;
    } else {
      Lizzie.frame.estimateResults.Counts(
          blackEat,
          whiteEat,
          blackPrisoner,
          whitePrisoner,
          blackPoints,
          whitePoints,
          blackAlive,
          whiteAlive);
      if (!Lizzie.frame.estimateResults.isVisible()) {
        Lizzie.frame.estimateResults.showEstimate();
        if (Lizzie.frame.isAutocounting) Lizzie.frame.setVisible(true);
      } else if (!Lizzie.frame.isAutocounting) Lizzie.frame.estimateResults.setVisible(true);
    }
    // 记录校准统计，便于核对（每秒最多一条）
    long now = System.currentTimeMillis();
    if (now - lastCalibrateLogTime >= 1000) {
      lastCalibrateLogTime = now;
      try {
        java.io.FileWriter fw = new java.io.FileWriter("estimate-calibrate.txt", true);
        fw.write(
            new java.util.Date().toString()
                + " | targetArea="
                + r.targetArea
                + " | targetEmpty="
                + r.targetEmpty
                + " | targetLead="
                + r.targetLead
                + " | baseDiff="
                + r.baseDiff
                + " | B="
                + r.blackPoints
                + " W="
                + r.whitePoints
                + " | blackEmpty="
                + r.blackEmpty
                + " whiteEmpty="
                + r.whiteEmpty
                + " | deadWhite="
                + r.blackDeadEnemy
                + " deadBlack="
                + r.whiteDeadEnemy
                + " diff="
                + r.diff
                + " | blackAlive="
                + r.blackAlive
                + " whiteAlive="
                + r.whiteAlive
                + " | acceptedMid="
                + r.acceptedMid
                + " droppedMid="
                + r.droppedMid
                + " | stop="
                + r.stop
                + " exact="
                + r.exact
                + " | th="
                + r.tHi
                + "/"
                + r.tLo
                + " komi="
                + r.komi
                + "\r\n");
        fw.close();
      } catch (Throwable ignore) {
      }
    }
  }
  private void territory() {
    if (Lizzie.config.estimateCalibrate && !Lizzie.config.useZenEstimate) {
      try {
        if (calibratedTerritory()) {
          numberofcount = 0;
          return;
        }
      } catch (Throwable t) {
        // 校准统计失败时退回原有算法
        t.printStackTrace();
      }
    }
    blackEatCount = Lizzie.board.getData().blackCaptures;
    whiteEatCount = Lizzie.board.getData().whiteCaptures;
    blackPrisonerCount = 0;
    whitePrisonerCount = 0;
    int blackpoint = 0;
    int whitepoint = 0;
    int blackAlive = 0;
    int whiteAlive = 0;
    ArrayList<Double> tempCountForRender = new ArrayList<Double>();
    int limit = 0;
    for (Double counts : tempcount) {
      tempCountForRender.add(counts);
      limit++;
      if (limit >= Board.boardWidth * Board.boardHeight) break;
    }

    Stone[] stones = Lizzie.board.getStones();
    for (int i = 0; i < stones.length; i++) {
      int[] coords = Board.getCoord(i);
      int countOrder = coords[0] + coords[1] * Board.boardWidth;
      Double count = tempcount.get(countOrder);
      if (stones[i] == Stone.BLACK) {
        if (count < 0) {
          blackPrisonerCount++;
          whitepoint++;
        }
        if (count >= 0) {
          blackAlive++;
          if (count > 0) {
            tempCountForRender.set(countOrder, 0.0);
          }
        }
      }
      if (stones[i] == Stone.WHITE) {
        if (count > 0) {
          whitePrisonerCount++;
          blackpoint++;
        }
        if (count <= 0) {
          whiteAlive++;
          if (count < 0) {
            tempCountForRender.set(countOrder, 0.0);
          }
        }
      }
      if (stones[i] == Stone.EMPTY) {
        if (count > 0) {
          Double trueCount = getRealCountBlack(coords, count);
          tempCountForRender.set(countOrder, trueCount);
          if (trueCount > 0) {
            blackpoint++;
          }
        }
        if (count < 0) {
          Double trueCount = getRealCountWhite(coords, count);
          tempCountForRender.set(countOrder, trueCount);
          if (trueCount < 0) {
            whitepoint++;
          }
        }
      }
    }
    LizzieFrame.boardRenderer.drawEstimateImage(tempCountForRender);
    if (Lizzie.frame.floatBoard != null && Lizzie.frame.floatBoard.isVisible())
      Lizzie.frame.floatBoard.boardRenderer.drawEstimateImage(tempCountForRender);
    repaintBoardOnly();
    hasResult = true;
    if (firstcount) {
      Lizzie.frame.estimateResults.Counts(
          blackEatCount,
          whiteEatCount,
          blackPrisonerCount,
          whitePrisonerCount,
          blackpoint,
          whitepoint,
          blackAlive,
          whiteAlive);
      if (!Lizzie.frame.estimateResults.isVisible()) {
        Lizzie.frame.estimateResults.showEstimate();
        if (Lizzie.frame.isAutocounting) Lizzie.frame.setVisible(true);
      } else if (!Lizzie.frame.isAutocounting) Lizzie.frame.estimateResults.setVisible(true);
      firstcount = false;
    } else {
      Lizzie.frame.estimateResults.Counts(
          blackEatCount,
          whiteEatCount,
          blackPrisonerCount,
          whitePrisonerCount,
          blackpoint,
          whitepoint,
          blackAlive,
          whiteAlive);
      if (!Lizzie.frame.estimateResults.isVisible()) {
        Lizzie.frame.estimateResults.showEstimate();
        if (Lizzie.frame.isAutocounting) Lizzie.frame.setVisible(true);
      } else if (!Lizzie.frame.isAutocounting) Lizzie.frame.estimateResults.setVisible(true);
    }
    numberofcount = 0;
  }
  public void shutdown() {
    // isShuttingdown = true;
    isNormalEnd = true;
    if (this.useJavaSSH) this.javaSSH.close();
    else if (process != null && process.isAlive()) process.destroy();
  }

  public void sendAndEstimate(String command, boolean needVerify) {
    new Thread() {
      public void run() {
        if (!needVerify
            || command.startsWith("play")
            || command.startsWith("undo")
            || command.startsWith("clear")
            || command.startsWith("boardsize")
            || command.startsWith("rectan")) {
          sendCommand(command);
          countStones();
        }
      }
    }.start();
  }

  public void sendCommand(String command) {
    if (!cmdQueue.isEmpty()) {
      cmdQueue.removeLast();
    }
    cmdQueue.addLast(command);
    trySendCommandFromQueue();
  }

  private void trySendCommandFromQueue() {
    // synchronized (cmdQueue) {
    if (cmdQueue.isEmpty()) {
      return;
    }
    String command = cmdQueue.removeFirst();
    sendCommandToZen(command);
    // }
  }

  private void sendCommandToZen(String command) {
    // System.out.printf("> %d %s\n", cmdNumber, command);
    // try {
    if (!noGtp) Lizzie.gtpConsole.addEstimateCommand(command, cmdNumber);
    // } catch (Exception ex) {
    // }
    cmdNumber++;
    try {
      outputStream.write((command + "\n").getBytes());
      outputStream.flush();
    } catch (Exception e) {
      // e.printStackTrace();
    }
  }

  private void playmove(int x, int y, boolean isblack) {
    String coordsname = Board.convertCoordinatesToName(x, y);
    String color = isblack ? "b" : "w";

    sendCommand("play" + " " + color + " " + coordsname);
  }

  public void boardSize(int width, int height) {
    if (width != height) sendCommand("rectangular_boardsize " + width + " " + height);
    else sendCommand("boardsize " + width);
  }

  public void syncboradstat() {
    noGtp = false;
    sendCommand("clear_board");
    boardSize(Board.boardWidth, Board.boardHeight);
    cmdNumber = 1;
    ArrayList<Movelist> movelist = Lizzie.board.getMoveList();
    if (Lizzie.board.hasStartStone) {
      for (int i = 0; i < Lizzie.board.startStonelist.size(); i++) {
        movelist.add(Lizzie.board.startStonelist.get(i));
      }
    }
    int lenth = movelist.size();
    for (int i = 0; i < lenth; i++) {
      Movelist move = movelist.get(lenth - 1 - i);
      if (!move.ispass) {
        playmove(move.x, move.y, move.isblack);
      }
    }
  }

  public void countStones() {
    if (shouldSkipRawNn()) return;
    if (numberofcount > 0) {
      // if (hasTempTerritory) return;
      Runnable runnable =
          new Runnable() {
            public void run() {
              int territoryCountTemp = territoryCount;
              try {
                Thread.sleep(300);
                //  hasTempTerritory = false;
              } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
              }
              if (territoryCountTemp == territoryCount) {
                // tempcount = new ArrayList<Double>();
                territoryCount++;
                numberofcount++;
                blackEatCount = 0;
                whiteEatCount = 0;
                blackPrisonerCount = 0;
                whitePrisonerCount = 0;
                if (Lizzie.config.useZenEstimate) sendCommand("territory");
                else sendCommand("kata-raw-nn 0");
                // Lizzie.gtpConsole.addLineforce("count");
              }
            }
          };
      Thread thread = new Thread(runnable);
      thread.start();
      return;
    }
    territoryCount++;
    numberofcount++;
    // tempcount = new ArrayList<Double>();
    blackEatCount = 0;
    whiteEatCount = 0;
    blackPrisonerCount = 0;
    whitePrisonerCount = 0;
    if (Lizzie.config.useZenEstimate) sendCommand("territory");
    else sendCommand("kata-raw-nn 0");
    // Lizzie.gtpConsole.addLineforce("count");
    //
    //  sendCommand("score_statistics");
    if (!hasResult) {
      Runnable runnable2 =
          new Runnable() {
            public void run() {
              try {
                Thread.sleep(5000);
              } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
              }
              if (!hasResult) {
                Lizzie.frame.estimateResults.Counts(0, 0, 0, 0, 0, 0, 0, 0);
                if (!Lizzie.frame.estimateResults.isVisible()) {
                  Lizzie.frame.estimateResults.showEstimate();
                  if (Lizzie.frame.isAutocounting) Lizzie.frame.setVisible(true);
                } else if (!Lizzie.frame.isAutocounting)
                  Lizzie.frame.estimateResults.setVisible(true);
                firstcount = false;
                //  numberofcount = 0;
              }
            }
          };
      Thread thread2 = new Thread(runnable2);
      thread2.start();
    }
  }

  public void tryToDignostic(String message) {
    EngineFailedMessage engineFailedMessage =
        new EngineFailedMessage(
            commands, engineCommand, message, !this.useJavaSSH && OS.isWindows(), true, false);
    engineFailedMessage.setVisible(true);
  }
}
