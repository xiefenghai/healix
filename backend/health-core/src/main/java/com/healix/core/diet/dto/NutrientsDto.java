package com.healix.core.diet.dto;

/** 营养合计；所有值保留 1 位小数展示精度，内部计算不截断。 */
public record NutrientsDto(
        double energyKcal, double carbG, double proteinG, double fatG, double fiberG, double sodiumMg) {

    public static final NutrientsDto ZERO = new NutrientsDto(0, 0, 0, 0, 0, 0);

    public NutrientsDto plus(NutrientsDto o) {
        return new NutrientsDto(
                energyKcal + o.energyKcal,
                carbG + o.carbG,
                proteinG + o.proteinG,
                fatG + o.fatG,
                fiberG + o.fiberG,
                sodiumMg + o.sodiumMg);
    }

    public NutrientsDto scale(double factor) {
        return new NutrientsDto(
                energyKcal * factor,
                carbG * factor,
                proteinG * factor,
                fatG * factor,
                fiberG * factor,
                sodiumMg * factor);
    }

    public NutrientsDto rounded() {
        return new NutrientsDto(r1(energyKcal), r1(carbG), r1(proteinG), r1(fatG), r1(fiberG), r1(sodiumMg));
    }

    private static double r1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
