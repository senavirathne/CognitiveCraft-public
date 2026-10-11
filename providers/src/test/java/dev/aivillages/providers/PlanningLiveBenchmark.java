package dev.aivillages.providers;

import dev.aivillages.core.kernel.*;
import java.net.URI;
import java.time.Clock;
import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.*;

/** Separately invoked held-out real inference benchmark; it makes no native execution/admission claim. */
public final class PlanningLiveBenchmark {
    private PlanningLiveBenchmark() { }
    public static void main(String[] args)throws Exception {
        String model=System.getenv("COGNITIVECRAFT_OLLAMA_MODEL");if(model==null || model.isBlank())throw new IllegalStateException("Explicit installed shared model required");
        Clock clock=Clock.systemUTC();long deadline=clock.millis()+600000;var totals=new Budgets.Limits(Map.of(Budgets.Kind.CALLS,3L,
                Budgets.Kind.INPUT_BYTES,98304L,Budgets.Kind.OUTPUT_BYTES,24576L),deadline);var ledger=new Budgets.Ledger(totals,clock);
        int valid=0;
        try(var adapter=new LocalGenerationAdapter(new LocalGenerationAdapter.Config(URI.create("http://127.0.0.1:11434/api/chat"),model,true,1024));
            var broker=new AIWorkBroker(adapter,clock,AIWorkBroker.Settings.defaults(),AIWorkBroker.sessionLimits(clock.millis()),r->true,AIWorkBroker.Sharing.privateScopes())) {
            int[][] heldout={{1,4},{3,2},{2,6}};
            for(int caseId=0;caseId<heldout.length;caseId++) {
                int first=heldout[caseId][0],second=heldout[caseId][1];var seed=PlanningAdapterTest.request("held-out");
                var values=new HashMap<>(seed.bound().request().arguments());values.put("amount",new IntValue(first+second));values.put("firstAmount",new IntValue(first));
                var goal=new ValidatedRequest(new CapabilityRequest(PlanningGoals.ID,values),seed.bound().context(),seed.bound().observation());
                var descriptor=new ArtifactDescriptor(new ArtifactRef(CropDelivery.ID,"a".repeat(64)),1,CropDelivery.SPEC.parameters(),Type.INT,CropDelivery.SPEC.effects(),List.of(),List.of());
                var snapshot=new ObservationSnapshot(goal.observation(),ObservationStatus.PRESENT,"bounded-held-out-source",1,9,Map.of("mature_wheat",9L,"unknown_cells",0L));
                var slots=PlanningGoals.slots(goal.request()).stream().map(r->new Plan.Slot(new ValidatedRequest(r,goal.context(),snapshot.reference()),snapshot,descriptor)).toList();
                var routing=new CapabilityResolver.Decision(new Resolution(ResolutionStatus.NEEDS_PLANNING,null,null,"held-out"),null,Set.of(),snapshot,1,List.of(),1,0,true,false,null);
                var coverage=DeterministicPrerequisiteResolver.outcomeCoverage(PlanningGoals.ID,new Prerequisites.Catalog(1,List.of(),List.of(Prerequisites.Production.cropDelivery()),Set.of(),true));
                var context=new Plan.Context(UUID.randomUUID(),new Jobs.Guard(1,0),goal,routing,coverage,slots,"");
                var request=new Generation.Request(UUID.randomUUID(),goal,PlanningGoals.SPEC,List.of(),List.of(descriptor),Generation.Role.PLANNING,context.prompt());
                var limits=new Budgets.InferenceLimits(new Budgets.Limits(Map.of(Budgets.Kind.CALLS,1L,Budgets.Kind.INPUT_BYTES,32768L,Budgets.Kind.OUTPUT_BYTES,8192L),deadline),32768,8192);
                long start=System.nanoTime();var handle=broker.submit(request,limits,ledger,Plan.OUTPUT,AIWorkBroker.Priority.NORMAL);
                while(!handle.result().toCompletableFuture().isDone() && clock.millis()<deadline){broker.step();Thread.sleep(10);}
                var result=handle.result().toCompletableFuture().get();
                Plan.Check checked=result.outcome()==Generation.Outcome.CANDIDATE?new PlanValidator(PlanningBudget.defaults()).validate(result.candidateIr(),context,
                        new Budgets.Limits(Map.of(Budgets.Kind.CALLS,512L,Budgets.Kind.INSTRUCTIONS,8192L),deadline),new PlanValidator.Current(){
                            public boolean controls(ActorRef a,TrustedContext c){return a.equals(((ActorValue)goal.request().arguments().get("actor")).value())&&c.equals(goal.context());}
                            public CapabilityResolver.Decision resolve(ValidatedRequest bound){return new CapabilityResolver.Decision(new Resolution(ResolutionStatus.RESOLVED,null,descriptor.ref(),"held-out"),
                                    bound,descriptor.effects(),snapshot,1,List.of(),1,1,true,false,null);}
                        },1):null;
                boolean accepted=checked!=null&&checked.accepted();if(accepted)valid++;
                if(result.descriptor().digest()==null)throw new AssertionError("Actual shared model digest missing");
                var evidence=new LinkedHashMap<String,Object>();evidence.put("case",caseId);evidence.put("quantities",List.of(first,second));evidence.put("model",model);
                evidence.put("modelDigest",result.descriptor().digest());evidence.put("latencyMillis",(System.nanoTime()-start)/1000000);evidence.put("outcome",result.outcome().name());
                evidence.put("valid",accepted);evidence.put("diagnostic",checked==null?result.reason().name():checked.accepted()?"NONE":checked.diagnostic().name());
                evidence.put("inputBytes",result.usage().inputBytes());evidence.put("outputBytes",result.usage().outputBytes());evidence.put("promptTokens",result.usage().promptTokens());evidence.put("outputTokens",result.usage().outputTokens());
                evidence.put("candidateSha256",result.candidateIr()==null?"unavailable":java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(result.candidateIr().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
                System.out.println("IMP-019 planning quality "+StrictJson.canonical(evidence));
            }
        }
        System.out.println("IMP-019 held-out completed cases=3 valid="+valid+" calls="+ledger.snapshot().get(Budgets.Kind.CALLS)+" dedicatedModels=0 nativeEffects=0");
    }
}
