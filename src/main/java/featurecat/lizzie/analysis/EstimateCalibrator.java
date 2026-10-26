package featurecat.lizzie.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/**
 * Calibrated territory estimation based on the estimate network (b10c384 nbt-tf2) output.
 *
 * <p>Counting model ("空 + 死子 + 提子"):
 *
 * <ul>
 *   <li>Points with the owning side's own stone: neither counted nor displayed.
 *   <li>Dead opponent stones inside your area: counted as "死子" for the capturer and displayed
 *       as the capturer's territory.
 *   <li>Empty points: strong ones (|ownership| &gt;= tHi) counted directly; uncertain ones
 *       (tLo..tHi) sorted by |ownership| descending and added until the target is reached.
 * </ul>
 *
 * <p>The target is derived from the first candidate's score lead (area basis, komi included) and
 * converted to an empty-point target:
 *
 * <pre>
 *   area = alive stones + empty points + dead opponent stones
 *   targetEmptyDiff = targetArea - (aliveB - aliveW) - (deadWhiteByBlack - deadBlackByWhite)
 * </pre>
 */
public class EstimateCalibrator {

  /** Result of one calibration run. */
  public static class Result {
    /** Final per-point assignment in [-1,1] (0 = neutral / own stone / not displayed). */
    public ArrayList<Double> map = new ArrayList<Double>();

    /** Target area difference (black area - white area) computed from the analysis lead. */
    public int targetArea;
    /** Rounded black-perspective lead (points, komi included). */
    public int targetLead;
    /** Target difference between the empty points of both sides. */
    public int targetEmpty;

    /** Black / white EMPTY points counted (dead stones NOT included). */
    public int blackEmpty;
    public int whiteEmpty;
    /** Black / white points INCLUDING dead opponent stones (kept for compatibility). */
    public int blackPoints;
    public int whitePoints;
    /** Dead white stones inside black's area (counted for black). */
    public int blackDeadEnemy;
    /** Dead black stones inside white's area (counted for white). */
    public int whiteDeadEnemy;
    /** Alive stone counts of both sides. */
    public int blackAlive;
    public int whiteAlive;
    /** Final empty-point difference (blackEmpty - whiteEmpty). */
    public int diff;
    /** Difference reached with the strong empty points only. */
    public int baseDiff;
    /** Number of uncertain (tLo..tHi) empty points that were accepted. */
    public int acceptedMid;
    /** Number of uncertain points left out. */
    public int droppedMid;
    /** Stop reason: BASE, EXACT, EXHAUSTED, NO_DATA. */
    public String stop = "NO_DATA";
    /** Thresholds used. */
    public double tHi;
    public double tLo;
    /** Komi used for conversion. */
    public double komi;
    /** True when the empty count matched the target exactly. */
    public boolean exact;
  }

  public static Result calibrate(
      ArrayList<Double> ownership,
      int width,
      int height,
      double blackLeadWithKomi,
      double komi,
      double tHi,
      double tLo,
      boolean includeKomi) {
    return calibrate(ownership, width, height, blackLeadWithKomi, komi, tHi, tLo, includeKomi, null);
  }

  /**
   * Runs the calibration.
   *
   * @param stoneColors 0 = empty, 1 = black stone, 2 = white stone; null means all empty
   */
  public static Result calibrate(
      ArrayList<Double> ownership,
      int width,
      int height,
      double blackLeadWithKomi,
      double komi,
      double tHi,
      double tLo,
      boolean includeKomi,
      int[] stoneColors) {
    Result r = new Result();
    r.tHi = tHi;
    r.tLo = tLo;
    r.komi = komi;
    int total = width * height;
    if (ownership == null || ownership.size() < total) {
      r.map = new ArrayList<Double>(Collections.nCopies(total, 0.0));
      r.stop = "NO_DATA";
      return r;
    }
    double lead = blackLeadWithKomi;
    r.targetLead = (int) Math.round(lead);
    r.targetArea = (int) Math.round(lead + (includeKomi ? komi : 0.0));

    ArrayList<Double> map = new ArrayList<Double>(Collections.nCopies(total, 0.0));
    final ArrayList<int[]> mid = new ArrayList<int[]>();
    int bEmpty = 0;
    int wEmpty = 0;
    int bDead = 0; // dead white stones counted for black
    int wDead = 0; // dead black stones counted for white
    int bAlive = 0;
    int wAlive = 0;

    for (int i = 0; i < total; i++) {
      double v = ownership.get(i);
      double av = Math.abs(v);
      int stone = stoneColors == null ? 0 : stoneColors[i];
      if (stone == 1) { // black stone
        if (av < tLo) continue;
        if (v > 0) {
          bAlive++; // own alive stone: neither counted nor displayed
        } else {
          // dead black stone: white's territory after removal (displayed as white)
          wDead++;
          map.set(i, -1.0);
        }
        continue;
      }
      if (stone == 2) { // white stone
        if (av < tLo) continue;
        if (v < 0) {
          wAlive++;
        } else {
          bDead++;
          map.set(i, 1.0);
        }
        continue;
      }
      // empty point
      if (av >= tHi) {
        int sign = v > 0 ? 1 : -1;
        map.set(i, (double) sign);
        if (sign > 0) bEmpty++;
        else wEmpty++;
      } else if (av >= tLo) {
        mid.add(new int[] {i, v > 0 ? 1 : -1});
      }
    }
    Collections.sort(
        mid,
        new Comparator<int[]>() {
          public int compare(int[] x, int[] y) {
            double vx = Math.abs(ownership.get(x[0]));
            double vy = Math.abs(ownership.get(y[0]));
            return Double.compare(vy, vx);
          }
        });

    r.blackEmpty = bEmpty;
    r.whiteEmpty = wEmpty;
    r.blackAlive = bAlive;
    r.whiteAlive = wAlive;
    r.blackDeadEnemy = bDead;
    r.whiteDeadEnemy = wDead;
    r.blackPoints = bEmpty + bDead;
    r.whitePoints = wEmpty + wDead;
    r.map = map;
    r.acceptedMid = 0;
    r.droppedMid = mid.size();

    // areaDiff = (aliveB - aliveW) + (emptyB - emptyW) + (deadWhiteForB - deadBlackForW)
    r.targetEmpty = r.targetArea - (bAlive - wAlive) - (bDead - wDead);
    r.diff = bEmpty - wEmpty;
    r.baseDiff = r.diff;

    if (r.diff == r.targetEmpty) {
      r.stop = "BASE";
      r.exact = true;
      return r;
    }

    for (int k = 0; k < mid.size(); k++) {
      int[] p = mid.get(k);
      if (p[1] > 0) bEmpty++;
      else wEmpty++;
      r.acceptedMid = k + 1;
      map.set(p[0], (double) p[1]);
      int d = bEmpty - wEmpty;
      if (d == r.targetEmpty) {
        r.exact = true;
        r.stop = "EXACT";
        break;
      }
      r.diff = d;
    }
    r.blackEmpty = bEmpty;
    r.whiteEmpty = wEmpty;
    r.diff = bEmpty - wEmpty;
    r.blackPoints = bEmpty + bDead;
    r.whitePoints = wEmpty + wDead;
    r.droppedMid = mid.size() - r.acceptedMid;
    if (!r.exact && !"EXHAUSTED".equals(r.stop)) r.stop = "EXHAUSTED";
    return r;
  }
}
