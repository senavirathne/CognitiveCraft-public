"""A cut already due at the fixed hot target must precede another admission."""
HOT_TARGET=48

def idle_before_cut(status):
    if not status or not status.get('ready'):return None
    jobs=status.get('jobs',{});leases=status.get('leases',{});hot=jobs.get('hot')
    if type(hot) is int and 0<=hot<HOT_TARGET and jobs.get('pending',True) is False and leases.get('pending',True) is False:return status
    return None
