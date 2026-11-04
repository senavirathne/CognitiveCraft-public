package dev.aivillages.core.kernel.runtime.evidence;

import dev.aivillages.core.kernel.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.*;

/** Additive v3 D lineage. Old deposits are never upgraded into an H/P proof. */
public final class NativeJoinedDeposit {
    private NativeJoinedDeposit(){}
    public record Prefix(NativePickup.Entry pickup,NativePickup.Trail actorTrail) {
        public Prefix {
            Objects.requireNonNull(pickup);Objects.requireNonNull(actorTrail);
            var p=pickup.result();var old=pickup.intent().actorTrail();
            if(p==null || p.live()!=NativePickup.Live.COMPLETE || p.receipt()==null || p.remaining()!=null || pickup.verification()!=null
                    || !old.entity().equals(actorTrail.entity()) || !old.incarnation().equals(actorTrail.incarnation()) || !actorTrail.chunks().containsAll(old.chunks()))
                throw new IllegalArgumentException("Complete original first pickup required");
        }
        void matches(Intent d) {
            var p=pickup.intent();var h=p.harvest().intent();var key=p.key();
            if(d.key().ordinal()!=3 || !d.key().world().equals(key.world()) || !d.key().root().equals(key.root()) || !d.key().step().equals(key.step())
                    || !d.key().attempt().equals(key.attempt()) || d.key().generation()!=key.generation() || !d.origin().equals(h.origin()) || !d.run().equals(h.run())
                    || !d.actor().equals(pickup.result().actor()) || !d.item().equals(h.item()) || d.operation().equals(p.operation()) || d.operation().equals(h.operation())
                    || d.receiptPrefix()==null || !d.receiptPrefix().receipts().equals(List.of(p.harvest().result().receipt(),pickup.result().receipt()))
                    || d.conditions().deadlineEpochMillis()!=p.conditions().deadlineEpochMillis() || d.conditions().jobRevision()<p.conditions().jobRevision()
                    || d.conditions().observedTick()<p.conditions().observedTick() || d.conditions().effectsRemaining()>p.conditions().effectsRemaining()-1
                    || d.conditions().usage().getOrDefault(Budgets.Kind.ATTEMPTED_EFFECTS,0L)!=2 || d.conditions().usage().getOrDefault(Budgets.Kind.COMMITTED_EFFECTS,0L)!=2)
                throw new IllegalArgumentException("Original cumulative H/P/D binding");
            for(var u:pickup.result().usage().entrySet())if(d.conditions().usage().getOrDefault(u.getKey(),0L)<u.getValue())throw new IllegalArgumentException("Deposit usage decreased");
            var prior=new HashMap<UUID,Claim>();p.conditions().claims().forEach(c->prior.put(c.id(),c));
            if(prior.size()!=d.conditions().claims().size())throw new IllegalArgumentException("Original deposit claims changed");
            for(var claim:d.conditions().claims()) {
                var old=prior.get(claim.id());if(old==null || !old.epoch().equals(claim.epoch()) || !old.group().equals(claim.group()) || old.generation()>claim.generation())
                    throw new IllegalArgumentException("Original deposit claim ancestry");
            }
        }
    }
    public static Reconciliation classify(Entry entry,NativePickup.Observation o) {
        if(o==null || o.actor()==null || o.source()==null)return Reconciliation.WAIT_WORLD;
        var prefix=entry.intent().pickupPrefix();var r=entry.result();
        if(prefix==null || r==null)return Reconciliation.UNPROVEN;
        var p=prefix.pickup();var expected=new Marker(entry.token(),entry.intent().digest(),MarkerPhase.RESULT,r.digest());
        if(!o.actor().restoredFromNativeSave() || !o.source().restoredFromNativeSave() || !o.actor().participant().equals(r.actor())
                || !o.source().source().equals(p.result().source()) || !expected.equals(o.actor().marker()) || !expected.equals(o.source().marker())
                || o.drop()!=null || o.actorTrail()==null || o.dropTrail()==null
                || !o.actorTrail().restoredFromNativeSave() || !o.dropTrail().restoredFromNativeSave()
                || !o.actorTrail().trail().equals(prefix.actorTrail()) || !o.dropTrail().trail().equals(p.intent().dropTrail())
                || !expected.equals(o.actorTrail().marker()) || !expected.equals(o.dropTrail().marker()))return Reconciliation.UNPROVEN;
        var refs=new ArrayList<NativePickup.Member>();
        prefix.actorTrail().chunks().forEach(c->refs.add(new NativePickup.Member(NativePickup.Role.ACTOR,prefix.actorTrail().entity(),c)));
        p.intent().dropTrail().chunks().forEach(c->refs.add(new NativePickup.Member(NativePickup.Role.DROP,p.intent().dropTrail().entity(),c)));
        if(o.memberships().size()<refs.size())return Reconciliation.WAIT_WORLD;
        if(o.memberships().size()!=refs.size())return Reconciliation.UNPROVEN;
        var rows=new HashMap<NativePickup.Member,NativePickup.MembershipWitness>();var frames=new HashMap<NativePickup.Chunk,String>();
        for(var row:o.memberships())if(rows.putIfAbsent(row.member(),row)!=null)return Reconciliation.UNPROVEN;
        for(var ref:refs) {
            var row=rows.get(ref);if(row==null)return Reconciliation.UNPROVEN;
            var frame=frames.putIfAbsent(ref.chunk(),row.frameDigest());if(frame!=null&&!frame.equals(row.frameDigest()))return Reconciliation.TORN_PARTICIPANTS;
            boolean actor=ref.role()==NativePickup.Role.ACTOR;boolean present=actor&&ref.chunk().equals(prefix.actorTrail().current());
            var incarnation=actor?r.actor().incarnation():p.intent().dropTrail().incarnation();
            long revision=actor?r.actor().revision():p.intent().harvest().result().drops().getFirst().revision();
            if(!row.restoredFromNativeSave() || !expected.equals(row.marker()) || !row.incarnation().equals(incarnation) || row.revision()!=revision
                    || row.savedPresent()!=present || row.currentPresent()!=present)return Reconciliation.TORN_PARTICIPANTS;
        }
        return r.live()==Live.COMPLETE?Reconciliation.SAVED_COMPLETE:r.live()==Live.NO_EFFECT?Reconciliation.SAVED_NO_EFFECT:Reconciliation.PARTIAL_UNRESOLVED;
    }
    static Map<String,Object> encode(Prefix prefix) {
        try{return Map.of("pickup",StrictJson.object(new String(NativePickup.encode(prefix.pickup()),StandardCharsets.UTF_8)),"actorTrail",NativePickup.trail(prefix.actorTrail()));}
        catch(StrictJson.Invalid impossible){throw new IllegalStateException(impossible);}
    }
    static Prefix decode(Map<String,Object> row) {
        keys(row,Set.of("pickup","actorTrail"));
        return new Prefix(NativePickup.decode(StrictJson.canonical(object(row,"pickup")).getBytes(StandardCharsets.UTF_8)),NativePickup.trail(object(row,"actorTrail")));
    }
}
