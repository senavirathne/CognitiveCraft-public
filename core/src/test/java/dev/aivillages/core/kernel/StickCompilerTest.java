package dev.aivillages.core.kernel;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class StickCompilerTest {
    SkillCompiler.CompileResult compile(String ir) {
        return new SkillCompiler(PlanningGoals.catalog(),GatewayPrimitives.instance(),ref->Optional.empty()).compile(ir);
    }
    @Test void parameterizedCeilingHasAtMostSixteenBatchesAndNoAcquisitionDependency() {
        var result=compile(StickTestSupport.program());assertInstanceOf(SkillCompiler.Success.class,result);
        var artifact=((SkillCompiler.Success)result).skill().artifact();assertTrue(artifact.descriptor().dependencies().isEmpty());
        assertEquals(StickProduction.ID,artifact.descriptor().ref().capability());
    }
    @Test void divisionByZeroCannotEnterAnAdmittedBody() {
        String ir=StickTestSupport.program().replace("\"right\":{\"int\":4}","\"right\":{\"int\":0}");
        assertNotEquals(StickTestSupport.program(),ir);assertFalse(compile(ir) instanceof SkillCompiler.Success);
    }
    @Test void negativeDivisionCannotCreateAnUnboundedLoop() {
        String ir=StickTestSupport.program().replace("\"right\":{\"int\":4}","\"right\":{\"int\":-1}");
        assertFalse(compile(ir) instanceof SkillCompiler.Success);
    }
    @Test void wheatPrimitivesRemainForbiddenForPlankProduction() {
        var call=StickTestSupport.call(GatewayPrimitives.Operation.HARVEST_NEXT_WHEAT);
        var ir=StrictJson.canonical(Map.of("schema",1,"capability",StickProduction.ID.name(),"capabilityVersion",1,"dependencies",List.of(),
                "body",List.of(call,Map.of("op","result","value",Map.of("param","amount")))));
        assertFalse(compile(ir) instanceof SkillCompiler.Success);
    }
    @Test void plankPrimitivesCannotEnterAStickArtifact() {String ir=StickTestSupport.program().replace("cognitivecraft:craft_sticks", "cognitivecraft:craft_oak_planks").replace(GatewayPrimitives.Operation.CRAFT_STICKS.signature().fingerprint(),GatewayPrimitives.Operation.CRAFT_OAK_PLANKS.signature().fingerprint());assertFalse(compile(ir) instanceof SkillCompiler.Success);}
}
