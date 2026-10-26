package com.seqwawa.seq.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class SeqTierTest {

    @Test
    void tierNamesAreReadWhateverTheirCase() {
        assertEquals(SeqTier.MYTHRIL, SeqTier.fromKey("mythril"));
        assertEquals(SeqTier.MYTHRIL, SeqTier.fromKey("MYTHRIL"));
        assertEquals(SeqTier.GOLD, SeqTier.fromKey(" Gold "));
    }

    @Test
    void anUnknownTierNameIsNoTier() {
        assertNull(SeqTier.fromKey(null));
        assertNull(SeqTier.fromKey(""));
        assertNull(SeqTier.fromKey("titanium"));
    }
}
