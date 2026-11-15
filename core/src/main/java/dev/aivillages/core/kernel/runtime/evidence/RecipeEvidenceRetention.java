package dev.aivillages.core.kernel.runtime.evidence;

import dev.aivillages.core.kernel.WorldRetentionManager;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import static dev.aivillages.core.kernel.WorldRetentionManager.*;

/** Same serialized evidence owner and category budgets with the optional exact recipe journal. */
public final class RecipeEvidenceRetention implements Owner {
    private final Thread writer=Thread.currentThread();
    private final List<Owner> evidence;
    private WorldRetentionManager manager;

    private RecipeEvidenceRetention(DepositJournal deposits,NativeRecipeJournal recipes) {
        evidence=List.of(deposits.retentionOwnerForCurrentWriter(),recipes.retentionOwner());
        if(evidence.stream().anyMatch(owner->owner.serializedWriter().orElse(null)!=writer))
            throw new IllegalArgumentException("Recipe and deposit require one serialized evidence writer");
        deposits.capacity(amount->reserve(0,amount));
        recipes.capacity(amount->reserve(1,amount));
    }
    public static Owner bind(DepositJournal deposits,NativeRecipeJournal recipes){return new RecipeEvidenceRetention(deposits,recipes);}
    private void thread(){if(Thread.currentThread()!=writer)throw new IllegalStateException("Recipe evidence storage writer");}
    public String id(){return evidence.getFirst().id();}
    public Optional<Thread> serializedWriter(){return Optional.of(writer);}
    public void bindPolicy(WorldRetentionManager manager) {
        thread();
        if(this.manager!=null)throw new IllegalStateException("Evidence retention already bound");
        this.manager=Objects.requireNonNull(manager);
    }
    public long revision() throws IOException {return Math.addExact(evidence.getFirst().revision(),evidence.getLast().revision());}
    private static Amount combined(Amount a,Amount b) {return new Amount(Math.addExact(a.bytes(),b.bytes()),Math.addExact(a.records(),b.records()));}
    public Optional<Amount> admissionCeiling(){return Optional.of(combined(evidence.getFirst().admissionCeiling().orElseThrow(),evidence.getLast().admissionCeiling().orElseThrow()));}
    private boolean reserve(int child,Amount proposed) {
        thread();
        return manager!=null && manager.assessReplacement(id(),Category.EVIDENCE,
                combined(proposed,evidence.get(1-child).admissionCeiling().orElseThrow())).status()==Capacity.AVAILABLE;
    }
    /** Keep all original deposit IDs. Only the added protected files receive a nested key. */
    private Key combinedKey(Key key){return key.owner().equals(evidence.getLast().id())?new Key(id(),"recipe:"+key.id()):key;}
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
