package dev.aivillages.fabric;

import dev.aivillages.core.kernel.Blueprint;
import dev.aivillages.core.kernel.BlueprintValidator;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class FabricBlueprintRegistryTest {
    @BeforeAll static void registryBootstrap(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    @Test void snapshotContainsOnlySupportedActualBlocksItemsStatesAndPlacementMeaning() {
        var snapshot=FabricBlueprintRegistry.capture();
        assertEquals(Set.of("minecraft:oak_planks","minecraft:oak_slab","minecraft:oak_door","minecraft:torch"),snapshot.blocks().keySet());
        for(var definition:snapshot.blocks().values())assertEquals(definition.block(),definition.item());
        assertEquals(Set.of("north","east","south","west"),snapshot.blocks().get("minecraft:oak_door").values().get("facing"));
        assertEquals(Set.of("lower","upper"),snapshot.blocks().get("minecraft:oak_door").values().get("half"));
        assertEquals("minecraft-26.3",snapshot.target());
        assertEquals(snapshot,FabricBlueprintRegistry.capture());
        assertThrows(UnsupportedOperationException.class,()->snapshot.blocks().clear());
    }
    @Test void actualNativeRegistrySnapshotValidatesBillAndAllRotatedDoorStatesWithoutWorldHandles() {
        var palette=List.of(new Blueprint.State("minecraft:oak_planks",Map.of()),
                new Blueprint.State("minecraft:oak_slab",Map.of("type","bottom")),
                new Blueprint.State("minecraft:oak_door",Map.of("facing","north","half","lower","hinge","left")),
                new Blueprint.State("minecraft:oak_door",Map.of("facing","north","half","upper","hinge","left")),
                new Blueprint.State("minecraft:torch",Map.of()));
        var cells=List.of(new Blueprint.Cell(0,0,0,0),new Blueprint.Cell(0,0,1,0),new Blueprint.Cell(1,0,0,1),
                new Blueprint.Cell(0,1,0,2),new Blueprint.Cell(0,2,0,3),new Blueprint.Cell(0,1,1,4));
        var body=new Blueprint.Body(new Blueprint.Dimensions(2,3,2),new Blueprint.Constraints(true,true,1),palette,cells);
        var snapshot=FabricBlueprintRegistry.capture();
        var validated=BlueprintValidator.validate(body.canonical(),snapshot,BlueprintValidator.Limits.defaults(),()->false);
        assertTrue(validated.valid(),validated.reason());
        var bill=Map.of("minecraft:oak_planks",2L,"minecraft:oak_slab",1L,"minecraft:oak_door",1L,"minecraft:torch",1L);
        assertEquals(bill,validated.materials());
        for(int turns=0;turns<4;turns++){
            var result=BlueprintValidator.validate(BlueprintValidator.rotate(validated.body(),turns).canonical(),snapshot,BlueprintValidator.Limits.defaults(),()->false);
            assertTrue(result.valid(),result.reason());assertEquals(bill,result.materials());
        }
    }
    @Test void installedButUnsupportedNativeBlockDoesNotBecomeAnImplicitPlacementCapability() {
        var body=new Blueprint.Body(new Blueprint.Dimensions(1,1,1),new Blueprint.Constraints(true,true,0),
                List.of(new Blueprint.State("minecraft:command_block",Map.of())),List.of(new Blueprint.Cell(0,0,0,0)));
        var result=BlueprintValidator.validate(body.canonical(),FabricBlueprintRegistry.capture(),BlueprintValidator.Limits.defaults(),()->false);
        assertEquals(BlueprintValidator.Status.UNSUPPORTED_BLOCK,result.status());
    }
}
