package dev.aivillages.core.kernel;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;
import static dev.aivillages.core.kernel.Blueprint.*;

/** Pure bounded structural validation against immutable registry snapshots. No generator or gateway port. */
public final class BlueprintValidator {
    public static final int PLACEMENT_POLICY = 1;
    public enum Kind { SINGLE, SLAB, DOOR }
    public enum Status { VALID, INVALID_DATA, INCOMPATIBLE, UNSUPPORTED_BLOCK, QUOTA, CANCELLED }
    public record Limits(int bytes, int dimension, long volume, int palette, int cells, int work) {
        public Limits {
            if (bytes < 0 || bytes > 65536 || dimension < 0 || dimension > 64
                    || volume < 0 || volume > 262144 || palette < 0 || palette > 64
                    || cells < 0 || cells > 512 || work < 0 || work > 8192)
                throw new IllegalArgumentException("Blueprint validation limits");
        }
        public static Limits defaults() { return new Limits(65536,64,262144,64,512,8192); }
    }
    /** A trusted host supplies only declared supported item-placement semantics. */
    public record Definition(String block, String item, Kind kind, Map<String, Set<String>> values,
                             Map<String, String> defaults, String meaning) {
        public Definition {
            identifier(block); identifier(item); Objects.requireNonNull(kind);
            if (values == null || defaults == null || values.size() > 16 || !values.keySet().equals(defaults.keySet())
                    || meaning == null || meaning.isBlank() || meaning.length() > 128)
                throw new IllegalArgumentException("Registry definition");
            var copy = new TreeMap<String,Set<String>>();
            var suppliedDefaults = defaults;
            values.forEach((k,v) -> {
                if (!k.matches("[a-z_]{1,32}") || v.isEmpty() || v.size() > 16
                        || !v.contains(suppliedDefaults.get(k)) || v.stream().anyMatch(s -> !s.matches("[a-z0-9_-]{1,32}")))
                    throw new IllegalArgumentException("Registry state values");
                copy.put(k,Set.copyOf(v));
            });
            values = Map.copyOf(copy); defaults = Map.copyOf(defaults);
        }
        public String fingerprint() {
            var ordered = new TreeMap<String,Object>();
            values.forEach((k,v) -> ordered.put(k,v.stream().sorted().toList()));
            return digest(StrictJson.canonical(Map.of("policy",PLACEMENT_POLICY,"block",block,"item",item,
                    "kind",kind.name(),"values",ordered,"defaults",defaults,"meaning",meaning)));
        }
    }
    public record Registry(String target, Map<String, Definition> blocks) {
        public Registry {
            if (target == null || target.isBlank() || target.length() > 64 || blocks == null || blocks.size() > 256)
                throw new IllegalArgumentException("Registry snapshot");
            blocks.forEach((id,definition) -> {
                if (!id.equals(definition.block())) throw new IllegalArgumentException("Registry key");
            });
            blocks = Map.copyOf(blocks);
        }
    }
    public record Requirement(String block, String item, Kind kind, String fingerprint) {
        public Requirement {
            identifier(block); identifier(item); Objects.requireNonNull(kind);
            if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Structural requirement");
        }
    }
    public record Result(Status status, Body body, Map<String, Long> materials, List<Requirement> requirements,
                         String target, int inputBytes, int work, String reason) {
        public Result {
            Objects.requireNonNull(status); materials = Map.copyOf(materials); requirements = List.copyOf(requirements);
            if (reason == null || reason.length() > 64 || inputBytes < 0 || work < 0
                    || status == Status.VALID && body == null) throw new IllegalArgumentException("Validation result");
        }
        public boolean valid() { return status == Status.VALID; }
    }
    private static final class Rejected extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final Status status; final String reason;
        Rejected(Status status,String reason) { this.status=status;this.reason=reason; }
    }
    private static final class Meter {
        final Limits limits; final BooleanSupplier cancel; int work;
        Meter(Limits limits,BooleanSupplier cancel) { this.limits=limits;this.cancel=cancel; }
        void step() {
            if (cancel.getAsBoolean()) throw new Rejected(Status.CANCELLED,"CANCELLED");
            if (work >= limits.work()) throw new Rejected(Status.QUOTA,"VALIDATION_WORK");
            work++;
        }
    }
    private BlueprintValidator() { }
    public static Result validate(String json, Registry registry, Limits limits, BooleanSupplier cancel) {
        Objects.requireNonNull(registry); Objects.requireNonNull(limits); Objects.requireNonNull(cancel);
        Meter meter=new Meter(limits,cancel); int bytes=0;
        try {
            if (cancel.getAsBoolean()) throw new Rejected(Status.CANCELLED,"CANCELLED");
            // Bound characters before making the UTF-8 allocation.
            if (json == null) throw new Rejected(Status.INVALID_DATA,"MISSING_INPUT");
            if (json.length()>limits.bytes()) throw new Rejected(Status.QUOTA,"INPUT_BYTES");
            bytes=json.getBytes(StandardCharsets.UTF_8).length;
            if (bytes>limits.bytes()) throw new Rejected(Status.QUOTA,"INPUT_BYTES");
            Body parsed=parse(json,meter);
            var normalized=new ArrayList<State>(); var requirements=new TreeMap<String,Requirement>();
            for (State state:parsed.palette()) {
                meter.step(); Definition definition=registry.blocks().get(state.block());
                if (definition==null) throw new Rejected(Status.UNSUPPORTED_BLOCK,"UNSUPPORTED_BLOCK");
                if(!requirements.containsKey(state.block())) {
                    supported(definition,meter);
                    requirements.put(state.block(),new Requirement(state.block(),definition.item(),definition.kind(),definition.fingerprint()));
                }
                var properties=new TreeMap<String,String>();
                for(var property:definition.defaults().entrySet()){meter.step();properties.put(property.getKey(),property.getValue());}
                for (var property:state.properties().entrySet()) {
                    meter.step();
                    if (!definition.values().containsKey(property.getKey())
                            || !definition.values().get(property.getKey()).contains(property.getValue()))
                        throw new Rejected(Status.INVALID_DATA,"UNKNOWN_STATE");
                    properties.put(property.getKey(),property.getValue());
                }
                State full=new State(state.block(),properties);
                if ("true".equals(properties.get("waterlogged")))
                    throw new Rejected(Status.UNSUPPORTED_BLOCK,"FLUID_PLACEMENT_UNSUPPORTED");
                normalized.add(full);
            }
            Body canonical=canonical(new Body(parsed.dimensions(),parsed.constraints(),normalized,parsed.cells()),meter);
            Set<String> used=canonical.palette().stream().map(State::block).collect(java.util.stream.Collectors.toSet());
            requirements.keySet().retainAll(used);
            Map<String,Long> bill=materials(canonical,requirements,meter);
            if (canonical.canonical().getBytes(StandardCharsets.UTF_8).length>limits.bytes())
                throw new Rejected(Status.QUOTA,"CANONICAL_BYTES");
            return new Result(Status.VALID,canonical,bill,List.copyOf(requirements.values()),registry.target(),bytes,meter.work,"VALID");
        } catch (Rejected rejected) {
            return new Result(rejected.status,null,Map.of(),List.of(),registry.target(),bytes,meter.work,rejected.reason);
        } catch (StrictJson.Invalid | IllegalArgumentException | ArithmeticException | ClassCastException | NullPointerException invalid) {
            return new Result(Status.INVALID_DATA,null,Map.of(),List.of(),registry.target(),bytes,meter.work,"INVALID_SHAPE");
        }
    }
    private static void supported(Definition definition,Meter meter) {
        meter.step();
        Set<String> keys=definition.values().keySet();
        Set<String> allowed=switch(definition.kind()) {
            case SINGLE -> Set.of("facing","axis","waterlogged");
            case SLAB -> Set.of("type","waterlogged");
            case DOOR -> Set.of("facing","half","hinge","open","powered");
        };
        if (!allowed.containsAll(keys) || definition.kind()==Kind.DOOR && !keys.equals(allowed)
                || definition.kind()==Kind.SLAB && !keys.contains("type"))
            throw new Rejected(Status.UNSUPPORTED_BLOCK,"PLACEMENT_SEMANTICS_UNSUPPORTED");
        for(var property:definition.values().entrySet()){meter.step();for(String value:property.getValue())meter.step();}
        if (keys.contains("facing") && !Set.of("north","east","south","west","up","down").containsAll(definition.values().get("facing"))
                || definition.kind()==Kind.DOOR && !Set.of("north","east","south","west").containsAll(definition.values().get("facing"))
                || keys.contains("axis") && !Set.of("x","y","z").containsAll(definition.values().get("axis"))
                || keys.contains("half") && !Set.of("lower","upper").equals(definition.values().get("half"))
                || keys.contains("hinge") && !Set.of("left","right").equals(definition.values().get("hinge"))
                || keys.contains("type") && !Set.of("top","bottom","double").containsAll(definition.values().get("type")))
            throw new Rejected(Status.UNSUPPORTED_BLOCK,"PLACEMENT_STATES_UNSUPPORTED");
        for (String flag:List.of("open","powered","waterlogged"))
            if (keys.contains(flag) && !Set.of("true","false").containsAll(definition.values().get(flag)))
                throw new Rejected(Status.UNSUPPORTED_BLOCK,"PLACEMENT_STATES_UNSUPPORTED");
    }
    private static Body parse(String json,Meter meter) throws StrictJson.Invalid {
        var root=StrictJson.object(json); exact(root,"schema","dimensions","constraints","palette","cells");
        if (number(root,"schema")!=SCHEMA) throw new Rejected(Status.INCOMPATIBLE,"UNSUPPORTED_SCHEMA");
        var d=object(root.get("dimensions"));exact(d,"x","y","z");
        int x=integer(d,"x"),y=integer(d,"y"),z=integer(d,"z");
        if (x<1 || y<1 || z<1) throw new Rejected(Status.INVALID_DATA,"DIMENSIONS");
        if (x>meter.limits.dimension() || y>meter.limits.dimension() || z>meter.limits.dimension()
                || (long)x*y*z>meter.limits.volume()) throw new Rejected(Status.QUOTA,"DIMENSIONS");
        var c=object(root.get("constraints"));exact(c,"supportedGround","nonHazardous","perimeter");
        var constraints=new Constraints((Boolean)c.get("supportedGround"),(Boolean)c.get("nonHazardous"),integer(c,"perimeter"));
        List<?> palette=(List<?>)root.get("palette"),cells=(List<?>)root.get("cells");
        if (palette.isEmpty() || cells.isEmpty()) throw new Rejected(Status.INVALID_DATA,"EMPTY_STRUCTURE");
        if (palette.size()>meter.limits.palette()) throw new Rejected(Status.QUOTA,"PALETTE");
        if (cells.size()>meter.limits.cells()) throw new Rejected(Status.QUOTA,"CELLS");
        var states=new ArrayList<State>();var geometry=new ArrayList<Cell>();
        for (Object value:palette) {
            meter.step();var state=object(value);exact(state,"block","properties");
            var properties=new TreeMap<String,String>();var p=object(state.get("properties"));
            if(p.size()>16)throw new Rejected(Status.QUOTA,"STATE_PROPERTIES");
            for(var e:p.entrySet()){meter.step();properties.put(e.getKey(),(String)e.getValue());}
            states.add(new State((String)state.get("block"),properties));
        }
        for(Object value:cells) {
            meter.step();var cell=object(value);exact(cell,"x","y","z","palette");
            geometry.add(new Cell(integer(cell,"x"),integer(cell,"y"),integer(cell,"z"),integer(cell,"palette")));
        }
        return new Body(new Dimensions(x,y,z),constraints,states,geometry);
    }
    private static Body canonical(Body body,Meter meter) {
        var positions=new TreeMap<String,State>();
        var used=new TreeMap<String,State>();
        for(Cell cell:body.cells()) {
            meter.step();
            if(cell.x()<0 || cell.x()>=body.dimensions().x() || cell.y()<0 || cell.y()>=body.dimensions().y()
                    || cell.z()<0 || cell.z()>=body.dimensions().z() || cell.palette()<0 || cell.palette()>=body.palette().size())
                throw new Rejected(Status.INVALID_DATA,"CELL_BOUNDS");
            State state=body.palette().get(cell.palette());
            String position=cell.x()+":"+cell.y()+":"+cell.z();
            State prior=positions.putIfAbsent(position,state);
            if(prior!=null)throw new Rejected(Status.INVALID_DATA,prior.equals(state)?"DUPLICATE_CELL":"CONFLICTING_CELL");
            used.put(state.key(),state);
        }
        var palette=List.copyOf(used.values()); var indexes=new HashMap<State,Integer>();
        for(int i=0;i<palette.size();i++){meter.step();indexes.put(palette.get(i),i);}
        var cells=new ArrayList<Cell>();
        positions.forEach((position,state)->{
            meter.step();String[] xyz=position.split(":");
            cells.add(new Cell(Integer.parseInt(xyz[0]),Integer.parseInt(xyz[1]),Integer.parseInt(xyz[2]),indexes.get(state)));
        });
        cells.sort((a,b)->{meter.step();int byY=Integer.compare(a.y(),b.y());
            if(byY!=0)return byY;int byZ=Integer.compare(a.z(),b.z());return byZ!=0?byZ:Integer.compare(a.x(),b.x());});
        return new Body(body.dimensions(),body.constraints(),palette,cells);
    }
    private static Map<String,Long> materials(Body body,Map<String,Requirement> definitions,Meter meter) {
        var positions=new HashMap<String,State>();var bill=new TreeMap<String,Long>();
        for(Cell c:body.cells()){meter.step();positions.put(c.x()+":"+c.y()+":"+c.z(),body.palette().get(c.palette()));}
        for(Cell cell:body.cells()) {
            meter.step();State state=body.palette().get(cell.palette());Requirement requirement=definitions.get(state.block());
            long amount=1;
            if(requirement.kind()==Kind.DOOR) {
                boolean lower="lower".equals(state.properties().get("half"));
                var otherProperties=new TreeMap<>(state.properties());otherProperties.put("half",lower?"upper":"lower");
                State other=positions.get(cell.x()+":"+(cell.y()+(lower?1:-1))+":"+cell.z());
                if(!new State(state.block(),otherProperties).equals(other))throw new Rejected(Status.INVALID_DATA,"DOOR_PAIR");
                // A door has two states but consumes exactly one item.
                if(!lower)continue;
                if(cell.y()==0?!body.constraints().supportedGround()
                        :!positions.containsKey(cell.x()+":"+(cell.y()-1)+":"+cell.z()))
                    throw new Rejected(Status.INVALID_DATA,"DOOR_SUPPORT");
            } else if(requirement.kind()==Kind.SLAB && "double".equals(state.properties().get("type"))) amount=2;
            bill.merge(requirement.item(),amount,Math::addExact);
        }
        return Map.copyOf(bill);
    }
    /** Dated observations only. Geometry and item-placement accounting remain owned here. */
    public record MaterialProjection(Map<String,Long> correct,Map<String,Long> outstanding,int partialStructures) {
        public MaterialProjection {correct=Map.copyOf(correct);outstanding=Map.copyOf(outstanding);}
    }
    public static MaterialProjection outstanding(View view,Map<String,Long> fullBill,List<State> actual) {
        Body body=view.geometry();
        if(actual==null || actual.size()!=body.cells().size())throw new IllegalArgumentException("Complete ordered site observations required");
        var positions=new HashMap<String,Integer>();
        for(int i=0;i<body.cells().size();i++){var c=body.cells().get(i);positions.put(c.x()+":"+c.y()+":"+c.z(),i);}
        var correct=new TreeMap<String,Long>();int partial=0;
        for(int i=0;i<body.cells().size();i++) {
            var cell=body.cells().get(i);var expected=body.palette().get(cell.palette());var seen=actual.get(i);
            if(expected.block().equals("minecraft:oak_door")) {
                if(!"lower".equals(expected.properties().get("half")))continue;
                Integer upper=positions.get(cell.x()+":"+(cell.y()+1)+":"+cell.z());
                if(upper==null)throw new IllegalArgumentException("Accepted door pair required");
                boolean lowerMatches=placementMatches(expected,seen);
                boolean upperMatches=placementMatches(body.palette().get(body.cells().get(upper).palette()),actual.get(upper));
                if(lowerMatches && upperMatches)correct.merge("minecraft:oak_door",1L,Math::addExact);
                else if(lowerMatches || upperMatches)partial++;
            }else if(placementMatches(expected,seen))correct.merge(expected.block(),
                    expected.block().equals("minecraft:oak_slab") && "double".equals(expected.properties().get("type"))?2L:1L,Math::addExact);
        }
        var remaining=new TreeMap<String,Long>();
        for(var e:fullBill.entrySet()) {
            long reduced=correct.getOrDefault(e.getKey(),0L);
            if(reduced>e.getValue())throw new IllegalArgumentException("Material projection exceeds accepted bill");
            remaining.put(e.getKey(),Math.subtractExact(e.getValue(),reduced));
        }
        return new MaterialProjection(correct,remaining,partial);
    }
    private static boolean placementMatches(State expected,State actual) {
        if(!expected.block().equals(actual.block()))return false;
        if(!expected.block().equals("minecraft:oak_door"))return expected.equals(actual);
        // Open/powered are transient world state, not a second material demand for a complete door.
        var a=new TreeMap<>(expected.properties());var b=new TreeMap<>(actual.properties());
        for(String key:List.of("open","powered")){a.remove(key);b.remove(key);}return a.equals(b);
    }
    public static View bind(Body body,SiteBinding binding) {
        if(!body.ref().equals(binding.source()))throw new IllegalArgumentException("Binding source");
        return new View(body.ref(),rotate(body,binding.quarterTurns()),binding);
    }
    /** Clockwise about +y: north -> east. Rotation preserves handedness; it does not mirror hinges. */
    public static Body rotate(Body body,int turns) {
        if(turns<0 || turns>3)throw new IllegalArgumentException("Quarter turns");
        Body result=body;
        for(int t=0;t<turns;t++) {
            var palette=new ArrayList<State>();
            for(State state:result.palette()) {
                var properties=new TreeMap<>(state.properties());String facing=properties.get("facing");
                if(facing!=null && List.of("north","east","south","west").contains(facing)) {
                    var directions=List.of("north","east","south","west");
                    properties.put("facing",directions.get((directions.indexOf(facing)+1)%4));
                }
                String axis=properties.get("axis");
                if("x".equals(axis))properties.put("axis","z");else if("z".equals(axis))properties.put("axis","x");
                palette.add(new State(state.block(),properties));
            }
            var cells=new ArrayList<Cell>();
            for(Cell c:result.cells())cells.add(new Cell(result.dimensions().z()-1-c.z(),c.y(),c.x(),c.palette()));
            result=canonical(new Body(new Dimensions(result.dimensions().z(),result.dimensions().y(),result.dimensions().x()),
                    result.constraints(),palette,cells),new Meter(Limits.defaults(),()->false));
        }
        return result;
    }
    /** Conservative bounded reader; current compatibility is a separate assessment. */
    static Body readCanonical(String json) throws StrictJson.Invalid {
        var meter=new Meter(Limits.defaults(),()->false);Body parsed=parse(json,meter);Body body=canonical(parsed,meter);
        if(!body.canonical().equals(json))throw new IllegalArgumentException("Noncanonical body");
        return body;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) {
        return (Map<String,Object>)value;
    }
    private static long number(Map<String,Object> map,String key) { return (Long)map.get(key); }
    private static int integer(Map<String,Object> map,String key) { return Math.toIntExact(number(map,key)); }
    private static void exact(Map<String,Object> map,String... keys) {
        if(!map.keySet().equals(Set.of(keys)))throw new Rejected(Status.INVALID_DATA,"UNEXPECTED_FIELDS");
    }
}
