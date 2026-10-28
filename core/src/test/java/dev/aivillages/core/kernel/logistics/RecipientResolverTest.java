package dev.aivillages.core.kernel.logistics;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.aivillages.core.kernel.storage.StorageResolutionTestSupport.*;
import static dev.aivillages.core.kernel.logistics.RecipientResolver.*;

class RecipientResolverTest {
    private static Selector named(String label){return new Selector(Kind.NAMED,label,null);}
    private static Citizen change(Citizen c,String label,UUID entity,long generation,boolean visible,boolean controlled,boolean loaded,dev.aivillages.core.kernel.storage.StorageAccessPolicy.Scope scope){return new Citizen(scope,c.epoch(),c.id(),entity,generation,c.policyRevision(),label,c.dimension(),c.inventoryRevision(),c.tick(),visible,controlled,loaded,c.permitted());}
    @Test void namesakesClarifyAndCannotChooseRandomCitizen(){var e=new Env();e.citizen(BOB,"ADA");var result=e.recipient(named("Ada"),false);assertEquals(Status.CLARIFY,result.status());assertNull(result.endpoint());}
    @Test void renameAfterAcceptanceNeverReroutesBoundDelivery(){var e=new Env();var bound=e.recipient(named("Ada"),false).endpoint();var a=e.citizens.get(ADA);e.citizens.put(ADA,change(a,"Renamed",a.entity(),1,true,true,true,SCOPE));e.citizen(BOB,"Ada");assertEquals(ADA,bound.fresh(SCOPE).id());assertEquals(BOB,e.recipient(named("Ada"),false).endpoint().citizenId());}
    @Test void unloadedForeignAndPrivateAreUniformlyUnavailable(){for(int i=0;i<4;i++){var e=new Env();var a=e.citizens.get(ADA);if(i==3)e.citizens.remove(ADA);else e.citizens.put(ADA,change(a,"Ada",a.entity(),1,i!=2,true,i!=0,i==1?new dev.aivillages.core.kernel.storage.StorageAccessPolicy.Scope(id(900),EPOCH,PRINCIPAL,ADA):SCOPE));var result=e.recipient(named("Ada"),false);assertEquals(Status.UNAVAILABLE,result.status());assertNull(result.endpoint());}}
    @Test void replacementGenerationAndSwappedEntityNeverRerouteBoundIdentity(){for(boolean generation:new boolean[]{false,true}){var e=new Env();var bound=e.recipient(named("Ada"),false).endpoint();var a=e.citizens.get(ADA);e.citizens.put(ADA,change(a,"Ada",generation?a.entity():id(999),generation?2:1,true,true,true,SCOPE));assertFalse(bound.current(SCOPE));assertEquals(ADA,bound.citizenId());}}
    @Test void ownerRoleRequiresControlWhileVisibleRecipientRemainsUsable(){var e=new Env();var a=e.citizens.get(ADA);e.citizens.put(ADA,change(a,"Ada",a.entity(),1,true,false,true,SCOPE));assertEquals(Status.RESOLVED,e.recipient(named("Ada"),false).status());assertEquals(Status.UNAVAILABLE,e.recipient(named("Ada"),true).status());}
    @Test void addressedAndExplicitIdStillRequireCurrentVisibility(){var e=new Env();assertEquals(ADA,e.recipient(new Selector(Kind.ADDRESSED,null,null),true).endpoint().citizenId());assertEquals(BOB,e.recipient(new Selector(Kind.EXPLICIT_ID,null,BOB),true).endpoint().citizenId());e.citizens.remove(BOB);assertEquals(Status.UNAVAILABLE,e.recipient(new Selector(Kind.EXPLICIT_ID,null,BOB),true).status());}
    @Test void namesUseExactUnicodeFoldAndUnknownFieldsAreRejected(){var e=new Env();e.citizen(ADA,"Straße");assertEquals(ADA,e.recipient(named("STRASSE"),false).endpoint().citizenId());assertEquals(Status.UNAVAILABLE,e.recipient(named("Stras"),false).status());assertThrows(IllegalArgumentException.class,()->Selector.from(Map.of("kind","NAMED","label","Ada","permission","ALLOW")));}
    @Test void boundRecipientRechecksCallerLoadAndRevocation(){var e=new Env();var b=e.recipient(named("Ada"),false).endpoint();assertFalse(b.current(new dev.aivillages.core.kernel.storage.StorageAccessPolicy.Scope(WORLD,EPOCH,id(999),ADA)));var a=e.citizens.get(ADA);e.citizens.put(ADA,change(a,"Ada",a.entity(),1,true,true,false,SCOPE));assertFalse(b.current(SCOPE));}
}
