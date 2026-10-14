package dev.aivillages.core.kernel;

import java.util.*;
import java.util.function.Supplier;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.KnowledgeAccess.*;

/** Trusted acknowledged owners plus a bounded, immutable game-thread presence observation. */
public final class KnowledgeAccessSource {
    private final Supplier<Jobs.Snapshot> jobs;
    private final Supplier<CitizenRegistry.Snapshot> identities;
    private final Supplier<Accomplishments.Snapshot> evidence;
    private final JobOutcomeSource outcomes;
    private volatile CitizenRegistry.Snapshot currentCitizens;
    private volatile Jobs.Snapshot currentJobs;
    private volatile Set<Pair> present=Set.of();
    public KnowledgeAccessSource(Supplier<Jobs.Snapshot> jobs,Supplier<CitizenRegistry.Snapshot> identities,
                                 Supplier<Accomplishments.Snapshot> evidence){
        this.jobs=Objects.requireNonNull(jobs);this.identities=Objects.requireNonNull(identities);
        this.evidence=Objects.requireNonNull(evidence);outcomes=new JobOutcomeSource(jobs,identities);
    }
    /** Host composition only. No Minecraft object, synchronous I/O or world change is retained. */
    public void observe(CitizenRegistry.Snapshot citizens,Jobs.Snapshot work,Set<Pair> presence){
        Objects.requireNonNull(citizens);Objects.requireNonNull(work);
        if(!citizens.worldId().equals(work.worldId())||presence.size()>8)throw new IllegalArgumentException("Supervision snapshot");
        currentCitizens=citizens;currentJobs=work;present=Set.copyOf(presence);
    }
    CitizenRegistry.Snapshot citizens(){return currentCitizens==null?identities.get():currentCitizens;}
    Jobs.Snapshot jobs(){return currentJobs==null?jobs.get():currentJobs;}
    Enrollment enrollment(ActorRef actor){var state=citizens();if(state==null)return null;
        return state.citizens().stream().filter(c->c.actor().equals(actor)).map(c->new Enrollment(c.actor(),c.owner())).findFirst().orElse(null);}
    boolean controls(Enrollment actor){return actor!=null&&actor.equals(enrollment(actor.actor()));}
    boolean present(Enrollment teacher,Enrollment learner){return controls(teacher)&&controls(learner)
            &&present.contains(new Pair(teacher.actor(),learner.actor()));}
    Jobs.Job job(UUID id){var state=jobs();return state==null?null:state.jobs().stream().filter(j->j.id().equals(id)).findFirst().orElse(null);}
    boolean qualified(Enrollment teacher,ArtifactRef method){
        var state=evidence.get();if(state==null||!state.world().equals(teacher.owner().scope().worldId()))return false;
        return state.aggregates().stream().anyMatch(a->a.key().actor().equals(teacher.actor())&&a.key().origin().equals(teacher.owner())
                &&a.key().method().equals(method)&&a.completedJobs()>=1&&a.quantity()>=2&&a.contributionSamples()>=1);
    }
    Jobs.Job verified(UUID id,JobOutcomeSource.Work work){
        var state=jobs.get();if(state==null)throw new JobOutcomeSource.Invalid();
        var job=state.jobs().stream().filter(j->j.id().equals(id)).findFirst().orElseThrow(JobOutcomeSource.Invalid::new);
        return outcomes.read(Accomplishments.OutcomeRef.of(job),work).job();
    }
    /** Ledger progress and actual Jobs receipts must both support the exact successful run. */
    boolean learned(Jobs.Job job,ArtifactRef method,ActorRef learner,JobOutcomeSource.Work work){
        if(job.state()!=Jobs.State.SUCCEEDED||job.current()==null||job.current().uncertain())return false;
        var attempt=job.current();if(!attempt.worker().equals(learner))return false;
        var state=evidence.get();if(state==null||!state.world().equals(job.origin().scope().worldId()))return false;
        var progress=state.progress().stream().filter(p->p.job().equals(job.id())&&p.revision()>=job.revision()&&p.completed()).findFirst().orElse(null);
        return progress!=null&&attempt.executions().stream().anyMatch(e->e.artifact().equals(method)&&JobOutcomeSource.completed(attempt,e,work));
    }
}
