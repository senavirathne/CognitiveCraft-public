package dev.aivillages.core.kernel.storage;

import java.util.*;
import java.util.function.Supplier;
import static dev.aivillages.core.kernel.storage.StorageRegistry.*;

/** Client-safe existence/selection projection. It cannot expose stock, owner ACLs or hidden totals. */
public final class StorageVisibility {
    public sealed interface Lookup permits Visible,Unavailable { }
    public record Visible(UUID storage,String label,String adapter,long generation) implements Lookup { }
    public enum Unavailable implements Lookup { INSTANCE }
    private final Supplier<Snapshot> snapshots;private final StorageAccessPolicy policy;
    public StorageVisibility(Supplier<Snapshot> snapshots,StorageAccessPolicy policy) { this.snapshots=Objects.requireNonNull(snapshots);this.policy=Objects.requireNonNull(policy); }
    public Lookup lookup(StorageAccessPolicy.Scope scope,UUID storage,Permission operation) {
        try {var snapshot=snapshots.get();var row=snapshot.records().get(storage);
        if(row==null || policy.evaluate(scope,storage,operation).result()!=StorageAccessPolicy.Result.ALLOW || snapshots.get()!=snapshot)return Unavailable.INSTANCE;
        return new Visible(row.id(),row.label(),row.adapterId(),row.generation());
        }catch(RuntimeException unavailable){return Unavailable.INSTANCE;}
    }
    public List<Visible> enumerate(StorageAccessPolicy.Scope scope,Permission operation) { return matching(scope,operation,null); }
    public List<Visible> named(StorageAccessPolicy.Scope scope,Permission operation,String label) { return matching(scope,operation,StorageLabelFold.key(StorageRegistry.label(label))); }
    private List<Visible> matching(StorageAccessPolicy.Scope scope,Permission operation,String label) {
        try {var snapshot=snapshots.get();var result=new ArrayList<Visible>();
        for(var row:snapshot.records().values()) {
            if(label!=null && !StorageLabelFold.key(row.label()).equals(label))continue;
            if(policy.evaluate(scope,row.id(),operation).result()==StorageAccessPolicy.Result.ALLOW)result.add(new Visible(row.id(),row.label(),row.adapterId(),row.generation()));
        }
        if(snapshots.get()!=snapshot)return List.of();
        result.sort(Comparator.comparing(v->v.storage().toString()));return List.copyOf(result);
        }catch(RuntimeException unavailable){return List.of();}
    }
}
