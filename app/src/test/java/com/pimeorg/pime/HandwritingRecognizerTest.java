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

    @Test
    public void firstStrokeBucketsFollowDirection() {
        assertEquals(HanziData.STROKE_HENG, HanziData.strokeBucket(0));
        assertEquals(HanziData.STROKE_HENG, HanziData.strokeBucket(128));
        assertEquals(HanziData.STROKE_SHU, HanziData.strokeBucket(192));
        assertEquals(HanziData.STROKE_PIE, HanziData.strokeBucket(160));
        assertEquals(HanziData.STROKE_DIAN, HanziData.strokeBucket(224));
    }
}
