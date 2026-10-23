package dev.aivillages.providers;

import dev.aivillages.core.kernel.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.*;

/** Genuine proposal measurements through the production broker/adapter/validators; no native effects. */
public final class PlanningLiveBenchmark {
    private PlanningLiveBenchmark() { }
    private record Input(ValidatedRequest bound,CapabilitySpec spec,String prompt,AIWorkBroker.Contract contract,Plan.Context wheat) { }
    private record Check(boolean valid,String diagnostic,String path,String status) { }
    private static final TrustedContext OWNER=new TrustedContext(new PrincipalRef(Plan.stable("benchmark-owner")),
            new ScopeRef(Plan.stable("benchmark-world"),Plan.stable("benchmark-domain")));
    private static final ActorRef ACTOR=new ActorRef(Plan.stable("benchmark-citizen"),Plan.stable("benchmark-entity"),"minecraft:overworld");
    private static final ArtifactDescriptor METHOD=new ArtifactDescriptor(new ArtifactRef(CropDelivery.ID,"a".repeat(64)),1,
            CropDelivery.SPEC.parameters(),Type.INT,CropDelivery.SPEC.effects(),List.of(),List.of());
    private static Input input(PlanningBenchmarkCases.Case c) {
        var reference=new ObservationRef(Plan.stable("benchmark-observation:"+c.id()),1,"minecraft:overworld");
        if(c.material()!=null) {
            var bound=new ValidatedRequest(new CapabilityRequest(MaterialPlan.ID,Map.of("amount",new IntValue(c.material().goals().values().stream().mapToLong(Long::longValue).sum()))),OWNER,reference);
            return new Input(bound,MaterialPlan.SPEC,c.material().prompt(),MaterialPlan.OUTPUT,null);
        }
        var args=new HashMap<String,Value>();args.put("actor",new ActorValue(ACTOR));
        args.put("source",new AreaValue(new Cuboid("minecraft:overworld",0,64,0,3,64,3)));
        args.put("destination",new ContainerValue(new ContainerRef("minecraft:overworld",5,64,0)));
        args.put("otherDestination",new ContainerValue(new ContainerRef("minecraft:overworld",8,64,0)));
        args.put("amount",new IntValue(c.first()+c.second()));args.put("firstAmount",new IntValue(c.first()));
        var goal=new ValidatedRequest(new CapabilityRequest(PlanningGoals.ID,args),OWNER,reference);
        var snapshot=new ObservationSnapshot(reference,ObservationStatus.PRESENT,"bounded-held-out-source",1,16,
                Map.of("mature_wheat",(long)c.first()+c.second()+1,"unknown_cells",0L));
        var slots=PlanningGoals.slots(goal.request()).stream().map(r->new Plan.Slot(new ValidatedRequest(r,OWNER,reference),snapshot,METHOD)).toList();
        var routing=new CapabilityResolver.Decision(new Resolution(ResolutionStatus.NEEDS_PLANNING,null,null,"held-out"),null,
                Set.of(),snapshot,1,List.of(),1,0,true,false,null);
        var coverage=DeterministicPrerequisiteResolver.outcomeCoverage(PlanningGoals.ID,
                new Prerequisites.Catalog(1,List.of(),List.of(Prerequisites.Production.cropDelivery()),Set.of(),true));
        var context=new Plan.Context(Plan.stable("benchmark-parent:"+c.id()),new Jobs.Guard(1,0),goal,routing,coverage,slots,"");
        return new Input(goal,PlanningGoals.SPEC,context.prompt(),Plan.OUTPUT,context);
    }
    private static Check check(PlanningBenchmarkCases.Case c,Input input,Generation.Result result,long deadline) {
        if(result.outcome()!=Generation.Outcome.CANDIDATE)return new Check(false,result.outcome().name(),"$.response","unavailable");
        if(c.material()!=null) {
            var checked=MaterialPlan.validate(result.candidateIr(),c.material());
            return new Check(checked.accepted(),checked.diagnostic(),checked.path(),checked.expectedStatus());
        }
        var ctx=input.wheat();var snapshot=ctx.slots().getFirst().observation();
        var checked=new PlanValidator(PlanningBudget.defaults()).validate(result.candidateIr(),ctx,
                new Budgets.Limits(Map.of(Budgets.Kind.CALLS,512L,Budgets.Kind.INSTRUCTIONS,8192L),deadline),new PlanValidator.Current(){
                    public boolean controls(ActorRef a,TrustedContext scope){return a.equals(ACTOR)&&scope.equals(OWNER);}
                    public CapabilityResolver.Decision resolve(ValidatedRequest bound){return new CapabilityResolver.Decision(
                            new Resolution(ResolutionStatus.RESOLVED,null,METHOD.ref(),"held-out"),bound,METHOD.effects(),snapshot,1,List.of(),1,1,true,false,null);}
                },1);
        return new Check(checked.accepted(),checked.accepted()?"NONE":checked.diagnostic().name(),checked.path(),"plan");
    }
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    public static void main(String[] args)throws Exception {
        String model=System.getenv("COGNITIVECRAFT_OLLAMA_MODEL");
        if(model==null||model.isBlank())throw new IllegalStateException("Explicit installed shared model required");
        int shard=Integer.parseInt(System.getenv().getOrDefault("PLANNING_BENCHMARK_SHARD","0"));
        int shards=Integer.parseInt(System.getenv().getOrDefault("PLANNING_BENCHMARK_SHARDS","1"));
        if(shards<1||shards>4||shard<0||shard>=shards)throw new IllegalArgumentException("Bounded benchmark shard");
        String datasetName=System.getenv().getOrDefault("PLANNING_BENCHMARK_DATASET","acceptance-requests.json");
        var cases=new ArrayList<>(PlanningBenchmarkCases.load(datasetName));if(cases.size()<30||cases.size()>50)throw new IllegalStateException("30–50 committed cases required");
        int primaryCount=cases.size();
        byte[] dataset;try(var in=PlanningLiveBenchmark.class.getResourceAsStream("/planning/"+datasetName)){dataset=Objects.requireNonNull(in).readAllBytes();}
        byte[] supplement=new byte[0];
        if("true".equals(System.getenv("PLANNING_STRUCTURE_SUPPLEMENT"))) {
            try(var in=PlanningLiveBenchmark.class.getResourceAsStream("/planning/structure-cases.json")){supplement=Objects.requireNonNull(in).readAllBytes();}
            cases.addAll(PlanningBenchmarkCases.load("structure-cases.json"));
        }
        int resourceStart=cases.size(); byte[] resourceSupplement=new byte[0];
        if("true".equals(System.getenv("PLANNING_RESOURCE_SUPPLEMENT"))) {
            try(var in=PlanningLiveBenchmark.class.getResourceAsStream("/planning/resource-cases.json")){resourceSupplement=Objects.requireNonNull(in).readAllBytes();}
            cases.addAll(PlanningBenchmarkCases.load("resource-cases.json"));
        }
        int facilityStart=cases.size(); byte[] facilitySupplement=new byte[0];
        if("true".equals(System.getenv("PLANNING_FACILITY_SUPPLEMENT"))) {
            try(var in=PlanningLiveBenchmark.class.getResourceAsStream("/planning/facility-cases.json")){facilitySupplement=Objects.requireNonNull(in).readAllBytes();}
            cases.addAll(PlanningBenchmarkCases.load("facility-cases.json"));
        }
        int orderStart=cases.size(); byte[] orderSupplement=new byte[0];
        if("true".equals(System.getenv("PLANNING_ORDER_SUPPLEMENT"))) {
            try(var in=PlanningLiveBenchmark.class.getResourceAsStream("/planning/order-cases.json")){orderSupplement=Objects.requireNonNull(in).readAllBytes();}
            cases.addAll(PlanningBenchmarkCases.load("order-cases.json"));
        }
        String source=new String(new ProcessBuilder("git","rev-parse","HEAD").start().getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim();
        String tree=new String(new ProcessBuilder("git","rev-parse","HEAD^{tree}").start().getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim();
        Path output=Path.of("providers/build/planning-quality");Files.createDirectories(output);
        String name=model.replace(':','-').replace('.','_');Path raw=output.resolve(name+"-"+shard+".jsonl");Files.deleteIfExists(raw);
        Path extra=output.resolve(name+"-structure-unseen-"+shard+".ndjson");Files.deleteIfExists(extra);
        Path resources=output.resolve(name+"-resource-unseen-"+shard+".rjsonl");Files.deleteIfExists(resources);
        Path facilities=output.resolve(name+"-facility-unseen-"+shard+".fjsonl");Files.deleteIfExists(facilities);
        Path ordered=output.resolve(name+"-order-unseen-"+shard+".ojsonl");Files.deleteIfExists(ordered);
        Clock clock=Clock.systemUTC();int valid=0,completed=0,extraValid=0,extraCompleted=0,resourceValid=0,resourceCompleted=0,facilityValid=0,facilityCompleted=0,orderValid=0,orderCompleted=0;long calls=0,repairs=0;
        try(var capture=new PlanningWireCapture(model,clock);
            var broker=new AIWorkBroker(capture.adapter(),clock,AIWorkBroker.Settings.defaults(),AIWorkBroker.sessionLimits(clock.millis()),r->true,AIWorkBroker.Sharing.privateScopes())) {
            for(int index=shard;index<cases.size();index+=shards) {
                var c=cases.get(index);boolean additional=index>=primaryCount,resourceAdditional=index>=resourceStart,facilityAdditional=index>=facilityStart,orderAdditional=index>=orderStart;var input=input(c);long deadline=clock.millis()+180000;
                var totals=new Budgets.Limits(Map.of(Budgets.Kind.CALLS,2L,Budgets.Kind.REPAIRS,1L,
                        Budgets.Kind.INPUT_BYTES,65536L,Budgets.Kind.OUTPUT_BYTES,16384L),deadline);
                var ledger=new Budgets.Ledger(totals,clock);var attempts=new ArrayList<Map<String,Object>>();
                String prompt=input.prompt();Check checked=null;long started=System.nanoTime();
                for(int attempt=0;attempt<PlanningFeedback.MAX_ATTEMPTS;attempt++) {
                    var role=attempt==0?Generation.Role.PLANNING:Generation.Role.PLANNING_REPAIR;
                    var request=new Generation.Request(Plan.stable("benchmark-call:"+c.id()+":"+attempt),input.bound(),input.spec(),List.of(),
                            input.wheat()==null?List.of():List.of(METHOD),role,prompt);
                    var limits=new Budgets.InferenceLimits(new Budgets.Limits(Map.of(Budgets.Kind.CALLS,1L,Budgets.Kind.REPAIRS,attempt==0?0L:1L,
                            Budgets.Kind.INPUT_BYTES,32768L,Budgets.Kind.OUTPUT_BYTES,8192L),deadline),32768,8192);
                    long begin=System.nanoTime();var handle=broker.submit(request,limits,ledger,input.contract(),AIWorkBroker.Priority.NORMAL);
                    while(!handle.result().toCompletableFuture().isDone()&&clock.millis()<deadline){broker.step();Thread.sleep(10);}
                    if(!handle.result().toCompletableFuture().isDone()){handle.cancel();broker.step();}
                    var result=handle.result().toCompletableFuture().get(5,TimeUnit.SECONDS);checked=check(c,input,result,deadline);
                    var row=new LinkedHashMap<String,Object>();row.put("attempt",attempt+1);row.put("role",role.name());row.put("valid",checked.valid());
                    row.put("diagnostic",checked.diagnostic());row.put("path",checked.path());row.put("outcome",result.outcome().name());row.put("modelDigest",result.descriptor().digest());
                    row.put("latencyMillis",Math.max(1,(System.nanoTime()-begin)/1000000));row.put("inputBytes",result.usage().inputBytes());row.put("outputBytes",result.usage().outputBytes());
                    row.put("promptTokens",result.usage().promptTokens());row.put("outputTokens",result.usage().outputTokens());row.put("promptSha256",sha(prompt.getBytes(StandardCharsets.UTF_8)));
                    var shape=input.wheat()==null?MaterialPlan.outputShape(c.material()):Plan.outputShape(input.bound().request());
                    row.put("constraintSha256",sha(StrictJson.canonical(shape).getBytes(StandardCharsets.UTF_8)));
                    row.put("constraintScope","request quantities and deterministic material precheck; model orders required steps");
                    row.put("prompt",prompt);row.put("candidate",result.candidateIr()==null?"unavailable":result.candidateIr());row.put("compute",result.compute().name());
                    row.put("transport",capture.evidence(c.material()!=null));attempts.add(row);
                    if(checked.valid()||attempt==1||clock.millis()>=deadline)break;
                    prompt=PlanningFeedback.correction(input.prompt(),result.candidateIr(),checked.diagnostic(),checked.path());
                }
                boolean accepted=Objects.requireNonNull(checked).valid();
                if(orderAdditional){if(accepted)orderValid++;orderCompleted++;}
                else if(facilityAdditional){if(accepted)facilityValid++;facilityCompleted++;}
                else if(resourceAdditional){if(accepted)resourceValid++;resourceCompleted++;}
                else if(additional){if(accepted)extraValid++;extraCompleted++;}else{if(accepted)valid++;completed++;}
                long count=ledger.snapshot().getOrDefault(Budgets.Kind.CALLS,0L),fixed=ledger.snapshot().getOrDefault(Budgets.Kind.REPAIRS,0L);
                if(!additional){calls+=count;repairs+=fixed;}
                var row=new LinkedHashMap<String,Object>();row.put("schema",2);row.put("case",orderAdditional?index-orderStart:facilityAdditional?index-facilityStart:resourceAdditional?index-resourceStart:additional?index-primaryCount:index);row.put("caseId",c.id());row.put("category",c.category());row.put("request",c.request());
                row.put("expectedStatus",c.expectedStatus());row.put("model",model);row.put("shard",shard);row.put("shards",shards);row.put("sourceCommit",source);row.put("sourceTree",tree);
                row.put("ciAttempt",Integer.parseInt(System.getenv().getOrDefault("GITHUB_RUN_ATTEMPT","1")));row.put("ciRunId",System.getenv().getOrDefault("GITHUB_RUN_ID","local"));row.put("datasetSha256",sha(orderAdditional?orderSupplement:facilityAdditional?facilitySupplement:resourceAdditional?resourceSupplement:additional?supplement:dataset));row.put("corpusRole",orderAdditional?"supplemental-contract-schema-order":facilityAdditional?"supplemental-facility-state":resourceAdditional?"supplemental-resource-grounding":additional?"supplemental-empty-array-structure":"original-frozen-benchmark");row.put("firstPassValid",attempts.getFirst().get("valid"));row.put("valid",accepted);row.put("attempts",attempts);
                row.put("calls",count);row.put("repairs",fixed);row.put("usage",ledger.snapshot().entrySet().stream().collect(java.util.stream.Collectors.toMap(e->e.getKey().name(),Map.Entry::getValue)));
                row.put("latencyMillis",Math.max(1,(System.nanoTime()-started)/1000000));row.put("nativeEffects",0);row.put("dedicatedModels",0);
                String line=StrictJson.canonical(row);Files.writeString(orderAdditional?ordered:facilityAdditional?facilities:resourceAdditional?resources:additional?extra:raw,line+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                System.out.println((orderAdditional?"Task 5.4 independent schema order ":facilityAdditional?"Task 5.3 independent facilities ":resourceAdditional?"Task 5.2 independent resources ":additional?"Task 5.1 independent structure ":"IMP-019 planning quality ")+line);
            }
        }
        System.out.println("IMP-019 held-out completed cases="+completed+" valid="+valid+" calls="+calls+" repairs="+repairs+" dedicatedModels=0 nativeEffects=0");
        if(extraCompleted>0)System.out.println("Task 5.1 separate unseen cases="+extraCompleted+" valid="+extraValid+" dedicatedModels=0 nativeEffects=0");
        if(orderCompleted>0)System.out.println("Task 5.4 separate unseen cases="+orderCompleted+" valid="+orderValid+" dedicatedModels=0 nativeEffects=0");
        if(facilityCompleted>0)System.out.println("Task 5.3 separate unseen cases="+facilityCompleted+" valid="+facilityValid+" dedicatedModels=0 nativeEffects=0");
        if(resourceCompleted>0)System.out.println("Task 5.2 separate unseen cases="+resourceCompleted+" valid="+resourceValid+" dedicatedModels=0 nativeEffects=0");
    }
}
