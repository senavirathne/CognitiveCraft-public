"""Route policy fixtures only; actual client movement remains an Actions E2E gate."""
import math
import unittest

from navigation import MAX_CELLS, in_observed_area, walk_route, walkable_block


def arena(width=12, depth=10):
    return {f"{x},201,{z}": "Block{minecraft:air}" for x in range(width) for z in range(depth)}


def route(blocks, position, target, reach=2.7):
    # These policy fixtures explicitly observe solid ground and clear headroom.
    return walk_route(blocks, position, target, reach,
                      standable={key: True for key in blocks})


class ClientNavigationTests(unittest.TestCase):
    def test_observed_fence_side_regression_escapes_outward_then_routes_around_pen(self):
        blocks = arena()
        for x in range(3, 6):
            for z in range(3, 6):
                if x in (3, 5) or z in (3, 5):
                    blocks[f"{x},201,{z}"] = "Block{minecraft:oak_fence}"
        path = route(blocks, (3.075, 201, 4.075), (9, 201, 7))
        self.assertIsNotNone(path)
        self.assertEqual((2.5, 4.5), path[0])
        self.assertTrue(all(walkable_block(blocks[f"{math.floor(x)},201,{math.floor(z)}"])
                            for x, z in path))
        self.assertLessEqual(math.hypot(9.5 - path[-1][0], 7.5 - path[-1][1]), 2.7)

    def test_reachable_short_interaction_range_does_not_require_entering_a_closed_pen(self):
        blocks = arena()
        for x in range(3, 6):
            for z in range(3, 6):
                if x in (3, 5) or z in (3, 5):
                    blocks[f"{x},201,{z}"] = "Block{minecraft:oak_fence}"
        path = route(blocks, (1.5, 201, 4.5), (4, 201, 4), 2.4)
        self.assertIsNotNone(path)
        self.assertLessEqual(math.hypot(4.5 - path[-1][0], 4.5 - path[-1][1]), 2.4)

    def test_existing_long_distance_unload_walk_is_outside_fixed_fixture_routing(self):
        blocks = arena()
        self.assertTrue(in_observed_area(blocks, (3.075, 201, 4.075)))
        self.assertFalse(in_observed_area(blocks, (60, 201, 6)))
        del blocks["3,201,4"]
        self.assertTrue(in_observed_area(blocks, (3, 201, 4)))
        self.assertFalse(walkable_block(blocks.get("3,201,4")))

    def test_closed_gate_blocks_route_but_observed_open_gate_is_traversable(self):
        blocks = arena(10, 9)
        for z in range(9):
            blocks[f"3,201,{z}"] = "Block{minecraft:oak_fence}"
        blocks["3,201,4"] = "Block{minecraft:oak_fence_gate}[open=false]"
        self.assertIsNone(route(blocks, (1.5, 201, 4.5), (8, 201, 4), .6))
        blocks["3,201,4"] = "Block{minecraft:oak_fence_gate}[open=true]"
        path = route(blocks, (1.5, 201, 4.5), (8, 201, 4), .6)
        self.assertIn((3.5, 4.5), path)

    def test_unobserved_gap_cannot_be_used_to_reach_the_target(self):
        blocks = {"0,201,0": "Block{minecraft:air}", "2,201,0": "Block{minecraft:air}"}
        self.assertIsNone(route(blocks, (.5, 201, .5), (2, 201, 0), .6))
        self.assertIsNone(route(blocks, (10.5, 201, .5), (2, 201, 0)))

    def test_route_stops_in_reach_without_entering_a_container(self):
        blocks = arena()
        blocks["9,201,7"] = "Block{minecraft:chest}[facing=west]"
        path = route(blocks, (1.5, 201, 1.5), (9, 201, 7))
        self.assertNotIn((9.5, 7.5), path)
        self.assertLessEqual(math.hypot(9.5 - path[-1][0], 7.5 - path[-1][1]), 2.7)
        previous = (1.5, 1.5)
        for point in path:
            self.assertEqual(1, abs(point[0] - previous[0]) + abs(point[1] - previous[1]))
            previous = point

    def test_changed_observed_obstacle_requires_a_different_route(self):
        blocks = arena()
        first = route(blocks, (1.5, 201, 1.5), (9, 201, 7), .6)
        x, z = first[0]
        blocks[f"{math.floor(x)},201,{math.floor(z)}"] = "Block{minecraft:stone}"
        second = route(blocks, (1.5, 201, 1.5), (9, 201, 7), .6)
        self.assertNotEqual(first, second)
        self.assertNotIn((x, z), second)

    def test_oversized_grid_and_invalid_reach_are_rejected(self):
        with self.assertRaises(ValueError):
            route(arena(MAX_CELLS + 1, 1), (.5, 201, .5), (4, 201, 0))
        with self.assertRaises(ValueError):
            walk_route(arena(), (.5, 201, .5), (4, 201, 0),
                       standable={key: True for key in arena(MAX_CELLS + 1, 1)})
        for reach in (0, -1, float("nan")):
            with self.assertRaises(ValueError):
                route(arena(), (.5, 201, .5), (4, 201, 0), reach)

    def test_observed_irrigation_holes_are_not_routes_despite_air_at_foot_level(self):
        blocks = arena(12, 14)
        support = {key: True for key in blocks}
        for x, z in ((8, 7), (8, 11)):
            support[f"{x},201,{z}"] = False
        path = walk_route(blocks, (8.5, 201, 12.5), (9, 201, 7), standable=support)
        self.assertIsNotNone(path)
        self.assertNotIn((8.5, 11.5), path)
        self.assertNotIn((8.5, 7.5), path)
        self.assertTrue(all(support[f"{math.floor(x)},201,{math.floor(z)}"] for x, z in path))

    def test_missing_support_and_blocked_headroom_do_not_create_a_corridor(self):
        blocks = arena(10, 9)
        support = {key: True for key in blocks}
        for z in range(9):
            if z % 2:
                support[f"3,201,{z}"] = False  # observed blocked headroom
            else:
                del support[f"3,201,{z}"]  # support was not observed
        self.assertIsNone(walk_route(blocks, (1.5, 201, 4.5), (8, 201, 4), .6,
                                     standable=support))

    def test_unknown_block_and_closed_door_are_not_assumed_passable(self):
        for state in (None, "unknown", "Block{minecraft:stone}", "Block{minecraft:oak_door}[open=false]"):
            self.assertFalse(walkable_block(state))


if __name__ == "__main__":
    unittest.main()
