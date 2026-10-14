package dev.aivillages.core.kernel;

import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.WorldReferenceResolver.*;
import static dev.aivillages.core.kernel.Outcomes.Reason;
import static dev.aivillages.core.kernel.SurvivalGateway.*;

/** Registered crop/container criteria over bounded read-only observations. No Minecraft or model API. */
public final class WorldReferenceDiscovery {
    private WorldReferenceDiscovery() { }
    public static final int HORIZONTAL_RADIUS = 16, VERTICAL_RANGE = 4, WORK_PER_POLL = 256;
    public static final int SEARCH_CELLS = 33 * 33 * 9;
    public static final long TOTAL_WORK = 32_768, RESOLUTION_MILLIS = 12_000;
    public static final int MAX_CANDIDATES = 128, MAX_DIAGNOSTICS = 32, MAX_RETAINED_WHEAT = 2_048;
    public static final int MAX_REGION_CELLS = 256, MAX_CONTAINER_SLOTS = 128, MIN_FIELD_CELLS = 2;

    /** Each call inspects at most one loaded cell/entity, plus the explicitly reported bounded slots. */
    public interface Observation {
        ActorState actor(ActorRef actor);
        CropState crop(ActorRef actor, Point point);
        ContainerSample container(ActorRef actor, ContainerRef container);
        default FacilitySample facility(ActorRef actor,FacilityRef table) {return new FacilitySample(ObservationStatus.UNKNOWN,"unsupported");}
        default ItemBlock itemBlock(ActorRef actor,Point point,ResourceDelivery.ItemIdentity item) {
            if(item.equals(ResourceDelivery.WHEAT)) {
                CropState c=crop(actor,point); return new ItemBlock(c.status(),c.matureWheat(),c.identity(),0);
            }
            return new ItemBlock(ObservationStatus.UNKNOWN,false,"unsupported",0);
        }
        default ItemContainerSample itemContainer(ActorRef actor,ContainerRef target,ResourceDelivery.ItemIdentity item) {
            if(item.equals(ResourceDelivery.WHEAT)) {
                ContainerSample c=container(actor,target);
                return new ItemContainerSample(item,c.status(),c.wheatCapacity(),c.slotsInspected(),c.identity());
            }
            return new ItemContainerSample(item,ObservationStatus.UNKNOWN,0,0,"unsupported");
        }
        default InputContainerSample inputContainer(ActorRef actor,ContainerRef source,ResourceDelivery.ItemIdentity item) {
            return item.equals(ResourceDelivery.OAK_LOG) ? inputContainer(actor,source)
                    : new InputContainerSample(ObservationStatus.UNKNOWN,0,0,"unsupported-input");
        }
        default InputContainerSample inputContainer(ActorRef actor,ContainerRef source) {
            return new InputContainerSample(ObservationStatus.UNKNOWN,0,0,"unsupported");
        }
    }
    public record FacilitySample(ObservationStatus status,String identity) {
        public FacilitySample {Objects.requireNonNull(status);if(identity==null || identity.length()>128)throw new IllegalArgumentException("Facility sample");}
    }
    public record FacilityTarget(FacilityRef facility,FacilitySample evidence) { }
    public static Discovery<FacilityTarget> tables(ActorRef actor,Point anchor,Observation world) {
        return new Discovery<>() {
            List<Candidate<FacilityTarget>> candidates=new ArrayList<>();int cursor;boolean unknown;
            public Scan<FacilityTarget> poll(int maximum) {
                int work=0,width=HORIZONTAL_RADIUS*2+1;
                while(cursor<SEARCH_CELLS && work<maximum) {
                    int at=cursor++;work++;var point=new Point(anchor.dimension(),anchor.x()-HORIZONTAL_RADIUS+at%width,
                            anchor.y()-VERTICAL_RANGE+at/(width*width),anchor.z()-HORIZONTAL_RADIUS+(at/width)%width);
                    var f=new FacilityRef(point.dimension(),point.x(),point.y(),point.z());var sample=world.facility(actor,f);
                    if(sample.status()==ObservationStatus.UNKNOWN)unknown=true;
                    else if(sample.status()==ObservationStatus.PRESENT) {
                        if(candidates.size()>=MAX_CANDIDATES)return Scan.complete(List.of(),work,Reason.BUDGET_EXHAUSTED,List.of("table-candidate-cap"));
                        candidates.add(new Candidate<>(new FacilityTarget(f,sample),point,sample.identity()));
                    }
                }
                if(cursor<SEARCH_CELLS)return Scan.progress(List.of(),work,List.of());
                return unknown ? Scan.complete(List.of(),work,Reason.STALE_OBSERVATION,List.of("incomplete-table-envelope"))
                        : Scan.complete(candidates,work,Reason.FACILITY_MISSING,List.of());
            }
            public void cancel() {cursor=SEARCH_CELLS;candidates=List.of();}
        };
    }
    public record ContainerSample(ObservationStatus status, int wheatCapacity, int slotsInspected, String identity) {
        public ContainerSample {
            Objects.requireNonNull(status); Objects.requireNonNull(identity);
            if (wheatCapacity < 0 || slotsInspected < 0 || slotsInspected > MAX_CONTAINER_SLOTS
                    || identity.length() > 128) throw new IllegalArgumentException("Container sample bounds");
        }
    }
    public record CitizenTarget(ActorRef actor, Point capturedPosition) { }
    public record ContainerTarget(ContainerRef container, ContainerSample evidence) { }
    public record WheatField(Cuboid area, Map<Point, Boolean> wheat) {
        public WheatField {
            wheat = Map.copyOf(wheat);
            if (wheat.size() > MAX_REGION_CELLS || volume(area) > MAX_REGION_CELLS)
                throw new IllegalArgumentException("Field evidence bounds");
        }
    }
    public record SourceArea(Cuboid area,Map<Point,Boolean> eligible,WheatField originalWheat) {
        public SourceArea {
            eligible=Map.copyOf(eligible);
            if(volume(area)>MAX_REGION_CELLS || eligible.size()>MAX_REGION_CELLS)
                throw new IllegalArgumentException("Source evidence");
        }
        public boolean oak() { return originalWheat==null; }
    }
    public record ItemContainerSample(ResourceDelivery.ItemIdentity item,ObservationStatus status,int capacity,
                                      int slotsInspected,String identity) {
        public ItemContainerSample {
            Objects.requireNonNull(item); Objects.requireNonNull(status); Objects.requireNonNull(identity);
            if(capacity<0 || slotsInspected<0 || slotsInspected>MAX_CONTAINER_SLOTS || identity.length()>128)
                throw new IllegalArgumentException("Item container evidence");
        }
    }
    public record ItemContainerTarget(ContainerRef container,ItemContainerSample evidence) { }
    public record InputContainerSample(ObservationStatus status,long available,int slotsInspected,String identity) {
        public InputContainerSample {
            Objects.requireNonNull(status);Objects.requireNonNull(identity);
            if(available<0 || available>8192 || slotsInspected<0 || slotsInspected>MAX_CONTAINER_SLOTS || identity.length()>128)
                throw new IllegalArgumentException("Input stock evidence");
        }
    }
    public record InputContainerTarget(ContainerRef container,InputContainerSample evidence) { }
    public static Discovery<InputContainerTarget> inputContainers(ActorRef actor,int required,Point anchor,Observation world) {
        if(required<1 || required>16)throw new IllegalArgumentException("Input batch envelope");
        return inputContainers(actor,required,anchor,world,ResourceDelivery.OAK_LOG);
    }
    public static Discovery<InputContainerTarget> inputContainers(ActorRef actor,int required,Point anchor,Observation world,ResourceDelivery.ItemIdentity item) {
        if(required<1 || required>32 || !item.equals(ResourceDelivery.OAK_LOG) && !item.equals(ResourceDelivery.OAK_PLANKS))
            throw new IllegalArgumentException("Input batch envelope");
        return new Discovery<>() {
            List<Candidate<InputContainerTarget>> candidates=new ArrayList<>();int cursor;boolean unknown;
            public Scan<InputContainerTarget> poll(int maximum) {
                int work=0,width=HORIZONTAL_RADIUS*2+1;
                while(cursor<SEARCH_CELLS && maximum-work>=MAX_CONTAINER_SLOTS+1) {
                    int at=cursor++;work++;
                    var point=new Point(anchor.dimension(),anchor.x()-HORIZONTAL_RADIUS+at%width,
                            anchor.y()-VERTICAL_RANGE+at/(width*width),anchor.z()-HORIZONTAL_RADIUS+(at/width)%width);
                    var ref=new ContainerRef(point.dimension(),point.x(),point.y(),point.z());var sample=world.inputContainer(actor,ref,item);
                    work+=sample.slotsInspected();
                    if(sample.status()==ObservationStatus.UNKNOWN)unknown=true;
                    else if(sample.status()==ObservationStatus.PRESENT && sample.available()>=required) {
                        if(candidates.size()>=MAX_CANDIDATES)return Scan.complete(List.of(),work,Reason.BUDGET_EXHAUSTED,List.of("input-container-candidate-limit"));
                        candidates.add(new Candidate<>(new InputContainerTarget(ref,sample),point,sample.identity()));
                    }
                }
                if(cursor<SEARCH_CELLS)return Scan.progress(List.of(),work,List.of());
                return unknown ? Scan.complete(List.of(),work,Reason.STALE_OBSERVATION,List.of("incomplete-input-container-envelope"))
                        : Scan.complete(candidates,work,Reason.RESOURCE_MISSING,List.of());
            }
            public void cancel() {cursor=SEARCH_CELLS;candidates=List.of();}
        };
    }
    private static <A,B> Discovery<B> mapDiscovery(Discovery<A> source,java.util.function.Function<A,B> mapper) {
        return new Discovery<>() {
            public Scan<B> poll(int work) {
                Scan<A> s=source.poll(work);
                return new Scan<>(s.candidates().stream().map(c -> new Candidate<>(mapper.apply(c.value()),
                        c.rankPoint(),c.stableIdentity())).toList(),s.chargedWork(),s.complete(),s.emptyReason(),s.diagnostics());
            }
            public void cancel() { source.cancel(); }
        };
    }
    public static Discovery<SourceArea> sources(CapabilityId capability,ActorRef actor,int amount,Point anchor,Observation world) {
        if(capability.equals(CropDelivery.ID)) return mapDiscovery(wheat(actor,amount,anchor,world),
                f -> new SourceArea(f.area(),f.wheat(),f));
        if(!capability.equals(OakLogDelivery.ID) || amount<1 || amount>OakLogDelivery.MAX_AMOUNT)
            throw new IllegalArgumentException("Source capability");
        return new OakLogDiscovery.Source(actor,amount,anchor,world);
    }
    public static Discovery<ItemContainerTarget> itemContainers(CapabilityId capability,ActorRef actor,int amount,Point anchor,Observation world) {
        if(RecipeProduction.is(capability)) {
            if(amount<1 || amount>64)throw new IllegalArgumentException("Plank amount");
            return new OakLogDiscovery.Containers(actor,amount,anchor,world,RecipeProduction.of(capability).output());
        }
        if(capability.equals(CropDelivery.ID)) return mapDiscovery(containers(actor,amount,anchor,world),
                c -> new ItemContainerTarget(c.container(),new ItemContainerSample(ResourceDelivery.WHEAT,c.evidence().status(),
                        c.evidence().wheatCapacity(),c.evidence().slotsInspected(),c.evidence().identity())));
        if(!capability.equals(OakLogDelivery.ID) || amount<1 || amount>OakLogDelivery.MAX_AMOUNT)
            throw new IllegalArgumentException("Container capability");
        return new OakLogDiscovery.Containers(actor,amount,anchor,world);
    }
    public static final class SourceValidation {
        private final SourceArea source; private final ActorRef actor; private final Observation world;
        private final FieldValidation wheat; private int cursor;
        public SourceValidation(SourceArea source,ActorRef actor,Observation world) {
            this.source=source; this.actor=actor; this.world=world;
            wheat=source.oak() ? null : new FieldValidation(source.originalWheat(),actor,world);
        }
        public ValidationStep poll(int maximum) {
            if(wheat!=null) return wheat.poll(maximum);
            int work=0,size=Math.toIntExact(volume(source.area()));
            var a=source.area(); int width=a.maxX()-a.minX()+1,depth=a.maxZ()-a.minZ()+1;
            while(work<maximum && cursor<size) {
                Point p=new Point(a.dimension(),a.minX()+cursor%width,a.minY()+cursor/(width*depth),a.minZ()+(cursor/width)%depth);
                cursor++; work++;
                var current=world.itemBlock(actor,p,ResourceDelivery.OAK_LOG);
                boolean expected=source.eligible().containsKey(p);
                if(current.status()==ObservationStatus.UNKNOWN || (current.status()==ObservationStatus.PRESENT && current.eligible())!=expected)
                    return new ValidationStep(work,true,Reason.STALE_OBSERVATION);
            }
            return new ValidationStep(work,cursor==size,null);
        }
    }
    public static Discovery<WheatField> wheat(ActorRef actor, int amount, Point anchor, Observation observation) {
        if (amount < 1 || amount > 64) throw new IllegalArgumentException("Quantity");
        return new FieldDiscovery(actor, amount, anchor, observation);
    }
    public static Discovery<ContainerTarget> containers(ActorRef actor, int amount, Point anchor, Observation observation) {
        if (amount < 1 || amount > 64) throw new IllegalArgumentException("Quantity");
        return new ContainerDiscovery(actor, amount, anchor, observation);
    }
    public static Discovery<CitizenTarget> citizens(List<CitizenRegistry.Address> controlled,
                                                    Point anchor, boolean unnamedOnly, Observation observation) {
        if (controlled.size() > CitizenRegistry.MAX_CITIZENS) throw new IllegalArgumentException("Citizen population");
        return new Discovery<>() {
            int cursor;
            public Scan<CitizenTarget> poll(int maxWork) {
                int work = 0;
                List<Candidate<CitizenTarget>> found = new ArrayList<>();
                while (work < maxWork && cursor < controlled.size()) {
                    CitizenRegistry.Address row = controlled.get(cursor++); work++;
                    if (row.availability() != CitizenRegistry.Availability.LOADED
                            || unnamedOnly && row.displayName() != null) continue;
                    ActorRef actor = row.actor(); ActorState state = observation.actor(actor);
                    Point point = new Point(state.dimension(), state.x(), state.y(), state.z());
                    if (!state.loaded() || !state.alive() || !actor.dimension().equals(anchor.dimension())
                            || !actor.dimension().equals(state.dimension()) || !insideEnvelope(anchor, point)) continue;
                    found.add(new Candidate<>(new CitizenTarget(actor, point), point, actor.citizenId().toString()));
                }
                return cursor == controlled.size() ? Scan.complete(found, work, Reason.ACTOR_UNAVAILABLE, List.of())
                        : Scan.progress(found, work, List.of());
            }
            public void cancel() { cursor = controlled.size(); }
        };
    }
    public static boolean insideEnvelope(Point anchor, Point point) {
        return anchor.dimension().equals(point.dimension())
                && Math.abs((long)point.x() - anchor.x()) <= HORIZONTAL_RADIUS
                && Math.abs((long)point.z() - anchor.z()) <= HORIZONTAL_RADIUS
                && Math.abs((long)point.y() - anchor.y()) <= VERTICAL_RANGE;
    }
    public static Point areaAnchor(Cuboid area) {
        return new Point(area.dimension(), Math.toIntExact(Math.floorDiv((long)area.minX() + area.maxX(),2)),
                Math.toIntExact(Math.floorDiv((long)area.minY() + area.maxY(),2)),
                Math.toIntExact(Math.floorDiv((long)area.minZ() + area.maxZ(),2)));
    }
    public static long volume(Cuboid area) {
        return Math.multiplyExact(Math.multiplyExact((long)area.maxX() - area.minX() + 1,
                (long)area.maxY() - area.minY() + 1), (long)area.maxZ() - area.minZ() + 1);
    }
    private static List<Point> neighbours(Point p) {
        return List.of(new Point(p.dimension(),p.x()+1,p.y(),p.z()), new Point(p.dimension(),p.x()-1,p.y(),p.z()),
                new Point(p.dimension(),p.x(),p.y(),p.z()+1), new Point(p.dimension(),p.x(),p.y(),p.z()-1));
    }
    private static int compareByDistance(Point a, Point b, Point anchor) {
        int distance = WorldReferenceResolver.squaredDistance(anchor,a).compareTo(WorldReferenceResolver.squaredDistance(anchor,b));
        if (distance != 0) return distance;
        int x = Integer.compare(a.x(),b.x()); if (x != 0) return x;
        int y = Integer.compare(a.y(),b.y()); return y != 0 ? y : Integer.compare(a.z(),b.z());
    }
    public record ValidationStep(int work, boolean complete, Reason reason) { }

    /** Final source evidence checking stays incremental and shares the parent's allowance. */
    public static final class FieldValidation {
        private final WheatField field;
        private final ActorRef actor;
        private final Observation observation;
        private int cursor, neighbour;
        private Reason reason;
        public FieldValidation(WheatField field, ActorRef actor, Observation observation) {
            this.field = field; this.actor = actor; this.observation = observation;
        }
        public ValidationStep poll(int maxWork) {
            int work = 0;
            Cuboid a = field.area(); int size = Math.toIntExact(volume(a));
            int width = a.maxX()-a.minX()+1, depth = a.maxZ()-a.minZ()+1;
            while (work < maxWork && cursor < size && reason == null) {
                Point p = new Point(a.dimension(), a.minX() + cursor % width,
                        a.minY() + cursor / (width*depth), a.minZ() + (cursor / width) % depth);
                work++;
                if (neighbour == 0) {
                    CropState current = observation.crop(actor, p);
                    Boolean expected = field.wheat().get(p);
                    if (current.status() == ObservationStatus.UNKNOWN
                            || expected == null && current.status() == ObservationStatus.PRESENT
                            || expected != null && (current.status() != ObservationStatus.PRESENT
                                    || current.matureWheat() != expected)) reason = Reason.STALE_OBSERVATION;
                    if (expected == null) { cursor++; continue; }
                    neighbour = 1;
                } else {
                    Point n = neighbours(p).get(neighbour - 1);
                    boolean outside = n.x() < a.minX() || n.x() > a.maxX()
                            || n.z() < a.minZ() || n.z() > a.maxZ();
                    if (outside) {
                        CropState current = observation.crop(actor,n);
                        if (current.status() != ObservationStatus.ABSENT) reason = Reason.STALE_OBSERVATION;
                    }
                    if (++neighbour == 5) { neighbour = 0; cursor++; }
                }
            }
            return new ValidationStep(work, reason != null || cursor == size, reason);
        }
    }

    private static final class FieldDiscovery implements Discovery<WheatField> {
        private final Observation world;
        private final ActorRef selectedActor;
        private final int amount;
        private final Point anchor;
        private final int minX, minY, minZ;
        private final int width = HORIZONTAL_RADIUS * 2 + 1;
        private final int height = VERTICAL_RANGE * 2 + 1;
        private final int depth = HORIZONTAL_RADIUS * 2 + 1;
        private Map<Point, Boolean> wheat = new HashMap<>();
        private Set<Point> unknown = new HashSet<>();
        private List<Point> wheatOrder = new ArrayList<>();
        private Set<Point> visited = new HashSet<>();
        private ArrayDeque<Point> queue = new ArrayDeque<>();
        private Set<Point> component = new HashSet<>();
        private List<Candidate<WheatField>> candidates = new ArrayList<>();
        private int scanCursor, seedCursor, componentSize, matureCount, boxCursor;
        private int cMinX, cMinY, cMinZ, cMaxX, cMaxY, cMaxZ;
        private boolean clipped, oversized, validating, enclosedDisconnected, failed, cancelled;
        private int clippedRegions, oversizedRegions, insufficientRegions;
        private Point nearestMature;

        FieldDiscovery(ActorRef selectedActor, int amount, Point anchor, Observation world) {
            this.world = Objects.requireNonNull(world);
            this.selectedActor = selectedActor;
            this.amount = amount;
            this.anchor = anchor;
            minX = anchor.x() - HORIZONTAL_RADIUS;
            minY = anchor.y() - VERTICAL_RANGE;
            minZ = anchor.z() - HORIZONTAL_RADIUS;
        }

        @Override public Scan<WheatField> poll(int maxWork) {
            if (cancelled) return Scan.complete(List.of(),0,Reason.CANCELLED,List.of());
            if (failed) return Scan.complete(List.of(), 0, Reason.BUDGET_EXHAUSTED,
                    List.of("field-retained-state-limit"));
            int work = 0;
            if (scanCursor < SEARCH_CELLS) {
                while (work < maxWork && scanCursor < SEARCH_CELLS) {
                    Point pos = searchPos(scanCursor++);
                    work++;
                    var crop = world.crop(selectedActor, pos);
                    if (crop.status() == ObservationStatus.UNKNOWN) {
                        unknown.add(pos);
                    } else if (crop.status() == ObservationStatus.PRESENT) {
                        if (wheat.size() >= MAX_RETAINED_WHEAT) {
                            failed = true;
                            return Scan.complete(List.of(), work, Reason.BUDGET_EXHAUSTED,
                                    List.of("field-wheat-retention-limit"));
                        }
                        boolean mature = crop.matureWheat();
                        wheat.put(pos, mature);
                        wheatOrder.add(pos);
                    }
                }
                if (scanCursor < SEARCH_CELLS)
                    return Scan.progress(List.of(), work, List.of());
            }

            while (work < maxWork) {
                if (failed) return Scan.complete(List.of(), work, Reason.BUDGET_EXHAUSTED,
                        List.of("field-candidate-limit"));
                if (validating) {
                    while (work < maxWork && boxCursor < boxVolume()) {
                        Point p = boxPos(boxCursor++);
                        work++;
                        if (wheat.containsKey(p) && !component.contains(p)) enclosedDisconnected = true;
                        if (unknown.contains(p)) {
                            enclosedDisconnected = true;
                            if (!clipped) { clipped = true; clippedRegions++; }
                        }
                    }
                    if (boxCursor < boxVolume()) break;
                    if (!enclosedDisconnected) {
                        if (maxWork - work < component.size()) break;
                        work += component.size(); // Bounded evidence-copy work.
                        addComponentCandidate();
                    }
                    resetComponent();
                    continue;
                }

                if (!queue.isEmpty()) {
                    if (work + 5 > maxWork) break;
                    Point p = queue.removeFirst();
                    work++;
                    componentSize++;
                    if (componentSize <= MAX_REGION_CELLS) component.add(p);
                    else oversized = true;
                    updateBounds(p);
                    if (Boolean.TRUE.equals(wheat.get(p))) {
                        matureCount++;
                        if (nearestMature == null || compareByDistance(p, nearestMature, anchor) < 0)
                            nearestMature = p;
                    }
                    for (Point n : neighbours(p)) {
                        work++;
                        if (!insideDomain(n) || unknown.contains(n)) {
                            clipped = true;
                            continue;
                        }
                        if (wheat.containsKey(n) && visited.add(n)) queue.addLast(n);
                    }
                    if (!queue.isEmpty()) continue;
                    finishComponent();
                    continue;
                }

                while (work < maxWork && seedCursor < wheatOrder.size()
                        && visited.contains(wheatOrder.get(seedCursor))) {
                    seedCursor++;
                    work++;
                }
                if (seedCursor >= wheatOrder.size()) {
                    if (candidates.isEmpty())
                        return Scan.complete(List.of(), work, Reason.RESOURCE_MISSING,
                                List.of("bounded-search-cells=" + SEARCH_CELLS, "excluded-boundary=" + clippedRegions,
                                        "excluded-size=" + oversizedRegions, "excluded-supply=" + insufficientRegions));
                    return Scan.complete(List.copyOf(candidates), work, Reason.RESOURCE_MISSING,
                            List.of("bounded-search-cells=" + SEARCH_CELLS, "excluded-boundary=" + clippedRegions,
                                        "excluded-size=" + oversizedRegions, "excluded-supply=" + insufficientRegions));
                }
                if (work >= maxWork) break;
                Point seed = wheatOrder.get(seedCursor++);
                work++;
                visited.add(seed);
                queue.add(seed);
                startComponent();
            }
            return Scan.progress(List.of(), work, List.of());
        }

        private void startComponent() {
            component.clear();
            componentSize = 0;
            matureCount = 0;
            clipped = false;
            oversized = false;
            validating = false;
            enclosedDisconnected = false;
            nearestMature = null;
            cMinX = cMinY = cMinZ = Integer.MAX_VALUE;
            cMaxX = cMaxY = cMaxZ = Integer.MIN_VALUE;
            boxCursor = 0;
        }

        private void finishComponent() {
            if (componentSize < MIN_FIELD_CELLS || matureCount < amount || clipped || oversized
                    || nearestMature == null || safeBoxVolume() > MAX_REGION_CELLS) {
                if (clipped) clippedRegions++;
                if (oversized || safeBoxVolume() > MAX_REGION_CELLS) oversizedRegions++;
                if (matureCount < amount || componentSize < MIN_FIELD_CELLS) insufficientRegions++;
                resetComponent();
                return;
            }
            validating = true;
            boxCursor = 0;
            enclosedDisconnected = false;
        }

        private void addComponentCandidate() {
            try {
                Cuboid area = new Cuboid(selectedActor.dimension(),
                        cMinX, cMinY, cMinZ, cMaxX, cMaxY, cMaxZ);
                Point rank = new Point(selectedActor.dimension(),
                        nearestMature.x(), nearestMature.y(), nearestMature.z());
                String id = cMinX + "," + cMinY + "," + cMinZ + "/"
                        + cMaxX + "," + cMaxY + "," + cMaxZ;
                Map<Point, Boolean> evidence = new HashMap<>();
                for (Point cell : component) evidence.put(cell, wheat.get(cell));
                if (candidates.size() >= MAX_CANDIDATES) { failed = true; return; }
                candidates.add(new Candidate<>(new WheatField(area, evidence), rank, id));
            } catch (RuntimeException invalid) {
                // Candidate is conservatively excluded; canonical request bounds remain authoritative.
            }
        }

        private void resetComponent() {
            queue.clear();
            component.clear();
            validating = false;
            componentSize = matureCount = boxCursor = 0;
            clipped = oversized = enclosedDisconnected = false;
            nearestMature = null;
        }

        private void updateBounds(Point p) {
            cMinX = Math.min(cMinX, p.x()); cMaxX = Math.max(cMaxX, p.x());
            cMinY = Math.min(cMinY, p.y()); cMaxY = Math.max(cMaxY, p.y());
            cMinZ = Math.min(cMinZ, p.z()); cMaxZ = Math.max(cMaxZ, p.z());
            if (p.x() == minX || p.x() == minX + width - 1
                    || p.y() == minY || p.y() == minY + height - 1
                    || p.z() == minZ || p.z() == minZ + depth - 1)
                clipped = true;
        }

        private Point searchPos(int cursor) {
            int x = cursor % width;
            int z = (cursor / width) % depth;
            int y = cursor / (width * depth);
            return new Point(selectedActor.dimension(), minX + x, minY + y, minZ + z);
        }

        private boolean insideDomain(Point p) {
            return p.x() >= minX && p.x() < minX + width
                    && p.y() >= minY && p.y() < minY + height
                    && p.z() >= minZ && p.z() < minZ + depth;
        }

        private List<Point> neighbours(Point p) {
            return WorldReferenceDiscovery.neighbours(p);
        }

        private long safeBoxVolume() {
            if (cMinX == Integer.MAX_VALUE) return Long.MAX_VALUE;
            try {
                return Math.multiplyExact(Math.multiplyExact(
                        (long)cMaxX - cMinX + 1, (long)cMaxY - cMinY + 1),
                        (long)cMaxZ - cMinZ + 1);
            } catch (ArithmeticException overflow) { return Long.MAX_VALUE; }
        }

        private int boxVolume() { return Math.toIntExact(safeBoxVolume()); }

        private Point boxPos(int cursor) {
            int w = cMaxX - cMinX + 1;
            int d = cMaxZ - cMinZ + 1;
            int x = cursor % w;
            int z = (cursor / w) % d;
            int y = cursor / (w * d);
            return new Point(selectedActor.dimension(), cMinX + x, cMinY + y, cMinZ + z);
        }

        @Override public void cancel() {
            cancelled = true;
            scanCursor = SEARCH_CELLS;
            // Drop finite scan structures in constant time; clearing their backing arrays here
            // would hide thousands of operations in a terminal/cancellation tick.
            wheat = Map.of(); unknown = Set.of(); wheatOrder = List.of(); visited = Set.of(); component = Set.of();
            seedCursor = 0;
            queue = new ArrayDeque<>();
            candidates = List.of();
        }
    }

    private static final class ContainerDiscovery implements Discovery<ContainerTarget> {
        private final Observation world;
        private final ActorRef selectedActor;
        private final int amount;
        private final Point anchor;
        private final int minX, minY, minZ;
        private final int width = HORIZONTAL_RADIUS * 2 + 1;
        private final int height = VERTICAL_RANGE * 2 + 1;
        private final int depth = HORIZONTAL_RADIUS * 2 + 1;
        private List<Candidate<ContainerTarget>> candidates = new ArrayList<>();
        private int cursor;

        ContainerDiscovery(ActorRef selectedActor, int amount, Point anchor, Observation world) {
            this.world = Objects.requireNonNull(world);
            this.selectedActor = selectedActor;
            this.amount = amount;
            this.anchor = anchor;
            minX = anchor.x() - HORIZONTAL_RADIUS;
            minY = anchor.y() - VERTICAL_RANGE;
            minZ = anchor.z() - HORIZONTAL_RADIUS;
        }

        @Override public Scan<ContainerTarget> poll(int maxWork) {
            int work = 0;
            while (cursor < SEARCH_CELLS && work < maxWork) {
                if (maxWork - work < MAX_CONTAINER_SLOTS + 1) break;
                Point pos = searchPos(cursor++);
                work++;
                ContainerRef target = new ContainerRef(selectedActor.dimension(),
                        pos.x(), pos.y(), pos.z());
                ContainerSample inspected = world.container(selectedActor, target);
                if (inspected.slotsInspected() > MAX_CONTAINER_SLOTS)
                    return Scan.complete(List.of(), work, Reason.BUDGET_EXHAUSTED,
                            List.of("container-slot-limit"));
                work += inspected.slotsInspected();
                if (inspected.status() == ObservationStatus.PRESENT
                        && inspected.wheatCapacity() >= amount) {
                    if (candidates.size() >= MAX_CANDIDATES)
                        return Scan.complete(List.of(), work, Reason.BUDGET_EXHAUSTED,
                                List.of("container-candidate-limit"));
                    candidates.add(new Candidate<>(new ContainerTarget(target, inspected),
                            new Point(selectedActor.dimension(), pos.x(), pos.y(), pos.z()),
                            inspected.identity()));
                    if (candidates.size() > MAX_CANDIDATES)
                        return Scan.complete(List.of(), work, Reason.BUDGET_EXHAUSTED,
                                List.of("container-candidate-limit"));
                }
            }
            if (cursor < SEARCH_CELLS) return Scan.progress(List.of(), work, List.of());
            return Scan.complete(List.copyOf(candidates), work, Reason.FACILITY_MISSING,
                    List.of("loaded-search-cells=" + SEARCH_CELLS));
        }

        private Point searchPos(int cursor) {
            int x = cursor % width;
            int z = (cursor / width) % depth;
            int y = cursor / (width * depth);
            return new Point(selectedActor.dimension(), minX + x, minY + y, minZ + z);
        }

        @Override public void cancel() {
            cursor = SEARCH_CELLS;
            candidates = List.of();
        }
    }

}
