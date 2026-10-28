package dev.aivillages.e2e.paralleld;

import dev.aivillages.core.kernel.storage.StorageBindingRules;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.storage.TagValueInput;

public final class DStorageTopologyGameTests {
    @GameTest(maxTicks=5000)
    public void pairingSplittingRepairingAndClonedMarkersRefuseStaleBindings(GameTestHelper h) {
        DNativeScene.open(h,"D02-GT02-TOPOLOGY",false,s->{
            BlockPos a=h.absolutePos(new BlockPos(1,2,2)),b=a.east(),clone=a.east(4);
            var single=Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING,Direction.NORTH);
            s.level.setBlock(a,single,2);
            s.register(a,StorageBindingRules.SINGLE,"pair",id->s.bind(id,a,true,first->{
                s.frame("SINGLE",id,a);
                s.level.setBlock(b,single.setValue(ChestBlock.TYPE,ChestType.RIGHT),2);s.level.setBlock(a,single.setValue(ChestBlock.TYPE,ChestType.LEFT),2);
                h.assertTrue(!s.adapter.validates(first),"pairing kept stale endpoint");s.frame("PAIRED_STALE",id,a,b);
                s.bind(id,a,true,pair->{
                    h.assertTrue(s.adapter.validates(pair) && pair.topology().members().size()==2,"native pair not bound");s.frame("PAIRED_REBOUND",id,a,b);
                    s.level.destroyBlock(b,false);s.level.setBlock(a,single,2);
                    h.assertTrue(!s.adapter.validates(pair),"split preserved pair");s.frame("SPLIT_STALE",id,a,b);
                    s.level.setBlock(b,single.setValue(ChestBlock.TYPE,ChestType.RIGHT),2);s.level.setBlock(a,single.setValue(ChestBlock.TYPE,ChestType.LEFT),2);
                    h.assertTrue(!s.adapter.validates(pair),"repair revived stale pair");
                    s.bind(id,a,true,repaired->{
                        h.assertTrue(s.adapter.validates(repaired),"authorized repair failed");s.frame("REPAIRED",id,a,b);
                        s.level.setBlock(clone,single,2);
                        s.register(clone,StorageBindingRules.SINGLE,"clone",other->s.bind(other,clone,true,otherBinding->{
                            s.frame("CLONE_BEFORE",other,clone);
                            var copied=s.be(a).saveWithFullMetadata(s.level.registryAccess());
                            s.be(clone).loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING,s.level.registryAccess(),copied));
                            h.assertTrue(!s.adapter.validates(repaired) && !s.adapter.validates(otherBinding),"duplicate did not quarantine every active binding");
                            s.frame("CLONE_ORIGINAL_REFUSED",id,a,b);s.frame("CLONE_COPY_REFUSED",other,clone);
                            s.level.destroyBlock(clone,false);
                            h.assertTrue(!s.adapter.validates(repaired),"duplicate quarantine automatically cleared");s.frame("QUARANTINE_RETAINED",id,a,b);s.finish();
                        }));
                    });
                });
            }));
        });
    }
}
