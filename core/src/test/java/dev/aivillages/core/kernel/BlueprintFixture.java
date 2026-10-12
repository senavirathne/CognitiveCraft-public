package dev.aivillages.core.kernel;

import java.util.*;
import static dev.aivillages.core.kernel.Blueprint.*;
import static dev.aivillages.core.kernel.BlueprintValidator.*;

/** Test-only two_rooms_timber_v1, independently specified in first-build-fixture.md. */
public final class BlueprintFixture {
    private BlueprintFixture() { }
    public static Registry registry() {
        var blocks=new TreeMap<String,Definition>();
        blocks.put("minecraft:oak_planks",new Definition("minecraft:oak_planks","minecraft:oak_planks",Kind.SINGLE,Map.of(),Map.of(),"minecraft-26.3:minecraft:oak_planks"));
        blocks.put("minecraft:torch",new Definition("minecraft:torch","minecraft:torch",Kind.SINGLE,Map.of(),Map.of(),"minecraft-26.3:minecraft:torch"));
        blocks.put("minecraft:oak_slab",new Definition("minecraft:oak_slab","minecraft:oak_slab",Kind.SLAB,
                Map.of("type",Set.of("top","bottom","double"),"waterlogged",Set.of("true","false")),
                Map.of("type","bottom","waterlogged","false"),"minecraft-26.3:minecraft:oak_slab"));
        blocks.put("minecraft:oak_door",new Definition("minecraft:oak_door","minecraft:oak_door",Kind.DOOR,
                Map.of("facing",Set.of("north","east","south","west"),"half",Set.of("lower","upper"),
                        "hinge",Set.of("left","right"),"open",Set.of("true","false"),"powered",Set.of("true","false")),
                Map.of("facing","north","half","lower","hinge","left","open","false","powered","false"),
                "minecraft-26.3:minecraft:oak_door"));
        return new Registry("minecraft-26.3",blocks);
    }
    public static Map<String,Long> bill() {
        return Map.of("minecraft:oak_planks",224L,"minecraft:oak_slab",99L,"minecraft:oak_door",2L,"minecraft:torch",4L);
    }
    public static Body timber() {
        var palette=List.of(new State("minecraft:oak_planks",Map.of()),new State("minecraft:oak_slab",Map.of("type","bottom")),
                new State("minecraft:oak_door",Map.of("facing","north","half","lower","hinge","left")),
                new State("minecraft:oak_door",Map.of("facing","north","half","upper","hinge","left")),
                new State("minecraft:oak_door",Map.of("facing","east","half","lower","hinge","left")),
                new State("minecraft:oak_door",Map.of("facing","east","half","upper","hinge","left")),
                new State("minecraft:torch",Map.of()));
        var cells=new ArrayList<Cell>();
        for(int x=0;x<11;x++)for(int z=0;z<9;z++) {
            cells.add(new Cell(x,0,z,0));cells.add(new Cell(x,4,z,1));
            if(x==0 || x==10 || z==0 || z==8)for(int y=1;y<=3;y++)
                if(!(x==2 && z==0 && y<=2))cells.add(new Cell(x,y,z,0));
        }
        for(int z=1;z<=7;z++)for(int y=1;y<=3;y++)
            if(!(z==4 && y<=2))cells.add(new Cell(5,y,z,0));
        cells.addAll(List.of(new Cell(2,1,0,2),new Cell(2,2,0,3),new Cell(5,1,4,4),new Cell(5,2,4,5),
                new Cell(2,1,2,6),new Cell(2,1,6,6),new Cell(8,1,2,6),new Cell(8,1,6,6)));
        return new Body(new Dimensions(11,5,9),new Constraints(true,true,1),palette,cells);
    }
    public static Body tiny() {
        return new Body(new Dimensions(2,1,1),new Constraints(true,true,1),
                List.of(new State("minecraft:oak_planks",Map.of())),List.of(new Cell(0,0,0,0)));
    }
    /** Compact GP-20 geometry: three supports, one slab, two door halves and one torch. */
    public static Body compact() {
        var palette=List.of(new State("minecraft:oak_planks",Map.of()),
                new State("minecraft:oak_slab",Map.of("type","bottom")),
                new State("minecraft:oak_door",Map.of("facing","north","half","lower","hinge","left")),
                new State("minecraft:oak_door",Map.of("facing","north","half","upper","hinge","left")),
                new State("minecraft:torch",Map.of()));
        return new Body(new Dimensions(3,3,2),new Constraints(true,true,1),palette,
                List.of(new Cell(0,0,0,0),new Cell(1,0,0,0),new Cell(2,0,0,0),
                        new Cell(2,1,0,1),new Cell(0,1,0,2),new Cell(0,2,0,3),new Cell(1,1,0,4)));
    }
    /** Independent item bill: the two door cells consume a single door item. */
    public static Map<String,Long> compactBill() {
        return Map.of("minecraft:oak_planks",3L,"minecraft:oak_slab",1L,"minecraft:oak_door",1L,"minecraft:torch",1L);
    }
    public static Body changed() {
        var body=tiny();return new Body(body.dimensions(),body.constraints(),body.palette(),List.of(new Cell(1,0,0,0)));
    }
    public static Registry changedMeaning() {
        var registry=registry();var map=new TreeMap<>(registry.blocks());var old=map.get("minecraft:oak_planks");
        map.put(old.block(),new Definition(old.block(),old.item(),old.kind(),old.values(),old.defaults(),"changed-placement"));
        return new Registry(registry.target(),map);
    }
}
