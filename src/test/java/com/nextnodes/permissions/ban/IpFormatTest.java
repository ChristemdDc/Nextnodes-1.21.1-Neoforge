package com.nextnodes.permissions.ban;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IpFormatTest {
    @Test void acceptsValidIpv4() {
        assertTrue(IpFormat.looksLikeIp("1.2.3.4"));
        assertTrue(IpFormat.looksLikeIp("192.168.0.255"));
    }
    @Test void acceptsIpv6ish() {
        assertTrue(IpFormat.looksLikeIp("::1"));
        assertTrue(IpFormat.looksLikeIp("2001:db8::1"));
    }
    @Test void rejectsGarbage() {
        assertFalse(IpFormat.looksLikeIp("banana"));
        assertFalse(IpFormat.looksLikeIp("10.0.0.999"));
        assertFalse(IpFormat.looksLikeIp("1.2.3"));
        assertFalse(IpFormat.looksLikeIp(""));
        assertFalse(IpFormat.looksLikeIp(null));
        assertFalse(IpFormat.looksLikeIp("1.2.3.x"));
    }
}
