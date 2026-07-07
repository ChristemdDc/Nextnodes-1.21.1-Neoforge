package com.nextnodes.tebex;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UuidUtilTest {
    @Test void dashedStaysCanonical() {
        assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5",
                UuidUtil.normalize("069A79F4-44E9-4726-A5BE-FCA90E38AAF5"));
    }
    @Test void undashedGetsDashes() {
        assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5",
                UuidUtil.normalize("069a79f444e94726a5befca90e38aaf5"));
    }
    @Test void invalidThrows() {
        assertThrows(IllegalArgumentException.class, () -> UuidUtil.normalize("not-a-uuid"));
        assertThrows(IllegalArgumentException.class, () -> UuidUtil.normalize(null));
        assertThrows(IllegalArgumentException.class, () -> UuidUtil.normalize("069a79f4"));
    }
}
