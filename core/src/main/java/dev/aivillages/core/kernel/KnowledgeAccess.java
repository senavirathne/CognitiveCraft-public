package dev.aivillages.core.kernel;

import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.*;

/** IMP-022 schema 1. References and learning evidence, never executable bodies or action authority. */
public final class KnowledgeAccess {
    public static final int SCHEMA=1;
    public static final String DOMAIN="knowledge-access";
    private KnowledgeAccess(){ }
    public enum Code { APPLIED, DUPLICATE, PENDING, DENIED, INVALID, LIMIT, UNAVAILABLE, CANCELLED }
    public enum Stage { PRACTICING, LEARNED, STOPPED }
    public enum Origin { PRACTICE, ACQUISITION, COMPATIBLE_MIGRATION }
    public record Limits(int grants,int history,int active,int bytes,int work) {
        public Limits {
            if(grants<0||grants>512||history<0||history>64||active<0||active>8
                    ||bytes<0||bytes>1_048_576||work<0||work>8192)throw new IllegalArgumentException("Access limits");
        }
        public static Limits defaults(){return new Limits(512,64,8,1_048_576,8192);}
    }
    public record Enrollment(ActorRef actor,TrustedContext owner) {
        public Enrollment { Objects.requireNonNull(actor);Objects.requireNonNull(owner); }
    }
    /** Explicit host observation; availability grants neither knowledge nor physical permission. */
    public record Pair(ActorRef teacher,ActorRef learner) {
        public Pair { Objects.requireNonNull(teacher);Objects.requireNonNull(learner); }
    }
    public record Grant(Enrollment learner,ArtifactRef method,Enrollment teacher,Origin origin,
                        UUID job,UUID attempt,long generation,UUID run,long jobRevision) {
        public Grant {
            Objects.requireNonNull(learner);Objects.requireNonNull(method);Objects.requireNonNull(teacher);
            Objects.requireNonNull(origin);Objects.requireNonNull(job);Objects.requireNonNull(attempt);Objects.requireNonNull(run);
            if(generation<1||jobRevision<0||!learner.owner().scope().worldId().equals(teacher.owner().scope().worldId()))
                throw new IllegalArgumentException("Learning proof");
        }
    }
    public record Practice(UUID job,Enrollment teacher,Enrollment learner,ArtifactRef method,
                           List<ArtifactRef> pinned,String requestSha256,long generation,long deadline,
                           Stage stage,String stoppedReason) {
        public Practice {
            Objects.requireNonNull(job);Objects.requireNonNull(teacher);Objects.requireNonNull(learner);
            Objects.requireNonNull(method);Objects.requireNonNull(stage);pinned=List.copyOf(pinned);
            if(pinned.isEmpty()||pinned.size()>16||!pinned.contains(method)||new HashSet<>(pinned).size()!=pinned.size()
                    ||requestSha256==null||!requestSha256.matches("[0-9a-f]{64}")||generation<1||deadline<1
                    ||stoppedReason==null||stoppedReason.length()>64
                    ||!learner.owner().scope().worldId().equals(teacher.owner().scope().worldId())
                    ||!teacher.actor().dimension().equals(learner.actor().dimension())||teacher.actor().equals(learner.actor()))
                throw new IllegalArgumentException("Practice references");
            if(stage==Stage.STOPPED)Reason.valueOf(stoppedReason);
            else if(!stoppedReason.isEmpty())throw new IllegalArgumentException("Practice reason");
        }
        public Practice finish(Stage value,Reason reason){return new Practice(job,teacher,learner,method,pinned,
                requestSha256,generation,deadline,value,reason==null?"":reason.name());}
    }
    /** Migration freezes both the existing citizen bindings and exact admitted method revisions. */
    public record Snapshot(UUID world,long revision,boolean migrated,List<Enrollment> legacyCitizens,
                           List<ArtifactRef> legacyMethods,List<Enrollment> personalCitizens,List<Grant> grants,List<Practice> practices) {
        public Snapshot {
            Objects.requireNonNull(world);legacyCitizens=List.copyOf(legacyCitizens);legacyMethods=List.copyOf(legacyMethods);
            personalCitizens=List.copyOf(personalCitizens);grants=List.copyOf(grants);practices=List.copyOf(practices);
            if(revision<0||legacyCitizens.size()>64||legacyMethods.size()>128||personalCitizens.size()>64||grants.size()>512||practices.size()>64
                    ||new HashSet<>(legacyCitizens).size()!=legacyCitizens.size()
                    ||new HashSet<>(legacyMethods).size()!=legacyMethods.size()
                    ||new HashSet<>(personalCitizens).size()!=personalCitizens.size()
                    ||personalCitizens.stream().anyMatch(legacyCitizens::contains)
                    ||grants.stream().map(g->List.of(g.learner(),g.method())).distinct().count()!=grants.size()
                    ||practices.stream().map(Practice::job).distinct().count()!=practices.size()
                    ||practices.stream().filter(p->p.stage()==Stage.PRACTICING).count()>8)
                throw new IllegalArgumentException("Access snapshot bounds");
            for(var e:legacyCitizens)if(!e.owner().scope().worldId().equals(world))throw new IllegalArgumentException("Foreign access");
            for(var e:personalCitizens)if(!e.owner().scope().worldId().equals(world))throw new IllegalArgumentException("Foreign personal access");
            for(var g:grants)if(!g.learner().owner().scope().worldId().equals(world))throw new IllegalArgumentException("Foreign grant");
            for(var p:practices)if(!p.learner().owner().scope().worldId().equals(world))throw new IllegalArgumentException("Foreign practice");
            if(!migrated&&(!legacyCitizens.isEmpty()||!legacyMethods.isEmpty()||!personalCitizens.isEmpty()||!grants.isEmpty()||!practices.isEmpty()))
                throw new IllegalArgumentException("Unmigrated records");
        }
        public static Snapshot empty(UUID world){return new Snapshot(world,0,false,List.of(),List.of(),List.of(),List.of(),List.of());}
    }
    public record Decision(boolean allowed,Reason reason) {
        public Decision { if(allowed==(reason!=null))throw new IllegalArgumentException("Access decision"); }
        public static Decision allow(){return new Decision(true,null);}
        public static Decision deny(Reason reason){return new Decision(false,Objects.requireNonNull(reason));}
    }
    public record Result(Code code,Reason reason,UUID job,int work,int bytes,boolean publicationMayHaveSucceeded) { }
    public record VersionTransfer(ArtifactRef from,ArtifactRef to,String policy) {
        public VersionTransfer {
            Objects.requireNonNull(from);Objects.requireNonNull(to);
            if(!from.capability().equals(to.capability())||from.equals(to)||policy==null||!policy.matches("[a-z][a-z0-9_-]{0,47}"))
                throw new IllegalArgumentException("Explicit compatible-version policy");
        }
    }
}
