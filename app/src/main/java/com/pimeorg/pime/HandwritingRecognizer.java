package com.pimeorg.pime;

import java.util.ArrayList;
import java.util.List;

/**
 * Recognises one hand-drawn character against the bundled stroke templates.
 *
 * <p>This is an independent Java implementation of the substroke matching
 * method used by HanziLookup (Jordan Kiang / hanzi_lookup): a stroke is split
 * at its corners, each piece becomes (direction, length, position), and the
 * input's pieces are aligned against a template's with a skip-tolerant score.
 * The templates themselves come from {@link HanziData}.
 *
 * <p>Runs on the UI thread after each stroke; a full pass over 9,507 templates
 * is a few million float operations, which is fast enough for a fingertip.
 */
final class HandwritingRecognizer {

    private static final int MAX_CHARACTER_STROKE_COUNT = 48;
    private static final int MAX_CHARACTER_SUB_STROKE_COUNT = 64;
    private static final float DEFAULT_LOOSENESS = 0.15f;
    private static final float AVG_SUBSTROKE_LENGTH = 0.33f;
    private static final float SKIP_PENALTY_MULTIPLIER = 1.75f;
    private static final float CORRECT_NUM_STROKES_BONUS = 0.1f;
    private static final float CORRECT_NUM_STROKES_CAP = 10f;
    /**
     * Corner detection compares distances between neighbouring points. The pad
     * samples every dp, which once the ink fills the working space below is
     * only a unit or two apart: touch jitter and rounding then dominate the
     * ratios, and every wobble is taken for a corner — a six-substroke
     * character explodes to thirty and no template is left in range. Points
     * are resampled to this fraction of the ink's longer side, the spacing the
     * method was tuned for (a mouse crossing a 256px canvas).
     */
    private static final float RESAMPLE_DIVISOR = 16f;
    /** Analysis stretches the ink so its longer side spans this much. */
    private static final float INK_SIDE = 255f;

    static final class Match {
        final char hanzi;
        final float score;

        Match(char hanzi, float score) {
            this.hanzi = hanzi;
            this.score = score;
        }
    }

    /** A substroke: direction and length in 0..255, centre in 0..15 per axis. */
    private static final class SubStroke {
        float direction;
        float length;
        float centerX;
        float centerY;
    }

    private final List<HanziData.Template> templates;
    private final float[] directionScoreTable = new float[256];
    private final float[] lengthScoreTable = new float[129];
    private final float[] positionScoreTable = new float[450];
    /** Ranges are the same for every template; precompute for each count. */
    private final int[] strokeRangeCache = new int[MAX_CHARACTER_STROKE_COUNT + 1];
    private final int[] subRangeCache = new int[MAX_CHARACTER_SUB_STROKE_COUNT + 1];
    private float[][] scoreMatrix = new float[0][0];

    HandwritingRecognizer(List<HanziData.Template> templates) {
        this.templates = templates;
        initScoreTables();
        for (int i = 0; i <= MAX_CHARACTER_STROKE_COUNT; ++i) {
            strokeRangeCache[i] = computeStrokesRange(i);
        }
        for (int i = 0; i <= MAX_CHARACTER_SUB_STROKE_COUNT; ++i) {
            subRangeCache[i] = computeSubStrokesRange(i);
        }
    }

    /**
     * @param strokes each entry is an array of {@code {x, y}} points in any
     *                uniform integer space — view pixels are fine; analysis
     *                stretches the ink to its own 0..255 working space
     * @param limit   how many characters to return
     */
    List<Match> lookup(List<int[][]> strokes, int limit) {
        List<Match> matches = new ArrayList<>(limit);
        if (limit <= 0 || strokes.isEmpty()) return matches;
        Analyzed analyzed = analyze(strokes);
        if (analyzed.subStrokes.isEmpty()) return matches;

        final int strokeCount = strokes.size();
        final int subCount = analyzed.subStrokes.size();
        final int strokeRange = strokeRangeCache[Math.min(strokeCount,
                MAX_CHARACTER_STROKE_COUNT)];
        final int minimumStrokes = Math.max(strokeCount - strokeRange, 1);
        final int maximumStrokes = Math.min(strokeCount + strokeRange,
                MAX_CHARACTER_STROKE_COUNT);
        final int subRange = subRangeCache[Math.min(subCount,
                MAX_CHARACTER_SUB_STROKE_COUNT)];
        final int minSubs = Math.max(subCount - subRange, 1);
        final int maxSubs = Math.min(subCount + subRange,
                MAX_CHARACTER_SUB_STROKE_COUNT);

        ensureMatrix(subCount, MAX_CHARACTER_SUB_STROKE_COUNT);
        for (HanziData.Template template : templates) {
            if (template.strokeCount < minimumStrokes || template.strokeCount > maximumStrokes) {
                continue;
            }
            final int templateSubs = template.subStrokeCount();
            if (templateSubs < minSubs || templateSubs > maxSubs) continue;
            float score = matchScore(analyzed.subStrokes, subRange, template, templateSubs);
            if (strokeCount == template.strokeCount
                    && strokeCount < CORRECT_NUM_STROKES_CAP) {
                float bonus = CORRECT_NUM_STROKES_BONUS
                        * Math.max(CORRECT_NUM_STROKES_CAP - strokeCount, 0f)
                        / CORRECT_NUM_STROKES_CAP;
                score += bonus * score;
            }
            fileMatch(matches, template.hanzi, score, limit);
        }
        return matches;
    }

    private void fileMatch(List<Match> matches, char hanzi, float score, int limit) {
        if (matches.size() == limit && score <= matches.get(matches.size() - 1).score) {
            return;
        }
        for (int i = 0; i < matches.size(); ++i) {
            if (matches.get(i).hanzi == hanzi) {
                if (score <= matches.get(i).score) return;
                matches.remove(i);
                break;
            }
        }
        int at = 0;
        while (at < matches.size() && matches.get(at).score >= score) ++at;
        matches.add(at, new Match(hanzi, score));
        if (matches.size() > limit) matches.remove(matches.size() - 1);
    }

    private float matchScore(List<SubStroke> input, int subRange,
                             HanziData.Template template, int templateSubs) {
        final byte[] subs = template.subStrokes;
        for (int x = 0; x < input.size(); ++x) {
            final SubStroke left = input.get(x);
            final int inputDirection = (int) Math.round(left.direction) & 0xFF;
            final int inputLength = (int) Math.round(left.length) & 0xFF;
            final int inputCenterX = (int) Math.round(left.centerX);
            final int inputCenterY = (int) Math.round(left.centerY);
            for (int y = 0; y < templateSubs; ++y) {
                float next = -Float.MAX_VALUE;
                if (Math.abs(x - y) <= subRange) {
                    final int templateDirection = subs[y * 3] & 0xFF;
                    final int templateLength = subs[y * 3 + 1] & 0xFF;
                    final int packed = subs[y * 3 + 2] & 0xFF;
                    final int templateCenterX = (packed >> 4) & 0x0F;
                    final int templateCenterY = packed & 0x0F;

                    final float skip1 = scoreMatrix[x][y + 1]
                            - (inputLength / 256f * SKIP_PENALTY_MULTIPLIER);
                    final float skip2 = scoreMatrix[x + 1][y]
                            - (templateLength / 256f * SKIP_PENALTY_MULTIPLIER);
                    final float skip = Math.max(skip1, skip2);
                    final float matched = scoreMatrix[x][y]
                            + subStrokeScore(inputDirection, inputLength,
                            templateDirection, templateLength,
                            inputCenterX, inputCenterY,
                            templateCenterX, templateCenterY);
                    next = Math.max(matched, skip);
                }
                scoreMatrix[x + 1][y + 1] = next;
            }
        }
        return scoreMatrix[input.size()][templateSubs];
    }

    private float subStrokeScore(int inputDirection, int inputLength,
                                 int templateDirection, int templateLength,
                                 int inputCenterX, int inputCenterY,
                                 int templateCenterX, int templateCenterY) {
        float direction = directionScoreTable[Math.abs(inputDirection - templateDirection)];
        if (inputLength < 64) {
            float maxBonus = Math.min(1f, 1f - direction);
            direction += maxBonus * (1f - inputLength / 64f);
        }
        final int ratio = inputLength > templateLength
                ? (int) Math.round(templateLength * 128f / inputLength)
                : (int) Math.round(inputLength * 128f / templateLength);
        float score = lengthScoreTable[Math.min(ratio, 128)] * direction;
        final int dx = inputCenterX - templateCenterX;
        final int dy = inputCenterY - templateCenterY;
        final float closeness = positionScoreTable[Math.min(dx * dx + dy * dy, 449)];
        return score > 0f ? score * closeness : score / closeness;
    }

    private void ensureMatrix(int inputSubs, int templateSubs) {
        final int rows = inputSubs + 1;
        final int columns = templateSubs + 1;
        if (scoreMatrix.length < rows || scoreMatrix[0].length < columns) {
            scoreMatrix = new float[Math.max(rows, 65)][Math.max(columns, 65)];
        }
        for (int i = 0; i < rows; ++i) {
            for (int j = 0; j < columns; ++j) {
                scoreMatrix[i][j] = 0f;
            }
        }
        for (int i = 0; i < rows; ++i) {
            float penalty = -AVG_SUBSTROKE_LENGTH * SKIP_PENALTY_MULTIPLIER * i;
            scoreMatrix[i][0] = penalty;
        }
        for (int i = 0; i < columns; ++i) {
            float penalty = -AVG_SUBSTROKE_LENGTH * SKIP_PENALTY_MULTIPLIER * i;
            scoreMatrix[0][i] = penalty;
        }
    }

    private void initScoreTables() {
        CubicCurve directionCurve = new CubicCurve(0f, 1f, 0.5f, 1f, 0.25f, -2f, 1f, 1f);
        sampleCurve(directionCurve, directionScoreTable);
        CubicCurve lengthCurve = new CubicCurve(0f, 0f, 0.25f, 1f, 0.75f, 1f, 1f, 1f);
        sampleCurve(lengthCurve, lengthScoreTable);
        for (int i = 0; i < positionScoreTable.length; ++i) {
            positionScoreTable[i] = 1f - (float) Math.sqrt(i) / 22f;
        }
    }

    private static void sampleCurve(CubicCurve curve, float[] table) {
        final float step = (curve.x2 - curve.x1) / table.length;
        float x = curve.x1;
        for (int i = 0; i < table.length; ++i) {
            float t = curve.firstSolutionForX(Math.min(x, curve.x2));
            table[i] = curve.yAt(t);
            x += step;
        }
    }

    /** The cubic curves grow slowly at first and quickly near the end. */
    private static int computeStrokesRange(int strokeCount) {
        CubicCurve curve = new CubicCurve(0f, 0f, 0.35f, strokeCount * 0.4f,
                0.6f, strokeCount, 1f, MAX_CHARACTER_STROKE_COUNT);
        float t = curve.firstSolutionForX(DEFAULT_LOOSENESS);
        return (int) Math.round(curve.yAt(t));
    }

    private static int computeSubStrokesRange(int subStrokeCount) {
        final float y0 = subStrokeCount * 0.25f;
        CubicCurve curve = new CubicCurve(0f, y0, 0.4f, 1.5f * y0,
                0.75f, 1.5f * 1.5f * y0, 1f, MAX_CHARACTER_SUB_STROKE_COUNT);
        float t = curve.firstSolutionForX(DEFAULT_LOOSENESS);
        return (int) Math.round(curve.yAt(t));
    }

    /* ---------------------------------------------------------------- */
    /* Stroke analysis: corners become substrokes.                       */
    /* ---------------------------------------------------------------- */

    private static final class Analyzed {
        final List<SubStroke> subStrokes = new ArrayList<>();
    }

    private static final class Rect {
        float top = Float.MAX_VALUE;
        float bottom = -Float.MAX_VALUE;
        float left = Float.MAX_VALUE;
        float right = -Float.MAX_VALUE;

        boolean isEmpty() {
            return top > bottom || left > right;
        }
    }

    private static Analyzed analyze(List<int[][]> strokes) {
        final Analyzed result = new Analyzed();
        final Rect bounds = boundingRect(strokes);
        if (bounds.isEmpty()) return result;
        /* The ink arrives in view pixels, and the same character is drawn
         * across a different number of them on the board and on the keyboard
         * pad. Analyses that blend segments under an absolute length are not
         * scale-free, so the same 好 segmented differently at different sizes
         * and 她 could overtake it. Stretch the ink to fill the working space
         * first: from here the analysis depends on the character's shape, not
         * on how large it happened to be drawn. */
        final float inkSide = Math.max(1f, Math.max(
                bounds.right - bounds.left, bounds.bottom - bounds.top));
        final float scale = INK_SIDE / inkSide;
        final List<int[][]> stretched = new ArrayList<>(strokes.size());
        for (int[][] stroke : strokes) {
            final int[][] points = new int[stroke.length][];
            for (int i = 0; i < stroke.length; ++i) {
                points[i] = new int[]{
                        Math.round((stroke[i][0] - bounds.left) * scale),
                        Math.round((stroke[i][1] - bounds.top) * scale),
                };
            }
            stretched.add(points);
        }
        final Rect inkBounds = boundingRect(stretched);
        final float spacing = INK_SIDE / RESAMPLE_DIVISOR;
        for (int[][] raw : stretched) {
            int[][] stroke = resample(raw, spacing);
            int[] pivots = pivotIndexes(stroke);
            for (int i = 1; i < pivots.length; ++i) {
                int[] a = stroke[pivots[i - 1]];
                int[] b = stroke[pivots[i]];
                double direction = direction(a, b);
                float normalizedDirection = (float) Math.round(
                        direction * 256.0 / Math.PI / 2.0);
                if (normalizedDirection >= 256f) normalizedDirection = 0f;
                SubStroke sub = new SubStroke();
                sub.direction = normalizedDirection;
                sub.length = Math.round(normalizedDistance(a, b, inkBounds) * 255f);
                sub.centerX = Math.round(normalizedCenterX(a, b, inkBounds) * 15f);
                sub.centerY = Math.round(normalizedCenterY(a, b, inkBounds) * 15f);
                result.subStrokes.add(sub);
            }
        }
        return result;
    }

    private static Rect boundingRect(List<int[][]> strokes) {
        Rect rect = new Rect();
        for (int[][] stroke : strokes) {
            for (int[] point : stroke) {
                rect.left = Math.min(rect.left, point[0]);
                rect.right = Math.max(rect.right, point[0]);
                rect.top = Math.min(rect.top, point[1]);
                rect.bottom = Math.max(rect.bottom, point[1]);
            }
        }
        return rect;
    }

    private static double distance(int[] a, int[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static double normalizedDistance(int[] a, int[] b, Rect rect) {
        double width = rect.right - rect.left;
        double height = rect.bottom - rect.top;
        double side = Math.max(width, height);
        double normalizer = Math.sqrt(side * side + side * side);
        return Math.min(distance(a, b) / normalizer, 1.0);
    }

    /** 0 is to the right, PI/2 is up, matching the template encoding. */
    private static double direction(int[] a, int[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        return Math.PI - Math.atan2(dy, dx);
    }

    private static float normalizedCenterX(int[] a, int[] b, Rect rect) {
        double width = rect.right - rect.left;
        double height = rect.bottom - rect.top;
        double side;
        double x = (a[0] + b[0]) / 2.0;
        double y = (a[1] + b[1]) / 2.0;
        if (width > height) {
            side = width;
            x = x - rect.left;
            y = y - rect.top + (side - height) / 2.0;
        } else {
            side = height;
            x = x - rect.left + (side - width) / 2.0;
            y = y - rect.top;
        }
        return (float) (x / side);
    }

    private static float normalizedCenterY(int[] a, int[] b, Rect rect) {
        double width = rect.right - rect.left;
        double height = rect.bottom - rect.top;
        double side;
        double x = (a[0] + b[0]) / 2.0;
        double y = (a[1] + b[1]) / 2.0;
        if (width > height) {
            side = width;
            x = x - rect.left;
            y = y - rect.top + (side - height) / 2.0;
        } else {
            side = height;
            x = x - rect.left + (side - width) / 2.0;
            y = y - rect.top;
        }
        return (float) (y / side);
    }

    /**
     * Walks the polyline and keeps a point every {@code spacing} units of arc
     * length, plus the first and last point. A stroke shorter than the spacing
     * survives as its two endpoints, so a 点 stays a short substroke instead of
     * disappearing.
     *
     * <p>Interior points then get a light [1 2 1] blur: a fingertip wobbles by
     * a pixel or two, and at this spacing that wobble is a sizeable fraction of
     * the ratio corner detection compares. The blur rounds corners by only a
     * fraction of the character's size and keeps those ratios below the
     * threshold.
     */
    private static int[][] resample(int[][] points, float spacing) {
        if (points.length <= 1) return points;
        final List<int[]> resampled = new ArrayList<>();
        resampled.add(points[0]);
        float carry = 0f;
        for (int i = 1; i < points.length; ++i) {
            final int[] from = points[i - 1];
            final int[] to = points[i];
            final double dx = to[0] - from[0];
            final double dy = to[1] - from[1];
            final double segment = Math.sqrt(dx * dx + dy * dy);
            if (segment <= 0) continue;
            double at = spacing - carry;
            while (at <= segment) {
                final double t = at / segment;
                final int x = (int) Math.round(from[0] + dx * t);
                final int y = (int) Math.round(from[1] + dy * t);
                final int[] last = resampled.get(resampled.size() - 1);
                if (x != last[0] || y != last[1]) resampled.add(new int[]{x, y});
                at += spacing;
            }
            carry = (float) (segment - (at - spacing));
        }
        final int[] last = resampled.get(resampled.size() - 1);
        final int[] end = points[points.length - 1];
        /* A tap has start == end; keep both points so the stroke still yields
         * one (zero-length) substroke instead of vanishing from the ink. */
        if (resampled.size() == 1 || end[0] != last[0] || end[1] != last[1]) {
            resampled.add(new int[]{end[0], end[1]});
        }
        final int[][] stroke = resampled.toArray(new int[0][]);
        final int[][] smoothed = stroke.clone();
        for (int i = 1; i + 1 < stroke.length; ++i) {
            smoothed[i] = new int[]{
                    (stroke[i - 1][0] + 2 * stroke[i][0] + stroke[i + 1][0] + 2) / 4,
                    (stroke[i - 1][1] + 2 * stroke[i][1] + stroke[i + 1][1] + 2) / 4,
            };
        }
        return smoothed;
    }

    private static int[] pivotIndexes(int[][] points) {
        if (points.length == 0) return new int[0];
        if (points.length == 1) return new int[]{0};
        final boolean[] markers = new boolean[points.length];
        markers[0] = true;
        int previous = 0;
        int first = 0;
        int pivot = 1;
        double localLength = distance(points[first], points[pivot]);
        double runningLength = localLength;
        for (int i = 2; i < points.length; ++i) {
            int[] next = points[i];
            double pivotLength = distance(points[pivot], next);
            localLength += pivotLength;
            runningLength += pivotLength;
            double fromPrevious = distance(points[previous], next);
            double fromFirst = distance(points[first], next);
            if (localLength > 1.1 * fromPrevious || runningLength > 1.09 * fromFirst) {
                if (markers[previous] && distance(points[previous], points[pivot]) < 12.5) {
                    markers[previous] = false;
                }
                markers[pivot] = true;
                runningLength = pivotLength;
                first = pivot;
            }
            localLength = pivotLength;
            previous = pivot;
            pivot = i;
        }
        markers[pivot] = true;
        if (markers[previous] && previous != 0
                && distance(points[previous], points[pivot]) < 12.5) {
            markers[previous] = false;
        }
        int count = 0;
        for (boolean marker : markers) if (marker) ++count;
        int[] result = new int[count];
        int at = 0;
        for (int i = 0; i < markers.length; ++i) {
            if (markers[i]) result[at++] = i;
        }
        return result;
    }

    /* ---------------------------------------------------------------- */
    /* Cubic Bezier solving, used only to precompute the score tables.   */
    /* ---------------------------------------------------------------- */

    private static final class CubicCurve {
        final float x1;
        final float y1;
        private final float ctrlX1;
        private final float ctrlY1;
        private final float ctrlX2;
        private final float ctrlY2;
        final float x2;
        final float y2;

        CubicCurve(float x1, float y1, float ctrlX1, float ctrlY1,
                   float ctrlX2, float ctrlY2, float x2, float y2) {
            this.x1 = x1;
            this.y1 = y1;
            this.ctrlX1 = ctrlX1;
            this.ctrlY1 = ctrlY1;
            this.ctrlX2 = ctrlX2;
            this.ctrlY2 = ctrlY2;
            this.x2 = x2;
            this.y2 = y2;
        }

        private float ax() {
            return x2 - x1 - bx() - cx();
        }

        private float ay() {
            return y2 - y1 - by() - cy();
        }

        private float bx() {
            return 3f * (ctrlX2 - ctrlX1) - cx();
        }

        private float by() {
            return 3f * (ctrlY2 - ctrlY1) - cy();
        }

        private float cx() {
            return 3f * (ctrlX1 - x1);
        }

        private float cy() {
            return 3f * (ctrlY1 - y1);
        }

        float firstSolutionForX(float x) {
            float[] roots = new float[3];
            int count = solveForX(x, roots);
            for (int i = 0; i < count; ++i) {
                float t = roots[i];
                if (t >= -0.0000001f && t <= 1.0000001f) {
                    if (t >= 0f && t <= 1f) return t;
                    return t < 0f ? 0f : 1f;
                }
            }
            return Float.NaN;
        }

        float yAt(float t) {
            float t2 = t * t;
            float t3 = t * t2;
            return ay() * t3 + by() * t2 + cy() * t + y1;
        }

        /** Cardano's method for x(t) = x; returns the number of real roots. */
        private int solveForX(float x, float[] roots) {
            final float a = ax();
            final float b = bx();
            final float c = cx();
            final float d = x1 - x;
            final float f = ((3f * c / a) - (b * b / (a * a))) / 3f;
            final float g = ((2f * b * b * b / (a * a * a)) - (9f * b * c / (a * a))
                    + (27f * d / a)) / 27f;
            final float h = (g * g / 4f) + (f * f * f / 27f);
            if (h > 0f) {
                float u = -g;
                float r = (u / 2f) + (float) Math.sqrt(h);
                float s = cuberoot(r);
                float t8 = (u / 2f) - (float) Math.sqrt(h);
                float v = cuberoot(-t8);
                roots[0] = (s - v) - (b / (3f * a));
                return 1;
            } else if (f == 0f && g == 0f && h == 0f) {
                roots[0] = (float) -Math.cbrt(d / a);
                return 1;
            } else {
                float i = (float) Math.sqrt((g * g / 4f) - h);
                float j = cuberoot(i);
                float k = (float) Math.acos(-g / (2f * i));
                float l = -j;
                float m = (float) Math.cos(k / 3f);
                float n = (float) (Math.sqrt(3.0) * Math.sin(k / 3f));
                float p = (b / (3f * a)) * -1f;
                roots[0] = 2f * j * (float) Math.cos(k / 3f) - (b / (3f * a));
                roots[1] = l * (m + n) + p;
                roots[2] = l * (m - n) + p;
                return 3;
            }
        }

        private static float cuberoot(float value) {
            if (value < 0f) return (float) -Math.cbrt(-value);
            return (float) Math.cbrt(value);
        }
    }
}
