package com.healix.core.science.domain;

import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/** 某条科普第一次看完的日期。 */
@Getter
@Setter
public class ScienceWatch {
    private String clipCode;
    private LocalDate firstBizDate;
}
