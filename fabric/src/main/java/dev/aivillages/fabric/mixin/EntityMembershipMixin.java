package dev.aivillages.fabric.mixin;

import dev.aivillages.core.kernel.runtime.evidence.NativePickup;
import dev.aivillages.fabric.runtime.evidence.*;
import java.util.UUID;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityMembershipMixin implements EntityMembershipParticipant {
    @Unique private EntityMembershipData cognitivecraft$membershipData;
    @Override public EntityMembershipData cognitivecraft$membershipData(){if(cognitivecraft$membershipData==null)cognitivecraft$membershipData=new EntityMembershipData((Entity)(Object)this);return cognitivecraft$membershipData;}
    @Override public EntityMembershipTrail cognitivecraft$membership(){return cognitivecraft$membershipData==null?null:cognitivecraft$membershipData.trail();}
    @Override public EntityMembershipTrail cognitivecraft$beginMembership(UUID incarnation){return cognitivecraft$membershipData().begin(incarnation);}
    @Unique private NativePickup.Chunk cognitivecraft$chunk(){Entity self=(Entity)(Object)this;var pos=self.chunkPosition();return new NativePickup.Chunk(self.level().dimension().identifier().toString(),pos.x(),pos.z());}
    @Inject(method="setPosRaw",at=@At("TAIL"))
    private void cognitivecraft$observeEveryChunkChange(double x,double y,double z,CallbackInfo ci){if(cognitivecraft$membership()!=null)cognitivecraft$membership().moved(cognitivecraft$chunk());}
    @Inject(method="setUUID",at=@At("TAIL"))
    private void cognitivecraft$refuseChangedEntityIdentity(UUID id,CallbackInfo ci){if(cognitivecraft$membership()!=null&&cognitivecraft$membership().complete()&&!cognitivecraft$membership().capture().entity().equals(id))cognitivecraft$membership().gap();}
}
