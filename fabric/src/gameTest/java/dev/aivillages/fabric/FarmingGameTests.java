package dev.aivillages.fabric;

import dev.aivillages.core.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ConversionParams;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.gamerules.GameRules;
import static dev.aivillages.core.Plan.Action.*;

/** Real world tests. No model service, player account or external API is used. */
public class FarmingGameTests {
    private static final BlockPos HOME=new BlockPos(2,1,2), TABLE=new BlockPos(3,1,2);
    private static Plan.Step step(Plan.Action action){return new Plan.Step(action,1,600,Plan.Condition.ALWAYS,"");}
    private static void floor(GameTestHelper h) {
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)h.setBlock(x,0,z,Blocks.STONE);
    }
    private static FarmPort port(GameTestHelper h,Villager v) {
        var p=h.absolutePos(HOME);return new FarmPort(v,new WorldData.Place(h.getLevel().dimension().identifier().toString(),p.getX(),p.getY(),p.getZ()),3,new Reservations(),s->{});
    }
    @GameTest(padding=16)
    public void craftingRequiresTableAndConservesWheat(GameTestHelper h) {
        floor(h);var v=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,2,1,3);v.getInventory().addItem(new ItemStack(Items.WHEAT,9));var p=port(h,v);
        h.assertTrue(p.perform(step(CRAFT_BREAD))==WorldPort.Outcome.BLOCKED,"Crafting without a table must fail");
        h.assertTrue(p.count(Items.WHEAT)==9,"Blocked crafting consumed wheat");h.setBlock(TABLE,Blocks.CRAFTING_TABLE);
        for(int i=0;i<3;i++)h.assertTrue(p.perform(step(CRAFT_BREAD))==WorldPort.Outcome.DONE,"Craft failed");
        h.assertTrue(p.count(Items.WHEAT)==0 && p.count(Items.BREAD)==3 && p.breadCrafted()==3,"Recipe accounting failed");h.succeed();
    }
    @GameTest(padding=16)
    public void matureWheatIsHarvestedAndReplanted(GameTestHelper h) {
        floor(h);var crop=new BlockPos(2,1,3);h.setBlock(crop.below(),Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE,7));
        h.setBlock(crop,Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7));
        var v=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,2,1,2);v.getInventory().addItem(new ItemStack(Items.WHEAT_SEEDS,1));var p=port(h,v);
        WorldPort.Outcome outcome=WorldPort.Outcome.WORKING;for(int i=0;i<30 && outcome==WorldPort.Outcome.WORKING;i++)outcome=p.perform(step(HARVEST_WHEAT));
        h.assertTrue(outcome==WorldPort.Outcome.DONE,"Mature crop not harvested");h.assertBlockProperty(crop,CropBlock.AGE,0);
        h.assertTrue(p.count(Items.WHEAT)==1,"Harvest must produce one wheat");h.succeed();
    }
    @GameTest(padding=16)
    public void fullStorageCannotDeleteOrDuplicateBread(GameTestHelper h) {
        floor(h);h.setBlock(HOME,Blocks.BARREL);var v=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,2,1,3);var p=port(h,v);
        v.getInventory().addItem(new ItemStack(Items.BREAD,2));var box=(Container)h.getLevel().getBlockEntity(h.absolutePos(HOME));
        for(int i=0;i<box.getContainerSize();i++)box.setItem(i,new ItemStack(Items.COBBLESTONE,64));
        h.assertTrue(p.perform(step(DELIVER_BREAD))==WorldPort.Outcome.EXHAUSTED && p.count(Items.BREAD)==2,"Full storage changed bread");
        box.setItem(0,ItemStack.EMPTY);h.assertTrue(p.perform(step(DELIVER_BREAD))==WorldPort.Outcome.DONE,"Delivery failed");
        h.assertTrue(box.countItem(Items.BREAD)==1 && p.count(Items.BREAD)==1,"Delivery duplicated or lost bread");h.succeed();
    }
    @GameTest(padding=16)
    public void mobGriefingRuleBlocksMutation(GameTestHelper h) {
        floor(h);h.setBlock(TABLE,Blocks.CRAFTING_TABLE);var v=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,2,1,3);v.getInventory().addItem(new ItemStack(Items.WHEAT,3));var p=port(h,v);
        var rules=h.getLevel().getGameRules();boolean before=rules.get(GameRules.MOB_GRIEFING);
        try {rules.set(GameRules.MOB_GRIEFING,false,h.getLevel().getServer());
            h.assertTrue(p.perform(step(CRAFT_BREAD))==WorldPort.Outcome.BLOCKED,"mobGriefing=false ignored");
            h.assertTrue(p.count(Items.WHEAT)==3 && p.count(Items.BREAD)==0,"Blocked action mutated inventory");
        } finally {rules.set(GameRules.MOB_GRIEFING,before,h.getLevel().getServer());}h.succeed();
    }
    @GameTest(maxTicks=3000,padding=16,skyAccess=true)
    public void enrolledVillagerNavigatesFarmsAndLearns(GameTestHelper h) {
        floor(h);h.setBlock(HOME,Blocks.BARREL);h.setBlock(TABLE,Blocks.CRAFTING_TABLE);
        for(int x=3;x<=5;x++)for(int z=3;z<=5;z++) {
            h.setBlock(x,0,z,Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE,7));
            h.setBlock(x,1,z,Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7));
        }
        var session=AiVillages.session();h.assertTrue(session!=null,"Session did not start");var v=h.spawn(EntityTypes.VILLAGER,6,1,6);v.setAge(0);
        var a=session.enroll(h.makeMockServerPlayerInLevel(),v,"FarmerTest");session.home(a,h.getLevel(),h.absolutePos(HOME));session.food(a,false);
        h.succeedWhen(()->{
            var box=(Container)h.getLevel().getBlockEntity(h.absolutePos(HOME));
            h.assertTrue(box.countItem(Items.BREAD)>=3,"Waiting for bread in storage; "+session.status(a));
            h.assertTrue(a.memories.stream().anyMatch(m->m.contains("bread_produced")),"Waiting for verified success");
            h.assertTrue(session.skillCount()>0,"Successful plan was not learned");session.release(a);
        });
    }
    @GameTest(maxTicks=150,padding=16)
    public void lowHealthReleasesVanillaAi(GameTestHelper h) {
        floor(h);h.setBlock(TABLE,Blocks.CRAFTING_TABLE);var session=AiVillages.session();h.assertTrue(session!=null,"No session");
        var v=h.spawn(EntityTypes.VILLAGER,6,1,6);v.setAge(0);v.getInventory().addItem(new ItemStack(Items.WHEAT,9));
        var a=session.enroll(h.makeMockServerPlayerInLevel(),v,"SafetyTest");session.home(a,h.getLevel(),h.absolutePos(HOME));session.food(a,false);
        h.runAtTickTime(40,()->v.setHealth(1));
        h.runAtTickTime(60,()->{
            h.assertTrue(!AiVillages.controls(v),"Low health did not release vanilla AI");
            h.assertTrue(!session.status(a).contains("working"),"Unsafe plan still running");session.release(a);h.succeed();
        });
    }
    @GameTest(padding=16)
    public void conversionPreservesIdentityAndCancelsWork(GameTestHelper h) {
        floor(h);var session=AiVillages.session();var v=h.spawn(EntityTypes.VILLAGER,2,1,3);v.setAge(0);
        var a=session.enroll(h.makeMockServerPlayerInLevel(),v,"ConversionTest");String identity=a.id;
        a.remember("Identity test memory");session.food(a,false);
        var zombie=v.convertTo(EntityTypes.ZOMBIE_VILLAGER,ConversionParams.single(v,false,false),mob->{});
        h.assertTrue(zombie!=null && a.entityId.equals(zombie.getUUID().toString()),"Conversion did not track replacement UUID");
        h.assertTrue(a.form.equals("transformed") && a.alive,"Transformation lifecycle is wrong");
        h.assertTrue(!AiVillages.controls(v),"Old entity still controlled");
        var cured=zombie.convertTo(EntityTypes.VILLAGER,ConversionParams.single(zombie,false,false),mob->{});
        h.assertTrue(cured!=null && a.entityId.equals(cured.getUUID().toString()),"Curing did not update UUID");
        h.assertTrue(a.id.equals(identity) && a.memories.contains("Identity test memory") && a.form.equals("villager"),"Curing lost identity or memory");
        session.release(a);h.succeed();
    }
    @GameTest(padding=16)
    public void deathIsRecordedWithoutDefaultRespawn(GameTestHelper h) {
        floor(h);var session=AiVillages.session();var v=h.spawn(EntityTypes.VILLAGER,2,1,3);v.setAge(0);
        var a=session.enroll(h.makeMockServerPlayerInLevel(),v,"DeathTest");String before=session.config.deathMode;
        try {
            session.config.deathMode="permanent";h.kill(v);
            h.assertTrue(!a.alive && a.respawnAtEpochMillis==0,"Death did not remain permanent");
            h.assertTrue(a.memories.contains("I died."),"Death memory missing");
        } finally {session.config.deathMode=before;session.release(a);}h.succeed();
    }
    @GameTest(maxTicks=100,padding=16)
    public void optionalRespawnKeepsIdentityButNotInventory(GameTestHelper h) {
        floor(h);var session=AiVillages.session();var v=h.spawn(EntityTypes.VILLAGER,2,1,3);v.setAge(0);
        var a=session.enroll(h.makeMockServerPlayerInLevel(),v,"RespawnTest");String before=session.config.deathMode,identity=a.id,oldEntity=a.entityId;
        a.remember("Before respawn");v.getInventory().addItem(new ItemStack(Items.BREAD,9));session.config.deathMode="respawn";h.kill(v);
        h.assertTrue(!a.alive && a.respawnAtEpochMillis>0,"Optional respawn was not scheduled");
        a.respawnAtEpochMillis=1; // Advance only the test deadline, avoiding a wall-clock wait.
        h.runAtTickTime(40,()->{
            try {
                var replacement=session.find(a);
                h.assertTrue(a.alive && replacement!=null && !a.entityId.equals(oldEntity),"Villager did not respawn");
                h.assertTrue(a.id.equals(identity) && a.memories.contains("Before respawn"),"Respawn lost persistent identity or memory");
                h.assertTrue(replacement.getInventory().isEmpty(),"Respawn duplicated inventory");
            } finally {session.config.deathMode=before;session.release(a);}h.succeed();
        });
    }
}
