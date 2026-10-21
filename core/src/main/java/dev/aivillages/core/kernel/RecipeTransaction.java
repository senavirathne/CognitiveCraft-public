package dev.aivillages.core.kernel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.Outcomes.Reason;

/** Bounded installed-recipe and physical-custody values; never Minecraft objects or inventory authority. */
public final class RecipeTransaction {
    private RecipeTransaction() { }
    public record Snapshot(String key,String fingerprint,long generation,int inputs,int outputs,int remainders) {
        public Snapshot {
            if(!hash(fingerprint) || generation<0
                    || inputs!=RecipeProduction.recipe(key).totalInputs() || outputs!=RecipeProduction.recipe(key).outputPerBatch() || remainders!=0)throw new IllegalArgumentException("Static oak recipe envelope");
        }
    }
    public record Preflight(ObservationStatus status,Snapshot recipe,long availableInputs,long outputCapacity,
                            String sourceIdentity,String destinationIdentity,String sourceFingerprint,
                            String custodyFingerprint,int sourceSlots,int destinationSlots,Reason reason,
                            long availableSecondary,FacilityRef facility,String facilityIdentity) {
        public Preflight(ObservationStatus status,Snapshot recipe,long availableInputs,long outputCapacity,
                         String sourceIdentity,String destinationIdentity,String sourceFingerprint,String custodyFingerprint,
                         int sourceSlots,int destinationSlots,Reason reason) {
            this(status,recipe,availableInputs,outputCapacity,sourceIdentity,destinationIdentity,sourceFingerprint,
                    custodyFingerprint,sourceSlots,destinationSlots,reason,0,null,"");
        }
        public Preflight {
            Objects.requireNonNull(status);
            if(availableSecondary<0 || !text(facilityIdentity) || availableInputs<0 || outputCapacity<0 || sourceSlots<0 || sourceSlots>128 || destinationSlots<0
                    || destinationSlots>128 || !text(sourceIdentity) || !text(destinationIdentity)
                    || !text(sourceFingerprint) || !text(custodyFingerprint))throw new IllegalArgumentException("Bounded recipe preflight");
            if(status==ObservationStatus.PRESENT && reason==null && recipe==null)
                throw new IllegalArgumentException("Installed recipe required");
        }
        public static Preflight unsupported() { return new Preflight(ObservationStatus.UNKNOWN,null,0,0,"","","","",0,0,Reason.UNSUPPORTED_PRIMITIVE); }
    }
    public record Mutation(int consumed,int produced,int moved,int remainders,String custodyBefore,
                           String custodyAfter,String sourceAfter,Reason reason) {
        public Mutation {
            if(consumed<0 || consumed>5 || produced<0 || produced>4 || moved<0 || moved>5
                    || remainders<0 || remainders>4 || !text(custodyBefore) || !text(custodyAfter)
                    || !text(sourceAfter))throw new IllegalArgumentException("Bounded transaction result");
        }
        public boolean committed() {return consumed>0 || produced>0 || moved>0;}
        /** Only unchanged physical custody may wait within the current action's budgets. */
        public boolean pending() {return !committed() && remainders==0 && reason==null && custodyBefore.isEmpty() && custodyAfter.isEmpty() && sourceAfter.isEmpty();}
        public static Mutation waiting() {return new Mutation(0,0,0,0,"","","",null);}
        public static Mutation blocked(Reason reason) {return new Mutation(0,0,0,0,"","","",Objects.requireNonNull(reason));}
    }
    /** Typed transformation payload. Acquisition receipts keep their original schema and drop lineage. */
    public record Transformation(Snapshot recipe,int consumedInputs,int producedOutputs,int remainders,
                                 int retainedOutputs,String custodyBefore,String custodyAfter,
                                 String sourceIdentity,String destinationIdentity,FacilityRef facility,String facilityIdentity) {
        public Transformation(Snapshot recipe,int consumedInputs,int producedOutputs,int remainders,int retainedOutputs,
                              String custodyBefore,String custodyAfter,String sourceIdentity,String destinationIdentity) {
            this(recipe,consumedInputs,producedOutputs,remainders,retainedOutputs,custodyBefore,custodyAfter,sourceIdentity,destinationIdentity,null,"");
        }
        public Transformation {
            Objects.requireNonNull(recipe);
            if(RecipeProduction.recipe(recipe.key()).axe() ? facility==null || facilityIdentity==null || facilityIdentity.isBlank()
                    : facility!=null || !"".equals(facilityIdentity))throw new IllegalArgumentException("Transformation facility binding");
            if(!text(facilityIdentity))throw new IllegalArgumentException("Facility identity bound");
            if(consumedInputs<0 || consumedInputs>recipe.inputs() || producedOutputs<0 || producedOutputs>4
                    || remainders!=0 || retainedOutputs<0 || retainedOutputs>4
                    || !hash(custodyBefore) || !hash(custodyAfter)
                    || sourceIdentity==null || sourceIdentity.isBlank() || !text(sourceIdentity)
                    || destinationIdentity==null || destinationIdentity.isBlank() || !text(destinationIdentity))
                throw new IllegalArgumentException("Transformation evidence");
        }
    }
    static boolean text(String value) {return value!=null && value.length()<=128;}
    public static boolean hash(String value) {return value!=null && value.matches("[a-f0-9]{64}");}
    public static String fingerprint(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible) {throw new AssertionError(impossible);}
    }
}
