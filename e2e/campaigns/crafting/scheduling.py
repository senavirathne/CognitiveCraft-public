"""Fair family ordering and control dependencies without new jobs or world resets."""
from __future__ import annotations

from collections import deque


def interleave(cases):
    """Visit every family early and always execute an included control first."""
    by_id = {case["id"]: case for case in cases}
    if len(by_id) != len(cases):
        raise ValueError("Duplicate scheduled case identity")
    groups = {}
    for family in sorted({case["family"] for case in cases}):
        ordered, seen, active = [], set(), set()
        def include(case):
            identity = case["id"]
            if identity in seen:
                return
            if identity in active:
                raise ValueError("Cyclic metamorphic control dependency")
            active.add(identity)
            control = by_id.get(case.get("metamorphicControlId"))
            if control:
                if control["family"] != family:
                    raise ValueError("Cross-family control dependency")
                include(control)
            active.remove(identity); seen.add(identity); ordered.append(case)
        pool = [case for case in cases if case["family"] == family]
        for case in sorted(pool, key=lambda value: bool(value.get("missingAdapter"))):
            include(case)
        groups[family] = deque(ordered)
    result = []
    while any(groups.values()):
        for queue in groups.values():
            if queue:
                result.append(queue.popleft())
    return result
