package dev.aivillages.e2e.paralleld;

import dev.aivillages.core.kernel.storage.StorageBindingRules;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.storage.TagValueInput;

/** One composite case with two actual JVMs sharing the saved isolated Minecraft world. */
public final class DStorageTopologyReloadGameTests {
    @GameTest(maxTicks=6000)
    public void witnessedColdReloadPreservesMatchingMarkerAndRefusesPendingOrMismatch(GameTestHelper h) {
        boolean read=System.getProperty("cognitivecraft.parallelD.phase").equals("reload-read");
        DNativeScene.open(h,"D02-GT02-RELOAD",read,s->{
            BlockPos good=new BlockPos(128,80,128),pending=good.east(2),mismatch=good.east(4);
            s.level.setChunkForced(good.getX()>>4,good.getZ()>>4,true);s.level.getChunkAt(good);
            if(read) {
                var a=s.named("good");var b=s.named("pending");var c=s.named("mismatch");
                h.assertTrue(s.marker(good)!=null && s.adapter.resolve(a).isPresent(),"actual saved marker/metadata did not reload");
                h.assertTrue(s.adapter.resolve(b).isEmpty() && s.adapter.resolve(c).isEmpty(),"pending or mismatched history became live");
                s.frame("RELOAD_VALID",a,good);s.frame("RELOAD_PENDING_REFUSED",b,pending);s.frame("RELOAD_MISMATCH_REFUSED",c,mismatch);s.finish();return;
            }
            s.level.setBlockAndUpdate(good,Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING,Direction.UP));
            s.level.setBlockAndUpdate(pending,Blocks.BARREL.defaultBlockState());s.level.setBlockAndUpdate(mismatch,Blocks.BARREL.defaultBlockState());
            s.register(good,StorageBindingRules.BARREL,"good",a->s.bind(a,good,true,live->{
                s.level.setBlockAndUpdate(good,s.level.getBlockState(good).setValue(BarrelBlock.OPEN,true));
                h.assertTrue(s.adapter.validates(live),"opening barrel changed topology");s.frame("SAVE_VALID",a,good);
                s.register(pending,StorageBindingRules.BARREL,"pending",b->s.bind(b,pending,false,uncommitted->{
                    h.assertTrue(s.adapter.resolve(b).isEmpty(),"pending intent grants live endpoint");s.frame("SAVE_PENDING",b,pending);
                    s.register(mismatch,StorageBindingRules.BARREL,"mismatch",c->s.bind(c,mismatch,true,old->{
                        var tag=s.be(mismatch).saveWithFullMetadata(s.level.registryAccess());
                        tag.getCompoundOrEmpty("cognitivecraft_storage_v1").putLong("generation",2);
                        s.be(mismatch).loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING,s.level.registryAccess(),tag));
                        h.assertTrue(!s.adapter.validates(old),"marker edit preserved stale binding");s.frame("SAVE_MISMATCH",c,mismatch);
                        s.level.getServer().saveEverything(false,true,true);s.finish();
                    }));
                }));
            }));
        });
    }
}
