package dev.aivillages.fabric.storage;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import com.mojang.serialization.JsonOps;
import dev.aivillages.core.kernel.storage.*;
import dev.aivillages.core.kernel.storage.StorageAccessPolicy.Scope;
import dev.aivillages.core.kernel.storage.StorageRegistry.Permission;
import dev.aivillages.core.kernel.storage.StorageBindingRules.Binding;
import static dev.aivillages.core.kernel.storage.StorageBindingRules.*;
import static dev.aivillages.core.kernel.storage.StorageCapabilities.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.BlockItem;
import java.util.function.Supplier;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;

/** Inert current-loader reader. Never unpacks loot, opens a menu, forces a chunk or changes inventory. */
public final class MinecraftStorageAdapter {
    @FunctionalInterface public interface Protection { boolean allows(Scope scope,UUID storage,Permission operation,BlockPos member); }
    /** Server-only result. Client callers use observe, whose refusal is uniformly fieldless. */
    public record Check(Refusal refusal,Binding binding,int matchingItems,int insertionCapacity){public boolean eligible(){return refusal==Refusal.NONE;}}
    private record Read(Refusal refusal,Binding binding,List<StorageCapabilities.Slot> slots,List<StorageSnapshot.Participant> participants){}
    private final ServerLevel level;private final StorageIncarnationAdapter identities;private final StorageAccessPolicy access;private final Protection protection;private final Supplier<StorageRegistry.Snapshot> metadata;
    public MinecraftStorageAdapter(ServerLevel level,StorageIncarnationAdapter identities,Supplier<StorageRegistry.Snapshot> metadata,StorageAccessPolicy access,Protection protection){this.metadata=Objects.requireNonNull(metadata);this.level=Objects.requireNonNull(level);this.identities=Objects.requireNonNull(identities);this.access=Objects.requireNonNull(access);this.protection=Objects.requireNonNull(protection);}
    public Check check(Scope scope,UUID storage,Permission operation,ItemStack probe){
        thread();if(!Set.of(Permission.INSPECT,Permission.DEPOSIT,Permission.WITHDRAW).contains(operation))throw new IllegalArgumentException("native storage operation");
        try{var decision=access.evaluate(scope,storage,operation);if(decision.result()!=StorageAccessPolicy.Result.ALLOW)return refused(decision.result()==StorageAccessPolicy.Result.UNKNOWN?Refusal.UNKNOWN:Refusal.PROTECTION);
            Item item=item(probe);var read=read(scope,storage,operation,probe);if(read.refusal()!=Refusal.NONE)return refused(read.refusal());
            var binding=read.binding();var f=new Facts(type(binding),true,true,true,false,false,true,read.slots());var result=StorageCapabilities.evaluate(f,item);
            if(!result.eligible())return refused(result.refusal());if(!decision.authorizesEffect(scope,storage,operation) || !identities.validates(binding) || !allowed(scope,binding,operation))return refused(Refusal.PROTECTION);
            return new Check(Refusal.NONE,binding,operation==Permission.DEPOSIT?0:result.matchingItems(),operation==Permission.WITHDRAW?0:result.insertionCapacity());
        }catch(IllegalArgumentException unsupported){return refused(Refusal.COMPONENTS);}catch(RuntimeException unavailable){return refused(Refusal.UNKNOWN);}
    }
    /** Only a current INSPECT decision can export actual slots. Deposit permission never implies stock inspection. */
    public StorageSnapshot observe(Scope scope,UUID storage){
        thread();try{var decision=access.evaluate(scope,storage,Permission.INSPECT);if(decision.result()!=StorageAccessPolicy.Result.ALLOW)return StorageSnapshot.Unavailable.INSTANCE;
            var probe=new ItemStack(Items.STONE);var read=read(scope,storage,Permission.INSPECT,probe);if(read.refusal()!=Refusal.NONE)return StorageSnapshot.Unavailable.INSTANCE;
            var eligibility=StorageCapabilities.evaluate(new Facts(type(read.binding()),true,true,true,false,false,true,read.slots()),item(probe));if(!eligibility.eligible())return StorageSnapshot.Unavailable.INSTANCE;
            var slots=read.slots().stream().map(s->new StorageSnapshot.Slot(s.participant(),s.index(),s.item(),s.count(),s.limit())).toList();
            if(!decision.authorizesEffect(scope,storage,Permission.INSPECT) || !identities.validates(read.binding()) || !allowed(scope,read.binding(),Permission.INSPECT))return StorageSnapshot.Unavailable.INSTANCE;
            return new StorageSnapshot.Visible(scope.world(),storage,read.binding().generation(),read.binding().epoch(),level.getGameTime(),read.participants(),slots,revision(slots));
        }catch(RuntimeException unavailable){return StorageSnapshot.Unavailable.INSTANCE;}
    }
    private Read read(Scope scope,UUID storage,Permission operation,ItemStack probe){
        var snapshot=metadata.get();var raw=snapshot.bindings().get(storage);if(raw==null)return unread(Refusal.UNKNOWN);var known=Binding.from(raw);
        for(var m:known.topology().members())if(!level.hasChunkAt(new BlockPos(m.x(),m.y(),m.z())))return unread(Refusal.UNLOADED);
        var live=identities.resolve(storage);if(live.isEmpty())return unread(Refusal.UNKNOWN);var binding=live.get();
        if(!scope.world().equals(binding.topology().world()) || !binding.topology().dimension().equals(level.dimension().identifier().toString()))return unread(Refusal.UNKNOWN);
        var slots=new ArrayList<StorageCapabilities.Slot>();var participants=new ArrayList<StorageSnapshot.Participant>();
        for(var member:binding.topology().members()){
            var pos=new BlockPos(member.x(),member.y(),member.z());if(!level.hasChunkAt(pos))return unread(Refusal.UNLOADED);
            Refusal refusal=binding.topology().adapter().equals(BARREL)?BarrelStorageAdapter.refusal(level,pos):ChestStorageAdapter.refusal(level,pos);if(refusal!=Refusal.NONE)return unread(refusal);
            if(!protection.allows(scope,storage,operation,pos))return unread(Refusal.PROTECTION);
            var container=(RandomizableContainerBlockEntity)level.getBlockEntity(pos);
            // getItem/isEmpty can unpack loot: these checks MUST precede every slot read.
            if(container.getLootTable()!=null)return unread(Refusal.LOOT_TABLE);if(container.isLocked())return unread(Refusal.LOCKED);
            if(container.getContainerSize()!=27)return unread(Refusal.SLOT_POLICY);participants.add(new StorageSnapshot.Participant(member.incarnation(),member.markerGeneration(),27));
            for(int i=0;i<27;i++){
                var nativeStack=container.getItem(i).copy();Item key=nativeStack.isEmpty()?null:item(nativeStack);
                int ownLimit=nativeStack.isEmpty()?container.getMaxStackSize():container.getMaxStackSize(nativeStack);
                if(!nativeStack.isEmpty() && nativeStack.getCount()>Math.min(ownLimit,nativeStack.getMaxStackSize()))return unread(Refusal.SLOT_POLICY);
                int limit=operation==Permission.INSPECT?ownLimit:container.getMaxStackSize(probe);
                if(ownLimit<1 || ownLimit>99 || limit<1 || limit>99)return unread(Refusal.SLOT_POLICY);
                slots.add(new StorageCapabilities.Slot(member.incarnation(),i,key,nativeStack.getCount(),ownLimit,limit,container.canPlaceItem(i,probe),nativeStack.isEmpty() || container.canTakeItem(container,i,nativeStack)));
            }
        }
        return new Read(Refusal.NONE,binding,List.copyOf(slots),List.copyOf(participants));
    }
    private boolean allowed(Scope scope,Binding binding,Permission op){for(var m:binding.topology().members())if(!protection.allows(scope,binding.storage(),op,new BlockPos(m.x(),m.y(),m.z())))return false;return true;}
    private void thread(){if(!level.getServer().isSameThread())throw new IllegalStateException("authorized server thread required");}
    /** Native item descriptor only; no inventory or stock access is implied. */
    public Item describeItem(ItemStack stack){thread();return item(stack);}
    private Item item(ItemStack stack){
        if(stack==null || stack.isEmpty())throw new IllegalArgumentException("explicit nonempty native probe required");var key=BuiltInRegistries.ITEM.getKey(stack.getItem());if(key==null)throw new IllegalArgumentException("unregistered item");
        boolean defaultComponents=stack.getComponentsPatch().isEmpty();boolean container=stack.has(DataComponents.CONTAINER) || stack.has(DataComponents.BUNDLE_CONTENTS) || stack.has(DataComponents.CHARGED_PROJECTILES) || stack.getItem() instanceof BlockItem b && b.getBlock() instanceof EntityBlock;
        var encoded=ItemStack.CODEC.encodeStart(level.registryAccess().createSerializationContext(JsonOps.INSTANCE),stack.copyWithCount(1)).getOrThrow();
        return new Item(key.toString(),digest(encoded.toString()),stack.getMaxStackSize(),defaultComponents,container);
    }
    private static Type type(Binding b){return b.topology().adapter().equals(BARREL)?Type.BARREL:b.topology().adapter().equals(DOUBLE)?Type.DOUBLE_CHEST:Type.SINGLE_CHEST;}
    private static Check refused(Refusal r){return new Check(r,null,0,0);}
    private static Read unread(Refusal r){return new Read(r,null,List.of(),List.of());}
    private static String revision(List<StorageSnapshot.Slot> slots){var s=new StringBuilder();for(var p:slots)s.append(p.participant()).append(':').append(p.index()).append(':').append(p.item()).append(':').append(p.count()).append(':').append(p.limit()).append('\n');return digest(s.toString());}
    private static String digest(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
}
