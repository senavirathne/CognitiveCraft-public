package dev.aivillages.fabric;

import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Shared UUID formatting; callers supply identifiers from scoped owner APIs. */
final class UuidSuggestions {
    private UuidSuggestions() { }

    static CompletableFuture<Suggestions> suggest(SuggestionsBuilder builder, Collection<UUID> ids) {
        String prefix = builder.getRemainingLowerCase();
        ids.stream().map(UUID::toString).filter(id -> id.startsWith(prefix)).distinct()
                .forEach(builder::suggest);
        return builder.buildFuture();
    }
}
