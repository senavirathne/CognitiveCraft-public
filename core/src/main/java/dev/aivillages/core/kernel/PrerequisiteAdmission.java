package dev.aivillages.core.kernel;

import java.util.*;
import java.util.function.LongSupplier;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.*;
import static dev.aivillages.core.kernel.Prerequisites.*;

/** Game-thread revalidation adapter. Only Jobs publishes work and credits actual receipts. */
public final class PrerequisiteAdmission {
    private final JobLifecycleStore jobs;
    private final Limits limits;
    private final LongSupplier ticks;
    public PrerequisiteAdmission(JobLifecycleStore jobs, Limits limits, LongSupplier ticks) {
        this.jobs=Objects.requireNonNull(jobs);this.limits=Objects.requireNonNull(limits);this.ticks=Objects.requireNonNull(ticks);
    }
    public Admission submit(Proposal proposal, TrustedContext caller, Catalog current,
                            Supplies supplies, Methods methods, Access access) {
        return submit(proposal,caller,current,supplies,methods,access,false);
    }
    /** A fresh expansion consumes actual work even when its producer IDs are already admitted. */
    public Admission submitExpanded(Proposal proposal, TrustedContext caller, Catalog current,
                                    Supplies supplies, Methods methods, Access access) {
        return submit(proposal,caller,current,supplies,methods,access,true);
    }
    private Admission submit(Proposal proposal, TrustedContext caller, Catalog current,
                              Supplies supplies, Methods methods, Access access,boolean chargeDuplicate) {
        Objects.requireNonNull(proposal);Objects.requireNonNull(caller);
        int work=proposal.metrics().work();
        ResolutionStatus status=proposal.status();Reason reason=proposal.reason();
        Qualification qualification=proposal.qualification();
        try {
            if(proposal.accepted()) {
                long now=ticks.getAsLong();
                if(now<proposal.capturedTick() || now-proposal.capturedTick()>limits.observationAge()
                        || !proposal.catalog().equals(current))throw new Refused(Reason.STALE_OBSERVATION,Qualification.CHANGED);
                for(Need need:proposal.needs()) {
                    work=add(work,1);
                    if(!access.current(need))throw new SecurityException();
                    // Queries preserve the store's current read/control and scope boundary.
                    Jobs.Job root=jobs.query(need.demand(),caller);
                    if(!root.origin().equals(need.origin()) || !root.request().equals(need.bound()))throw new SecurityException();
                }
                var checkedStock=new HashSet<String>();
                for(StockUse used:proposal.stock()) {
                    if(ResourceComposition.make(used.owner()) && !checkedStock.add(used.owner().demand()+":"+used.proof().item()+":"+used.proof().identity())){work=add(work,1);continue;}
                    Supply proof=Objects.requireNonNull(supplies.inspect(used.owner(),used.proof().item()));
                    work=add(work,1+proof.inspected());
                    if(proof.reason()==Reason.AUTHORITY_DENIED)throw new SecurityException();
                    if(proof.reason()!=null)throw new Refused(proof.reason(),Qualification.UNKNOWN);
                    if(proof.status()==ObservationStatus.UNKNOWN || proof.tick()>now || now-proof.tick()>limits.observationAge())
                        throw new Refused(Reason.STALE_OBSERVATION,Qualification.UNKNOWN);
                    if(!proof.item().equals(used.proof().item()) || !proof.identity().equals(used.proof().identity())
                            || !proof.observation().dimension().equals(used.proof().observation().dimension())
                            || proof.available()!=used.proof().available() || proof.status()!=used.proof().status())
                        throw new Refused(Reason.STALE_OBSERVATION,Qualification.CHANGED);
                }
                var observations=new HashMap<UUID,ObservationRef>();
                for(Node node:proposal.nodes()) {
                    Need owner=proposal.needs().stream().filter(n->n.demand().equals(node.owner())).findFirst().orElseThrow();
                    var selectedMethod=Objects.requireNonNull(methods.resolve(node.production(),node.batches(),node.output(),owner));
                    var selected=selectedMethod.decision();
                    work=add(work,Math.addExact(1+selectedMethod.inspected(),Math.addExact(selected.candidateWork(),selected.queriedPages())));
                    if(selected.routing().status()!=ResolutionStatus.RESOLVED) {
                        status=selected.routing().status()==ResolutionStatus.UNAUTHORIZED?ResolutionStatus.UNAUTHORIZED
                                :selected.routing().status()==ResolutionStatus.UNSUPPORTED_RUNTIME?ResolutionStatus.UNSUPPORTED_RUNTIME:ResolutionStatus.BLOCKED;
                        throw new Refused(selected.routing().reason()==null?Reason.RESOURCE_MISSING:selected.routing().reason(),Qualification.CHANGED);
                    }
                    if(!selected.complete() || !node.method().equals(selected.routing().artifact())
                            || !node.request().context().equals(selected.selectedBindings().context())
                            || !node.request().request().equals(selected.selectedBindings().request())
                            || !selected.observation().reference().equals(selected.selectedBindings().observation())
                            || selected.observation().status()!=ObservationStatus.PRESENT || selected.observation().observedTick()>now
                            || now-selected.observation().observedTick()>limits.observationAge())
                        throw new Refused(Reason.STALE_OBSERVATION,Qualification.CHANGED);
                    observations.put(node.id(),selected.observation().reference());
                }
                Jobs.Change change=jobs.admitPrerequisites(proposal,observations,work,caller);
                if(change.accepted()) {
                    if(chargeDuplicate && change.code()==Jobs.Code.DUPLICATE)
                        change=jobs.chargePrerequisiteWork(proposal.needs(),work,caller);
                    if(change.accepted())return new Admission(ResolutionStatus.RESOLVED,null,change,
                            proposal.nodes().stream().map(Node::id).toList(),Qualification.NONE);
                    return new Admission(change.reason()==Reason.AUTHORITY_DENIED?ResolutionStatus.UNAUTHORIZED:ResolutionStatus.BLOCKED,change.reason(),change,List.of(),
                            change.reason()==Reason.BUDGET_EXHAUSTED?Qualification.LIMIT:Qualification.CHANGED);
                }
                reason=change.reason();status=reason==Reason.AUTHORITY_DENIED?ResolutionStatus.UNAUTHORIZED:ResolutionStatus.BLOCKED;
                qualification=reason==Reason.STALE_OBSERVATION?Qualification.CHANGED:Qualification.NONE;
                // Admission is atomic. A rejected compound mutation did not charge its scratch work.
            }
        } catch(SecurityException denied) {
            status=ResolutionStatus.UNAUTHORIZED;reason=Reason.AUTHORITY_DENIED;qualification=Qualification.NONE;
        } catch(Refused refused) {
            if(status==ResolutionStatus.RESOLVED)status=ResolutionStatus.BLOCKED;
            reason=refused.reason;qualification=refused.qualification;
        } catch(ArithmeticException | IllegalArgumentException malformed) {
            status=ResolutionStatus.BLOCKED;reason=Reason.REQUEST_INVALID;qualification=Qualification.NONE;
        } catch(RuntimeException unavailable) {
            status=ResolutionStatus.BLOCKED;reason=Reason.STORAGE_UNAVAILABLE;qualification=Qualification.UNKNOWN;
        }
        Jobs.Change billing=jobs.chargePrerequisiteWork(proposal.needs(),work,caller);
        if(!billing.accepted()) {
            reason=billing.reason();status=reason==Reason.AUTHORITY_DENIED?ResolutionStatus.UNAUTHORIZED:ResolutionStatus.BLOCKED;
            qualification=reason==Reason.BUDGET_EXHAUSTED?Qualification.LIMIT:reason==Reason.STALE_OBSERVATION?Qualification.CHANGED
                    :reason==Reason.STORAGE_UNAVAILABLE?Qualification.UNKNOWN:qualification;
        }
        return new Admission(status,reason,billing,List.of(),qualification);
    }
    private int add(int used,int amount) {
        if(amount<0 || amount>limits.work()-used)throw new Refused(Reason.BUDGET_EXHAUSTED,Qualification.LIMIT);
        return used+amount;
    }
    /** Intent is not credit: require the existing producer's terminal, attributable deposit receipt. */
    public Jobs.Change allocate(AllocationIntent intent, UUID attempt, UUID receipt, Jobs.Guard currentDemand,
                                TrustedContext caller) {
        return allocate(intent,intent.quantity(),attempt,receipt,currentDemand,caller);
    }
    public Jobs.Change allocate(AllocationIntent intent,long quantity, UUID attempt, UUID receipt, Jobs.Guard currentDemand,
                                TrustedContext caller) {
        if(intent.policy()!=AllocationPolicy.ATTRIBUTABLE_WHEAT)
            return new Jobs.Change(Jobs.Code.REJECTED,null,Reason.UNSUPPORTED_PRIMITIVE,0);
        if(quantity<1 || quantity>intent.quantity())return new Jobs.Change(Jobs.Code.REJECTED,null,Reason.REQUEST_INVALID,0);
        UUID id=stableId("allocation:1:"+intent.demand()+":"+intent.producer()+":"+attempt+":"+receipt);
        return jobs.allocate(new Jobs.Allocation(id,intent.producer(),attempt,receipt,intent.demand(),quantity),currentDemand,intent.quantity(),caller);
    }
    private static final class Refused extends RuntimeException {
        final Reason reason;final Qualification qualification;
        Refused(Reason reason,Qualification qualification){this.reason=reason;this.qualification=qualification;}
    }
}
