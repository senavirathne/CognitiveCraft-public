package dev.aivillages.fabric.runtime.evidence;

import com.google.gson.*;
import com.mojang.serialization.*;
import dev.aivillages.core.kernel.runtime.evidence.NativeDeposit.*;
import java.lang.reflect.Proxy;
import java.util.*;
import org.junit.jupiter.api.Test;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import static org.junit.jupiter.api.Assertions.*;

/** Serializer interface contracts only; fake values/scopes never count as native save evidence. */
final class DepositParticipantStateTest {
    static final class Values {
        final JsonObject data=new JsonObject();
        final ValueInput input=(ValueInput)Proxy.newProxyInstance(ValueInput.class.getClassLoader(),new Class<?>[]{ValueInput.class},(proxy,method,args)->{
            String name=method.getName();String key=args==null || args.length==0?"":String.valueOf(args[0]);JsonElement value=data.get(key);
            if(name.equals("read") && args.length==2)return value==null?Optional.empty():parse((Codec<?>)args[1],value);
            if(name.equals("getString"))return isString(value)?Optional.of(value.getAsString()):Optional.empty();
            if(name.equals("getStringOr"))return isString(value)?value.getAsString():args[1];
            throw new UnsupportedOperationException("Unexpected ValueInput contract "+name);
        });
        final ValueOutput output=(ValueOutput)Proxy.newProxyInstance(ValueOutput.class.getClassLoader(),new Class<?>[]{ValueOutput.class},(proxy,method,args)->{
            if(method.getName().equals("putString")){data.addProperty((String)args[0],(String)args[1]);return null;}
            if(method.getName().equals("store") && args.length==3){data.add((String)args[0],encode((Codec<?>)args[1],args[2]));return null;}
            throw new UnsupportedOperationException("Unexpected ValueOutput contract "+method.getName());
        });
        static boolean isString(JsonElement value){return value!=null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();}
        static Optional<?> parse(Codec<?> codec,JsonElement value){return codec.parse(JsonOps.INSTANCE,value).result();}
        @SuppressWarnings({"rawtypes","unchecked"})static JsonElement encode(Codec codec,Object value){return (JsonElement)codec.encodeStart(JsonOps.INSTANCE,value).result().orElseThrow();}
    }
    static Marker prepared(){return new Marker("a".repeat(64),"b".repeat(64),MarkerPhase.PREPARED,"");}
    static Values saved(DepositParticipantState state){Values values=new Values();state.save(values.output);return values;}

    @Test void untouchedLegacyParticipantAddsNoMetadataAndClaimsNoSave() {
        var state=new DepositParticipantState();assertEquals(0,saved(state).data.size());assertFalse(state.active());assertFalse(state.restored());
    }
    @Test void exactStoredMetadataRestoresOnlyInsideTheNativeStorageCallScope() {
        var state=new DepositParticipantState();state.activate();state.mark(prepared(),state::mutation);assertEquals(0,state.revision());
        var values=saved(state);assertFalse(state.restored());var restored=new DepositParticipantState();
        try(var scope=DepositNativeLoad.enter(Kind.ACTOR)){restored.load(values.input,Kind.ACTOR);}
        assertTrue(restored.valid());assertTrue(restored.restored());assertEquals(state.incarnation(),restored.incarnation());assertEquals(state.marker(),restored.marker());
        assertEquals(values.data,saved(restored).data);
    }
    @Test void copiedMetadataAndCommandStyleNbtLoadsArePermanentlyQuarantined() {
        var state=new DepositParticipantState();state.activate();state.mark(prepared(),()->{});var original=saved(state);
        var copied=new DepositParticipantState();copied.load(original.input,Kind.CHEST);assertFalse(copied.valid());assertFalse(copied.restored());
        Values quarantine=saved(copied);assertEquals(original.data.get(DepositParticipantState.TAG),quarantine.data.get(DepositParticipantState.TAG+"_original"));
        var reloaded=new DepositParticipantState();try(var scope=DepositNativeLoad.enter(Kind.CHEST)){reloaded.load(quarantine.input,Kind.CHEST);}
        assertFalse(reloaded.valid());assertFalse(reloaded.restored());
    }
    @Test void equalCountMutationInvalidatesSaveProvenanceAndAdvancesTheRevision() {
        var state=new DepositParticipantState();state.activate();state.mark(prepared(),()->{});Values data=saved(state);
        var restored=new DepositParticipantState();try(var scope=DepositNativeLoad.enter(Kind.ACTOR)){restored.load(data.input,Kind.ACTOR);}
        assertTrue(restored.restored());long previous=restored.revision();restored.mutation();
        assertEquals(previous+1,restored.revision());assertFalse(restored.restored());
    }
    @Test void unknownNativeFieldsAreRetainedWithoutBecomingAValidMarker() {
        Values values=new Values();JsonObject future=new JsonObject();future.addProperty("schema",99);future.addProperty("unknown","preserved");values.data.add(DepositParticipantState.TAG,future);
        var state=new DepositParticipantState();try(var scope=DepositNativeLoad.enter(Kind.CHEST)){state.load(values.input,Kind.CHEST);}
        assertFalse(state.valid());assertEquals(values.data,saved(state).data);assertThrows(IllegalStateException.class,state::activate);
    }
    @Test void nativeLoadScopesCloseEvenWhenDeserializationThrows() {
        assertThrows(IllegalStateException.class,()->{try(var scope=DepositNativeLoad.enter(Kind.ACTOR)){assertTrue(DepositNativeLoad.saved(Kind.ACTOR));throw new IllegalStateException("fixture");}});
        assertFalse(DepositNativeLoad.saved(Kind.ACTOR));assertFalse(DepositNativeLoad.saved(Kind.CHEST));
    }
}
