package featurecat.lizzie.analysis;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Self test / verification tool for {@link EstimateCalibrator}.
 *
 * <p>Usage (from the package folder, so that the engine and weight files resolve):
 *
 * <pre>
 *   java -cp &lt;classes-or-jar&gt; featurecat.lizzie.analysis.EstimateCalibratorTest          // synthetic only
 *   java -cp &lt;classes-or-jar&gt; featurecat.lizzie.analysis.EstimateCalibratorTest real      // + real engine run
 * </pre>
 */
public class EstimateCalibratorTest {

  static int pass = 0;
  static int fail = 0;

  public static void main(String[] args) throws Exception {
    System.out.println("=== EstimateCalibrator synthetic tests ===");
    syntheticTests();
    territorySemanticsTest();
    deadBlackTest();
    if (args.length > 0 && args[0].equalsIgnoreCase("real")) {
      System.out.println();
      System.out.println("=== Real engine test (b10c384 ownership + b11c768 first-move lead) ===");
      realEngineTest();
    }
    System.out.println();
    System.out.println("RESULT: PASS=" + pass + " FAIL=" + fail);
    if (fail > 0) System.exit(1);
  }

  // ------------------------------------------------------------------ synthetic

  static ArrayList<Double> emptyGrid() {
    ArrayList<Double> g = new ArrayList<Double>();
    for (int i = 0; i < 19 * 19; i++) g.add(0.0);
    return g;
  }

  static ArrayList<Double> buildGrid() {
    ArrayList<Double> g = emptyGrid();
    // 40 black strong points
    for (int i = 0; i < 40; i++) g.set(i, 0.90);
    // 30 white strong points
    for (int i = 40; i < 70; i++) g.set(i, -0.80);
    // black uncertain points (0.45 .. 0.50) -> sorted last
    double[] bm = {0.50, 0.45};
    for (int i = 0; i < bm.length; i++) g.set(70 + i, bm[i]);
    // white uncertain points (0.56 .. 0.68) -> sorted first, used to reduce the difference
    double[] wm = {-0.68, -0.65, -0.62, -0.59, -0.56};
    for (int i = 0; i < wm.length; i++) g.set(80 + i, wm[i]);
    return g;
  }

  static void check(String name, EstimateCalibrator.Result r, String stop, int b, int w, int acc) {
    boolean ok =
        stop.equals(r.stop) && r.blackPoints == b && r.whitePoints == w && r.acceptedMid == acc;
    if (ok) {
      pass++;
      System.out.println("PASS " + name + " -> stop=" + r.stop + " B=" + r.blackPoints + " W="
          + r.whitePoints + " diff=" + r.diff + " target=" + r.targetArea + " acceptedMid="
          + r.acceptedMid + " droppedMid=" + r.droppedMid);
    } else {
      fail++;
      System.out.println("FAIL " + name + " expected stop=" + stop + " B=" + b + " W=" + w
          + " acc=" + acc + " but got stop=" + r.stop + " B=" + r.blackPoints + " W="
          + r.whitePoints + " acc=" + r.acceptedMid + " diff=" + r.diff + " target="
          + r.targetArea);
    }
  }

  static void syntheticTests() {
    ArrayList<Double> g = buildGrid();
    // base = 40 - 30 = 10
    // Case A: lead = -1.5 -> area target = round(-1.5 + 7.5) = 6 -> needs 4 white mid points
    EstimateCalibrator.Result a =
        EstimateCalibrator.calibrate(g, 19, 19, -1.5, 7.5, 0.7, 0.4, true);
    check("A exact-stop", a, "EXACT", 40, 34, 4);

    // Case B: lead = -5.5 -> target = round(-5.5 + 7.5) = 2, cannot be reached -> exhausted
    EstimateCalibrator.Result b =
        EstimateCalibrator.calibrate(g, 19, 19, -5.5, 7.5, 0.7, 0.4, true);
    check("B exhausted", b, "EXHAUSTED", 42, 35, 7);

    // Case C: lead = 2.5 -> target = 10 == base -> stop immediately
    EstimateCalibrator.Result c =
        EstimateCalibrator.calibrate(g, 19, 19, 2.5, 7.5, 0.7, 0.4, true);
    check("C base-hit", c, "BASE", 40, 30, 0);

    // Case D: thresholds 0.75 / 0.45 -> 0.45 black point no longer uncertain
    EstimateCalibrator.Result d =
        EstimateCalibrator.calibrate(g, 19, 19, -1.5, 7.5, 0.75, 0.45, true);
    check("D thresholds", d, "EXACT", 40, 34, 4);

    // Case E: includeKomi = false -> target = round(-1.5) = -1, unreachable (opposite dir)
    EstimateCalibrator.Result e =
        EstimateCalibrator.calibrate(g, 19, 19, -1.5, 7.5, 0.7, 0.4, false);
    boolean eOk = "EXHAUSTED".equals(e.stop) && !e.exact;
    if (eOk) { pass++; System.out.println("PASS E no-komi mode -> stop=" + e.stop + " diff=" + e.diff + " target=" + e.targetArea); }
    else { fail++; System.out.println("FAIL E no-komi mode -> stop=" + e.stop + " diff=" + e.diff + " target=" + e.targetArea); }
  }

  // ------------------------------------------------------------------ real engine

  /** A deliberately decisive position: solid black wall (columns A-D) vs white wall (P-T). */
  static List<String> playCommands() {
    List<String> cmds = new ArrayList<String>();
    String[] bcols = {"A", "B", "C", "D"};
    String[] wcols = {"P", "Q", "R", "S", "T"};
    for (String col : bcols) for (int r = 1; r <= 19; r++) cmds.add("play B " + col + r);
    for (String col : wcols) for (int r = 1; r <= 19; r++) cmds.add("play W " + col + r);
    return cmds;
  }

  static boolean blackToPlayAfterPlays() {
    int b = 0, w = 0;
    for (String cmd : playCommands()) {
      if (cmd.startsWith("play B")) b++;
      else w++;
    }
    return b == w;
  }

  static final String[] MOVES = {
    "D4", "D10", "Q4", "Q10", "Q16", "K4", "D16", "K16", "K10", "C6"
  }; // B D4, W D10, B Q4, W Q10, B Q16, W K4, B D16, W K16, B K10, W C6
  static final int KOMI = 5;
  static final double KOMI_VALUE = 7.5;

  static void realEngineTest() throws Exception {
    String engine = System.getProperty("lizzie.test.engine", "katago_opencl/katago.exe");
    String estModel =
        System.getProperty(
            "lizzie.test.estModel", "weights/kata1-tf2-b10c384-s2941M-d5872M.bin.gz");
    String estCfg = System.getProperty("lizzie.test.estCfg", "katago_configs/estimate.cfg");
    String mainModel =
        System.getProperty(
            "lizzie.test.mainModel", "weights/kata1-tf3-b11c768-s12501M-d6467M.bin.gz");
    String mainCfg = System.getProperty("lizzie.test.mainCfg", "katago_configs/default_gtp.cfg");

    if (!new File(engine).exists()) {
      System.out.println("SKIP real test: engine not found: " + new File(engine).getAbsolutePath());
      return;
    }

    System.out.println("position: B D4 Q4 Q16 D16 K10 / W D10 Q10 K4 K16 C6, komi " + KOMI_VALUE
        + ", black to play");
    ArrayList<Double> ownership = queryOwnership(engine, estModel, estCfg);
    if (ownership == null) {
      System.out.println("FAIL real test: no ownership data");
      fail++;
      return;
    }
    double min = 1e9, max = -1e9, sum = 0;
    for (double v : ownership) {
      min = Math.min(min, v);
      max = Math.max(max, v);
      sum += v;
    }
    System.out.println("ownership parsed: n=" + ownership.size() + " min=" + fmt(min) + " max="
        + fmt(max) + " sum=" + fmt(sum) + " (black-positive)");

    String leadModel = System.getProperty("lizzie.test.leadModel", mainModel);
    System.out.println("score-lead engine model: " + leadModel);
    double[] lead = queryFirstMoveLead(engine, leadModel, mainCfg);
    boolean blackToPlay = blackToPlayAfterPlays();
    double blackLead = blackToPlay ? lead[0] : -lead[0];
    System.out.println("first candidate: scoreLead=" + fmt(lead[1]) + " (side to move: "
        + (blackToPlay ? "black" : "white") + "), black-perspective lead=" + fmt(blackLead));

    EstimateCalibrator.Result r =
        EstimateCalibrator.calibrate(ownership, 19, 19, blackLead, KOMI_VALUE, 0.7, 0.4, true);
    System.out.println("calibrated: stop=" + r.stop + " exact=" + r.exact + " targetArea="
        + r.targetArea + " baseDiff=" + r.baseDiff + " B=" + r.blackPoints + " W="
        + r.whitePoints + " diff=" + r.diff + " acceptedMid=" + r.acceptedMid + " droppedMid="
        + r.droppedMid + " leadShown=" + fmt(r.diff - KOMI_VALUE));

    // Print the strongest black/white assigned points for a quick sanity check.
    List<String> lines = new ArrayList<String>();
    for (int y = 0; y < 19; y++) {
      StringBuilder sb = new StringBuilder();
      char[] letters = "ABCDEFGHJKLMNOPQRST".toCharArray();
      for (int x = 0; x < 19; x++) {
        int idx = x + y * 19;
        double v = ownership.get(idx);
        double m = r.map.get(idx);
        char c;
        if (m > 0) c = 'X';
        else if (m < 0) c = 'O';
        else if (Math.abs(v) >= 0.4) c = '+'; // uncertain but not needed
        else c = '.';
        sb.append(c);
      }
      lines.add(sb.toString());
    }
    System.out.println("final map (X=black point, O=white point, +=uncertain-not-counted):");
    for (String line : lines) System.out.println("  " + line);
  }

  static String fmt(double d) {
    return String.format(java.util.Locale.ENGLISH, "%.2f", d);
  }

  static ArrayList<Double> queryOwnership(String engine, String model, String cfg) throws Exception {
    List<String> out = runEngine(engine, model, cfg, "kata-raw-nn 0", 120);
    ArrayList<Double> vals = new ArrayList<Double>();
    boolean inOwn = false;
    int rows = 0;
    for (String line : out) {
      if (line.startsWith("whiteOwnership")) { inOwn = true; continue; }
      if (!inOwn) continue;
      String t = line.trim();
      if (t.isEmpty()) continue;
      String[] parts = t.split("\\s+");
      if (parts.length == 19) {
        try {
          for (String s : parts) vals.add(-Double.parseDouble(s));
          rows++;
        } catch (NumberFormatException ignore) {
        }
        if (rows == 19) break;
      } else if (rows > 0) break;
    }
    if (rows != 19) {
      System.out.println("WARN: only " + rows + " ownership rows parsed");
      return vals.isEmpty() ? null : vals;
    }
    return vals;
  }

  /** @return {blackLead, rawScoreLead, sideToMoveBlack?1:0} */
  static double[] queryFirstMoveLead(String engine, String model, String cfg) throws Exception {
    List<String> out = runEngine(engine, model, cfg, "kata-analyze 100", 45);
    double lead = 0;
    boolean found = false;
    for (String line : out) {
      if (!line.startsWith("info")) continue;
      String[] parts = line.trim().split("\\s+");
      for (int i = 0; i < parts.length - 1; i++) {
        if ("scoreLead".equals(parts[i])) {
          try {
            lead = Double.parseDouble(parts[i + 1]);
            found = true;
          } catch (NumberFormatException ignore) {
          }
          break;
        }
      }
      if (found) break;
    }
    System.out.println("  (raw kata-analyze scoreLead=" + fmt(lead) + ", found=" + found + ")");
    if (!found) System.out.println("WARN: no scoreLead found, using 0");
    return new double[] {lead, lead, 1};
  }

  /** Runs one GTP session and returns all stdout lines read within the wait time. */
  static List<String> runEngine(
      String engine, String model, String cfg, String lastCommand, int waitSeconds)
      throws Exception {
    ProcessBuilder pb = new ProcessBuilder(engine, "gtp", "-model", model, "-config", cfg);
    pb.directory(new File("."));
    Process p = pb.start();
    final List<String> out = java.util.Collections.synchronizedList(new ArrayList<String>());
    Thread reader = new Thread(new Runnable() {
      public void run() {
        try (java.io.BufferedReader r =
            new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
          String line;
          while ((line = r.readLine()) != null) out.add(line);
        } catch (Exception ignore) {
        }
      }
    });
    reader.setDaemon(true);
    reader.start();
    // Drain stderr as well: the GTP config may log to stderr, and an unread pipe would
    // fill up and block the engine (this was the cause of the earlier no-analysis hang).
    Thread errReader = new Thread(new Runnable() {
      public void run() {
        try (java.io.BufferedReader r =
            new java.io.BufferedReader(new java.io.InputStreamReader(p.getErrorStream()))) {
          String line;
          while ((line = r.readLine()) != null) {
            // discard
          }
        } catch (Exception ignore) {
        }
      }
    });
    errReader.setDaemon(true);
    errReader.start();
    BufferedWriter w = new BufferedWriter(new OutputStreamWriter(p.getOutputStream()));
    w.write("boardsize 19\n");
    w.write("komi " + KOMI_VALUE + "\n");
    w.write("clear_board\n");
    for (String playCmd : playCommands()) w.write(playCmd + "\n");
    w.write(lastCommand + "\n");
    w.flush();
    long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
    boolean enough = false;
    while (System.currentTimeMillis() < deadline) {
      Thread.sleep(500);
      synchronized (out) {
        int infoLines = 0;
        boolean ownDone = false;
        int rowCount = 0;
        boolean inOwn = false;
        for (String line : out) {
          if (line.startsWith("info")) infoLines++;
          if (line.startsWith("whiteOwnership")) { inOwn = true; continue; }
          if (inOwn) {
            if (line.trim().isEmpty()) continue;
            rowCount++;
          }
        }
        if (lastCommand.startsWith("kata-raw-nn") && rowCount >= 19) { enough = true; break; }
        if (lastCommand.startsWith("kata-analyze") && infoLines >= 3) { enough = true; break; }
      }
    }
    try { w.write("quit\n"); w.flush(); } catch (Exception ignore) {}
    Thread.sleep(800);
    p.destroyForcibly();
    return new ArrayList<String>(out);
  }
  /** New territory semantics: own stones are neither counted nor displayed. */
  static void territorySemanticsTest() {
    ArrayList<Double> g = emptyGrid();
    int[] stones = new int[19 * 19];
    // 10 alive black stones (ownership black-positive)
    for (int i = 0; i < 10; i++) { g.set(i, 0.90); stones[i] = 1; }
    // 5 dead white stones inside black area (ownership black-positive -> counted for black)
    for (int i = 10; i < 15; i++) { g.set(i, 0.90); stones[i] = 2; }
    // 10 empty black points
    for (int i = 20; i < 30; i++) g.set(i, 0.90);
    // 5 empty white points
    for (int i = 40; i < 45; i++) g.set(i, -0.90);
    // lead 12.5, komi 7.5 -> target area = 20; alive stones diff = 10 -> target territory = 10
    EstimateCalibrator.Result r =
        EstimateCalibrator.calibrate(g, 19, 19, 12.5, 7.5, 0.6, 0.4, true, stones);
    boolean ok =
        "BASE".equals(r.stop)
            && r.blackEmpty == 10
            && r.whiteEmpty == 5
            && r.blackPoints == 15
            && r.whitePoints == 5
            && r.blackDeadEnemy == 5
            && r.whiteDeadEnemy == 0
            && r.diff == 5
            && r.blackAlive == 10
            && r.whiteAlive == 0
            && r.targetArea == 20
            && r.targetEmpty == 5;
    if (ok) {
      pass++;
      System.out.println(
          "PASS F territory-semantics -> stop=" + r.stop + " B=" + r.blackPoints + " W="
              + r.whitePoints + " diff=" + r.diff + " alive=" + r.blackAlive + "/" + r.whiteAlive
              + " targetArea=" + r.targetArea + " targetEmpty=" + r.targetEmpty);
    } else {
      fail++;
      System.out.println(
          "FAIL F territory-semantics -> stop=" + r.stop + " B=" + r.blackPoints + " W="
              + r.whitePoints + " diff=" + r.diff + " alive=" + r.blackAlive + "/" + r.whiteAlive
              + " targetArea=" + r.targetArea + " targetEmpty=" + r.targetEmpty);
    }
  }
  /** Symmetric dead-stone case: dead black stones inside white's area. */
  static void deadBlackTest() {
    ArrayList<Double> g = emptyGrid();
    int[] stones = new int[19 * 19];
    // 8 alive white stones (white-positive ownership => v<0)
    for (int i = 0; i < 8; i++) { g.set(i, -0.9); stones[i] = 2; }
    // 4 dead black stones inside white area (ownership white => v<0)
    for (int i = 10; i < 14; i++) { g.set(i, -0.9); stones[i] = 1; }
    // 6 empty white points / 3 empty black points
    for (int i = 20; i < 26; i++) g.set(i, -0.9);
    for (int i = 30; i < 33; i++) g.set(i, 0.9);
    // lead: area target = round(lead + 7.5)
    EstimateCalibrator.Result r =
        EstimateCalibrator.calibrate(g, 19, 19, -1.0, 7.5, 0.6, 0.4, true, stones);
    // area target = round(6.5) = 7; alive 0 vs 8 -> -8; dead (0 - 4) -> -4
    // targetEmpty = 7 - (0-8) - (0-4) = 7 + 8 + 4 = 19; empty diff = 3 - 6 = -3 -> EXHAUSTED
    boolean ok =
        r.blackEmpty == 3
            && r.whiteEmpty == 6
            && r.whiteDeadEnemy == 4
            && r.blackAlive == 0
            && r.whiteAlive == 8
            && r.targetEmpty == 19
            && r.targetArea == 7
            && "EXHAUSTED".equals(r.stop);
    if (ok) {
      pass++;
      System.out.println(
          "PASS G dead-black -> B(empty)=" + r.blackEmpty + " W(empty)=" + r.whiteEmpty
              + " deadBlack=" + r.whiteDeadEnemy + " alive=" + r.blackAlive + "/" + r.whiteAlive
              + " targetArea=" + r.targetArea + " targetEmpty=" + r.targetEmpty + " stop=" + r.stop);
    } else {
      fail++;
      System.out.println(
          "FAIL G dead-black -> B(empty)=" + r.blackEmpty + " W(empty)=" + r.whiteEmpty
              + " deadBlack=" + r.whiteDeadEnemy + " alive=" + r.blackAlive + "/" + r.whiteAlive
              + " targetArea=" + r.targetArea + " targetEmpty=" + r.targetEmpty + " stop=" + r.stop);
    }
  }
}
