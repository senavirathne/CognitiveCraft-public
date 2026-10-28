package dev.aivillages.core.kernel.storage;

import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;
import dev.aivillages.core.kernel.logistics.RecipientResolver;
import dev.aivillages.core.kernel.storage.contract.*;
import static dev.aivillages.core.kernel.storage.StorageRegistry.*;
import static dev.aivillages.core.kernel.storage.StorageBindingRules.*;
import static dev.aivillages.core.kernel.storage.StorageAccessPolicy.*;
import static dev.aivillages.core.kernel.storage.StorageResolver.*;

/** Synthetic server ports only. Not native, client, inventory or effect authority. */
public final class StorageResolutionTestSupport {
    public static UUID id(int n){return UUID.fromString(String.format("d0400000-0000-4000-8000-%012d",n));}
    public static final UUID WORLD=id(1),EPOCH=id(2),PRINCIPAL=id(3),ADA=id(4),BOB=id(5),SELECTION=id(6);
    public static final Scope SCOPE=new Scope(WORLD,EPOCH,PRINCIPAL,ADA);
    public static final String DIM="minecraft:overworld";
    public static final Owner PUBLIC=new Owner(OwnerKind.PUBLIC,null);
    public static StorageWire.Message selector(Map<String,Object> body){return StorageCodec.decode(StorageMetadataCodec.canonical(Map.of("schema",StorageWire.VERSION,"kind","StorageSelector","body",body)));}
    public static Map<String,Object> named(String label){return Map.of("kind","NAMED","label",label,"container_type","ANY_MVP");}
    public static Map<String,Object> nearest(String anchor){return Map.of("kind","NEAREST_PUBLIC","anchor",anchor,"container_type","ANY_MVP");}
    public static Map<String,Object> role(String citizen,String role){return Map.of("kind","OWNER_ROLE","owner",Map.of("kind","NAMED","label",citizen),"role",role,"container_type","ANY_MVP");}
    public static final class Env implements NativePort,SelectionPort {
        public final AtomicLong tick=new AtomicLong(100);public final Map<UUID,StorageRegistry.Record> records=new LinkedHashMap<>();public final Map<UUID,Binding> bindings=new HashMap<>();public final Map<RoleKey,Assignment> assignments=new HashMap<>();public final Map<UUID,RecipientResolver.Citizen> citizens=new LinkedHashMap<>();
        public final Map<UUID,Eligibility> capacity=new HashMap<>();public final Set<UUID> unloaded=new HashSet<>(),unsupported=new HashSet<>(),nativeDenied=new HashSet<>();
        public long revision;public UUID epoch=EPOCH;public boolean complete=true,selectionValid=true;public int consumes,observations;public Selection selected;
        public Position caller=new Position(DIM,500,64500,500),actor=new Position(DIM,10500,64500,500);
        public Snapshot snapshot=Snapshot.empty(WORLD);
        public final StorageAccessPolicy policy=new StorageAccessPolicy(()->snapshot,(scope,ids)->new OrganizationMembershipPort.View(scope,revision,Map.of()),(scope,owner)->new Control(scope,owner,revision,true,true,Set.of(Permission.INSPECT)),(scope,row,op,now)->new Protection(scope,row.id(),op,revision,now,row.generation(),nativeDenied.contains(row.id())?Result.DENY:Result.ALLOW),tick::get);
        public final RecipientResolver recipientResolver=new RecipientResolver(new RecipientResolver.Port(){public RecipientResolver.View view(Scope scope,long now){return citizenView(scope,now);}public RecipientResolver.Citizen lookup(Scope scope,UUID id,long now){return Env.this.lookup(scope,id,now);}},tick::get);
        public final StorageResolver resolver=new StorageResolver(()->snapshot,policy,this,this,recipientResolver,tick::get);
        public Env(){citizen(ADA,"Ada");citizen(BOB,"Bob");}
        public UUID add(int n,String label,int x,int y,int z,boolean publicly){
            UUID id=id(n);var grant=publicly?PUBLIC:new Owner(OwnerKind.PLAYER,PRINCIPAL);
            records.put(id,new StorageRegistry.Record(WORLD,id,label,PUBLIC,Set.of(),List.of(new Grant(grant,Set.of(Permission.INSPECT,Permission.DEPOSIT,Permission.WITHDRAW))),0,1,0,State.ACTIVE,SINGLE));
            bindings.put(id,new Binding(id,1,EPOCH,tick.get(),"VERIFIED_LIVE",new Topology(WORLD,DIM,SINGLE,"NORTH",List.of(new Member(x,y,z,id(n+10000),1)))));refresh();return id;
        }
        public void refresh(){snapshot=new Snapshot(WORLD,revision,new LinkedHashMap<>(records),assignments,bindings.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,e->e.getValue().wire())));}
        public void assign(UUID citizen,Role role,UUID storage){var key=new RoleKey(new Owner(OwnerKind.CITIZEN,citizen),role);assignments.put(key,new Assignment(WORLD,key,storage,1,0));refresh();}
        public void citizen(UUID id,String label){citizens.put(id,new RecipientResolver.Citizen(SCOPE,EPOCH,id,id(id.equals(ADA)?1004:1005),1,0,label,DIM,"0".repeat(64),tick.get(),true,true,true,Set.of(Permission.INSPECT,Permission.DEPOSIT)));}
        private RecipientResolver.Citizen at(RecipientResolver.Citizen c,long now){return new RecipientResolver.Citizen(c.scope(),c.epoch(),c.id(),c.entity(),c.generation(),c.policyRevision(),c.label(),c.dimension(),c.inventoryRevision(),now,c.visible(),c.controlled(),c.loaded(),c.permitted());}
        @Override public StorageResolver.View view(Scope scope,long now){return new StorageResolver.View(scope,epoch,revision,now,caller,actor,complete);}
        @Override public Observation observe(Scope scope,StorageRegistry.Record row,Permission op,long now){observations++;var b=bindings.get(row.id());return new Observation(scope,new Binding(b.storage(),b.generation(),epoch,now,b.status(),b.topology()),now,!unloaded.contains(row.id()),!unsupported.contains(row.id()),capacity.getOrDefault(row.id(),Eligibility.ELIGIBLE));}
        @Override public Selection consume(Scope scope,UUID id,long now){consumes++;Selection found=selected;selected=null;return found;}
        @Override public boolean current(Scope scope,Selection selection,Binding b,long now){return selectionValid;}
        public RecipientResolver.Citizen lookup(Scope scope,UUID id,long now){var c=citizens.get(id);return c==null?null:at(c,now);}
        // Separate view adapter avoids Java return-type collision between the two read-only ports.
        public RecipientResolver.View citizenView(Scope scope,long now){return new RecipientResolver.View(scope,epoch,revision,now,citizens.values().stream().map(c->at(c,now)).toList(),complete);}
        public StorageResolution resolve(Map<String,Object> body,Permission op){var q=resolver.begin(SCOPE,selector(body),op);for(int i=0;i<100;i++){var result=q.step();if(result.status()!=StorageResolution.Status.PENDING)return result;tick.incrementAndGet();}throw new AssertionError("query did not finish");}
        public RecipientResolver.Resolution recipient(RecipientResolver.Selector selector,boolean control){var q=recipientResolver.begin(SCOPE,selector,Permission.INSPECT,control);for(int i=0;i<100;i++){var result=q.step();if(result.status()!=RecipientResolver.Status.PENDING)return result;tick.incrementAndGet();}throw new AssertionError("recipient did not finish");}
        public void selection(UUID storage,long captured,long expires,Scope scope,UUID lifecycle){var member=bindings.get(storage).topology().members().getFirst();var witness=Map.ofEntries(Map.entry("selection_id",SELECTION.toString()),Map.entry("scope",scope.body()),Map.entry("dimension",DIM),Map.entry("lifecycle_epoch",lifecycle.toString()),Map.entry("selection_revision",1L),Map.entry("member",Map.of("x",(long)member.x(),"y",(long)member.y(),"z",(long)member.z(),"block_registry_id","minecraft:chest","live_instance_token",id(900).toString())),Map.entry("captured_tick",captured),Map.entry("expires_tick",expires),Map.entry("distance_milliblocks",1000L),Map.entry("line_of_sight",true));selected=new Selection(storage,StorageCodec.decode(StorageMetadataCodec.canonical(Map.of("schema",StorageWire.VERSION,"kind","SelectionWitness","body",witness))));}
    }
    private StorageResolutionTestSupport(){}
}
