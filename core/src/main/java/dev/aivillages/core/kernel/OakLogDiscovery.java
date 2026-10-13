package dev.aivillages.core.kernel;

import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.SurvivalGateway.*;
import static dev.aivillages.core.kernel.WorldReferenceDiscovery.*;
import static dev.aivillages.core.kernel.WorldReferenceResolver.*;
import static dev.aivillages.core.kernel.Outcomes.Reason;

/** Registered read-only RB criteria for the existing bounded world-reference owner. */
final class OakLogDiscovery {
    static final int RETAINED_LOGS=32;
    private static int compare(Point a,Point b,Point origin) {
        int d=squaredDistance(origin,a).compareTo(squaredDistance(origin,b));
        if(d!=0) return d;
        int x=Integer.compare(a.x(),b.x()); if(x!=0) return x;
        int y=Integer.compare(a.y(),b.y()); return y!=0 ? y : Integer.compare(a.z(),b.z());
    }
    private static Point searchPoint(Point anchor,int cursor) {
        int width=HORIZONTAL_RADIUS*2+1;
        return new Point(anchor.dimension(),anchor.x()-HORIZONTAL_RADIUS+cursor%width,
                anchor.y()-VERTICAL_RANGE+cursor/(width*width),anchor.z()-HORIZONTAL_RADIUS+(cursor/width)%width);
    }
    static final class Source implements Discovery<SourceArea> {
        final ActorRef actor; final int amount; final Point anchor; final Observation world;
        Map<Point,Boolean> logs=new LinkedHashMap<>(); List<Point> points=List.of(),selected=new ArrayList<>();
        List<Candidate<SourceArea>> candidates=new ArrayList<>(); Set<Cuboid> seen=new HashSet<>();
        int cursor,seed,other; boolean unknown,cancelled,prepared;
        Source(ActorRef actor,int amount,Point anchor,Observation world) {
            this.actor=actor;this.amount=amount;this.anchor=anchor;this.world=world;
        }
        public Scan<SourceArea> poll(int maxWork) {
            if(cancelled) return Scan.complete(List.of(),0,Reason.CANCELLED,List.of());
            int work=0;
            while(cursor<SEARCH_CELLS && work<maxWork) {
                Point p=searchPoint(anchor,cursor++); work++;
                ItemBlock block=world.itemBlock(actor,p,ResourceDelivery.OAK_LOG);
                if(block.status()==ObservationStatus.UNKNOWN) unknown=true;
                else if(block.status()==ObservationStatus.PRESENT && block.eligible()) {
                    if(logs.size()==RETAINED_LOGS) return Scan.complete(List.of(),work,Reason.BUDGET_EXHAUSTED,List.of("oak-retained-log-limit"));
                    logs.put(p,true);
                }
            }
            if(cursor<SEARCH_CELLS) return Scan.progress(List.of(),work,List.of());
            if(unknown) return Scan.complete(List.of(),work,Reason.STALE_OBSERVATION,List.of("incomplete-oak-envelope"));
            if(logs.size()<amount) return Scan.complete(List.of(),work,Reason.RESOURCE_MISSING,List.of());
            if(!prepared) {
                if(maxWork-work<logs.size()) return Scan.progress(List.of(),work,List.of());
                points=List.copyOf(logs.keySet());work+=points.size();prepared=true;
            }
            while(seed<points.size()) {
                while(other<points.size() && maxWork-work>=amount+1) {
                    Point p=points.get(other++),origin=points.get(seed); int at=0; work++;
                    while(at<selected.size()) { work++; if(compare(p,selected.get(at),origin)<0) break; at++; }
                    if(at<amount) { selected.add(at,p); if(selected.size()>amount) selected.remove(amount); }
                }
                if(other<points.size() || maxWork-work<points.size()+amount) return Scan.progress(List.of(),work,List.of());
                int x1=Integer.MAX_VALUE,y1=x1,z1=x1,x2=Integer.MIN_VALUE,y2=x2,z2=x2;
                Point rank=selected.getFirst();
                for(Point p:selected) {
                    x1=Math.min(x1,p.x());y1=Math.min(y1,p.y());z1=Math.min(z1,p.z());
                    x2=Math.max(x2,p.x());y2=Math.max(y2,p.y());z2=Math.max(z2,p.z());
                    if(compare(p,rank,anchor)<0) rank=p;
                }
                work+=selected.size()+points.size();
                Cuboid area=new Cuboid(anchor.dimension(),x1,y1,z1,x2,y2,z2);
                if(volume(area)<=MAX_REGION_CELLS && seen.add(area)) {
                    var retained=new LinkedHashMap<Point,Boolean>();
                    for(Point p:points) if(new ResourceDelivery.BlockPosition(p.dimension(),p.x(),p.y(),p.z()).inside(area)) retained.put(p,true);
                    String identity="oak:"+UUID.nameUUIDFromBytes(area.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    candidates.add(new Candidate<>(new SourceArea(area,retained,null),rank,identity));
                }
                seed++; other=0; selected=new ArrayList<>();
            }
            return Scan.complete(candidates,work,Reason.RESOURCE_MISSING,List.of("loaded-oak-search-cells="+SEARCH_CELLS));
        }
        public void cancel() { cancelled=true; logs=Map.of(); points=List.of(); selected=List.of(); candidates=List.of(); seen=Set.of(); }
    }
    static final class Containers implements Discovery<ItemContainerTarget> {
        final ActorRef actor; final int amount; final Point anchor; final Observation world;
        List<Candidate<ItemContainerTarget>> candidates=new ArrayList<>(); int cursor; boolean unknown;
        Containers(ActorRef actor,int amount,Point anchor,Observation world) {
            this.actor=actor;this.amount=amount;this.anchor=anchor;this.world=world;
        }
        public Scan<ItemContainerTarget> poll(int maxWork) {
            int work=0;
            while(cursor<SEARCH_CELLS && maxWork-work>=MAX_CONTAINER_SLOTS+1) {
                Point p=searchPoint(anchor,cursor++); work++;
                var target=new ContainerRef(p.dimension(),p.x(),p.y(),p.z());
                var sample=world.itemContainer(actor,target,ResourceDelivery.OAK_LOG); work+=sample.slotsInspected();
                if(sample.status()==ObservationStatus.UNKNOWN) unknown=true;
                else if(sample.status()==ObservationStatus.PRESENT && sample.capacity()>=amount) {
                    if(candidates.size()==MAX_CANDIDATES) return Scan.complete(List.of(),work,Reason.BUDGET_EXHAUSTED,List.of("oak-container-candidate-limit"));
                    candidates.add(new Candidate<>(new ItemContainerTarget(target,sample),p,sample.identity()));
                }
            }
            if(cursor<SEARCH_CELLS) return Scan.progress(List.of(),work,List.of());
            return unknown ? Scan.complete(List.of(),work,Reason.STALE_OBSERVATION,List.of("incomplete-oak-container-envelope"))
                    : Scan.complete(candidates,work,Reason.FACILITY_MISSING,List.of());
        }
        public void cancel() { cursor=SEARCH_CELLS; candidates=List.of(); }
    }
    private OakLogDiscovery() { }
}
