package com.fastcar.home;

/** Цветовые темы лаунчера. Только цвета: на скорость не влияют. */
final class Theme {
    static final String[] NAMES = {"Ночь", "Изумруд", "Закат", "Пурпур", "Графит"};
    // фон (3 цвета), акцент 1, акцент 2, обложка 1, обложка 2, свечение
    private static final int[][] T = {
            {0xFF070A18, 0xFF110C2C, 0xFF06101F, 0xFF3B82F6, 0xFF22D3EE, 0xFF7C3AED, 0xFFEC4899, 0xFF3B82F6},
            {0xFF04120E, 0xFF06201A, 0xFF03100D, 0xFF10B981, 0xFF2DD4BF, 0xFF059669, 0xFF84CC16, 0xFF10B981},
            {0xFF180A08, 0xFF2A0F12, 0xFF140A10, 0xFFF97316, 0xFFFB7185, 0xFFEF4444, 0xFFF59E0B, 0xFFF97316},
            {0xFF0E0718, 0xFF1D0B33, 0xFF0A0614, 0xFFA855F7, 0xFFEC4899, 0xFF7C3AED, 0xFFEC4899, 0xFFA855F7},
            {0xFF000000, 0xFF0B0B0D, 0xFF000000, 0xFFE5E7EB, 0xFF9CA3AF, 0xFF4B5563, 0xFF9CA3AF, 0xFFFFFFFF},
    };
    static int idx;
    static int[] bg = new int[3];
    static int acc1, acc2, cov1, cov2, glow;

    static void set(int i) {
        if (i < 0 || i >= T.length) i = 0;
        idx = i;
        int[] t = T[i];
        bg = new int[]{t[0], t[1], t[2]};
        acc1 = t[3]; acc2 = t[4]; cov1 = t[5]; cov2 = t[6]; glow = t[7];
    }

    static int preview(int i) { return T[i][3]; }
    static int preview2(int i) { return T[i][4]; }

    static {
        set(0);
    }
}
