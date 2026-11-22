package dev.aivillages.core.kernel;

import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.Reason;

/** Immutable witnesses only. Native inventory and block state remain authoritative. */
public final class PlacementTransaction {
    private PlacementTransaction() { }
    public record HeldTable(int inputSlot,int placementSlot,int inputCount) {
        public HeldTable {
            if(inputSlot<0 || inputSlot>=8 || placementSlot<0 || placementSlot>=8 || inputSlot==placementSlot
                    || inputCount<1 || inputCount>64)throw new IllegalArgumentException("Exact held table slots");
        }
        public String identity(ActorRef actor) {
            return "held-table:"+actor.entityId()+":"+inputSlot+":"+placementSlot;
        }
        public static boolean matches(String identity,ActorRef actor) {
            String prefix="held-table:"+actor.entityId()+":";
            if(!identity.startsWith(prefix) || identity.length()!=prefix.length()+3)return false;
            int offset=prefix.length();
            char source=identity.charAt(offset),destination=identity.charAt(offset+2);
            return identity.charAt(offset+1)==':' && source>='0' && source<='7'
                    && destination>='0' && destination<='7' && source!=destination;
        }
    }
    /** PRESENT describes known structural witnesses; pending fences a live entity obstruction. */
    public record Preflight(ObservationStatus status,long availableItems,int sourceSlots,String sourceIdentity,
                            String sourceFingerprint,String custodyFingerprint,String siteFingerprint,
                            FacilityRef target,ContainerRef source,Reason reason,boolean pending,HeldTable heldTable) {
        public Preflight(ObservationStatus status,long availableItems,int sourceSlots,String sourceIdentity,
                         String sourceFingerprint,String custodyFingerprint,String siteFingerprint,
                         FacilityRef target,ContainerRef source,Reason reason,boolean pending) {
            this(status,availableItems,sourceSlots,sourceIdentity,sourceFingerprint,custodyFingerprint,siteFingerprint,target,source,reason,pending,null);
        }
        public Preflight(ObservationStatus status,long availableItems,int sourceSlots,String sourceIdentity,
                         String sourceFingerprint,String custodyFingerprint,String siteFingerprint,
                         FacilityRef target,ContainerRef source,Reason reason) {
            this(status,availableItems,sourceSlots,sourceIdentity,sourceFingerprint,custodyFingerprint,siteFingerprint,target,source,reason,false);
        }
        public Preflight {
            Objects.requireNonNull(status);
            if(availableItems<0 || sourceSlots<0 || sourceSlots>128 || !RecipeTransaction.text(sourceIdentity)
                    || !RecipeTransaction.text(sourceFingerprint) || !RecipeTransaction.text(custodyFingerprint)
                    || !RecipeTransaction.text(siteFingerprint))throw new IllegalArgumentException("Bounded placement witness");
            if(pending && (status!=ObservationStatus.PRESENT || reason!=null))throw new IllegalArgumentException("Known temporary site obstruction");
            if(heldTable!=null && (status!=ObservationStatus.PRESENT || availableItems!=1 || reason!=null))
                throw new IllegalArgumentException("A held table requires a known placement witness");
            if(status==ObservationStatus.PRESENT && reason==null && (target==null || source==null || !source.dimension().equals(target.dimension()) || sourceIdentity.isBlank()
                    || !RecipeTransaction.hash(sourceFingerprint) || !RecipeTransaction.hash(custodyFingerprint)
                    || !RecipeTransaction.hash(siteFingerprint)))throw new IllegalArgumentException("Exact placement witness");
        }
        public static Preflight blocked(Reason reason){return new Preflight(ObservationStatus.UNKNOWN,0,0,"","","","",null,null,Objects.requireNonNull(reason));}
    }
    public record Mutation(int withdrawn,int consumed,boolean placed,String custodyBefore,String custodyAfter,
                           String sourceAfter,String facilityIdentity,Reason reason) {
        public Mutation {
            if(withdrawn<0 || withdrawn>1 || consumed<0 || consumed>1 || !RecipeTransaction.text(custodyBefore)
                    || !RecipeTransaction.text(custodyAfter) || !RecipeTransaction.text(sourceAfter)
                    || !RecipeTransaction.text(facilityIdentity))throw new IllegalArgumentException("Bounded placement mutation");
            if((withdrawn>0 || consumed>0 || placed) && (!RecipeTransaction.hash(custodyBefore)
                    || !RecipeTransaction.hash(custodyAfter)))throw new IllegalArgumentException("Actual custody mutation");
        }
        public boolean committed(){return withdrawn>0 || consumed>0 || placed;}
        /** A physical wait has no mutation or receipt; the same bounded action polls again. */
        public boolean pending(){return !committed() && reason==null && custodyBefore.isEmpty() && custodyAfter.isEmpty() && sourceAfter.isEmpty() && facilityIdentity.isEmpty();}
        public static Mutation waiting(){return new Mutation(0,0,false,"","","","",null);}
        public static Mutation blocked(Reason reason){return new Mutation(0,0,false,"","","","",Objects.requireNonNull(reason));}
    }
    public record Evidence(FacilityRef target,String sourceIdentity,String siteFingerprint,
                           String custodyBefore,String custodyAfter,String blockState,String facilityIdentity) {
        public Evidence {
            Objects.requireNonNull(target);
            if(sourceIdentity==null || sourceIdentity.isBlank() || !RecipeTransaction.text(sourceIdentity)
                    || !RecipeTransaction.hash(siteFingerprint) || !RecipeTransaction.hash(custodyBefore)
                    || !RecipeTransaction.hash(custodyAfter) || !RecipeTransaction.text(facilityIdentity)
                    || !Set.of("minecraft:air","minecraft:crafting_table").contains(blockState)
                    || blockState.equals("minecraft:air") && !facilityIdentity.isEmpty()
                    || blockState.equals("minecraft:crafting_table") && facilityIdentity.isBlank())
                throw new IllegalArgumentException("Exact table placement evidence");
        }
    }
}
