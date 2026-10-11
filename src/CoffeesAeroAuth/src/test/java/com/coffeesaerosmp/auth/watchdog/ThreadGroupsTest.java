package com.coffeesaerosmp.auth.watchdog;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ThreadGroupsTest {

    @Test
    void numberedPoolsCollapseToOneGroup() {
        assertEquals("DH-Worker-#", ThreadGroups.group("DH-Worker-3"));
        assertEquals("DH-Worker-#", ThreadGroups.group("DH-Worker-12"));
        assertEquals("Worker-Main-#", ThreadGroups.group("Worker-Main-7"));
        assertEquals("Server thread", ThreadGroups.group("Server thread"));
        assertEquals("pool-#-thread-#", ThreadGroups.group("pool-3-thread-14"));
        assertEquals("(unnamed)", ThreadGroups.group(""));
        assertEquals("DH-Worker-#", ThreadGroups.group(ThreadGroups.group("DH-Worker-9")), "grouping twice is stable");
    }

    @Test
    void topSumsGroupsAndOrdersByCpu() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("Server thread", 40_000_000_000L);
        m.put("DH-Worker-1", 20_000_000_000L);
        m.put("DH-Worker-2", 25_000_000_000L);
        m.put("Netty Epoll Server IO #3", 1_000_000_000L);
        m.put("idle", 0L);
        String s = ThreadGroups.top(m, 2);
        assertTrue(s.startsWith("DH-Worker-# 45.0s, Server thread 40.0s"), s);
        assertTrue(s.endsWith("(all threads 86.0s CPU)"), s);
    }
}
