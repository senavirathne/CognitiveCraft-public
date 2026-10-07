package dev.aivillages.core.kernel;

import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;

/** I/O-free exact closure used before job assignment/trial publication. */
public final class JobArtifactPins {
    private JobArtifactPins() { }
    public static List<ArtifactRef> closure(ArtifactDescriptor root, ArtifactCatalog catalog) {
        var refs = new LinkedHashSet<ArtifactRef>();
        visit(root, catalog, refs, new HashSet<>(), 0);
        return List.copyOf(refs);
    }
    private static void visit(ArtifactDescriptor descriptor, ArtifactCatalog catalog,
                              Set<ArtifactRef> refs, Set<ArtifactRef> path, int depth) {
        if (depth > 8 || !path.add(descriptor.ref())) throw new IllegalArgumentException("Artifact cycle/depth");
        if (refs.add(descriptor.ref())) {
            if (refs.size() > 16) throw new IllegalArgumentException("Job artifact closure limit");
            for (ArtifactRef dependency : descriptor.dependencies())
                visit(catalog.find(dependency).orElseThrow(() -> new IllegalArgumentException("Missing dependency")),
                        catalog, refs, path, depth + 1);
        }
        path.remove(descriptor.ref());
    }
}
