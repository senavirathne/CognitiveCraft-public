package dev.aivillages.core.kernel;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class AxeCompilerTest {
    SkillCompiler.CompileResult compile(String ir) {
        return new SkillCompiler(PlanningGoals.catalog(),GatewayPrimitives.instance(),ref->Optional.empty()).compile(ir);
    }
    @Test void parameterizedCeilingHasAtMostSixteenBatchesAndNoAcquisitionDependency() {
        var result=compile(AxeTestSupport.program());assertInstanceOf(SkillCompiler.Success.class,result);
        var artifact=((SkillCompiler.Success)result).skill().artifact();assertTrue(artifact.descriptor().dependencies().isEmpty());
        assertEquals(WoodenAxeProduction.ID,artifact.descriptor().ref().capability());
    }
    @Test void divisionByZeroCannotEnterAnAdmittedBody() {
        String ir=AxeTestSupport.program().replace("\"right\":{\"int\":1}","\"right\":{\"int\":0}");
        assertNotEquals(AxeTestSupport.program(),ir);assertFalse(compile(ir) instanceof SkillCompiler.Success);
    }
    @Test void negativeDivisionCannotCreateAnUnboundedLoop() {
        String ir=AxeTestSupport.program().replace("\"right\":{\"int\":1}","\"right\":{\"int\":-1}");
        assertFalse(compile(ir) instanceof SkillCompiler.Success);
    }
    @Test void wheatPrimitivesRemainForbiddenForPlankProduction() {
        var call=AxeTestSupport.call(GatewayPrimitives.Operation.HARVEST_NEXT_WHEAT);
        var ir=StrictJson.canonical(Map.of("schema",1,"capability",WoodenAxeProduction.ID.name(),"capabilityVersion",1,"dependencies",List.of(),
                "body",List.of(call,Map.of("op","result","value",Map.of("param","amount")))));
        assertFalse(compile(ir) instanceof SkillCompiler.Success);
    }
    @Test void plankPrimitivesCannotEnterAStickArtifact() {String ir=AxeTestSupport.program().replace("cognitivecraft:craft_wooden_axe", "cognitivecraft:craft_oak_planks").replace(GatewayPrimitives.Operation.CRAFT_WOODEN_AXE.signature().fingerprint(),GatewayPrimitives.Operation.CRAFT_OAK_PLANKS.signature().fingerprint());assertFalse(compile(ir) instanceof SkillCompiler.Success);}
}
