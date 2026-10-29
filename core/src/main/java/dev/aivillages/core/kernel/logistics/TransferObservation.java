package dev.aivillages.core.kernel.logistics;
import java.util.*;
/** Server-only native delta, LIVE_OBSERVED at most. Contains no effect key, receipt or save proof. */
public record TransferObservation(Status status,Reason reason,int quantity,Counts before,Counts after,String location) {
 public TransferObservation{Objects.requireNonNull(status);Objects.requireNonNull(reason);Objects.requireNonNull(location);if(quantity<0 || quantity>64 || location.length()>200 || status==Status.REFUSED && quantity!=0 || (status==Status.LIVE_WITHDRAWN || status==Status.LIVE_DEPOSITED) && (quantity<1 || before==null || after==null || reason!=Reason.NONE))throw new IllegalArgumentException("bounded native delta observation");}
 public enum Status { REFUSED, LIVE_WITHDRAWN, LIVE_DEPOSITED, UNCERTAIN }
 public enum Reason { NONE,SAME_ENDPOINT,UNAUTHORIZED,STALE_ENDPOINT,UNSUPPORTED_ITEM,UNSUPPORTED_PRIMITIVE,SELECTION_REQUIRED,SHORTAGE,CAPACITY,NO_DISJOINT_SLOT,INTERFERENCE,OUT_OF_REACH,UNKNOWN_CUSTODY,BATCH_LIMIT,WRONG_PHASE,UNCERTAIN_EFFECT }
 public record Counts(int source,int carrier,int destination){public Counts{if(source<0 || carrier<0 || destination<0 || source>8192 || carrier>8192 || destination>8192)throw new IllegalArgumentException("bounded participant counts");}public int total(){return source+carrier+destination;}}
}
