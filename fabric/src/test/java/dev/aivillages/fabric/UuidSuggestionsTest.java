package dev.aivillages.fabric;

import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UuidSuggestionsTest {
    private static final UUID FIRST = UUID.fromString("a1234567-1234-1234-1234-123456789012");
    private static final UUID SECOND = UUID.fromString("b1234567-1234-1234-1234-123456789012");

    @Test void emptyPrefixReturnsCanonicalUniqueUuids() {
        var result = UuidSuggestions.suggest(new SuggestionsBuilder("", 0),
                List.of(SECOND, FIRST, FIRST)).join();
        assertEquals(List.of(FIRST.toString(), SECOND.toString()),
                result.getList().stream().map(Suggestion::getText).toList());
    }

    @Test void partialUuidIsCaseInsensitiveAndReplacesOnlyTheArgument() {
        String command = "aivillage kernel status A1234567-1234";
        int start = command.lastIndexOf(' ') + 1;
        var result = UuidSuggestions.suggest(new SuggestionsBuilder(command, start),
                List.of(FIRST, SECOND)).join();
        assertEquals(List.of(FIRST.toString()), result.getList().stream().map(Suggestion::getText).toList());
        assertEquals("aivillage kernel status " + FIRST, result.getList().getFirst().apply(command));
    }

    @Test void unmatchedPrefixAndEmptyScopedIdsReturnNothing() {
        assertTrue(UuidSuggestions.suggest(new SuggestionsBuilder("c123", 0), List.of(FIRST, SECOND))
                .join().isEmpty());
        assertTrue(UuidSuggestions.suggest(new SuggestionsBuilder("", 0), List.of()).join().isEmpty());
    }
}
