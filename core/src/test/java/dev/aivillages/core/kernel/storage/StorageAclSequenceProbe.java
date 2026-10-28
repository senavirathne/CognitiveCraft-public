package dev.aivillages.core.kernel.storage;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static dev.aivillages.core.kernel.storage.StoragePolicyTestSupport.*;
import static dev.aivillages.core.kernel.storage.StorageRegistry.*;
import static dev.aivillages.core.kernel.storage.StorageAccessPolicy.*;

/** Test-only state-machine probe. Synthetic trusted projections; no native/client or A authority. */
public final class StorageAclSequenceProbe {
    private static <T> T await(CompletionStage<T> stage)throws Exception{return stage.toCompletableFuture().get(30,TimeUnit.SECONDS);}
    private static void ack(StorageMetadataStore.Ack ack){if(ack.status()!=StorageMetadataStore.Status.ACKNOWLEDGED)throw new IllegalStateException(ack.toString());}
    private static void bind(StorageRegistry registry,UUID id)throws Exception {
        var before=registry.snapshot();var row=before.records().get(id);var intent=binding(id,row.generation(),"UNKNOWN");
        ack(await(registry.prepareBinding(new Caller(WORLD,ADMIN),before.revision(),id,row.revision(),intent.wire())));
        var prepared=registry.snapshot();ack(await(registry.acknowledgeBinding(new Caller(WORLD,ADMIN),prepared.revision(),id,prepared.records().get(id).revision(),binding(id,row.generation(),"VERIFIED_LIVE").wire())));
    }
    public static void main(String[]args)throws Exception {
        Path input=Path.of(args[0]),output=Path.of(args[1]);Files.createDirectories(output);
        var schedule=object(new com.google.gson.Gson().fromJson(Files.readString(input),Map.class));
        var rows=list(schedule.get("transitions"));if(rows.size()!=100)throw new IllegalArgumentException("100 transitions required");
        var env=new Environment();env.delegation.set(EnumSet.allOf(Permission.class));
        var authority=new Authority(){
            public boolean controls(Caller caller,Owner subject){return caller.world().equals(WORLD)&&caller.principal().equals(ADMIN);}
            public boolean allows(Caller caller,Action action,StorageRegistry.Record record){return controls(caller,record.owner());}
            public boolean mayGrant(Caller caller,StorageRegistry.Record row,Grant grant){return controls(caller,row.owner())&&env.policy.mayAdminister(ADMIN_SCOPE,row,Permission.GRANT,grant.permissions());}
            public boolean mayTransfer(Caller caller,StorageRegistry.Record row,Owner target){return controls(caller,row.owner())&&target.kind()==OwnerKind.ORGANIZATION;}
        };
        var store=await(StorageMetadataStore.createNew(output.resolve("metadata"),WORLD));
        try {
            var registry=new StorageRegistry(store,authority);env.state.set(registry.snapshot());
            var registration=await(registry.registerMetadata(new Caller(WORLD,ADMIN),0,ORG_OWNER,"Synthetic sequence",StorageBindingRules.SINGLE,Set.of(),List.of()));ack(registration);
            UUID id=registration.storage();bind(registry,id);env.state.set(registry.snapshot());
            var observations=new StringBuilder();int ordinal=0;
            for(Object raw:rows) {
                var row=object(raw);String caseId=(String)row.get("id"),action=(String)row.get("action");
                if(!caseId.equals(String.format("D03-ACL-%03d",ordinal)))throw new IllegalArgumentException("transition ID");env.tick.set(1000+ordinal);
                long start=System.nanoTime();var before=env.policy.evaluate(READER_SCOPE,id,Permission.DEPOSIT);long elapsed=System.nanoTime()-start;
                boolean beforeCurrent=before.current(READER_SCOPE,id,Permission.DEPOSIT);
                var state=registry.snapshot();var record=state.records().get(id);var caller=new Caller(WORLD,ADMIN);
                switch(action) {
                    case "GRANT" -> {var permissions=EnumSet.noneOf(Permission.class);for(Object permission:list(row.get("permissions")))permissions.add(Permission.valueOf((String)permission));ack(await(registry.grant(caller,state.revision(),id,record.revision(),new Grant(READER_OWNER,permissions))));}
                    case "REVOKE" -> {var permissions=EnumSet.noneOf(Permission.class);for(var grant:record.acl())if(grant.subject().equals(READER_OWNER))permissions.addAll(grant.permissions());ack(await(registry.revoke(caller,state.revision(),id,record.revision(),READER_OWNER,permissions)));}
                    case "TRANSFER" -> {ack(await(registry.transferOwnership(caller,state.revision(),id,record.revision(),new Owner(OwnerKind.ORGANIZATION,UUID.fromString((String)row.get("target"))))));env.state.set(registry.snapshot());bind(registry,id);}
                    case "MEMBERSHIP" -> {env.membershipState.set(OrganizationMembershipPort.State.valueOf((String)row.get("state")));env.membershipRevision.incrementAndGet();}
                    default -> throw new IllegalArgumentException("unknown transition");
                }
                env.state.set(registry.snapshot());var after=env.policy.evaluate(READER_SCOPE,id,Permission.DEPOSIT);var result=new TreeMap<String,Object>();
                result.put("id",caseId);result.put("tier","PURE_ACL_SEQUENCE");result.put("action",action);result.put("before",before.wire().body());result.put("after",after.wire().body());
                result.put("old_current",before.current(READER_SCOPE,id,Permission.DEPOSIT));result.put("old_effect_allowed",before.authorizesEffect(READER_SCOPE,id,Permission.DEPOSIT));result.put("before_current",beforeCurrent);result.put("after_current",after.current(READER_SCOPE,id,Permission.DEPOSIT));result.put("after_effect_allowed",after.authorizesEffect(READER_SCOPE,id,Permission.DEPOSIT));
                var current=registry.snapshot().records().get(id);result.put("owner",current.owner().id().toString());result.put("record_revision",current.revision());result.put("acl",current.acl().stream().map(g->Map.of("subject",g.subject().id().toString(),"permissions",g.permissions().stream().map(Enum::name).sorted().toList())).toList());
                result.put("evaluation_ns",elapsed);result.put("integration_root",null);result.put("participant_save",null);observations.append(new String(StorageMetadataCodec.canonical(result),java.nio.charset.StandardCharsets.UTF_8));ordinal++;
            }
            Files.writeString(output.resolve("observations.jsonl"),observations.toString());
            System.out.println("SYNTHETIC_ACL_TRANSITIONS "+ordinal+"; native/client executions=0");
        }finally{await(store.closeAsync());}
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) { return (Map<String,Object>)value; }
    @SuppressWarnings("unchecked") private static List<Object> list(Object value) { return (List<Object>)value; }
    private StorageAclSequenceProbe() { }
}
