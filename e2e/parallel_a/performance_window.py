"""Clock agreement for the fixed native performance window; no sample or limit changes."""

def reached(snapshot, begin, ticks):
    # The mailbox marker and this field both use Minecraft game time. Observer
    # frame counters restart independently and published snapshots can lag.
    return snapshot['time'] >= begin['startMillis'] and snapshot['gameTick'] >= begin['startTick'] + ticks
