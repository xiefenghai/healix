package com.healix.common.result;

import java.util.List;

public record PageResult<T>(long total, List<T> items) {
}
