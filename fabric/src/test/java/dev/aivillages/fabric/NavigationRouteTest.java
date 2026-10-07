package dev.aivillages.fabric;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Fake-clock path snapshots exercise the policy without replacing Minecraft integration. */
final class NavigationRouteTest {
    private static final NavigationRoute.Position WAYPOINT = new NavigationRoute.Position(0, 0, 0);
    private static final NavigationRoute.Position START = new NavigationRoute.Position(6, 0, 6);

    private static NavigationRoute route(int candidates) {
        List<NavigationRoute.Approach> approaches = new ArrayList<>();
        for (int i = 0; i < candidates; i++)
            approaches.add(new NavigationRoute.Approach(i, 0, 0));
        NavigationRoute route = new NavigationRoute(approaches);
        assertNotNull(route.select(START));
        route.chargedPathfinding();
        return route;
    }
    private static NavigationRoute.PathSample at(Object path, long geometry, int node,
                                                  int count, double distance) {
        return new NavigationRoute.PathSample(path, geometry, node, count, WAYPOINT,
                new NavigationRoute.Position(distance, 0, 0));
    }

    @Test void oscillationStillChargesTravelButExpiresAtOneWaypoint() {
        NavigationRoute route = route(2);
        Object path = new Object();
        route.accepted(at(path, 1, 0, 3, 4.2), 0);
        long chargedTravel = 0;
        for (int tick = 1; tick <= 20; tick++) {
            chargedTravel++; // independent actual travel, including backwards movement
            NavigationRoute.Follow result = route.follow(at(path, 1, 0, 3,
                    tick % 2 == 1 ? 4.5 : 4.2), tick);
            assertEquals(tick == 20 ? NavigationRoute.Follow.ABANDON
                    : NavigationRoute.Follow.CONTINUE, result);
        }
        assertEquals(20, chargedTravel);
        assertEquals("stalled", route.lastReason());
        assertEquals(0, route.lastProgress());
        assertEquals(1, route.navigationCalls());
    }

    @Test void subBlockGainsAccumulateAgainstTheLastCreditedBest() {
        NavigationRoute route = route(1);
        Object path = new Object();
        route.accepted(at(path, 1, 0, 2, 4.2), 0);
        assertEquals(NavigationRoute.Follow.CONTINUE, route.follow(at(path, 1, 0, 2, 4.18), 1));
        assertEquals(0, route.lastProgress());
        route.follow(at(path, 1, 0, 2, 4.16), 2);
        assertEquals(0, route.lastProgress());
        route.follow(at(path, 1, 0, 2, 4.14), 3);
        assertEquals(3, route.lastProgress());
        assertEquals(4.14, route.bestDistance());
        // Returning to the former best does not buy another interval.
        route.follow(at(path, 1, 0, 2, 4.2), 4);
        route.follow(at(path, 1, 0, 2, 4.14), 5);
        assertEquals(3, route.lastProgress());
    }

    @Test void obstacleDetourCanMoveAwayForMoreThanTwentyTicksWhileNodesAdvance() {
        NavigationRoute route = route(1);
        Object path = new Object();
        route.accepted(at(path, 1, 0, 6, 2), 0);
        for (int tick = 1; tick <= 50; tick++) {
            int node = tick / 12;
            // The destination can get farther throughout; the active route still progresses.
            assertEquals(NavigationRoute.Follow.CONTINUE,
                    route.follow(at(path, 1, node, 6, 2 + tick * 0.1), tick));
        }
        assertEquals(4, route.lastSample().node());
        assertEquals(48, route.lastProgress());
        assertEquals(NavigationRoute.Stage.FOLLOW_PATH, route.stage());
    }

    @Test void regressionGeometryChangeAndEvenEquivalentReplacementAbandon() {
        NavigationRoute route = route(4);
        Object first = new Object();
        route.accepted(at(first, 123, 2, 5, 3), 0);
        assertEquals(NavigationRoute.Follow.ABANDON, route.follow(at(first, 123, 1, 5, 2), 1));
        assertEquals("regressed-node", route.lastReason());
        route.select(START);
        route.chargedPathfinding();
        route.accepted(at(first, 123, 0, 5, 3), 2);
        assertEquals(NavigationRoute.Follow.ABANDON, route.follow(at(first, 124, 1, 5, 2), 3));
        assertEquals("changed-geometry", route.lastReason());
        route.select(START);
        route.chargedPathfinding();
        route.accepted(at(first, 123, 0, 5, 3), 4);
        assertEquals(NavigationRoute.Follow.ABANDON,
                route.follow(at(new Object(), 123, 0, 5, 2), 5));
        assertEquals("replaced-path", route.lastReason());
        assertEquals(3, route.navigationCalls());
        assertEquals(0, route.select(START).x());
        assertNull(route.select(START));
    }

    @Test void nullPartialRejectedFinishedAndAllFailedCandidatesStayFinite() {
        NavigationRoute route = route(8);
        String[] outcomes = {"null-path", "partial-path", "moveTo-rejected",
                "finished-outside-reach", "null-path", "partial-path",
                "moveTo-rejected", "finished-outside-reach"};
        route.abandon(outcomes[0]);
        for (int i = 1; i < outcomes.length; i++) {
            assertNotNull(route.select(START));
            route.chargedPathfinding();
            if (outcomes[i].equals("finished-outside-reach")) {
                Object path = new Object();
                route.accepted(at(path, 1, 3, 3, 0.1), i);
            }
            route.abandon(outcomes[i]);
        }
        assertNull(route.select(START));
        route.terminal("approaches-exhausted");
        assertEquals(8, route.attempts());
        assertEquals(8, route.navigationCalls());
        assertEquals(NavigationRoute.Stage.TERMINAL, route.stage());
        assertThrows(IllegalStateException.class, () -> route.select(START));
        assertTrue(route.history().size() <= 12);
    }

    @Test void duplicateTickAndRetryCannotResetElapsedOrWork() {
        NavigationRoute route = route(2);
        Object path = new Object();
        route.accepted(at(path, 1, 0, 2, 4.2), 100);
        assertEquals(NavigationRoute.Follow.CONTINUE, route.follow(at(path, 1, 0, 2, 4), 100));
        assertEquals(100, route.lastProgress());
        assertEquals(1, route.navigationCalls());
        assertEquals(NavigationRoute.Follow.ABANDON, route.follow(at(path, 1, 0, 2, 4.2), 120));
        route.select(START);
        route.chargedPathfinding();
        route.accepted(at(new Object(), 1, 0, 2, 4.2), 121);
        assertEquals(2, route.attempts());
        assertEquals(2, route.navigationCalls());
        assertEquals(121, route.attemptStart());
    }
}
