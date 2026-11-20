package com.pimeorg.pime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Plays the four hand-drawn samples that HanziLookup's own test suite uses.
 * They are the only independent check that the Java port's stroke analysis,
 * score tables and matching stay faithful.
 */
public class HandwritingRecognizerTest {

    /** "x,y x,y|x,y ..." — strokes separated by |, points by space. */
    private static List<int[][]> parse(String strokes) {
        List<int[][]> result = new ArrayList<>();
        for (String stroke : strokes.split("\\|")) {
            String[] pairs = stroke.trim().split(" ");
            int[][] points = new int[pairs.length][];
            for (int i = 0; i < pairs.length; ++i) {
                String[] xy = pairs[i].split(",");
                points[i] = new int[]{
                        Integer.parseInt(xy[0].trim()),
                        Integer.parseInt(xy[1].trim()),
                };
            }
            result.add(points);
        }
        return result;
    }

    private static final String YI =
            "70,124 71,124 79,124 104,124 119,124 132,125 151,126 168,126 "
                    + "169,126 189,125 191,124 191,124";
    private static final String SHI =
            "76,127 77,127 84,127 97,128 119,128 125,129 138,130 147,130 "
                    + "153,131 154,131 158,131 162,131 167,131 168,131 169,131 169,131"
                    + "|129,60 129,62 128,74 128,102 128,118 129,143 130,162 130,170 "
                    + "130,178 131,184 131,188 131,193 131,196 131,198 131,203 131,203";
    private static final String YUAN =
            "86,65 98,66 146,69 152,69 161,69 166,69 170,68 170,68"
                    + "|47,97 48,97 54,97 89,103 117,104 146,101 169,100 176,98 "
                    + "180,98 184,98 189,98 193,98 195,98 195,98"
                    + "|103,109 103,110 99,132 91,156 70,180 56,190 53,192"
                    + "|143,105 143,106 142,114 140,134 138,149 138,160 138,167 "
                    + "140,174 144,182 150,186 155,190 161,193 166,194 172,196 "
                    + "188,197 193,197 197,197 206,197 206,196 207,196 208,196 "
                    + "208,194 204,182 203,174 202,174 202,175 202,176";
    private static final String QI =
            "76,32 76,33 75,37 73,43 70,51 67,58 64,66 61,72 57,77 52,82 50,85 50,85"
                    + "|68,58 69,58 76,58 90,59 100,60 110,62 118,62 132,62 136,62 "
                    + "141,62 145,62 146,62 148,62 148,62"
                    + "|68,95 69,95 77,96 96,96 105,96 110,96 126,97 144,98 "
                    + "146,98 154,98 156,98 156,98"
                    + "|59,126 60,126 67,126 90,130 107,131 120,132 134,132 149,132 "
                    + "151,132 156,132 158,133 158,134 156,142 154,147 153,155 "
                    + "152,160 151,166 150,172 150,179 150,183 150,186 150,190 "
                    + "151,194 152,199 156,204 158,206 162,209 167,213 171,215 "
                    + "175,216 184,220 192,222 196,223 200,224 204,225 208,225 "
                    + "210,225 214,225 218,223 218,222 216,214 214,208 214,207 214,207"
                    + "|79,147 82,148 87,155 91,161 91,161"
                    + "|124,148 123,148 116,155 110,162 108,164 108,164"
                    + "|73,175 75,175 88,178 98,180 104,180 111,182 117,182 122,182 125,182"
                    + "|100,148 100,151 102,172 102,195 103,204 103,211 104,216 104,220 104,224"
                    + "|94,189 93,189 81,204 72,210 71,210"
                    + "|109,192 112,194 120,199 132,208 133,210 133,210";

    /** 好 as Make Me a Hanzi draws it: six strokes, y down, 1024 canvas. */
    private static final int[][][] HAO_MEDIANS = {
            {{282, 112}, {307, 131}, {327, 167}, {264, 435}, {216, 579}, {235, 602}, {386, 706}, {411, 734}, {424, 767}},
            {{390, 344}, {417, 370}, {424, 384}, {422, 396}, {387, 539}, {338, 645}, {304, 693}, {260, 735}, {206, 773}, {137, 803}},
            {{59, 443}, {107, 466}, {373, 409}, {380, 399}},
            {{493, 244}, {517, 254}, {550, 256}, {680, 208}, {706, 201}, {743, 204}, {771, 231}, {765, 243}, {677, 354}, {674, 365}, {663, 364}},
            {{613, 370}, {637, 381}, {659, 401}, {674, 426}, {687, 468}, {711, 611}, {709, 734}, {692, 808}, {672, 841}, {648, 859}, {551, 815}},
            {{449, 516}, {504, 523}, {860, 473}, {906, 474}, {960, 488}},
    };

    /**
     * A hand is not a font: it places each stroke a little off, at a slightly
     * different length and angle, with a slow drift along the path. Analysis
     * used absolute thresholds, so the same 好 segmented differently depending
     * on how large it was drawn — drawn small its first stroke gained a spare
     * substroke and 她 overtook it.
     */
    @Test
    public void recognisesTheSameHandwritingAtAnySize() throws Exception {
        HandwritingRecognizer recognizer = recognizer();
        /* Each of these drew 好 as 她 or 妈 while analysis still used absolute
         * segment lengths; a slide back to those should fail here. */
        double[] sizes = {255, 110, 70};
        long[] seeds = {3, 2, 13};
        for (int i = 0; i < sizes.length; ++i) {
            List<HandwritingRecognizer.Match> matches =
                    recognizer.lookup(sloppyHao(sizes[i], seeds[i]), 8);
            assertEquals("drawn " + (int) sizes[i] + " units tall",
                    '好', matches.get(0).hanzi);
        }
    }

    /** 好 as a sloppy hand draws it, the skeleton's longer side spanning size. */
    private static List<int[][]> sloppyHao(double size, long seed) {
        final java.util.Random random = new java.util.Random(seed);
        final double spacing = Math.max(0.7, size / 64);
        List<int[][]> scaled = new ArrayList<>();
        for (int[][] stroke : HAO_MEDIANS) {
            /* Draw the skeleton as a finger would: a point every few units. */
            List<double[]> points = new ArrayList<>();
            points.add(new double[]{(stroke[0][0] - 59) * size / 901, (stroke[0][1] - 112) * size / 901});
            for (int i = 1; i < stroke.length; ++i) {
                final double x0 = (stroke[i - 1][0] - 59) * size / 901;
                final double y0 = (stroke[i - 1][1] - 112) * size / 901;
                final double x1 = (stroke[i][0] - 59) * size / 901;
                final double y1 = (stroke[i][1] - 112) * size / 901;
                final double segment = Math.hypot(x1 - x0, y1 - y0);
                final int steps = Math.max(1, (int) (segment / spacing));
                for (int step = 1; step <= steps; ++step) {
                    points.add(new double[]{x0 + (x1 - x0) * step / steps,
                            y0 + (y1 - y0) * step / steps});
                }
            }
            double centerX = 0;
            double centerY = 0;
            for (double[] point : points) {
                centerX += point[0] / points.size();
                centerY += point[1] / points.size();
            }
            final double angle = Math.toRadians(random.nextGaussian() * 3);
            final double stretch = 1 + random.nextGaussian() * 0.04;
            final double driftX = random.nextGaussian() * 0.012 * size;
            final double driftY = random.nextGaussian() * 0.012 * size;
            final double wobble = 0.01 * size * random.nextDouble();
            final double frequency = 0.6 + random.nextDouble() * 1.2;
            final double phase = random.nextDouble() * Math.PI * 2;
            int[][] result = new int[points.size()][];
            double total = 0;
            for (int i = 1; i < points.size(); ++i) {
                total += Math.hypot(points.get(i)[0] - points.get(i - 1)[0],
                        points.get(i)[1] - points.get(i - 1)[1]);
            }
            double travelled = 0;
            for (int i = 0; i < points.size(); ++i) {
                if (i > 0) {
                    travelled += Math.hypot(points.get(i)[0] - points.get(i - 1)[0],
                            points.get(i)[1] - points.get(i - 1)[1]);
                }
                final double x = (points.get(i)[0] - centerX) * stretch;
                final double y = (points.get(i)[1] - centerY) * stretch;
                result[i] = new int[]{
                        (int) Math.round(centerX + x * Math.cos(angle) - y * Math.sin(angle)
                                + driftX + random.nextGaussian() * 0.25),
                        (int) Math.round(centerY + x * Math.sin(angle) + y * Math.cos(angle)
                                + driftY + random.nextGaussian() * 0.25
                                + wobble * Math.sin(frequency * 2 * Math.PI * travelled / total + phase)),
                };
            }
            scaled.add(result);
        }
        return scaled;
    }

    private static HandwritingRecognizer recognizer() throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(
                "src/main/assets/hanzi/mmah.bin"));
        List<HanziData.Template> templates = HanziData.parseTemplates(data);
        assertEquals(9507, templates.size());
        return new HandwritingRecognizer(templates);
    }

    @Test
    public void bundlesTheWholeTemplateSet() throws Exception {
        recognizer();
    }

    @Test
    public void recognisesSimpleCharacters() throws Exception {
        HandwritingRecognizer recognizer = recognizer();
        assertEquals('一', recognizer.lookup(parse(YI), 8).get(0).hanzi);
        assertEquals('十', recognizer.lookup(parse(SHI), 8).get(0).hanzi);
        assertEquals('氣', recognizer.lookup(parse(QI), 8).get(0).hanzi);
        List<HandwritingRecognizer.Match> yuan = recognizer.lookup(parse(YUAN), 8);
        assertEquals('元', yuan.get(1).hanzi);
        assertTrue(yuan.size() > 1);
    }

    /**
     * The pad reports a point per dp, which in the recogniser's 0..255 space is
     * under one unit apart — far denser than the mouse traces above. At that
     * density touch jitter used to be read as a corner at every wobble, so a
     * six-substroke character produced thirty, no template stayed within the
     * loosened stroke range, and the candidate list came back empty or wrong.
     */
    @Test
    public void recognisesDenselySampledInk() throws Exception {
        HandwritingRecognizer recognizer = recognizer();
        String[][] samples = {
                {"一", YI}, {"十", SHI}, {"元", YUAN}, {"氣", QI},
        };
        for (String[] sample : samples) {
            List<HandwritingRecognizer.Match> matches =
                    recognizer.lookup(denseJittered(parse(sample[1])), 8);
            int rank = -1;
            for (int i = 0; i < matches.size(); ++i) {
                if (matches.get(i).hanzi == sample[0].charAt(0)) rank = i + 1;
            }
            assertTrue(sample[0] + " came back at rank " + rank,
                    rank > 0 && rank <= 5);
        }
    }

    /** Samples the polyline every 0.7 units (the pad's spacing) plus touch jitter. */
    private static List<int[][]> denseJittered(List<int[][]> strokes) {
        java.util.Random random = new java.util.Random(11);
        final double spacing = 0.7;
        List<int[][]> result = new ArrayList<>();
        for (int[][] stroke : strokes) {
            List<double[]> path = new ArrayList<>();
            double total = 0;
            double[] cumulative = new double[stroke.length];
            path.add(new double[]{stroke[0][0], stroke[0][1]});
            for (int i = 1; i < stroke.length; ++i) {
                double dx = stroke[i][0] - stroke[i - 1][0];
                double dy = stroke[i][1] - stroke[i - 1][1];
                total += Math.sqrt(dx * dx + dy * dy);
                cumulative[i] = total;
                path.add(new double[]{stroke[i][0], stroke[i][1]});
            }
            List<int[]> dense = new ArrayList<>();
            int segment = 1;
            for (double at = 0; at <= total; at += spacing) {
                while (segment < path.size() - 1 && cumulative[segment] < at) ++segment;
                double from = cumulative[segment - 1];
                double length = cumulative[segment] - from;
                double t = length <= 0 ? 0 : (at - from) / length;
                double x = path.get(segment - 1)[0]
                        + (path.get(segment)[0] - path.get(segment - 1)[0]) * t;
                double y = path.get(segment - 1)[1]
                        + (path.get(segment)[1] - path.get(segment - 1)[1]) * t;
                dense.add(new int[]{
                        (int) Math.round(x + random.nextGaussian() * 0.25),
                        (int) Math.round(y + random.nextGaussian() * 0.25),
                });
            }
            result.add(dense.toArray(new int[0][]));
        }
        return result;
    }

    @Test
    public void firstStrokeBucketsFollowDirection() {
        assertEquals(HanziData.STROKE_HENG, HanziData.strokeBucket(0));
        assertEquals(HanziData.STROKE_HENG, HanziData.strokeBucket(128));
        assertEquals(HanziData.STROKE_SHU, HanziData.strokeBucket(192));
        assertEquals(HanziData.STROKE_PIE, HanziData.strokeBucket(160));
        assertEquals(HanziData.STROKE_DIAN, HanziData.strokeBucket(224));
    }
}
