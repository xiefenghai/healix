package com.healix.core.science.catalog;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 已审过的科普片库。服务端只返编码；文件在 {@code frontend-user/src/assets/science/}。
 * 同一套片给所有人，不按病种挑选。新增：这里加一行，前端放同名文件。
 */
public final class ScienceCatalog {

    public static final String DISCLAIMER = "内容仅供健康管理参考，不能替代医生的面对面评估。如有不适，请及时就医。";

    public record Clip(String code, String title) {}

    public static final List<Clip> CLIPS = List.of(new Clip("PEPPER_COLORS", "青椒为什么有不同颜色"));

    private ScienceCatalog() {}

    public static int total() {
        return CLIPS.size();
    }

    public static Optional<Clip> find(String code) {
        return CLIPS.stream().filter(c -> c.code().equals(code)).findFirst();
    }

    public static String title(String code) {
        return find(code).map(Clip::title).orElse("科普");
    }

    /** 按片库顺序取下一条还没看过的。没有随机。 */
    public static Clip nextUnwatched(Collection<String> watched) {
        for (Clip clip : CLIPS) {
            if (watched == null || !watched.contains(clip.code())) {
                return clip;
            }
        }
        return null;
    }

    /** 片库都看过后，按日期在全库轮播，所有人同一天同一条。 */
    public static Clip replay(LocalDate day) {
        LocalDate d = day == null ? LocalDate.EPOCH : day;
        int index = Math.floorMod(d.toEpochDay(), CLIPS.size());
        return CLIPS.get(index);
    }
}
