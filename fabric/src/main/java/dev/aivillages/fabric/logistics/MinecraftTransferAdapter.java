package dev.aivillages.fabric.logistics;
import java.util.*;
import dev.aivillages.core.kernel.logistics.*;
import dev.aivillages.core.kernel.logistics.MobilityPolicy.Actor;
import dev.aivillages.core.kernel.storage.*;
import dev.aivillages.core.kernel.storage.StorageBindingRules.Binding;
import dev.aivillages.fabric.storage.MinecraftStorageAdapter;
import static dev.aivillages.core.kernel.storage.StorageRegistry.Permission;
import static dev.aivillages.core.kernel.logistics.TransferObservation.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/** Inert guarded transfer port. Exclusive A custody/resource order is injected; this creates no locks or ledger. */
public final class MinecraftTransferAdapter {
 public record Control(Actor actor,boolean authorized,boolean exclusiveCustody,long custodyRevision){public Control{if(custodyRevision<0)throw new IllegalArgumentException("custody revision");}}
 @FunctionalInterface public interface Authority {Control observe(Actor actor,List<String> orderedParticipants);}
 public record Selected(UUID storage,UUID participant,int slot,ItemStack nativeStack,int admittedCount,long observedTick,UUID principal){}
 @FunctionalInterface public interface Selection {Selected observe(UUID storage,ItemStack exactItem,long tick);}
 private record NativeSlot(Container container,int index){}
 private final ServerLevel level;private final Villager carrier;private final Actor identity;private final StorageAccessPolicy.Scope scope;private final MinecraftStorageAdapter storage;private final Authority authority;private final SimpleContainer inventory;
 public MinecraftTransferAdapter(ServerLevel level,Villager carrier,Actor identity,StorageAccessPolicy.Scope scope,MinecraftStorageAdapter storage,Authority authority){this.level=Objects.requireNonNull(level);this.carrier=Objects.requireNonNull(carrier);this.identity=Objects.requireNonNull(identity);this.scope=Objects.requireNonNull(scope);this.storage=Objects.requireNonNull(storage);this.authority=Objects.requireNonNull(authority);inventory=carrier.getInventory();}
 public final class Batch {
  private final MinecraftTransferAdapter owner=MinecraftTransferAdapter.this;private final DeliveryPolicy.Goal goal;private final Binding source,destination;private final ItemStack probe,attributed;private final int slot,quantity;private final long custodyRevision;private final String carrierRevision;private boolean consumed,uncertain;
  private Batch(DeliveryPolicy.Goal goal,Binding source,Binding destination,ItemStack probe,ItemStack attributed,int slot,int q,Control c){this.goal=goal;this.source=source;this.destination=destination;this.probe=probe;this.attributed=attributed;this.slot=slot;quantity=q;custodyRevision=c.custodyRevision;carrierRevision=revision();}
  public int quantity(){return quantity;}public int slot(){return slot;}public UUID request(){return goal.request();}
 }
 public record Withdrawal(TransferObservation observation,Batch batch){}
 public Withdrawal withdraw(DeliveryPolicy.Session session,Binding source,Binding destination,ItemStack requested,Selection selection){thread();var g=session.goal();Reason invalid=DeliveryPolicy.admit(g);if(invalid!=Reason.NONE)return new Withdrawal(refuse(invalid,null,"source"),null);
  if(session.phase()!=DeliveryPolicy.Phase.READY)return new Withdrawal(refuse(Reason.WRONG_PHASE,null,"source"),null);if(session.batches()>=8)return new Withdrawal(refuse(Reason.BATCH_LIMIT,null,"source"),null);
  if(!matches(g,source,destination) || !nativeIdentity())return new Withdrawal(refuse(Reason.STALE_ENDPOINT,null,"source"),null);
  if(requested==null || requested.isEmpty())return new Withdrawal(refuse(Reason.UNSUPPORTED_ITEM,null,"source"),null);ItemStack probe=requested.copyWithCount(1);try{if(!storage.describeItem(probe).equals(g.item()))return new Withdrawal(refuse(Reason.UNSUPPORTED_ITEM,null,"source"),null);}catch(RuntimeException bad){return new Withdrawal(refuse(Reason.UNSUPPORTED_ITEM,null,"source"),null);}
  if(!g.explicitItem() && selection==null)return new Withdrawal(refuse(Reason.SELECTION_REQUIRED,null,"source"),null);
  var order=order(source,destination);Control c=authority.observe(identity,order);if(!authorized(c))return new Withdrawal(refuse(Reason.UNAUTHORIZED,null,"source"),null);if(!carrierSupported())return new Withdrawal(refuse(Reason.UNSUPPORTED_ITEM,null,"carrier"),null);
  Reason guards=guards(source,destination,probe);if(guards!=Reason.NONE)return new Withdrawal(refuse(guards,null,"source"),null);if(!reach(source))return new Withdrawal(refuse(Reason.OUT_OF_REACH,null,"source"),null);
  var src=slots(source);var dst=slots(destination);var before=counts(src,dst,probe);Selected selected=g.explicitItem()?null:selection.observe(source.storage(),probe,level.getGameTime());
  List<NativeSlot> admittedSource=src;
  if(!g.explicitItem()){
   if(selected==null || !selected.storage.equals(source.storage()) || !selected.principal.equals(identity.principal()) || selected.observedTick>level.getGameTime() || level.getGameTime()-selected.observedTick>40 || selected.admittedCount<g.quantity() || selected.slot<0 || selected.slot>=27)return new Withdrawal(refuse(Reason.SELECTION_REQUIRED,before,"source"),null);
   int member=-1;for(int i=0;i<source.topology().members().size();i++)if(source.topology().members().get(i).incarnation().equals(selected.participant))member=i;
   if(member<0)return new Withdrawal(refuse(Reason.SELECTION_REQUIRED,before,"source"),null);var slot=src.get(member*27+selected.slot);
   if(slot.container.getItem(slot.index)!=selected.nativeStack || selected.nativeStack.getCount()!=selected.admittedCount || !ItemStack.isSameItemSameComponents(selected.nativeStack,probe))return new Withdrawal(refuse(Reason.SELECTION_REQUIRED,before,"source"),null);admittedSource=List.of(slot);
  }
  int free=-1;for(int i=0;i<inventory.getContainerSize();i++)if(inventory.getItem(i).isEmpty()){free=i;break;}if(free<0)return new Withdrawal(refuse(Reason.NO_DISJOINT_SLOT,before,"source"),null);
  int batch=DeliveryPolicy.batch(g,session.remaining(),Math.min(inventory.getMaxStackSize(probe),probe.getMaxStackSize()),capacity(dst,probe));if(batch==0)return new Withdrawal(refuse(Reason.CAPACITY,before,"source"),null);if(before.source()<batch)return new Withdrawal(refuse(Reason.SHORTAGE,before,"source"),null);
  String revision=revision(),sourceRevision=nativeRevision(src),destinationRevision=nativeRevision(dst);var changes=withdrawPlan(admittedSource,probe,batch);Control fresh=authority.observe(identity,order);
  if(!c.equals(fresh) || !authorized(fresh) || guards(source,destination,probe)!=Reason.NONE || !revision.equals(revision()) || !sourceRevision.equals(nativeRevision(src)) || !destinationRevision.equals(nativeRevision(dst)) || selected!=null && !selected.equals(selection.observe(source.storage(),probe,level.getGameTime())) || !inventory.getItem(free).isEmpty() || !reach(source))return new Withdrawal(new TransferObservation(Status.REFUSED,Reason.INTERFERENCE,0,before,safeCounts(src,dst,probe),"source; external interference"),null);
  ItemStack carried=probe.copyWithCount(batch);Batch token;
  try{for(var e:changes.entrySet())e.getKey().container.setItem(e.getKey().index,e.getValue());inventory.setItem(free,carried);for(var p:src)p.container.setChanged();inventory.setChanged();token=new Batch(g,source,destination,probe,inventory.getItem(free),free,batch,c);}
  catch(RuntimeException uncertain){return new Withdrawal(new TransferObservation(Status.UNCERTAIN,Reason.UNCERTAIN_EFFECT,batch,before,safeCounts(src,dst,probe),"native participants; J reconciliation required"),null);}
  var after=counts(src,dst,probe);if(!DeliveryPolicy.withdrawal(before,after,batch)){token.uncertain=true;return new Withdrawal(new TransferObservation(Status.UNCERTAIN,Reason.UNCERTAIN_EFFECT,batch,before,after,"native participants; J reconciliation required"),null);}
  return new Withdrawal(new TransferObservation(Status.LIVE_WITHDRAWN,Reason.NONE,batch,before,after,"carrier slot "+free),token);
 }
 public TransferObservation deposit(Batch token){thread();if(token==null || token.owner!=this || token.consumed || token.uncertain)return refuse(Reason.UNKNOWN_CUSTODY,null,"carrier");token.consumed=true;
  var source=token.source;var destination=token.destination;var order=order(source,destination);Control c=authority.observe(identity,order);
  if(!authorized(c) || c.custodyRevision!=token.custodyRevision)return refuse(Reason.UNAUTHORIZED,null,"carrier; custody changed, J reconciliation required");
  if(!carrierSupported())return refuse(Reason.UNSUPPORTED_ITEM,null,"carrier; custody requires J reconciliation");Reason guard=guards(source,destination,token.probe);if(guard!=Reason.NONE)return refuse(guard,null,"carrier slot "+token.slot);if(!reach(destination))return refuse(Reason.OUT_OF_REACH,null,"carrier slot "+token.slot);
  var src=slots(source);var dst=slots(destination);var before=counts(src,dst,token.probe);
  if(inventory.getItem(token.slot)!=token.attributed || inventory.getItem(token.slot).getCount()!=token.quantity || !ItemStack.isSameItemSameComponents(inventory.getItem(token.slot),token.probe) || !token.carrierRevision.equals(revision()))return refuse(Reason.INTERFERENCE,before,"carrier slot "+token.slot);
  if(capacity(dst,token.probe)<token.quantity)return refuse(Reason.CAPACITY,before,"carrier slot "+token.slot);
  String sourceRevision=nativeRevision(src),destinationRevision=nativeRevision(dst);var plan=depositPlan(dst,token.probe,token.quantity);Control fresh=authority.observe(identity,order);
  if(!c.equals(fresh) || !authorized(fresh) || !nativeIdentity() || guards(source,destination,token.probe)!=Reason.NONE || !token.carrierRevision.equals(revision()) || !sourceRevision.equals(nativeRevision(src)) || !destinationRevision.equals(nativeRevision(dst)) || inventory.getItem(token.slot)!=token.attributed || !reach(destination))return new TransferObservation(Status.REFUSED,Reason.INTERFERENCE,0,before,safeCounts(src,dst,token.probe),"carrier slot "+token.slot+"; external interference");
  token.consumed=true;
  try{inventory.setItem(token.slot,ItemStack.EMPTY);for(var e:plan.entrySet())e.getKey().container.setItem(e.getKey().index,e.getValue());inventory.setChanged();for(var p:dst)p.container.setChanged();}
  catch(RuntimeException uncertain){token.uncertain=true;return new TransferObservation(Status.UNCERTAIN,Reason.UNCERTAIN_EFFECT,token.quantity,before,safeCounts(src,dst,token.probe),"native participants; J reconciliation required");}
  var after=counts(src,dst,token.probe);if(!DeliveryPolicy.deposit(before,after,token.quantity)){token.uncertain=true;return new TransferObservation(Status.UNCERTAIN,Reason.UNCERTAIN_EFFECT,token.quantity,before,after,"native participants; J reconciliation required");}
  return new TransferObservation(Status.LIVE_DEPOSITED,Reason.NONE,token.quantity,before,after,"destination "+destination.storage());
 }
 private boolean matches(DeliveryPolicy.Goal g,Binding s,Binding d){return g.carrier().equals(identity.citizen()) && g.source().world().equals(identity.world()) && g.destination().world().equals(identity.world()) && g.source().storage().equals(s.storage()) && g.destination().storage().equals(d.storage()) && g.source().dimension().equals(s.topology().dimension()) && g.destination().dimension().equals(d.topology().dimension()) && s.topology().world().equals(identity.world()) && d.topology().world().equals(identity.world()) && s.topology().dimension().equals(identity.dimension()) && d.topology().dimension().equals(identity.dimension()) && g.source().participants().equals(participants(s)) && g.destination().participants().equals(participants(d));}
 private Set<UUID> participants(Binding b){var ids=new HashSet<UUID>();for(var m:b.topology().members())ids.add(m.incarnation());return ids;}
 private List<String> order(Binding a,Binding b){var ids=new TreeSet<String>();ids.add(identity.world()+"/citizen/"+identity.citizen());for(var e:List.of(a,b))for(var m:e.topology().members())ids.add(identity.world()+"/storage/"+m.incarnation());return List.copyOf(ids);}
 private boolean authorized(Control c){return c!=null && c.authorized && c.exclusiveCustody && c.actor.equals(identity) && nativeIdentity() && scope.world().equals(identity.world()) && scope.principal().equals(identity.principal()) && Objects.equals(scope.actorCitizen(),identity.citizen());}
 private boolean carrierSupported(){for(int i=0;i<inventory.getContainerSize();i++){var x=inventory.getItem(i);if(x.isEmpty())continue;if(!x.getComponentsPatch().isEmpty() || x.has(net.minecraft.core.component.DataComponents.CONTAINER) || x.has(net.minecraft.core.component.DataComponents.BUNDLE_CONTENTS) || x.has(net.minecraft.core.component.DataComponents.CHARGED_PROJECTILES) || x.getItem()instanceof net.minecraft.world.item.BlockItem b && b.getBlock()instanceof net.minecraft.world.level.block.EntityBlock || x.getCount()>Math.min(x.getMaxStackSize(),inventory.getMaxStackSize(x)))return false;}return true;}
 private boolean nativeIdentity(){return carrier.isAlive() && carrier.level()==level && carrier.getUUID().equals(identity.entity()) && level.dimension().identifier().toString().equals(identity.dimension()) && inventory==carrier.getInventory() && inventory.getContainerSize()==8;}
 private Reason guards(Binding a,Binding b,ItemStack item){if(!storage.currentEndpoint(a) || !storage.currentEndpoint(b))return Reason.STALE_ENDPOINT;for(var pair:List.of(Map.entry(a,Permission.WITHDRAW),Map.entry(b,Permission.DEPOSIT))){var x=storage.check(scope,pair.getKey().storage(),pair.getValue(),item);if(!x.eligible())return x.refusal()==StorageCapabilities.Refusal.PROTECTION?Reason.UNAUTHORIZED:x.refusal()==StorageCapabilities.Refusal.COMPONENTS?Reason.UNSUPPORTED_ITEM:Reason.STALE_ENDPOINT;if(!StorageBindingRules.currentEndpoint(pair.getKey(),x.binding()))return Reason.STALE_ENDPOINT;}return Reason.NONE;}
 private boolean reach(Binding b){return b.topology().members().stream().anyMatch(m->carrier.distanceToSqr(m.x()+.5,m.y()+.5,m.z()+.5)<=9);}
 private List<NativeSlot> slots(Binding b){var out=new ArrayList<NativeSlot>();for(var m:b.topology().members()){var pos=new BlockPos(m.x(),m.y(),m.z());if(!level.hasChunkAt(pos))throw new IllegalStateException("unloaded");var c=(Container)level.getBlockEntity(pos);if(c.getContainerSize()!=27)throw new IllegalStateException("slot bound");for(int i=0;i<27;i++)out.add(new NativeSlot(c,i));}return out;}
 private int matching(List<NativeSlot> slots,ItemStack probe){int n=0;for(var s:slots){var item=s.container.getItem(s.index);if(ItemStack.isSameItemSameComponents(item,probe))n+=item.getCount();}return n;}
 private Counts counts(List<NativeSlot> a,List<NativeSlot> b,ItemStack item){int n=0;for(int i=0;i<inventory.getContainerSize();i++)if(ItemStack.isSameItemSameComponents(inventory.getItem(i),item))n+=inventory.getItem(i).getCount();return new Counts(matching(a,item),n,matching(b,item));}
 private Counts safeCounts(List<NativeSlot>a,List<NativeSlot>b,ItemStack item){try{return counts(a,b,item);}catch(RuntimeException unknown){return null;}}
 private int capacity(List<NativeSlot> slots,ItemStack item){int n=0;for(var s:slots){var x=s.container.getItem(s.index);if(s.container.canPlaceItem(s.index,item) && (x.isEmpty() || ItemStack.isSameItemSameComponents(x,item)))n+=Math.max(0,Math.min(s.container.getMaxStackSize(item),item.getMaxStackSize())-x.getCount());}return n;}
 private Map<NativeSlot,ItemStack> withdrawPlan(List<NativeSlot> slots,ItemStack item,int amount){var out=new LinkedHashMap<NativeSlot,ItemStack>();for(var s:slots){var stack=s.container.getItem(s.index);if(amount>0 && ItemStack.isSameItemSameComponents(stack,item) && s.container.canTakeItem(s.container,s.index,stack)){int take=Math.min(amount,stack.getCount());out.put(s,stack.getCount()==take?ItemStack.EMPTY:stack.copyWithCount(stack.getCount()-take));amount-=take;}}if(amount!=0)throw new IllegalStateException("withdrawal plan shortage");return out;}
 private Map<NativeSlot,ItemStack> depositPlan(List<NativeSlot> slots,ItemStack item,int amount){var out=new LinkedHashMap<NativeSlot,ItemStack>();for(var s:slots){var x=s.container.getItem(s.index);if(amount>0 && s.container.canPlaceItem(s.index,item) && (x.isEmpty() || ItemStack.isSameItemSameComponents(x,item))){int take=Math.min(amount,Math.min(s.container.getMaxStackSize(item),item.getMaxStackSize())-x.getCount());if(take>0){out.put(s,item.copyWithCount(x.getCount()+take));amount-=take;}}}if(amount!=0)throw new IllegalStateException("deposit plan capacity");return out;}
 private String nativeRevision(List<NativeSlot> slots){var out=new StringBuilder();for(var s:slots){var x=s.container.getItem(s.index);out.append(s.index).append(':').append(x.isEmpty()?"empty":storage.describeItem(x).toString()+":"+x.getCount()).append(';');}return out.toString();}
 private String revision(){var out=new StringBuilder();for(int i=0;i<inventory.getContainerSize();i++){var x=inventory.getItem(i);out.append(i).append(':').append(x.isEmpty()?"empty":storage.describeItem(x).toString()+":"+x.getCount()).append(';');}return out.toString();}
 private TransferObservation refuse(Reason reason,Counts counts,String location){return new TransferObservation(Status.REFUSED,reason,0,counts,counts,location);}
 private void thread(){if(!level.getServer().isSameThread())throw new IllegalStateException("same server tick critical section required");}
}
