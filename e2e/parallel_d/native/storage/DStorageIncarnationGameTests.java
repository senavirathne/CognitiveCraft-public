package dev.aivillages.e2e.paralleld;

import dev.aivillages.core.kernel.storage.StorageBindingRules;
import dev.aivillages.fabric.storage.StorageMarkerAccess;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

public final class DStorageIncarnationGameTests {
    @GameTest(maxTicks=4000)
    public void breakRebuildRefusesOldBindingAndRenamePreservesIdentity(GameTestHelper h) {
        DNativeScene.open(h,"D02-GT01",false,s->{
            BlockPos p=h.absolutePos(new BlockPos(2,2,2));s.level.setBlockAndUpdate(p,Blocks.CHEST.defaultBlockState());
            h.assertTrue(s.marker(p)==null,"selection created marker");
            s.register(p,StorageBindingRules.SINGLE,"before",id->{
                s.frame("UNMARKED",id,p);s.allow.set(false);
                boolean refused=false;try{s.adapter.plan(id,1,p);}catch(SecurityException expected){refused=true;}
                h.assertTrue(refused && s.marker(p)==null,"unprovided authority wrote marker");s.allow.set(true);
                s.bind(id,p,true,first->{
                    h.assertTrue(s.adapter.validates(first),"fresh binding unavailable");s.frame("BOUND",id,p);
                    s.be(p).applyComponents(net.minecraft.core.component.DataComponentMap.builder().set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,Component.literal("native rename")).build(),net.minecraft.core.component.DataComponentPatch.EMPTY);
                    var snapshot=s.registry.snapshot();var row=snapshot.records().get(id);
                    DNativeScene.when(h,s.registry.rename(s.caller,snapshot.revision(),id,row.revision(),"after"),a->{
                        s.ack(a);s.adapter.updateMetadata(s.registry.snapshot());h.assertTrue(s.adapter.validates(first),"rename changed identity");s.frame("RENAMED",id,p);
                        var original=s.marker(p);s.level.destroyBlock(p,false);s.level.setBlockAndUpdate(p,Blocks.CHEST.defaultBlockState());
                        h.assertTrue(s.marker(p)==null && !s.adapter.validates(first),"replacement inherited old binding");s.frame("REPLACED_UNMARKED",id,p);
                        s.bind(id,p,true,second->{
                            h.assertTrue(!original.incarnation().equals(s.marker(p).incarnation()),"rebuild reused incarnation");
                            h.assertTrue(!s.adapter.validates(first) && s.adapter.validates(second),"explicit rebind/stale refusal");s.frame("REBOUND",id,p);s.finish();
                        });
                    });
                });
            });
        });
    }
}
