package dev.aivillages.core.kernel.runtime.evidence;

import dev.aivillages.core.kernel.WorldRetentionManager;
import java.io.IOException;
import java.util.*;
import static dev.aivillages.core.kernel.WorldRetentionManager.*;

/**
 * One existing harvest owner slot covers every H/P journal file and a combined64-row ceiling.
 * Bind/register only on the shared storage worker. Not yet selected by runtime composition.
 */
public final class HarvestPickupRetention implements Owner {
    private final Thread writer=Thread.currentThread();private final HarvestJournal harvest;private final PickupJournal pickup;
    private final List<Owner> children;private WorldRetentionManager manager;
    private HarvestPickupRetention(HarvestJournal harvest,PickupJournal pickup) {
        this.harvest=Objects.requireNonNull(harvest);this.pickup=Objects.requireNonNull(pickup);
        if(!harvest.snapshot().world().equals(pickup.snapshot().world()))throw new IllegalArgumentException("H/P retention world");
        children=List.of(harvest.retentionOwnerForCurrentWriter(),pickup.retentionOwnerForCurrentWriter());
        if(children.stream().anyMatch(o->o.serializedWriter().orElse(null)!=writer))throw new IllegalArgumentException("H/P serialized writer");
        harvest.capacity(amount->reserve(0,amount));pickup.capacity(amount->reserve(1,amount));
    }
    public static HarvestPickupRetention bind(HarvestJournal harvest,PickupJournal pickup){return new HarvestPickupRetention(harvest,pickup);}
    private void thread(){if(Thread.currentThread()!=writer)throw new IllegalStateException("H/P shared storage writer");}
    public String id(){return "harvests";}
    public Optional<Thread> serializedWriter(){return Optional.of(writer);}
    public void bindPolicy(WorldRetentionManager manager){thread();if(this.manager!=null)throw new IllegalStateException("H/P retention already registered");this.manager=Objects.requireNonNull(manager);}
    private boolean valid(){return !harvest.readOnly()&&!pickup.readOnly()&&(long)harvest.snapshot().entries().size()+pickup.snapshot().entries().size()<=NativePickup.MAX_ENTRIES;}
    public long revision()throws IOException {
        if(!valid())throw new IOException("Fenced/oversized H/P family; preserve files without a partial inventory");
        return Math.addExact(children.get(0).revision(),children.get(1).revision());
    }
    private static Amount sum(Amount a,Amount b){return new Amount(Math.addExact(a.bytes(),b.bytes()),Math.addExact(a.records(),b.records()));}
    public Optional<Amount> admissionCeiling(){return Optional.of(sum(children.get(0).admissionCeiling().orElseThrow(),children.get(1).admissionCeiling().orElseThrow()));}
    private boolean reserve(int child,Amount replacement) {
        thread();if(manager==null||!valid())return false;
        Amount combined=sum(replacement,children.get(1-child).admissionCeiling().orElseThrow());
        return combined.records()<=NativePickup.MAX_ENTRIES&&manager.assessReplacement(id(),Category.EVIDENCE,combined).status()==Capacity.AVAILABLE;
    }
    private Key key(Key original) {
        if(original.owner().equals(children.get(0).id()))return new Key(id(),"harvest:"+original.id());
        if(original.owner().equals(children.get(1).id()))return new Key(id(),"pickup:"+original.id());
        return original;
    }
    private Entry entry(Entry original){return new Entry(key(original.key()),original.category(),original.usage(),original.dependencies().stream().map(this::key).toList(),original.protectedRecord(),original.eligible(),original.viewers(),original.identity());}
    /** O(1) creation, then metered traversal of both original cursors; no owner or file is omitted. */
    public Cursor open()throws IOException {
        long captured=revision();
        return new Cursor() {
            private int child;private Cursor active;private boolean closed;
            public long revision(){return captured;}
            public Page next(int maximum,int bytes)throws IOException {
                if(closed||maximum<1||bytes<0)throw new IOException("H/P cursor state or work budget");
                var entries=new ArrayList<Entry>();int inspected=0,read=0;boolean consistent=true;
                while(child<children.size()&&inspected<maximum) {
                    if(active==null)active=children.get(child).open();
                    Page page=active.next(maximum-inspected,bytes-read);
                    if(page.inspected()>maximum-inspected||page.readBytes()>bytes-read)throw new IOException("H/P child exceeded native slice");
                    for(Entry row:page.entries())entries.add(entry(row));inspected+=page.inspected();read+=page.readBytes();consistent&=page.valid();
                    if(page.complete()){active.close();active=null;child++;}
                    else break;
                }
                return new Page(entries,child==children.size(),consistent&&captured==HarvestPickupRetention.this.revision()&&(captured&1)==0,inspected,read);
            }
            public void close()throws IOException {closed=true;if(active!=null){active.close();active=null;}}
        };
    }
    public OwnerResult collect(Entry entry,long expectedRevision,java.util.function.BooleanSupplier cancel){return OwnerResult.of(OwnerStatus.PROTECTED);}
}
