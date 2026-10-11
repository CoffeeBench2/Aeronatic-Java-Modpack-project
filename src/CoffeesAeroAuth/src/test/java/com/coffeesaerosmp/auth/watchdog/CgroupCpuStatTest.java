package com.coffeesaerosmp.auth.watchdog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CgroupCpuStatTest {

    @Test
    void parsesCgroupV2() {
        String v2 = "usage_usec 9876543210\nuser_usec 1\nsystem_usec 2\nnr_periods 1200\nnr_throttled 300\nthrottled_usec 4500000\n";
        CgroupCpuStat s = CgroupCpuStat.parse(v2);
        assertEquals(new CgroupCpuStat(1200, 300, 4_500_000), s);
    }

    @Test
    void parsesCgroupV1NanosecondsIntoMicroseconds() {
        String v1 = "nr_periods 50\nnr_throttled 7\nthrottled_time 2500000000\n";
        assertEquals(new CgroupCpuStat(50, 7, 2_500_000), CgroupCpuStat.parse(v1));
    }

    @Test
    void notACpuStatIsNull() {
        assertNull(CgroupCpuStat.parse("hello world\n"));
        assertNull(CgroupCpuStat.parse(null));
        assertNull(CgroupCpuStat.parse("usage_usec 5\n"));
    }

    @Test
    void deltasNeverGoNegative() {
        CgroupCpuStat a = new CgroupCpuStat(100, 10, 1000);
        CgroupCpuStat b = new CgroupCpuStat(160, 25, 4000);
        assertEquals(new CgroupCpuStat(60, 15, 3000), b.since(a));
        assertEquals(new CgroupCpuStat(0, 0, 0), a.since(b));
        assertEquals(b, b.since(null));
    }

    @Test
    void windowsLineEndingsAndJunkAreTolerated() {
        assertEquals(new CgroupCpuStat(3, 1, 9), CgroupCpuStat.parse("nr_periods 3\r\nbogus line here\r\nnr_throttled 1\r\nthrottled_usec 9\r\n"));
    }
}
