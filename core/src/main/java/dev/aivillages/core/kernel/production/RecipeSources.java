package dev.aivillages.core.kernel.production;

import java.util.*;
import dev.aivillages.core.kernel.StrictJson;
import static dev.aivillages.core.kernel.Contracts.*;

/** Explicit supplied source policy, not a grant. Native current authority is required for every source. */
public record RecipeSources(List<ContainerRef> containers) {
    public RecipeSources {
        containers=List.copyOf(containers);
        if(containers.isEmpty() || containers.size()>2 || containers.stream().distinct().count()!=containers.size()
                || containers.stream().map(ContainerRef::dimension).distinct().count()!=1)throw new IllegalArgumentException("One or two explicit same-dimension sources required");
        containers=containers.stream().sorted(Comparator.comparing(c->StrictJson.canonical(RecipeChoice.container(c)))).toList();
    }
    public static RecipeSources single(ValidatedRequest request){if(!(request.request().arguments().get("source") instanceof ContainerValue c))throw new IllegalArgumentException("Explicit source required");return new RecipeSources(List.of(c.value()));}
    public void validate(ValidatedRequest request){var first=single(request).containers().getFirst();if(!containers.contains(first) || containers.stream().anyMatch(c->!c.dimension().equals(first.dimension())))throw new IllegalArgumentException("Supplied policy must contain the bound request source");}
}
