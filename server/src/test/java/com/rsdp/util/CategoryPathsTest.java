package com.rsdp.util;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class CategoryPathsTest {

    @ParameterizedTest
    @CsvSource({
        "DK, '[\"家具\",\"桌类\",\"书桌/写字台\"]'",
        "MT, '[\"家具\",\"卧室家具\",\"床垫\"]'",
        "MR, '[\"家居\",\"镜子\"]'",
        "RG, '[\"软装\",\"地毯\"]'",
        "PD, '[\"家具\",\"屏风/隔断\"]'",
        "CW, '[\"软装\",\"窗帘/窗饰\"]'"
    })
    void resolve_shouldMapExtendedCategoriesExplicitly(String code, String expected) {
        assertThat(CategoryPaths.resolve(code)).isEqualTo(expected);
    }
}
