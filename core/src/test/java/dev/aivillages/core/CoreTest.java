package dev.aivillages.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.aivillages.core.Plan.Action.*;
import static dev.aivillages.core.Plan.Condition.*;

class CoreTest {
    @TempDir Path temp;
    private static Plan.Step step(Plan.Action action, int count, int timeout) {
        return new Plan.Step(action, count, timeout, ALWAYS, "");
    }
    private static Plan plan(Plan.Step... steps) { return new Plan("test_food", 1, "FOOD_SUPPLY", 200, List.of(steps)); }
    private static String valid() { return Json.GSON.toJson(Plan.food()); }
    @Test void rejectsCommandsAndUnknownFields() {
        assertThrows(IllegalArgumentException.class, () -> PlanCodec.decode(valid().replace("HARVEST_WHEAT", "EXECUTE_COMMAND")));
        assertThrows(IllegalArgumentException.class, () -> PlanCodec.decode(valid().replace("\"goal\":", "\"command\":\"op player\",\"goal\":")));
        assertThrows(IllegalArgumentException.class, () -> PlanCodec.validate(plan(new Plan.Step(SPEAK,1,20,ALWAYS,"/op player"),step(CRAFT_BREAD,1,20))));
    }
    @Test void strictJsonRejectsDuplicateFieldsAndTrailingData() {
        assertThrows(IllegalArgumentException.class, () -> PlanCodec.decode(valid().replace("\"version\": 1", "\"version\": 1, \"version\": 2")));
        for (var bad : List.of(valid()+"{}", "```json\n"+valid()+"\n```", "{id: 'bad'}"))
            assertThrows(IllegalArgumentException.class, () -> PlanCodec.decode(bad));
    }
    @Test void rejectsFractionalCountsAndUnboundedWork() {
        assertThrows(IllegalArgumentException.class, () -> PlanCodec.decode(valid().replace("\"count\": 24", "\"count\": 1.5")));
        assertThrows(IllegalArgumentException.class, () -> PlanCodec.validate(plan(step(CRAFT_BREAD,65,20))));
        assertThrows(IllegalArgumentException.class, () -> PlanCodec.validate(plan(step(CRAFT_BREAD,64,20),step(CRAFT_BREAD,64,20),step(CRAFT_BREAD,64,20),step(CRAFT_BREAD,64,20),step(CRAFT_BREAD,1,20))));
    }
    @Test void rejectsDeepAndOversizedInput() {
        assertThrows(IllegalArgumentException.class, () -> Json.object("{\"a\":"+"[".repeat(20)+"0"+"]".repeat(20)+"}",1000));
        assertThrows(IllegalArgumentException.class, () -> PlanCodec.decode(" ".repeat(24001)));
    }
    @Test void canonicalPlanRoundTrips() { assertEquals(Plan.food(), PlanCodec.decode(valid())); }
    @Test void productionUsesRealResourceDelta() {
        var world = new FakeWorld(); world.wheat=9;
        var runner = new PlanRunner(plan(step(CRAFT_BREAD,8,100)),world,0);
        for(int t=1;t<20;t++) runner.tick(t);
        assertEquals(PlanRunner.State.SUCCEEDED,runner.state());
        assertEquals(3,world.bread); assertEquals(0,world.wheat); assertEquals(4,world.calls);
    }
    @Test void noResourcesMeansNoSuccessOrLearnedSkill() {
        var world = new FakeWorld(); var p=plan(step(CRAFT_BREAD,2,20)); var runner=new PlanRunner(p,world,0);
        runner.tick(1); runner.tick(2); var data=new WorldData();
        data.record(p,"mock",runner.state()==PlanRunner.State.SUCCEEDED,runner.reason(),2);
        assertEquals(PlanRunner.State.FAILED,runner.state()); assertTrue(data.skills.isEmpty());
    }
    @Test void speechDoesNotProveSuccess() {
        var world=new FakeWorld(); var runner=new PlanRunner(plan(new Plan.Step(SPEAK,1,20,ALWAYS,"I made bread"),step(CRAFT_BREAD,1,20)),world,0);
        for(int t=1;t<5;t++) runner.tick(t);
        assertEquals(PlanRunner.State.FAILED,runner.state());
    }
    @Test void unsafeInterruptHappensBeforeMutation() {
        var world=new FakeWorld(); world.unsafe=true;world.wheat=9;
        var runner=new PlanRunner(plan(step(CRAFT_BREAD,1,20)),world,0); runner.tick(1);
        assertEquals(PlanRunner.State.CANCELLED,runner.state());assertEquals(0,world.calls);assertTrue(world.stops>0);
    }
    @Test void deadVillagerCannotAct() {
        var world=new FakeWorld(); world.alive=false;
        var runner=new PlanRunner(plan(step(CRAFT_BREAD,1,20)),world,0);runner.tick(1);
        assertEquals("villager_dead",runner.reason());assertEquals(0,world.calls);
    }
    @Test void unloadedWorkPausesButExpires() {
        var world=new FakeWorld();world.loaded=false;var runner=new PlanRunner(plan(step(CRAFT_BREAD,1,20)),world,0);
        assertEquals(PlanRunner.State.RUNNING,runner.tick(30));assertEquals(0,world.calls);
        assertEquals(PlanRunner.State.FAILED,runner.tick(200));assertEquals("plan_expired",runner.reason());
    }
    @Test void stuckWorkTimesOut() {
        var world=new FakeWorld();world.forced=WorldPort.Outcome.WORKING;
        var runner=new PlanRunner(plan(step(CRAFT_BREAD,1,10)),world,0);runner.tick(1);runner.tick(10);
        assertEquals("step_timeout:CRAFT_BREAD",runner.reason());assertEquals(1,world.calls);
    }
    @Test void blockedAndCancelledPlansNeverResume() {
        var world=new FakeWorld();world.forced=WorldPort.Outcome.BLOCKED;
        var runner=new PlanRunner(plan(step(CRAFT_BREAD,1,20)),world,0);runner.tick(1);runner.tick(2);
        assertEquals(1,world.calls);assertEquals(PlanRunner.State.FAILED,runner.state());
        var cancelled=new PlanRunner(plan(step(CRAFT_BREAD,1,20)),world,0);cancelled.cancel("player_stop");cancelled.tick(1);
        assertEquals(1,world.calls);assertEquals("player_stop",cancelled.reason());
    }
    @Test void waitYieldsUntilDeadline() {
        var world=new FakeWorld();world.wheat=3;var runner=new PlanRunner(plan(step(WAIT,1,40),step(CRAFT_BREAD,1,40)),world,0);
        for(int t=1;t<20;t++) runner.tick(t);
        assertEquals(0,world.calls);runner.tick(20);runner.tick(21);runner.tick(22);
        assertEquals(PlanRunner.State.SUCCEEDED,runner.state());
    }
    @Test void successfulSkillPersistsAndRepeatedFailureDisablesIt() throws Exception {
        var data=new WorldData();data.record(Plan.food(),"mock",true,"bread_produced",100);
        var store=new WorldStore(temp.resolve("state.json"));store.save(data);var restored=store.load();
        assertTrue(restored.reusableFoodSkill().isPresent());
        restored.record(Plan.food(),"cached",false,"blocked",20);restored.record(Plan.food(),"cached",false,"blocked",20);
        assertTrue(restored.reusableFoodSkill().isEmpty());assertEquals(140,restored.skills.values().iterator().next().totalTicks);
    }
    @Test void stableIdentityMemoryAndPreviousGenerationSurviveSaving() throws Exception {
        var data=new WorldData();var a=agent();a.remember("Met Alex");a.interaction(a.owner);a.lastFoodEvidence="9:2:0:4";data.agents.put(a.id,a);
        var path=temp.resolve("state.json");var store=new WorldStore(path);store.save(data);
        String oldEntity=a.entityId;a.entityId=UUID.randomUUID().toString();a.remember("Returned to villager form.");store.save(data);
        var restored=store.load().agents.get(a.id);assertEquals(a.entityId,restored.entityId);assertEquals(2,restored.memories.size());assertEquals(1,restored.trust.get(a.owner));assertEquals(a.lastFoodEvidence,restored.lastFoodEvidence);
        assertEquals(oldEntity,new WorldStore(temp.resolve("state.json.bak")).load().agents.get(a.id).entityId);
    }
    @Test void corruptAndFutureSavesFailClosed() throws Exception {
        var path=temp.resolve("state.json");var store=new WorldStore(path);
        for(var bad:List.of("not json","{\"schemaVersion\":999}")) {
            Files.writeString(path,bad);assertThrows(IOException.class,store::load);assertEquals(bad,Files.readString(path));
        }
    }
    @Test void memoryAndTrustStayBounded() {
        var a=agent();for(int i=0;i<100;i++){a.remember("x".repeat(600));a.interaction(UUID.randomUUID().toString());}
        assertEquals(40,a.memories.size());assertEquals(512,a.memories.getFirst().length());assertEquals(32,a.trust.size());
        for(int i=0;i<200;i++)a.interaction(a.owner);assertEquals(100,a.trust.get(a.owner));
    }
    private static WorldData.Agent agent() {
        var a=new WorldData.Agent();a.id=UUID.randomUUID().toString();a.entityId=UUID.randomUUID().toString();a.owner=UUID.randomUUID().toString();a.name="Ada";a.home=new WorldData.Place("minecraft:overworld",0,64,0);return a;
    }
    static final class FakeWorld implements WorldPort {
        boolean loaded=true,alive=true,unsafe;int wheat,seeds,bread,calls,stops;Outcome forced;
        public Snapshot snapshot(){return new Snapshot(loaded,alive,unsafe,wheat,seeds,bread);}
        public Outcome perform(Plan.Step step){calls++;if(forced!=null)return forced;if(step.action()==CRAFT_BREAD){if(wheat<3)return Outcome.EXHAUSTED;wheat-=3;bread++;}return Outcome.DONE;}
        public void stop(){stops++;}public int breadCrafted(){return bread;}
    }
}
