package dev.aivillages.core.kernel;

import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.*;

/** IMP-021 schema 1: derived measurements and recognition, never work, rights or actor stats. */
public final class Accomplishments {
    public static final int SCHEMA = 1, RULE_POLICY = 1;
    public static final String DOMAIN = "accomplishment-evidence";
    private Accomplishments() { }

    public enum Code { APPLIED, DUPLICATE, STALE, UNVERIFIABLE, DENIED, LIMIT, CANCELLED, UNAVAILABLE }
    public enum OutcomeClass { CONTRIBUTION, SUCCESS, ENVIRONMENT, CANCELLATION, AUTHORITY, EXECUTION_FAILURE, INFRASTRUCTURE, INTERRUPTED }
    public enum Size { SMALL, MEDIUM, LARGE }
    public enum Route { NEAR, MEDIUM, FAR }
    public record Environment(String dimension, Size sourceSize, Route route) {
        public Environment { new ContainerRef(dimension,0,0,0); Objects.requireNonNull(sourceSize); Objects.requireNonNull(route); }
        public static Environment from(ValidatedRequest request) {
            var source=((AreaValue)request.request().arguments().get("source")).value();
            var target=((ContainerValue)request.request().arguments().get("destination")).value();
            if(!source.dimension().equals(target.dimension()))throw new IllegalArgumentException("Cross-dimensional outcome");
            long volume=((long)source.maxX()-source.minX()+1)*((long)source.maxY()-source.minY()+1)*((long)source.maxZ()-source.minZ()+1);
            long distance=Math.abs((long)target.x()-source.minX())+Math.abs((long)target.y()-source.minY())+Math.abs((long)target.z()-source.minZ());
            return new Environment(source.dimension(),volume<=16?Size.SMALL:volume<=128?Size.MEDIUM:Size.LARGE,
                    distance<=8?Route.NEAR:distance<=32?Route.MEDIUM:Route.FAR);
        }
    }
    public record Limits(int batch, int aggregates, int contexts, int samples, int jobs, int bytes, int work) {
        public Limits {
            if(batch<0||batch>8||aggregates<0||aggregates>128||contexts<0||contexts>8||samples<0||samples>4
                    ||jobs<0||jobs>64||bytes<0||bytes>1_048_576||work<0||work>8192)
                throw new IllegalArgumentException("Finite accomplishment limits");
        }
        public static Limits defaults(){return new Limits(8,128,8,4,64,1_048_576,8192);}
    }
    /** Only source identity/revision is supplied. Quantities, worker, method and context come from Jobs. */
    public record OutcomeRef(UUID world, UUID job, long revision) {
        public OutcomeRef { Objects.requireNonNull(world); Objects.requireNonNull(job); if(revision<1)throw new IllegalArgumentException("Outcome revision"); }
        public static OutcomeRef of(Jobs.Job job){return new OutcomeRef(job.origin().scope().worldId(),job.id(),job.revision());}
    }
    public record Key(ActorRef actor, TrustedContext origin, CapabilityId capability, ArtifactRef method, Environment environment) {
        public Key {
            Objects.requireNonNull(actor);Objects.requireNonNull(origin);Objects.requireNonNull(capability);Objects.requireNonNull(method);Objects.requireNonNull(environment);
            if(!capability.equals(method.capability())||!actor.dimension().equals(environment.dimension()))throw new IllegalArgumentException("Evidence key");
        }
    }
    public record Sample(UUID job, long jobRevision, UUID attempt, UUID run, OutcomeClass outcome, Reason reason,
                         long quantity, List<UUID> receipts) {
        public Sample {
            Objects.requireNonNull(job);Objects.requireNonNull(attempt);Objects.requireNonNull(run);Objects.requireNonNull(outcome);
            receipts=List.copyOf(receipts);
            if(jobRevision<1||quantity<0||quantity>64||receipts.size()>3||new HashSet<>(receipts).size()!=receipts.size()
                    ||quantity>0&&receipts.size()!=3||outcome==OutcomeClass.SUCCESS&&reason!=null)
                throw new IllegalArgumentException("Compact verified sample");
            if(outcome==OutcomeClass.CONTRIBUTION?(quantity==0||reason!=null):outcome!=OutcomeClass.SUCCESS&&reason==null)
                throw new IllegalArgumentException("Sample outcome meaning");
        }
    }
    public record Aggregate(Key key, long quantity, long completedJobs, long contributionSamples,
                            Map<OutcomeClass,Long> outcomes, List<Sample> samples) {
        public Aggregate {
            Objects.requireNonNull(key);outcomes=Map.copyOf(outcomes);samples=List.copyOf(samples);
            if(quantity<0||completedJobs<0||contributionSamples<0||samples.size()>4
                    ||outcomes.values().stream().anyMatch(n->n==null||n<0))throw new IllegalArgumentException("Measured aggregate");
        }
        public static Aggregate empty(Key key){return new Aggregate(key,0,0,0,Map.of(),List.of());}
    }
    /** One progress row per source job/attempt/run; no per-action archive or lossy dedup filter. */
    public record RunCredit(UUID run, ArtifactRef method, long quantity) {
        public RunCredit { Objects.requireNonNull(run);Objects.requireNonNull(method);if(quantity<0||quantity>64)throw new IllegalArgumentException("Run credit"); }
    }
    public record AttemptProgress(UUID attempt, long generation, ActorRef worker, List<RunCredit> runs, int outcomes) {
        public AttemptProgress {
            Objects.requireNonNull(attempt);Objects.requireNonNull(worker);runs=List.copyOf(runs);
            if(generation<1||runs.size()>8||runs.stream().map(RunCredit::run).distinct().count()!=runs.size()
                    ||outcomes<0||outcomes>=(1<<OutcomeClass.values().length)||runs.stream().mapToLong(RunCredit::quantity).sum()>64)
                throw new IllegalArgumentException("Attempt progress");
        }
    }
    public record Progress(UUID job, long revision, TrustedContext origin, String requestSha256,
                           boolean completed, List<AttemptProgress> attempts) {
        public Progress {
            Objects.requireNonNull(job);Objects.requireNonNull(origin);attempts=List.copyOf(attempts);
            if(revision<1||requestSha256==null||!requestSha256.matches("[0-9a-f]{64}")||attempts.size()>8
                    ||attempts.stream().map(AttemptProgress::attempt).distinct().count()!=attempts.size())
                throw new IllegalArgumentException("Persisted ingestion progress");
        }
    }
    public record Rule(String id, int version, String title, CapabilityId capability,
                       long completedJobs, long quantity, long samples, boolean active) {
        public Rule(String id,int version,String title,CapabilityId capability,long completedJobs,long quantity,long samples){
            this(id,version,title,capability,completedJobs,quantity,samples,true);
        }
        public Rule {
            if(id==null||!id.matches("[a-z][a-z0-9_-]{0,31}")||version<1||version>1_000_000
                    ||title==null||!title.matches("[A-Za-z0-9 ,.'-]{1,48}")||capability==null
                    ||completedJobs<1||completedJobs>512||quantity<1||quantity>4096||samples<1||samples>512)
                throw new IllegalArgumentException("Measured recognition rule");
        }
        public static List<Rule> defaults(){return List.of(new Rule("crop-contributor",1,"Measured crop contributor",CropDelivery.ID,1,2,1));}
        public Rule retired(){return new Rule(id,version,title,capability,completedJobs,quantity,samples,false);}
        public boolean sameMeaning(Rule other){return id.equals(other.id)&&version==other.version&&title.equals(other.title)
                &&capability.equals(other.capability)&&completedJobs==other.completedJobs&&quantity==other.quantity&&samples==other.samples;}
    }
    public record Recognition(ActorRef actor, TrustedContext origin, String rule, int version, String title,
                              boolean active, Key support, long completedJobs, long quantity, long samples,
                              int priorVersion, String priorTitle) {
        public Recognition {
            Objects.requireNonNull(actor);Objects.requireNonNull(origin);
            new Rule(rule,version,title,CropDelivery.ID,1,1,1);
            if(completedJobs<0||quantity<0||samples<0||priorVersion<0||priorVersion>1_000_000
                    ||priorTitle!=null&&!priorTitle.matches("[A-Za-z0-9 ,.'-]{1,48}")
                    ||active&&support==null||support!=null&&(!support.actor().equals(actor)||!support.origin().equals(origin)))
                throw new IllegalArgumentException("Recognition metadata");
        }
    }
    public record Snapshot(UUID world, long revision, List<Aggregate> aggregates, List<Progress> progress,
                           List<Rule> rules, List<Recognition> recognition) {
        public Snapshot {
            Objects.requireNonNull(world);aggregates=List.copyOf(aggregates);progress=List.copyOf(progress);
            rules=List.copyOf(rules);recognition=List.copyOf(recognition);
            if(revision<0||aggregates.size()>128||progress.size()>64||rules.size()>4||recognition.size()>256
                    ||aggregates.stream().map(Aggregate::key).distinct().count()!=aggregates.size()
                    ||progress.stream().map(Progress::job).distinct().count()!=progress.size()
                    ||rules.stream().map(Rule::id).distinct().count()!=rules.size()
                    ||recognition.stream().map(r->List.of(r.actor().citizenId(),r.origin(),r.rule())).distinct().count()!=recognition.size()
                    ||aggregates.stream().anyMatch(a->!a.key().origin().scope().worldId().equals(world))
                    ||progress.stream().anyMatch(p->!p.origin().scope().worldId().equals(world))
                    ||recognition.stream().anyMatch(r->!r.origin().scope().worldId().equals(world)))throw new IllegalArgumentException("Ledger snapshot");
        }
        public static Snapshot empty(UUID world){return new Snapshot(world,0,List.of(),List.of(),Rule.defaults(),List.of());}
    }
    public record Result(Code code, int events, int work, int bytes, boolean publicationMayHaveSucceeded) { }
    public record Query(Code code, List<Aggregate> evidence, List<Recognition> recognition, boolean more) {
        public Query { evidence=List.copyOf(evidence);recognition=List.copyOf(recognition); }
    }
    /** An explicit aggregate grant excludes private job/receipt samples. */
    public record Summary(CapabilityId capability, Environment environment, long quantity, long completedJobs,
                          long contributionSamples, Map<OutcomeClass,Long> outcomes) {
        public Summary { outcomes=Map.copyOf(outcomes); }
    }
    public static OutcomeClass classify(Execution outcome){
        if(outcome.status()==ExecutionStatus.SUCCEEDED)return OutcomeClass.SUCCESS;
        if(outcome.status()==ExecutionStatus.CANCELLED)return OutcomeClass.CANCELLATION;
        if(outcome.status()==ExecutionStatus.INTERRUPTED)return OutcomeClass.INTERRUPTED;
        return switch(outcome.reason()){
            case AUTHORITY_DENIED->OutcomeClass.AUTHORITY;
            case RESOURCE_MISSING,FACILITY_MISSING,TARGET_UNAVAILABLE,TARGET_INVALID,ACTOR_UNAVAILABLE,STALE_OBSERVATION,
                    KNOWLEDGE_REQUIRED,UNSUPPORTED_PRIMITIVE->OutcomeClass.ENVIRONMENT;
            case STORAGE_LIMIT_REACHED,STORAGE_UNAVAILABLE,MODEL_UNAVAILABLE,BUDGET_EXHAUSTED,DEPENDENCY_INCOMPATIBLE,ARTIFACT_INCOMPATIBLE->OutcomeClass.INFRASTRUCTURE;
            default->OutcomeClass.EXECUTION_FAILURE;
        };
    }
}
