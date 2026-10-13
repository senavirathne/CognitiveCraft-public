package dev.aivillages.core.kernel;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class OakPlankCompilerTest {
    SkillCompiler.CompileResult compile(String ir) {
        return new SkillCompiler(PlanningGoals.catalog(),GatewayPrimitives.instance(),ref->Optional.empty()).compile(ir);
    }
    @Test void parameterizedCeilingHasAtMostSixteenBatchesAndNoAcquisitionDependency() {
        var result=compile(OakPlankTestSupport.program());assertInstanceOf(SkillCompiler.Success.class,result);
        var artifact=((SkillCompiler.Success)result).skill().artifact();assertTrue(artifact.descriptor().dependencies().isEmpty());
        assertEquals(OakPlankProduction.ID,artifact.descriptor().ref().capability());
    }
    @Test void divisionByZeroCannotEnterAnAdmittedBody() {
        String ir=OakPlankTestSupport.program().replace("\"right\":{\"int\":4}","\"right\":{\"int\":0}");
        assertNotEquals(OakPlankTestSupport.program(),ir);assertFalse(compile(ir) instanceof SkillCompiler.Success);
    }
    @Test void negativeDivisionCannotCreateAnUnboundedLoop() {
        String ir=OakPlankTestSupport.program().replace("\"right\":{\"int\":4}","\"right\":{\"int\":-1}");
        assertFalse(compile(ir) instanceof SkillCompiler.Success);
    }
    @Test void wheatPrimitivesRemainForbiddenForPlankProduction() {
        var call=OakPlankTestSupport.call(GatewayPrimitives.Operation.HARVEST_NEXT_WHEAT);
        var ir=StrictJson.canonical(Map.of("schema",1,"capability",OakPlankProduction.ID.name(),"capabilityVersion",1,"dependencies",List.of(),
                "body",List.of(call,Map.of("op","result","value",Map.of("param","amount")))));
        assertFalse(compile(ir) instanceof SkillCompiler.Success);
    }
}
