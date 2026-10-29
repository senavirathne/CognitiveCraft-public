package dev.aivillages.fabric.logistics;

import java.util.*;
import dev.aivillages.core.kernel.logistics.*;
import dev.aivillages.core.kernel.logistics.MobilityPolicy.Actor;
import dev.aivillages.core.kernel.logistics.RecipientDeliveryPolicy.*;
import dev.aivillages.core.kernel.storage.*;
import dev.aivillages.core.kernel.storage.StorageBindingRules.Binding;
import dev.aivillages.fabric.storage.MinecraftStorageAdapter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import static dev.aivillages.core.kernel.logistics.TransferObservation.*;

/** Inert guarded handoff. A supplies current enrollment, custody, resource order and native protection. */
public final class VillagerTransferAdapter {
    public record Control(Actor carrier,boolean controlled,boolean exclusiveSender,boolean exclusiveRecipient,
                          boolean existingSourceAuthorized,boolean protectionAllowed,long custodyRevision,double interactionLimit){
        public Control{Objects.requireNonNull(carrier);if(custodyRevision<0 || !Double.isFinite(interactionLimit) || interactionLimit<=0)throw new IllegalArgumentException("current bounded custody/protection");}
    }
    @FunctionalInterface public interface Authority {Control observe(Goal goal,List<String> orderedParticipants);}
    public record SelectedSlot(int slot,ItemStack nativeStack,int quantity){}
    public record Selection(UUID principal,UUID request,UUID sender,long observedTick,List<SelectedSlot> slots){public Selection{slots=List.copyOf(slots);}}
    @FunctionalInterface public interface SourceSelection {Selection observe(Goal goal,long tick);}
    private record Image(ItemStack[] references,List<String> contents){}
    private record Slot(Container container,int index){}
    private final ServerLevel level;private final Villager sender;private final Actor identity;private final StorageAccessPolicy.Scope scope;
    private final MinecraftStorageAdapter storage;private final Authority authority;private final SimpleContainer inventory;
    public VillagerTransferAdapter(ServerLevel level,Villager sender,Actor identity,StorageAccessPolicy.Scope scope,MinecraftStorageAdapter storage,Authority authority){this.level=Objects.requireNonNull(level);this.sender=Objects.requireNonNull(sender);this.identity=Objects.requireNonNull(identity);this.scope=Objects.requireNonNull(scope);this.storage=Objects.requireNonNull(storage);this.authority=Objects.requireNonNull(authority);inventory=sender.getInventory();}
    /** One-use native provenance only. No serialization, effect key, inventory ledger or save authority. */
    public final class Cargo {
        private final VillagerTransferAdapter owner=VillagerTransferAdapter.this;private final Session session;private final RecipientResolver.Bound recipient;
        private final Binding source;private final Villager nativeRecipient;private final ItemStack probe;private final int[] amounts;private final Image accepted;
        private final long custodyRevision;private final int quantity;private boolean consumed;
        private Cargo(Session session,RecipientResolver.Bound recipient,Villager target,Binding source,ItemStack probe,int[] amounts,Control control){this.session=session;this.recipient=recipient;nativeRecipient=target;this.source=source;this.probe=probe;this.amounts=amounts.clone();custodyRevision=control.custodyRevision;quantity=Arrays.stream(amounts).sum();accepted=image(inventory);}
        public int quantity(){return quantity;}public UUID request(){return session.goal().request();}
    }
    public record Preparation(Cargo cargo,TransferObservation observation){}
    public Preparation existing(Session session,RecipientResolver.Bound recipient,ItemStack requested,SourceSelection selection){
        thread();Goal goal=session.goal();Reason invalid=admission(session,Source.EXISTING_CARRIER);if(invalid!=Reason.NONE)return denied(session,invalid);
        Control control=authority.observe(goal,order(goal,null));if(!authorized(control,goal) || !control.existingSourceAuthorized)return denied(session,Reason.UNAUTHORIZED);
        Villager target=recipient(goal,recipient);if(target==null)return denied(session,Reason.STALE_ENDPOINT);
        ItemStack probe=probe(goal,requested);if(probe==null)return denied(session,Reason.UNSUPPORTED_ITEM);
        if(!primitive(sender,inventory) || !primitive(target,target.getInventory()))return denied(session,Reason.UNSUPPORTED_PRIMITIVE);
        if(!supported(inventory) || !supported(target.getInventory()))return denied(session,Reason.UNSUPPORTED_ITEM);
        if(selection==null)return denied(session,Reason.SELECTION_REQUIRED);
        Selection selected=selection.observe(goal,level.getGameTime());int[] amounts=selected(goal,selected,probe,session.remaining());if(amounts==null)return denied(session,Reason.SELECTION_REQUIRED);
        if(capacity(target.getInventory(),probe)<session.remaining())return denied(session,Reason.CAPACITY);
        Image before=image(inventory),receiverBefore=image(target.getInventory());Control fresh=authority.observe(goal,order(goal,null));
        Villager freshTarget=recipient(goal,recipient);Selection freshSelection=selection.observe(goal,level.getGameTime());
        if(!control.equals(fresh) || !authorized(fresh,goal) || !fresh.existingSourceAuthorized || freshTarget!=target || !selected.equals(freshSelection) || !current(inventory,before) || !current(target.getInventory(),receiverBefore))return denied(session,Reason.INTERFERENCE);
        Cargo cargo=new Cargo(session,recipient,target,null,probe,amounts,fresh);session.acceptExisting(cargo.quantity);return new Preparation(cargo,null);
    }
    /** Guarded storage pickup uses an empty carrier slot. Uncertain pickup never creates a cargo token. */
    public Preparation withdraw(Session session,Binding source,RecipientResolver.Bound recipient,ItemStack requested){
        thread();Goal goal=session.goal();Reason invalid=admission(session,Source.STORAGE);if(invalid!=Reason.NONE)return denied(session,invalid);if(!source(goal,source))return denied(session,Reason.STALE_ENDPOINT);
        Control control=authority.observe(goal,order(goal,source));if(!authorized(control,goal))return denied(session,Reason.UNAUTHORIZED);
        Villager target=recipient(goal,recipient);if(target==null)return denied(session,Reason.STALE_ENDPOINT);ItemStack probe=probe(goal,requested);if(probe==null)return denied(session,Reason.UNSUPPORTED_ITEM);
        if(!primitive(sender,inventory) || !primitive(target,target.getInventory()))return denied(session,Reason.UNSUPPORTED_PRIMITIVE);if(!supported(inventory) || !supported(target.getInventory()))return denied(session,Reason.UNSUPPORTED_ITEM);
        Reason guard=sourceGuard(source,probe);if(guard!=Reason.NONE)return denied(session,guard);if(!sourceReach(source))return denied(session,Reason.OUT_OF_REACH);
        int free=-1;for(int i=0;i<8;i++)if(inventory.getItem(i).isEmpty()){free=i;break;}if(free<0)return denied(session,Reason.NO_DISJOINT_SLOT);
        int quantity=Math.min(session.remaining(),Math.min(probe.getMaxStackSize(),inventory.getMaxStackSize(probe)));if(capacity(target.getInventory(),probe)<quantity)return denied(session,Reason.CAPACITY);
        List<Slot> slots=slots(source);Counts before=counts(source,target,probe);if(before.source()<quantity)return denied(session,Reason.SHORTAGE);
        Map<Slot,ItemStack> changes=new LinkedHashMap<>();int left=quantity;
        for(Slot slot:slots){ItemStack stack=slot.container.getItem(slot.index);if(left>0 && ItemStack.isSameItemSameComponents(stack,probe) && slot.container.canTakeItem(slot.container,slot.index,stack)){int take=Math.min(left,stack.getCount());changes.put(slot,take==stack.getCount()?ItemStack.EMPTY:stack.copyWithCount(stack.getCount()-take));left-=take;}}
        if(left!=0)return denied(session,Reason.SHORTAGE);
        List<String> sourceBefore=contents(slots);Image senderBefore=image(inventory),receiverBefore=image(target.getInventory());ItemStack carried=probe.copyWithCount(quantity);Control fresh=authority.observe(goal,order(goal,source));
        Villager freshTarget=recipient(goal,recipient);Reason freshSource=sourceGuard(source,probe);
        if(!control.equals(fresh) || !authorized(fresh,goal) || freshTarget!=target || freshSource!=Reason.NONE || !sourceBefore.equals(contents(slots)) || !current(inventory,senderBefore) || !current(target.getInventory(),receiverBefore) || !sourceReach(source))return denied(session,Reason.INTERFERENCE);
        try{for(var entry:changes.entrySet())entry.getKey().container.setItem(entry.getKey().index,entry.getValue());inventory.getItems().set(free,carried);for(Slot slot:slots)slot.container.setChanged();}
        catch(RuntimeException uncertain){TransferObservation result=new TransferObservation(Status.UNCERTAIN,Reason.UNCERTAIN_EFFECT,quantity,before,safeCounts(source,target,probe),"native participants; owner reconciliation required");session.observe(result);return new Preparation(null,result);}
        Counts after=counts(source,target,probe);if(!DeliveryPolicy.withdrawal(before,after,quantity)){TransferObservation result=new TransferObservation(Status.UNCERTAIN,Reason.UNCERTAIN_EFFECT,quantity,before,after,"native participants; owner reconciliation required");session.observe(result);return new Preparation(null,result);}
        int[] amounts=new int[8];amounts[free]=quantity;Cargo cargo=new Cargo(session,recipient,target,source,probe,amounts,fresh);TransferObservation result=new TransferObservation(Status.LIVE_WITHDRAWN,Reason.NONE,quantity,before,after,"authorized carrier slot");session.observe(result);return new Preparation(cargo,result);
    }
    public TransferObservation handoff(Cargo cargo){
        thread();if(cargo==null || cargo.owner!=this || cargo.consumed)return refusal(Reason.UNKNOWN_CUSTODY);cargo.consumed=true;Session session=cargo.session;Goal goal=session.goal();
        if(session.phase()!=Phase.IN_FLIGHT || session.inFlight()!=cargo.quantity)return decline(session,Reason.WRONG_PHASE);
        Control control=authority.observe(goal,order(goal,cargo.source));if(!authorized(control,goal) || !control.exclusiveRecipient || control.custodyRevision!=cargo.custodyRevision || cargo.source==null && !control.existingSourceAuthorized)return decline(session,Reason.UNAUTHORIZED);
        Villager target=recipient(goal,cargo.recipient);if(target==null || target!=cargo.nativeRecipient)return decline(session,Reason.STALE_ENDPOINT);
        if(!primitive(sender,inventory) || !primitive(target,target.getInventory()))return decline(session,Reason.UNSUPPORTED_PRIMITIVE);if(!supported(inventory) || !supported(target.getInventory()))return decline(session,Reason.UNSUPPORTED_ITEM);
        if(!current(inventory,cargo.accepted))return decline(session,Reason.INTERFERENCE);
        if(cargo.source!=null){Reason guard=sourceGuard(cargo.source,cargo.probe);if(guard!=Reason.NONE)return decline(session,guard);}
        if(!reach(target,control))return decline(session,Reason.OUT_OF_REACH);if(capacity(target.getInventory(),cargo.probe)<cargo.quantity)return decline(session,Reason.CAPACITY);
        SimpleContainer destination=target.getInventory();Counts before=counts(cargo.source,target,cargo.probe);Image receiverBefore=image(destination);ItemStack[] senderAfter=new ItemStack[8],recipientAfter=new ItemStack[8];int left=cargo.quantity;
        for(int i=0;i<8;i++){
            ItemStack stack=inventory.getItem(i);int amount=cargo.amounts[i];if(amount<0 || amount>stack.getCount() || amount>0 && !ItemStack.isSameItemSameComponents(stack,cargo.probe))return decline(session,Reason.UNKNOWN_CUSTODY);
            senderAfter[i]=amount==0?stack:amount==stack.getCount()?ItemStack.EMPTY:stack.copyWithCount(stack.getCount()-amount);
            ItemStack other=destination.getItem(i);int room=other.isEmpty() || ItemStack.isSameItemSameComponents(other,cargo.probe)?Math.max(0,Math.min(destination.getMaxStackSize(cargo.probe),cargo.probe.getMaxStackSize())-other.getCount()):0;int put=Math.min(left,room);recipientAfter[i]=put==0?other:cargo.probe.copyWithCount(other.getCount()+put);left-=put;
        }
        if(left!=0)return decline(session,Reason.CAPACITY);
        Control fresh=authority.observe(goal,order(goal,cargo.source));Villager freshTarget=recipient(goal,cargo.recipient);Reason freshSource=cargo.source==null?Reason.NONE:sourceGuard(cargo.source,cargo.probe);
        if(!control.equals(fresh) || !authorized(fresh,goal) || !fresh.exclusiveRecipient || freshTarget!=target || freshSource!=Reason.NONE || !current(inventory,cargo.accepted) || !current(destination,receiverBefore) || !reach(target,fresh))return decline(session,Reason.INTERFERENCE);
        NonNullList<ItemStack> from=inventory.getItems(),to=destination.getItems();
        // Exact qualified vanilla primitive: fixed Arrays-backed list, valid indices and non-null prebuilt values.
        // No callbacks, allocation, inventory getters, policy calls or yields in this write section.
        for(int i=0;i<8;i++){from.set(i,senderAfter[i]);to.set(i,recipientAfter[i]);}
        Counts after=counts(cargo.source,target,cargo.probe);TransferObservation result=DeliveryPolicy.deposit(before,after,cargo.quantity)?new TransferObservation(Status.LIVE_DEPOSITED,Reason.NONE,cargo.quantity,before,after,"authorized recipient inventory; live observation only"):new TransferObservation(Status.UNCERTAIN,Reason.UNCERTAIN_EFFECT,cargo.quantity,before,after,"native participants; owner reconciliation required");session.observe(result);return result;
    }
    private Reason admission(Session session,Source expected){if(!session.goal().carrier().equals(identity) || session.goal().source()!=expected)return Reason.UNAUTHORIZED;Reason reason=RecipientDeliveryPolicy.admit(session.goal());if(reason!=Reason.NONE)return reason;if(session.phase()!=Phase.READY)return Reason.WRONG_PHASE;return session.batches()<8?Reason.NONE:Reason.BATCH_LIMIT;}
    private boolean authorized(Control c,Goal goal){return c!=null && c.controlled && c.exclusiveSender && c.protectionAllowed && c.carrier.equals(identity) && goal.carrier().equals(identity) && scope.world().equals(identity.world()) && scope.principal().equals(identity.principal()) && Objects.equals(scope.actorCitizen(),identity.citizen()) && sender.isAlive() && !sender.isRemoved() && sender.level()==level && level.getEntity(identity.entity())==sender && sender.getUUID().equals(identity.entity()) && identity.dimension().equals(level.dimension().identifier().toString()) && inventory==sender.getInventory();}
    private Villager recipient(Goal goal,RecipientResolver.Bound bound){if(bound==null || goal.recipient().current(bound.fresh(scope),scope,level.getGameTime())!=Reason.NONE)return null;var entity=level.getEntity(goal.recipient().entity());return entity instanceof Villager v && v.isAlive() && !v.isRemoved() && v.level()==level && level.hasChunkAt(v.blockPosition()) && goal.recipient().dimension().equals(level.dimension().identifier().toString())?v:null;}
    private boolean source(Goal goal,Binding binding){return binding!=null && goal.storageSource().world().equals(binding.topology().world()) && goal.storageSource().storage().equals(binding.storage()) && goal.storageSource().dimension().equals(binding.topology().dimension()) && goal.storageSource().participants().equals(new HashSet<>(binding.topology().members().stream().map(StorageBindingRules.Member::incarnation).toList()));}
    private Reason sourceGuard(Binding binding,ItemStack probe){if(!storage.currentEndpoint(binding))return Reason.STALE_ENDPOINT;var c=storage.check(scope,binding.storage(),StorageRegistry.Permission.WITHDRAW,probe);if(!c.eligible())return c.refusal()==StorageCapabilities.Refusal.PROTECTION?Reason.UNAUTHORIZED:c.refusal()==StorageCapabilities.Refusal.COMPONENTS?Reason.UNSUPPORTED_ITEM:Reason.STALE_ENDPOINT;return StorageBindingRules.currentEndpoint(binding,c.binding())?Reason.NONE:Reason.STALE_ENDPOINT;}
    private boolean sourceReach(Binding source){return source.topology().members().stream().anyMatch(m->sender.distanceToSqr(m.x()+.5,m.y()+.5,m.z()+.5)<=9);}
    private ItemStack probe(Goal goal,ItemStack requested){if(requested==null || requested.isEmpty())return null;ItemStack probe=requested.copyWithCount(1);return storage.describeItem(probe).equals(goal.item())?probe:null;}
    private int[] selected(Goal goal,Selection selection,ItemStack probe,int quantity){
        if(selection==null || !identity.principal().equals(selection.principal) || !goal.request().equals(selection.request) || !identity.citizen().equals(selection.sender) || selection.observedTick>level.getGameTime() || level.getGameTime()-selection.observedTick>40 || selection.slots.isEmpty() || selection.slots.size()>8)return null;
        int[] amounts=new int[8];int sum=0;for(SelectedSlot s:selection.slots){int i=s.slot;if(i<0 || i>=8 || amounts[i]!=0 || s.quantity<1 || inventory.getItem(i)!=s.nativeStack || s.quantity>s.nativeStack.getCount() || !ItemStack.isSameItemSameComponents(s.nativeStack,probe))return null;amounts[i]=s.quantity;sum+=s.quantity;}return sum==quantity?amounts:null;
    }
    private boolean reach(Villager target,Control c){return RecipientDeliveryPolicy.withinReach(sender.distanceTo(target),c.interactionLimit) && sender.hasLineOfSight(target);}
    private boolean primitive(Villager owner,SimpleContainer c){return owner.getClass()==Villager.class && c.getClass()==SimpleContainer.class && c.getContainerSize()==8 && c.getItems().getClass()==NonNullList.class && c.getItems().size()==8;}
    private boolean supported(SimpleContainer c){for(int i=0;i<8;i++){ItemStack stack=c.getItem(i);if(stack.isEmpty())continue;var key=storage.describeItem(stack);if(!key.defaultComponents() || key.containerItem() || stack.getCount()>Math.min(stack.getMaxStackSize(),c.getMaxStackSize(stack)))return false;}return true;}
    private String content(ItemStack stack){return stack.isEmpty()?"empty":storage.describeItem(stack).toString()+":"+stack.getCount();}
    private Image image(SimpleContainer c){ItemStack[] references=new ItemStack[8];List<String> rows=new ArrayList<>();for(int i=0;i<8;i++){references[i]=c.getItem(i);rows.add(content(references[i]));}return new Image(references,List.copyOf(rows));}
    private boolean current(SimpleContainer c,Image image){for(int i=0;i<8;i++)if(c.getItem(i)!=image.references[i] || !content(c.getItem(i)).equals(image.contents.get(i)))return false;return true;}
    private int capacity(SimpleContainer c,ItemStack probe){int room=0;for(int i=0;i<8;i++){ItemStack stack=c.getItem(i);if(c.canPlaceItem(i,probe) && (stack.isEmpty() || ItemStack.isSameItemSameComponents(stack,probe)))room+=Math.max(0,Math.min(c.getMaxStackSize(probe),probe.getMaxStackSize())-stack.getCount());}return room;}
    private List<Slot> slots(Binding binding){List<Slot> rows=new ArrayList<>();for(var m:binding.topology().members()){BlockPos p=new BlockPos(m.x(),m.y(),m.z());if(!level.hasChunkAt(p))throw new IllegalStateException("unloaded source");Container c=(Container)level.getBlockEntity(p);if(c.getContainerSize()!=27)throw new IllegalStateException("source slot bound");for(int i=0;i<27;i++)rows.add(new Slot(c,i));}return rows;}
    private List<String> contents(List<Slot> slots){List<String> rows=new ArrayList<>();for(Slot s:slots)rows.add(content(s.container.getItem(s.index)));return rows;}
    private int count(Container c,ItemStack probe){int n=0;for(int i=0;i<c.getContainerSize();i++)if(ItemStack.isSameItemSameComponents(c.getItem(i),probe))n+=c.getItem(i).getCount();return n;}
    private Counts counts(Binding source,Villager target,ItemStack probe){int stored=0;if(source!=null)for(Slot s:slots(source))if(ItemStack.isSameItemSameComponents(s.container.getItem(s.index),probe))stored+=s.container.getItem(s.index).getCount();return new Counts(stored,count(inventory,probe),count(target.getInventory(),probe));}
    private Counts safeCounts(Binding source,Villager target,ItemStack probe){try{return counts(source,target,probe);}catch(RuntimeException unknown){return null;}}
    private List<String> order(Goal goal,Binding source){SortedSet<String> keys=new TreeSet<>();keys.add(identity.world()+"/citizen/"+identity.citizen());keys.add(identity.world()+"/citizen/"+goal.recipient().citizen());if(source!=null)for(var m:source.topology().members())keys.add(identity.world()+"/storage/"+m.incarnation());return List.copyOf(keys);}
    private TransferObservation refusal(Reason reason){return new TransferObservation(Status.REFUSED,reason,0,null,null,"handoff unavailable; owner revalidation required");}
    private TransferObservation decline(Session session,Reason reason){TransferObservation result=refusal(reason);if(session.phase()==Phase.READY || session.phase()==Phase.IN_FLIGHT)session.observe(result);return result;}
    private Preparation denied(Session session,Reason reason){return new Preparation(null,decline(session,reason));}
    private void thread(){if(!level.getServer().isSameThread())throw new IllegalStateException("exclusive server-thread handoff required");}
}
