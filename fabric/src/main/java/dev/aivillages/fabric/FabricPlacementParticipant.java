package dev.aivillages.fabric;

import dev.aivillages.core.kernel.Contracts.FacilityRef;
import dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.Marker;
import dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.MarkerPhase;
import dev.aivillages.core.kernel.runtime.evidence.NativeRecipeJournal;
import dev.aivillages.fabric.runtime.evidence.HarvestSourceParticipant;
import dev.aivillages.fabric.runtime.evidence.HarvestSourceState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import static dev.aivillages.core.kernel.RecipeTransaction.fingerprint;

/** A native facility cell in the original chunk save envelope. */
final class FabricPlacementParticipant {
    private final ServerLevel level;
    FabricPlacementParticipant(ServerLevel level){this.level=level;}

    record Capture(LevelChunk chunk,BlockPos position,BlockState block,HarvestSourceState history,
                   NativeRecipeJournal.Participant evidence) {
        void mark(Marker marker){history.mark(position,marker,chunk::markUnsaved);}
        boolean placedFrom(Capture prior) {
            return prior.block.isAir() && block.is(Blocks.CRAFTING_TABLE) && prior.chunk==chunk
                    && evidence.incarnation().equals(prior.evidence.incarnation())
                    && evidence.revision()==Math.addExact(prior.evidence.revision(),1);
        }
    }
    static String address(FacilityRef site){return "block:"+site.dimension()+"/"+site.x()+","+site.y()+","+site.z();}

    Capture capture(FacilityRef site,boolean activate) {
        if(!level.getServer().isSameThread())throw new IllegalStateException("Placement participant game thread");
        if(!site.dimension().equals(level.dimension().identifier().toString()))return null;
        var position=new BlockPos(site.x(),site.y(),site.z());
        var chunk=level.getChunkSource().getChunkNow(position.getX()>>4,position.getZ()>>4);
        if(chunk==null)return null;
        var block=chunk.getBlockState(position);
        var history=((HarvestSourceParticipant)chunk).cognitivecraft$harvestSources();
        String actual=HarvestSourceState.blockStateText(block);
        var snapshot=history.snapshot(site.dimension(),chunk.getPos(),position,actual,activate);
        if(snapshot==null)return null;
        var evidence=new NativeRecipeJournal.Participant(address(site),snapshot.incarnation(),snapshot.revision(),fingerprint(actual));
        return new Capture(chunk,position,block,history,evidence);
    }

    boolean restored(FacilityRef site,NativeRecipeJournal.Entry entry,NativeRecipeJournal.Participant saved) {
        var result=entry.result();
        if(result==null || result.status()!=NativeRecipeJournal.Status.APPLIED || !result.after().contains(saved))return false;
        var marker=new Marker(fingerprint(entry.intent().token().toString()),entry.intent().digest(),MarkerPhase.RESULT,result.digest());
        try {
            var actual=capture(site,false);
            return actual!=null && actual.history().restored(actual.position()) && saved.equals(actual.evidence())
                    && marker.equals(actual.history().marker(actual.position()));
        }catch(RuntimeException unavailable){return false;}
    }
}
