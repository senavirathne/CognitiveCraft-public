"""The original I00 endurance denominator and unchanged A02 limits."""

COUNT = 1000
RESTART_AFTER = tuple(range(100, COUNT, 100))
TERMINAL = frozenset({"SUCCEEDED", "FAILED", "CANCELLED"})
LOG, PLANK = "minecraft:birch_log", "minecraft:birch_planks"
METHOD = "cognitivecraft:produce_birch_planks"
SOURCE = (326, 208, 66)
WAREHOUSE = ((329, 208, 66), (331, 208, 66), (334, 208, 66))
TEST_TARGETS = ((338, 208, 72), (339, 208, 76))
SHARED = (330, 208, 74)
SHARED_TARGETS = ((325, 208, 74), (335, 208, 74))
LIMITS = {"jobsActive": 32, "jobsHot": 64, "leasesActive": 64, "leasesHot": 128,
          "archiveBytes": 8 * 1024 * 1024, "archiveSegments": 128,
          "tickP95Ms": 20, "tickP99Ms": 40, "captureP99Ms": 2,
          "ackP95Ms": 250, "ackP99Ms": 1000, "ackMaxMs": 30_000}


def workload():
    results = []
    for index in range(1, COUNT + 1):
        role = {2: "admitted-failure", 3: "settled-cancellation", 4: "partial-custody",
                5: "contention", 6: "contention"}.get(index, "delivery")
        destination = SHARED_TARGETS[index - 5] if role == "contention" else {
            "admitted-failure": TEST_TARGETS[0], "partial-custody": TEST_TARGETS[1],
        }.get(role, WAREHOUSE[(index - 1) // 350])
        results.append({"index": index, "role": role, "player": "PlayerB" if index == 6 else "PlayerA",
                        "demand": 3 if index % 100 == 1 else 4,
                        "source": list(SHARED if role == "contention" else SOURCE), "destination": list(destination)})
    return results
