package dev.aivillages.core.kernel.runtime.evidence;

import dev.aivillages.core.kernel.WorldRetentionManager;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import static dev.aivillages.core.kernel.WorldRetentionManager.*;

/** Present all active native evidence journals under one serialized retention owner. */
public final class RecipeEvidenceRetention implements Owner {
    private final Thread writer=Thread.currentThread();
    private final List<Owner> evidence;
    private WorldRetentionManager manager;

    private RecipeEvidenceRetention(DepositJournal deposits,NativeRecipeJournal recipes,
                                    HarvestJournal harvest,PickupJournal pickup) {
        var owners=new ArrayList<Owner>();
        owners.add(deposits.retentionOwnerForCurrentWriter());
        owners.add(recipes.retentionOwner());
        if(pickup!=null) {
            var family=HarvestPickupRetention.bind(harvest,pickup);
            owners.add(family);
            family.bindCapacity(amount->reserve(2,amount));
        } else if(harvest!=null) {
            owners.add(harvest.retentionOwnerForCurrentWriter());
            harvest.capacity(amount->reserve(2,amount));
        }
        evidence=List.copyOf(owners);
        if(evidence.stream().anyMatch(owner->owner.serializedWriter().orElse(null)!=writer))
            throw new IllegalArgumentException("Native evidence requires one serialized writer");
        deposits.capacity(amount->reserve(0,amount));
        recipes.capacity(amount->reserve(1,amount));
    }
    public static Owner bind(DepositJournal deposits,NativeRecipeJournal recipes,HarvestJournal harvest,PickupJournal pickup) {
        return new RecipeEvidenceRetention(deposits,recipes,harvest,pickup);
    }
    private void thread(){if(Thread.currentThread()!=writer)throw new IllegalStateException("Recipe evidence storage writer");}
    public String id(){return evidence.getFirst().id();}
    public Optional<Thread> serializedWriter(){return Optional.of(writer);}
    public void bindPolicy(WorldRetentionManager manager) {
        thread();
        if(this.manager!=null)throw new IllegalStateException("Evidence retention already bound");
        this.manager=Objects.requireNonNull(manager);
    }
    public long revision() throws IOException {
        long revision=0;
        for(Owner owner:evidence)revision=Math.addExact(revision,owner.revision());
        return revision;
    }
    public Optional<Amount> admissionCeiling(){return Optional.of(ceiling(-1,new Amount(0,0)));}
    private Amount ceiling(int changed,Amount proposed) {
        long bytes=0,records=0;
        for(int index=0;index<evidence.size();index++) {
            Amount amount=index==changed?proposed:evidence.get(index).admissionCeiling().orElseThrow();
            bytes=Math.addExact(bytes,amount.bytes());
            records=Math.addExact(records,amount.records());
        }
        return new Amount(bytes,records);
    }
    private boolean reserve(int child,Amount proposed) {
        thread();
        return manager!=null && manager.assessReplacement(id(),Category.EVIDENCE,
                ceiling(child,proposed)).status()==Capacity.AVAILABLE;
    }
    /** Keep deposit keys and namespace the other children inside this owner. */
    private Key combinedKey(Key key) {
        for(int index=1;index<evidence.size();index++) {
            if(evidence.get(index).id().equals(key.owner()))
                return new Key(id(),(index==1?"recipe:":"acquisition:")+key.id());
        }
        return key;
    }
    private Entry combinedEntry(Entry source) {
        return new Entry(combinedKey(source.key()),source.category(),source.usage(),source.dependencies().stream().map(this::combinedKey).toList(),
                source.protectedRecord(),source.eligible(),source.viewers(),source.identity());
    }
    public Cursor open() throws IOException {
        long starting=revision();
        return new Cursor() {
            private int child;
            private Cursor active;
            public long revision(){return starting;}
            public Page next(int maximum,int bytes) throws IOException {
                List<Entry> result=new ArrayList<>();
                int inspected=0,read=0;
                boolean consistent=true;
                while(child<evidence.size() && inspected<maximum) {
                    if(active==null)active=evidence.get(child).open();
                    Page page=active.next(maximum-inspected,bytes-read);
                    if(page.inspected()>maximum-inspected || page.readBytes()>bytes-read)throw new IOException("Evidence child exceeded native slice");
                    for(var row:page.entries())result.add(combinedEntry(row));
                    inspected+=page.inspected();read+=page.readBytes();consistent&=page.valid();
                    if(!page.complete())break;
                    active.close(); active=null; child++;
                }
                return new Page(result,child==evidence.size(),consistent && starting==RecipeEvidenceRetention.this.revision() && (starting&1)==0,inspected,read);
            }
            public void close() throws IOException{if(active!=null)active.close();}
        };
    }
    public OwnerResult collect(Entry entry,long expected,BooleanSupplier cancel){return OwnerResult.of(OwnerStatus.PROTECTED);}
}
