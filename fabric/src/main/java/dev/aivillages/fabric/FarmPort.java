package dev.aivillages.fabric;

import dev.aivillages.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.*;
import java.util.*;

/** Real server-side actions with reach, resource, work-area and mob-griefing checks. */
public final class FarmPort implements WorldPort {
    private final Villager villager; private final ServerLevel world; private final BlockPos home;
    private final int radius; private final Reservations reservations; private final java.util.function.Consumer<String> speak;
    private final Set<BlockPos> skipped=new HashSet<>();
    private Iterator<BlockPos> scan; private BlockPos target; private Plan.Action scanningFor;
    private long targetSince,lastPathTick=-1000; private int crafted; private boolean hadBlockedTarget;
    public FarmPort(Villager villager,WorldData.Place home,int radius,Reservations reservations,java.util.function.Consumer<String> speak) {
        this.villager=villager;world=(ServerLevel)villager.level();this.home=new BlockPos(home.x(),home.y(),home.z());
        this.radius=radius;this.reservations=reservations;this.speak=speak;
    }
    public Villager villager(){return villager;}
    public boolean unsafe() {
        if(villager.getHealth()<villager.getMaxHealth()*0.3f || villager.isOnFire() || villager.hurtTime>0 || villager.getTradingPlayer()!=null || villager.isSleeping())return true;
        return !world.getEntitiesOfClass(Monster.class,villager.getBoundingBox().inflate(8),e->e.isAlive() && villager.hasLineOfSight(e)).isEmpty();
    }
    @Override public Snapshot snapshot() {
        boolean loaded=!villager.isRemoved() && world.isPositionEntityTicking(villager.blockPosition());
        return new Snapshot(loaded,villager.getHealth()>0,loaded && unsafe(),count(Items.WHEAT),count(Items.WHEAT_SEEDS),count(Items.BREAD));
    }
    public int count(Item item){return villager.getInventory().countItem(item);}
    @Override public int breadCrafted(){return crafted;}
    @Override public Outcome perform(Plan.Step step) {
        boolean mutates=switch(step.action()){case HARVEST_WHEAT,PLANT_WHEAT,COLLECT_WHEAT,CRAFT_BREAD,DELIVER_BREAD->true;default->false;};
        if(mutates && !world.getGameRules().get(GameRules.MOB_GRIEFING))return Outcome.BLOCKED;
        if(!inside(villager.blockPosition()) || !world.hasChunkAt(home))return Outcome.BLOCKED;
        return switch(step.action()) {
            case HARVEST_WHEAT,PLANT_WHEAT->crop(step.action());
            case COLLECT_WHEAT->collect();case CRAFT_BREAD->craft();case DELIVER_BREAD->deliver();
            case GO_HOME->approach(home);case SPEAK->{speak.accept(step.text());yield Outcome.DONE;}case WAIT->Outcome.DONE;
        };
    }
    private boolean inside(BlockPos p){return Math.abs(p.getX()-home.getX())<=radius && Math.abs(p.getZ()-home.getZ())<=radius && Math.abs(p.getY()-home.getY())<=4 && world.getWorldBorder().isWithinBounds(p);}
    private boolean usable(BlockPos p){return inside(p) && world.hasChunkAt(p) && world.mayInteract(villager,p);}
    private boolean matches(BlockPos p,Plan.Action action) {
        if(!usable(p))return false;var state=world.getBlockState(p);
        if(action==Plan.Action.HARVEST_WHEAT)return state.is(Blocks.WHEAT) && ((CropBlock)Blocks.WHEAT).isMaxAge(state);
        return state.isAir() && world.getBlockState(p.below()).is(Blocks.FARMLAND) && Blocks.WHEAT.defaultBlockState().canSurvive(world,p);
    }
    private Outcome crop(Plan.Action action) {
        if(action==Plan.Action.PLANT_WHEAT && count(Items.WHEAT_SEEDS)==0)return Outcome.EXHAUSTED;
        if(scanningFor!=action){clearTarget();skipped.clear();scan=null;scanningFor=action;hadBlockedTarget=false;}
        if(target!=null && !matches(target,action))clearTarget();
        if(target==null) {
            if(scan==null)scan=BlockPos.betweenClosed(home.offset(-radius,-3,-radius),home.offset(radius,3,radius)).iterator();
            int budget=128;
            while(scan.hasNext() && budget-->0) {
                var p=scan.next().immutable();
                if(!skipped.contains(p) && matches(p,action) && reservations.acquire(world,p,villager.getUUID(),world.getGameTime())) {target=p;targetSince=world.getGameTime();break;}
            }
            if(target==null)return scan.hasNext()?Outcome.WORKING:hadBlockedTarget?Outcome.BLOCKED:Outcome.EXHAUSTED;
        }
        if(!reservations.acquire(world,target,villager.getUUID(),world.getGameTime())){clearTarget();return Outcome.WORKING;}
        Outcome movement=approach(target);
        if(movement==Outcome.BLOCKED || world.getGameTime()-targetSince>160){skipped.add(target);hadBlockedTarget=true;clearTarget();return Outcome.WORKING;}
        if(movement!=Outcome.DONE)return movement;
        if(!matches(target,action)){clearTarget();return Outcome.WORKING;}
        if(action==Plan.Action.HARVEST_WHEAT) {
            var state=world.getBlockState(target);var drops=Block.getDrops(state,world,target,null,villager,ItemStack.EMPTY);
            if(!world.setBlock(target,Blocks.AIR.defaultBlockState(),3))return Outcome.BLOCKED;
            for(var stack:drops){var leftover=villager.getInventory().addItem(stack);if(!leftover.isEmpty())Block.popResource(world,target,leftover);}
            if(count(Items.WHEAT_SEEDS)>0 && matches(target,Plan.Action.PLANT_WHEAT) && world.setBlock(target,Blocks.WHEAT.defaultBlockState(),3))remove(Items.WHEAT_SEEDS,1);
        } else {
            if(!world.setBlock(target,Blocks.WHEAT.defaultBlockState(),3))return Outcome.BLOCKED;
            remove(Items.WHEAT_SEEDS,1);
        }
        villager.getInventory().setChanged();clearTarget();return Outcome.DONE;
    }
    private Outcome collect() {
        var items=world.getEntitiesOfClass(ItemEntity.class,villager.getBoundingBox().inflate(4),e->e.isAlive() && !e.hasPickUpDelay() && usable(e.blockPosition())
            && (e.getItem().getItem()==Items.WHEAT || e.getItem().getItem()==Items.WHEAT_SEEDS) && villager.getInventory().canAddItem(e.getItem()));
        items.sort(Comparator.comparingDouble(villager::distanceToSqr));if(items.isEmpty())return Outcome.EXHAUSTED;
        var item=items.getFirst();var movement=approach(item.blockPosition());if(movement!=Outcome.DONE)return movement;
        var before=item.getItem();var leftover=villager.getInventory().addItem(before.copy());
        if(leftover.getCount()==before.getCount())return Outcome.EXHAUSTED;
        if(leftover.isEmpty())item.discard();else item.setItem(leftover);return Outcome.DONE;
    }
    private Outcome craft() {
        if(count(Items.WHEAT)<3)return Outcome.EXHAUSTED;
        BlockPos table=null;
        for(var p:BlockPos.betweenClosed(home.offset(-3,-2,-3),home.offset(3,2,3)))
            if(usable(p) && world.getBlockState(p).is(Blocks.CRAFTING_TABLE)){table=p.immutable();break;}
        if(table==null)return Outcome.BLOCKED;
        var movement=approach(table);if(movement!=Outcome.DONE)return movement;
        var bread=new ItemStack(Items.BREAD,1);if(!villager.getInventory().canAddItem(bread))return Outcome.BLOCKED;
        remove(Items.WHEAT,3);var leftover=villager.getInventory().addItem(bread);
        if(!leftover.isEmpty())Block.popResource(world,villager.blockPosition(),leftover);
        crafted++;return Outcome.DONE;
    }
    private Outcome deliver() {
        if(count(Items.BREAD)==0)return Outcome.EXHAUSTED;var storage=storage();if(storage==null)return Outcome.EXHAUSTED;
        var movement=approach(home);if(movement!=Outcome.DONE)return movement;
        for(int slot=0;slot<storage.getContainerSize();slot++) {
            var existing=storage.getItem(slot);var bread=new ItemStack(Items.BREAD,1);if(!storage.canPlaceItem(slot,bread))continue;
            if(existing.isEmpty()){storage.setItem(slot,bread);remove(Items.BREAD,1);storage.setChanged();return Outcome.DONE;}
            if(ItemStack.isSameItemSameComponents(existing,bread) && existing.getCount()<Math.min(existing.getMaxStackSize(),storage.getMaxStackSize(existing))) {
                existing.grow(1);remove(Items.BREAD,1);storage.setChanged();return Outcome.DONE;
            }
        }
        return Outcome.EXHAUSTED;
    }
    private Container storage() {
        if(!usable(home))return null;var e=world.getBlockEntity(home);
        if(!(e instanceof BarrelBlockEntity || e instanceof ChestBlockEntity))return null;
        if(e instanceof BaseContainerBlockEntity base && base.isLocked())return null;
        if(e instanceof RandomizableContainerBlockEntity random && random.getLootTable()!=null)return null;
        return (Container)e;
    }
    public int foodReserve(){var storage=storage();return count(Items.BREAD)+(storage==null?0:storage.countItem(Items.BREAD));}
    public String evidence(){return count(Items.WHEAT)+":"+count(Items.WHEAT_SEEDS)+":"+foodReserve()+":"+(world.getGameTime()/24000);}
    private void remove(Item item,int amount) {
        var inventory=villager.getInventory();
        for(int i=0;i<inventory.getContainerSize() && amount>0;i++) {
            var stack=inventory.getItem(i);if(stack.getItem()==item){int take=Math.min(amount,stack.getCount());inventory.removeItem(i,take);amount-=take;}
        }
        if(amount!=0)throw new IllegalStateException("Inventory precondition changed");
    }
    private boolean canReach(BlockPos p) {
        var center=Vec3.atCenterOf(p);if(villager.distanceToSqr(center)>6.25)return false;
        var hit=world.clip(new ClipContext(villager.getEyePosition(),center,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,villager));
        return hit.getType()==HitResult.Type.MISS || hit.getBlockPos().equals(p);
    }
    private Outcome approach(BlockPos p) {
        if(!usable(p))return Outcome.BLOCKED;villager.getLookControl().setLookAt(p.getX()+0.5,p.getY()+0.5,p.getZ()+0.5);
        if(canReach(p)){villager.getNavigation().stop();return Outcome.DONE;}
        if(world.getGameTime()-lastPathTick>=20) {
            lastPathTick=world.getGameTime();var path=villager.getNavigation().createPath(p,1);
            if(path==null || !path.canReach())return Outcome.BLOCKED;villager.getNavigation().moveTo(path,0.65);
        }
        return Outcome.WORKING;
    }
    private void clearTarget(){reservations.release(villager.getUUID());target=null;lastPathTick=-1000;villager.getNavigation().stop();}
    @Override public void stop(){clearTarget();scan=null;scanningFor=null;skipped.clear();}
}
