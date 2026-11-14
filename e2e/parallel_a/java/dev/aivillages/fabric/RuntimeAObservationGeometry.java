package dev.aivillages.fabric;

import java.util.*;

/** Immutable coordinates and boxes only. Native state and occupancy are queried on every capture. */
public final class RuntimeAObservationGeometry<P,B> {
    @FunctionalInterface public interface Position<P> { P at(int x,int y,int z); }
    @FunctionalInterface public interface Bounds<B> { B at(double x0,double y0,double z0,double x1,double y1,double z1); }
    public record Column<P,B>(int x,int z,String key,P position,P below,List<P> levels,B bounds) {
        public Column { levels=List.copyOf(levels); }
        public P assessmentPosition(int dy) { return levels.get(dy+1); }
    }
    private final List<Column<P,B>> columns;
    private final B occupancyBounds,actorsBounds;
    public RuntimeAObservationGeometry(int[] area,boolean assessment,int maxDy,Position<P> position,Bounds<B> bounds) {
        if(area.length!=5 || maxDy<0 || maxDy>5)throw new IllegalArgumentException("Bounded native observation geometry");
        long width=(long)area[3]-area[0]+1,depth=(long)area[4]-area[2]+1;
        if(width<1 || depth<1 || width*depth>512)throw new IllegalArgumentException("Original observation column bound");
        var rows=new ArrayList<Column<P,B>>();
        for(int x=area[0];x<=area[3];x++)for(int z=area[2];z<=area[4];z++) {
            var levels=new ArrayList<P>();
            if(assessment)for(int dy=-1;dy<=maxDy;dy++)levels.add(position.at(x,area[1]+dy,z));
            rows.add(new Column<>(x,z,x+","+area[1]+","+z,position.at(x,area[1],z),position.at(x,area[1]-1,z),levels,
                    bounds.at(x,area[1],z,x+1,area[1]+3,z+1)));
        }
        columns=List.copyOf(rows);
        occupancyBounds=bounds.at(area[0],area[1],area[2],area[3]+1,area[1]+3,area[4]+1);
        actorsBounds=bounds.at(area[0]-2,area[1]-2,area[2]-2,area[3]+3,area[1]+5,area[4]+3);
    }
    public List<Column<P,B>> columns(){return columns;}
    public B occupancyBounds(){return occupancyBounds;}
    public B actorsBounds(){return actorsBounds;}
    public static void main(String[] args) {
        record Coord(int x,int y,int z) { }
        record Box(double x0,double y0,double z0,double x1,double y1,double z1) { }
        int[] area={63,208,63,84,78};
        var geometry=new RuntimeAObservationGeometry<Coord,Box>(area,true,5,Coord::new,Box::new);
        int index=0;
        for(int x=63;x<=84;x++)for(int z=63;z<=78;z++) {
            var column=geometry.columns().get(index++);
            if(column.x()!=x || column.z()!=z || !column.key().equals(x+",208,"+z)
                    || !column.position().equals(new Coord(x,208,z)) || !column.below().equals(new Coord(x,207,z))
                    || !column.bounds().equals(new Box(x,208,z,x+1,211,z+1)))throw new AssertionError("Original column geometry changed");
            for(int dy=-1;dy<=5;dy++)if(!column.assessmentPosition(dy).equals(new Coord(x,208+dy,z)))
                throw new AssertionError("Original assessment cell changed");
        }
        if(index!=352 || !geometry.occupancyBounds().equals(new Box(63,208,63,85,211,79))
                || !geometry.actorsBounds().equals(new Box(61,206,61,87,213,81)))throw new AssertionError("Original native query bounds changed");
        var original=geometry.columns().get(0);area[0]=0;
        if(geometry.columns().get(0)!=original || original.position()!=geometry.columns().get(0).position()
                || original.bounds()!=geometry.columns().get(0).bounds())throw new AssertionError("Geometry was recreated or changed");
        try{geometry.columns().clear();throw new AssertionError("Published geometry mutable");}catch(UnsupportedOperationException expected) { }
        new RuntimeAObservationGeometry<Coord,Box>(new int[]{0,208,0,511,0},false,2,Coord::new,Box::new);
        try{new RuntimeAObservationGeometry<Coord,Box>(new int[]{0,208,0,512,0},true,5,Coord::new,Box::new);throw new AssertionError("Column bound raised");}
        catch(IllegalArgumentException expected) { }
        System.out.println("A10_OBSERVATION_GEOMETRY_PASS original-cells-order-and-native-boxes immutable-coordinates-only no-state-or-occupancy-cache");
    }
}
