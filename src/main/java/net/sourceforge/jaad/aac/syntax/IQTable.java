package net.sourceforge.jaad.aac.syntax;

/**
 * Inverse quantization lookup table for all possible values from 0<sup>4/3</sup> to 8191<sup>4/3</sup>.
 *
 * <p>PATCHED COPY of {@code net.sourceforge.jaad.aac.syntax.IQTable} from JAAD 0.9.4
 * ({@code com.tianscar.javasound:jaad}, Apache License 2.0). The original literal table has 8191 entries (0 to
 * 8190), but the largest legal quantized value is 8191, so a spectral coefficient at the escape-code maximum threw
 * an {@code ArrayIndexOutOfBoundsException}. The table is computed at start-up with 8192 entries instead.
 * build.gradle repackages the JAAD jar without the original class, so this one is the only one
 * (see NOTICE).
 */
interface IQTable {

    float[] IQ_TABLE = create();

    private static float[] create() {
        float[] t = new float[8192];
        for (int i = 1; i < t.length; i++) {
            t[i] = (float) Math.pow(i, 4.0 / 3.0);
        }
        return t;
    }
}
