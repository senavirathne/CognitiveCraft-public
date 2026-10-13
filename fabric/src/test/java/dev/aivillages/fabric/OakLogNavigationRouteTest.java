package dev.aivillages.fabric;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class OakLogNavigationRouteTest {
    private static final NavigationRoute.Position GOAL=new NavigationRoute.Position(7.5,201,10.5);
    private static NavigationRoute route(Object path) {
        var route=new NavigationRoute(List.of(new NavigationRoute.Approach(7,201,10)));
        route.select(new NavigationRoute.Position(6.6,201,10.5));
        route.chargedPathfinding();route.accepted(sample(path,0,6.6),0);
        return route;
    }
    private static NavigationRoute.PathSample sample(Object path,int node,double x) {
        var actor=new NavigationRoute.Position(x,201,10.5);
        return new NavigationRoute.PathSample(path,123,node,1,node==1 ? actor : GOAL,actor);
    }
    @Test void endpointProgressRetainsTheRealCompletedCursorAndPathAllowance() {
        Object path=new Object();var route=route(path);
        assertEquals(NavigationRoute.Follow.CONTINUE,route.follow(sample(path,1,6.9),1,GOAL));
        assertEquals(1,route.lastSample().node());assertEquals(1,route.lastSample().count());
        assertEquals(NavigationRoute.Follow.CONTINUE,route.follow(sample(path,1,7.2),10,GOAL));
        assertEquals(0.3,route.bestDistance(),1e-9);assertEquals(10,route.lastProgress());
        assertEquals(1,route.attempts());assertEquals(1,route.navigationCalls());
    }
    @Test void finishedEndpointStillUsesTheOriginalTwentyTickStall() {
        Object path=new Object();var route=route(path);
        route.follow(sample(path,1,6.9),1,GOAL);
        assertEquals(NavigationRoute.Follow.CONTINUE,route.follow(sample(path,1,6.9),20,GOAL));
        assertEquals(NavigationRoute.Follow.ABANDON,route.follow(sample(path,1,6.9),21,GOAL));
        assertEquals("stalled",route.lastReason());assertFalse(route.hasRemaining());
        assertEquals(1,route.navigationCalls());
    }
    @Test void refinementCannotSubstituteAPathOrAnEndpoint() {
        Object path=new Object();var route=route(path);
        assertEquals(NavigationRoute.Follow.ABANDON,route.follow(sample(new Object(),1,6.9),1,GOAL));
        assertEquals("replaced-path",route.lastReason());
        route=route(path);
        assertEquals(NavigationRoute.Follow.ABANDON,route.follow(sample(path,1,6.9),1,
                new NavigationRoute.Position(7.4,201,10.5)));
        assertEquals("invalid-endpoint",route.lastReason());
        assertEquals(1,route.attempts());assertEquals(1,route.navigationCalls());
    }
}
