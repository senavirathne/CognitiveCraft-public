package dev.aivillages.core.kernel;

import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;

/** Additive item-delivery evidence. CropDelivery v1 keeps its original wheat semantics. */
public final class ResourceDelivery {
    public static final int RECEIPT_SCHEMA = 1, MAX_RECEIPTS = 64;
    private ResourceDelivery() { }

    /** A bounded data identity, never an executable selector. Only default components are supported. */
    public record ItemIdentity(String id, String components) {
        public ItemIdentity {
            if (id == null || id.length() > 128 || !id.matches("[a-z0-9_.-]+:[a-z0-9_/.-]{1,96}")
                    || !"default".equals(components)) throw new IllegalArgumentException("Item identity/components");
        }
    }
    public static final ItemIdentity OAK_LOG = new ItemIdentity("minecraft:oak_log", "default");
    public static final ItemIdentity WHEAT = new ItemIdentity("minecraft:wheat", "default");
    public static final ItemIdentity OAK_PLANKS = new ItemIdentity("minecraft:oak_planks", "default");
    public enum Stage { HARVEST, PICKUP, DEPOSIT, WITHDRAW, CRAFT }
    public record BlockPosition(String dimension,int x,int y,int z) {
        public BlockPosition { new Cuboid(dimension,x,y,z,x,y,z); }
        public boolean inside(Cuboid a) {
            return dimension.equals(a.dimension()) && x>=a.minX() && x<=a.maxX()
                    && y>=a.minY() && y<=a.maxY() && z>=a.minZ() && z<=a.maxZ();
        }
    }

    /** Issued by the gateway and retained by its existing execution/Jobs owners, never accepted from IR. */
    public record Receipt(UUID receiptId, TrustedContext origin, UUID runId, UUID operationId, UUID batchId,
                          ActorRef actor, Cuboid source, ContainerRef destination, ItemIdentity item,
                          Stage stage, long quantity, BlockPosition sourceBlock, List<UUID> dropIds,
                          RecipeTransaction.Transformation transformation) {
        public Receipt(UUID receiptId, TrustedContext origin, UUID runId, UUID operationId, UUID batchId,
                       ActorRef actor, Cuboid source, ContainerRef destination, ItemIdentity item,
                       Stage stage, long quantity, BlockPosition sourceBlock, List<UUID> dropIds) {
            this(receiptId,origin,runId,operationId,batchId,actor,source,destination,item,stage,quantity,sourceBlock,dropIds,null);
        }
        public Receipt {
            Objects.requireNonNull(receiptId); Objects.requireNonNull(origin); Objects.requireNonNull(runId);
            Objects.requireNonNull(operationId); Objects.requireNonNull(batchId); Objects.requireNonNull(actor);
            Objects.requireNonNull(source); Objects.requireNonNull(destination); Objects.requireNonNull(item);
            Objects.requireNonNull(stage);
            Objects.requireNonNull(sourceBlock); dropIds=List.copyOf(dropIds);
            if (quantity < 1 || quantity > 64 || !actor.dimension().equals(source.dimension())
                    || !actor.dimension().equals(destination.dimension()) || !sourceBlock.inside(source)
                    || (transformation==null ? dropIds.isEmpty() || stage==Stage.WITHDRAW || stage==Stage.CRAFT
                    : !dropIds.isEmpty() || (stage!=Stage.WITHDRAW && stage!=Stage.CRAFT && stage!=Stage.DEPOSIT))
                    || dropIds.size()>64 || new HashSet<>(dropIds).size()!=dropIds.size())
                throw new IllegalArgumentException("Resource receipt");
        }
    }

    public static boolean isDelivery(CapabilityId id) {
        return CropDelivery.ID.equals(id) || OakLogDelivery.ID.equals(id) || OakPlankProduction.ID.equals(id);
    }
    public static boolean resource(CapabilityId id) {return OakLogDelivery.ID.equals(id) || OakPlankProduction.ID.equals(id);}
    public static Optional<CapabilitySpec> spec(CapabilityId id) {
        return id.equals(CropDelivery.ID) ? Optional.of(CropDelivery.SPEC)
                : id.equals(OakLogDelivery.ID) ? Optional.of(OakLogDelivery.SPEC)
                : id.equals(OakPlankProduction.ID) ? Optional.of(OakPlankProduction.SPEC) : Optional.empty();
    }
    public static boolean completed(ValidatedRequest bound, UUID run, List<CropDelivery.CropReceipt> crops,
                                    List<Receipt> resources) {
        if (bound.request().capability().equals(CropDelivery.ID))
            return resources.isEmpty() && CropDelivery.completed(bound, run, crops);
        return crops.isEmpty() && (bound.request().capability().equals(OakPlankProduction.ID)
                ? OakPlankProduction.completed(bound,run,resources) : OakLogDelivery.completed(bound, run, resources));
    }

    public static boolean matches(ValidatedRequest bound, UUID run, Receipt r) {
        if(bound.request().capability().equals(OakPlankProduction.ID))return TransformationDelivery.matches(bound,run,r);
        if(!bound.request().capability().equals(OakLogDelivery.ID) || r.transformation()!=null) return false;
        var a = bound.request().arguments();
        return r.origin().equals(bound.context()) && r.runId().equals(run) && r.item().equals(OAK_LOG)
                && a.get("actor") instanceof ActorValue actor && actor.value().equals(r.actor())
                && a.get("source") instanceof AreaValue area && area.value().equals(r.source())
                && a.get("destination") instanceof ContainerValue target && target.value().equals(r.destination());
    }

    /** Exact duplicates are idempotent; changed IDs/operation claims and over-pickup/deposit fail closed. */
    public static long quantity(ValidatedRequest bound, UUID run, List<Receipt> receipts) {
        if(bound.request().capability().equals(OakPlankProduction.ID))return TransformationDelivery.quantity(bound,run,receipts);
        if (!bound.request().capability().equals(OakLogDelivery.ID) || receipts.size() > MAX_RECEIPTS)
            throw new IllegalArgumentException("Unsupported resource evidence");
        Map<UUID, Receipt> seen = new HashMap<>();
        Map<UUID, long[]> batches = new HashMap<>();
        Map<UUID, Receipt> batchBindings = new HashMap<>();
        Set<UUID> harvests = new HashSet<>();
        Map<UUID,UUID> dropBatches=new HashMap<>();
        Set<String> operations = new HashSet<>();
        for (Receipt r : receipts) {
            if (!matches(bound, run, r)) throw new IllegalArgumentException("Receipt binding");
            Receipt old = seen.putIfAbsent(r.receiptId(), r);
            if (old != null) {
                if (!old.equals(r)) throw new IllegalArgumentException("Changed receipt ID");
                continue;
            }
            // Each oak primitive produces at most one receipt per batch/stage. Transfers issue
            // a separate operation ID per physical item even when a handle transfers several.
            if (!operations.add(r.operationId()+"/"+r.batchId()+"/"+r.stage()))
                throw new IllegalArgumentException("Repeated physical operation");
            var binding=batchBindings.putIfAbsent(r.batchId(),r);
            if (binding!=null && (!binding.sourceBlock().equals(r.sourceBlock()) || !binding.dropIds().equals(r.dropIds())))
                throw new IllegalArgumentException("Changed batch custody");
            if (r.stage()==Stage.HARVEST && !harvests.add(r.batchId()))
                throw new IllegalArgumentException("Repeated harvest batch");
            for(UUID drop:r.dropIds()) {
                UUID oldBatch=dropBatches.putIfAbsent(drop,r.batchId());
                if(oldBatch!=null && !oldBatch.equals(r.batchId())) throw new IllegalArgumentException("Drop assigned twice");
            }
            long[] stages = batches.computeIfAbsent(r.batchId(), ignored -> new long[3]);
            stages[r.stage().ordinal()] = Math.addExact(stages[r.stage().ordinal()], r.quantity());
        }
        long total = 0;
        for (long[] s : batches.values()) {
            if (s[1] > s[0] || s[2] > s[1]) throw new IllegalArgumentException("Unattributable units");
            total = Math.addExact(total, Math.min(s[0], Math.min(s[1], s[2])));
        }
        return total;
    }
    public static Cuboid sourceArea(CapabilityRequest request) {
        Value source=request.arguments().get("source");
        return source instanceof ContainerValue box ? ResourceLeases.point(box.value()) : ((AreaValue)source).value();
    }
}
