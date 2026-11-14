"""A07 transport retry for an explicit zero-work busy cancellation response."""
import time

def cancel(h,player,job_id,timeout=30):
    deadline=time.monotonic()+timeout
    command='/aivillage kernel job-cancel '+job_id
    while True:
        remaining=deadline-time.monotonic()
        if remaining<=0:raise TimeoutError('Original cancellation acknowledgement deadline: '+job_id)
        answer=h.command(player,command,'cancellation=(?:true|false)',remaining)
        if 'cancellation=true' in answer:return answer
        if 'cancellation=false reason=BUDGET_EXHAUSTED' not in answer:
            raise AssertionError('Original cancellation refused: '+answer)
        # Jobs has published no cancellation for this refusal. Use the same
        # public request, original owner and original total feedback deadline.
        h.event('cancellation-publication-busy',{'job':job_id,'feedback':answer})
