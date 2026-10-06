package com.sysj.collector.domain.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SystemConfigService} 类型化解析（静态部分）单元测试：
 * DB 值脏数据一律退回默认，绝不因配置错误打断采集主流程。
 */
class SystemConfigServiceParsingTest {

    @Test
    void boolParsing() {
        assertTrue(SystemConfigService.asBool("true", false));
        assertTrue(SystemConfigService.asBool("TRUE", false));
        assertTrue(SystemConfigService.asBool("1", false));
        assertFalse(SystemConfigService.asBool("false", true));
        assertFalse(SystemConfigService.asBool("yes", true), "非 0/1/true/false 一律退回默认");
        assertFalse(SystemConfigService.asBool(null, false));
        assertTrue(SystemConfigService.asBool(" ", true), "空白视为未配置，退回默认值");
    }

    @Test
    void longParsing() {
        assertEquals(60L, SystemConfigService.asLong("60", 1));
        assertEquals(60L, SystemConfigService.asLong(" 60 ", 1));
        assertEquals(1L, SystemConfigService.asLong("abc", 1));
        assertEquals(1L, SystemConfigService.asLong(null, 1));
    }

    @Test
    void doubleParsing() {
        assertEquals(50.0, SystemConfigService.asDouble("50", 1.0));
        assertEquals(0.7, SystemConfigService.asDouble("0.7", 1.0));
        assertEquals(1.0, SystemConfigService.asDouble("oops", 1.0));
    }
}
