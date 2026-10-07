package dev.aivillages.fabric;

import dev.aivillages.core.kernel.*;
import dev.aivillages.core.kernel.Contracts.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static net.minecraft.commands.Commands.literal;

/** Injected interpretation and test-only acquired knowledge; all binding and physical owners are production. */
public final class WorldReferenceGameTests {
    @GameTest(maxTicks = 3000, padding = 32)
    public void registeredNamingAndChangedNearestBindingsUseRealPhysicalExecution(GameTestHelper h) {
        Driver d = new Driver(h);
        h.failIfEver(() -> { if (d.fatal != null) h.fail(d.fatal); if (d.fixture.fatal != null) h.fail(d.fixture.fatal); });
        h.succeedWhen(() -> { d.step(); h.assertTrue(d.phase == 9,"Waiting for deterministic nearest fixture phase="+d.phase); });
    }
    private static final class Driver {
        static final BlockPos OFFSET = new BlockPos(8,0,8);
        static final String[] NAMES = {"Ada","Bea","Cy","Dee","Emi"};
        static final String[] PHRASES = {"I name you Ada","your name is Bea","i give you the name Cy",
                "name the villager Dee","give name to the villager as Emi"};
        final GameTestHelper h; final BlockPos origin; final Path world;
        final BootstrapGameTests.Run fixture;
        final List<Villager> extras = new ArrayList<>();
        KernelSession session; ServerPlayer owner, foreign; Villager first, selected, unclaimed, unavailable;
        ActorRef actor, namingTarget, named, foreignActor, unavailableActor, distant, backup;
        ArtifactRef admitted; TrustedContext ownerContext; CompletableFuture<Void> closing;
        UUID ticket, run; BlockPos start; Cuboid expectedSource; ContainerRef expectedDestination;
        int phase, setup, nameIndex, location, fakeCalls; String fatal;
        Driver(GameTestHelper h) {
            this.h=h; origin=h.absolutePos(OFFSET);
            world=h.getLevel().getServer().getWorldPath(LevelResource.ROOT).resolve("cognitivecraft-reference-fixture");
            // Disposable setup loads a finite halo before any task; discovery itself never requests a chunk.
            for (int x=-1;x<=3;x++) for (int z=-1;z<=1;z++)
                BootstrapGameTests.forceFixtureChunks(h,origin.offset(x*16,0,z*16));
            for (int x=0;x<=34;x++) for (int z=0;z<=12;z++) h.setBlock(OFFSET.offset(x,0,z),Blocks.STONE);
            for (int x=1;x<=4;x++) for (int z=2;z<=5;z++)
                if ((x==1||x==4||z==2||z==5)&&!(x==4&&z==4))
                    h.setBlock(OFFSET.offset(x,1,z),Blocks.STONE);
            patch(2,3,3,4,7); h.setBlock(OFFSET.offset(4,1,4),Blocks.CHEST);
            first=spawn(3,3); extras.remove(first); first.setNoAi(false);
            fixture=new BootstrapGameTests.Run(h,first,null,world,origin,null,true,true);
        }
        Villager spawn(int x,int z) {
            Villager v=h.spawnWithNoFreeWill(EntityTypes.VILLAGER,OFFSET.getX()+x,1,OFFSET.getZ()+z);
            v.setNoAi(true); v.setPersistenceRequired(); extras.add(v); return v;
        }
        void patch(int x1,int x2,int z1,int z2,int age) {
            for (int x=x1;x<=x2;x++) for (int z=z1;z<=z2;z++) {
                h.setBlock(OFFSET.offset(x,0,z),Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE,7));
                h.setBlock(OFFSET.offset(x,1,z),Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,age));
            }
        }
        void step() {
            if (phase==9) return;
            java.util.concurrent.locks.LockSupport.parkNanos(phase<=4||phase==8 ? 50_000_000 : 5_000_000);
            if (session!=null && phase<8) session.tick();
            switch (phase) {
                case 0 -> {
                    fixture.advance(); h.assertTrue(fixture.stage==9,"Acquiring test-only admitted movement fixture");
                    require(fixture.fakeCalls==1,"Unexpected fixture generation calls");
                    actor=fixture.actor; ownerContext=fixture.owner; admitted=fixture.admitted;
                    closing=fixture.closeAcceptanceFixture(); phase=1;
                }
                case 1 -> {
                    await(closing); session=new KernelSession(h.getLevel().getServer(),world,this::interpret);
                    owner=h.makeMockServerPlayerInLevel(); foreign=h.makeMockServerPlayerInLevel();
                    owner.setUUID(ownerContext.principal().id()); foreign.setUUID(UUID.randomUUID());
                    var root=literal("aivillage"); KernelCommands.attach(root,()->session,v->ticket=v.id());
                    h.getLevel().getServer().getCommands().getDispatcher().register(root); phase=2;
                }
                case 2 -> {
                    ready();
                    switch (setup++) {
                        case 0 -> { var v=spawn(1,3); beside(owner,v); named=session.enroll(owner,v); }
                        case 1 -> require(session.name(owner,named.citizenId(),"Known").accepted(),"Named distractor setup rejected");
                        case 2 -> { var v=spawn(0,3); beside(foreign,v); foreignActor=session.enroll(foreign,v); }
                        case 3 -> { unavailable=spawn(2,2); beside(owner,unavailable); unavailableActor=session.enroll(owner,unavailable); }
                        case 4 -> { session.entityUnloaded(unavailable); unavailable.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK); }
                        case 5 -> { var v=spawn(25,3); beside(owner,v); distant=session.enroll(owner,v); }
                        case 6 -> { var v=spawn(12,3); beside(owner,v); backup=session.enroll(owner,v); }
                        case 7 -> {
                            unclaimed=spawn(0,2); namingTarget=actor; selected=first; callerAnchor();
                            session.inference(true); ask(PHRASES[0]); phase=3;
                        }
                        default -> throw new IllegalStateException("Setup "+setup);
                    }
                }
                case 3 -> {
                    var view=language(LanguageRequests.Phase.NAMED); ready();
                    var citizen=session.citizen(owner,namingTarget.citizenId());
                    require(NAMES[nameIndex].equals(citizen.displayName())&&citizen.actor().equals(namingTarget)
                            &&citizen.owner().equals(ownerContext),"Naming mismatch index="+nameIndex+" expectedTarget="+namingTarget+" current="+citizen+" expectedOwner="+ownerContext+" view="+view);
                    var addressed=session.address(owner,NAMES[nameIndex]);
                    require(addressed.status()==CitizenRegistry.AddressStatus.FOUND
                            &&addressed.candidates().getFirst().actor().equals(namingTarget),"Published name not addressable");
                    excludedUnchanged();
                    if (++nameIndex<NAMES.length) {
                        selected=spawn(3,3); namingTarget=session.enroll(owner,selected); phase=4;
                    } else phase=5;
                }
                case 4 -> {
                    ready();
                    h.assertTrue(session.citizen(owner,namingTarget.citizenId()).availability()==CitizenRegistry.Availability.LOADED,
                            "Waiting for the newly enrolled citizen's current loaded observation");
                    callerAnchor(); ask(PHRASES[nameIndex]); phase=3;
                }
                case 5 -> {
                    ready();
                    for (Villager v:extras) if (!v.isRemoved()) v.setPos(origin.getX()+0.5,origin.getY()+1,origin.getZ()+10.5);
                    owner.setPos(origin.getX()+45.5,origin.getY()+1,origin.getZ()+10.5);
                    foreign.setPos(origin.getX()+46.5,origin.getY()+1,origin.getZ()+10.5);
                    arena(); start=first.blockPosition();
                    require(first.distanceToSqr(expectedSource.minX()+0.5,expectedSource.minY(),expectedSource.minZ()+0.5)>6.25,
                            "Source fixture is already within unchanged interaction reach");
                    ask("Ada, harvest 4 wheat"); phase=6;
                }
                case 6 -> { var view=language(LanguageRequests.Phase.SUBMITTED); run=view.runId(); phase=7; }
                case 7 -> {
                    var view=session.status(owner,run);
                    h.assertTrue(view.phase()==BootstrapController.Phase.TERMINAL&&view.marker()!=null,"Waiting for real harvest/delivery");
                    require("SUCCEEDED".equals(view.marker().outcome())&&view.marker().modelCalls()==0
                            &&admitted.sha256().equals(view.marker().artifactSha256()),"Known physical execution failed or changed artifact "+view);
                    require(view.receipts().size()>=12&&view.marker().effects()>=12,"Missing crop custody effects");
                    for (var receipt:view.receipts()) require(receipt.actor().equals(actor)&&receipt.source().equals(expectedSource)
                            &&receipt.destination().equals(expectedDestination),"Resolved canonical receipt binding changed");
                    long dx=(long)first.blockPosition().getX()-start.getX(),dz=(long)first.blockPosition().getZ()-start.getZ();
                    require(dx*dx+dz*dz>=16,"No genuine actor movement between bound targets");
                    var box=(Container)h.getLevel().getBlockEntity(new BlockPos(expectedDestination.x(),expectedDestination.y(),expectedDestination.z()));
                    require(box.countItem(Items.WHEAT)>=4,"Bound container did not receive actual harvested wheat");
                    if (++location<2) phase=5;
                    else {
                        require(fakeCalls==7&&session.languageCalls()==0,"Injected fixture invoked real NLU or repeated interpretation");
                        System.out.println("IMP-009 nearest accepted actualNeedle=false namingForms=5 excludedCitizens=true "
                                +"coordinateFreeRuns=2 delivered=8 physicalMovement=true changedLocations=true sameArtifact="+admitted.sha256()
                                +" generationCalls=0 fakeInterpretations="+fakeCalls+" registeredCommand=aivillage");
                        closing=session.storeClosure(); session.close(); phase=8;
                    }
                }
                case 8 -> { await(closing); phase=9; }
                default -> throw new IllegalStateException("Phase "+phase);
            }
        }
        void arena() {
            h.setBlock(OFFSET.offset(4,1,3),Blocks.AIR);
            for (int x=2;x<=3;x++) for (int z=3;z<=4;z++) h.setBlock(OFFSET.offset(x,1,z),Blocks.AIR);
            int x=location==0?10:24, dest=location==0?14:28;
            for (int wallX=x-1;wallX<=x+2;wallX++) for (int z=2;z<=5;z++)
                if ((wallX==x-1||wallX==x+2||z==2||z==5)
                        && !((wallX==x-1||wallX==x+2)&&z==3))
                    h.setBlock(OFFSET.offset(wallX,1,z),Blocks.STONE);
            patch(x,x+1,3,4,7); patch(x-4,x-3,3,3,0);
            h.setBlock(OFFSET.offset(x-4,1,3),Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,7));
            h.setBlock(OFFSET.offset(x+2,1,4),Blocks.CHEST);
            var full=(Container)h.getLevel().getBlockEntity(origin.offset(x+2,1,4));
            for (int i=0;i<full.getContainerSize();i++) full.setItem(i,new ItemStack(Items.COBBLESTONE,64));
            full.setChanged(); h.setBlock(OFFSET.offset(dest,1,4),Blocks.CHEST);
            expectedSource=new Cuboid(actor.dimension(),origin.getX()+x,origin.getY()+1,origin.getZ()+3,
                    origin.getX()+x+1,origin.getY()+1,origin.getZ()+4);
            expectedDestination=new ContainerRef(actor.dimension(),origin.getX()+dest,origin.getY()+1,origin.getZ()+4);
            first.setNoAi(false);
        }
        LanguageRequests.Handle interpret(LanguageRequests.Input input) {
            fakeCalls++;
            var classified=LanguageReferenceClassifier.classify(input.text(),input.references());
            var extracted=classified.intent()==LanguageReferenceClassifier.Intent.NAME_CITIZEN
                    ? new LanguageRequests.Extracted(LanguageRequests.Action.NAME_CITIZEN,null,null,null,null,null,classified.proposedName())
                    : new LanguageRequests.Extracted("Ada",4L,null,null,null);
            var result=CompletableFuture.completedFuture(new LanguageRequests.Result(LanguageRequests.Kind.EXTRACTED,extracted,1));
            return new LanguageRequests.Handle() { public CompletableFuture<LanguageRequests.Result> result() { return result; }
                public void cancel() { } };
        }
        void excludedUnchanged() {
            require("Known".equals(session.citizen(owner,named.citizenId()).displayName()),"Already named citizen changed");
            require(session.citizen(foreign,foreignActor.citizenId()).displayName()==null,"Foreign citizen named");
            require(session.citizen(owner,unavailableActor.citizenId()).displayName()==null
                    &&session.citizen(owner,distant.citizenId()).displayName()==null
                    &&session.citizen(owner,backup.citizenId()).displayName()==null,"Excluded/unselected citizen named");
            require(!session.enrolled(unclaimed)&&unclaimed.getCustomName()==null,"Naming enrolled or directly named an unclaimed villager");
        }
        void callerAnchor() { owner.setPos(origin.getX()+0.5,origin.getY()+1,origin.getZ()+3.5); }
        void beside(ServerPlayer p,Villager v) { p.setPos(v.getX(),v.getY(),v.getZ()); }
        void ask(String text) {
            System.out.println("IMP-009 fake dispatch text="+text+" phase="+phase+" nameIndex="+nameIndex
                    +" expected="+namingTarget+" firstPosition="+first.blockPosition()+" ownerPosition="+owner.blockPosition());
            try { require(h.getLevel().getServer().getCommands().getDispatcher().execute("aivillage kernel ask "+text,
                    owner.createCommandSourceStack())==1,"Registered language command rejected"); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        }
        LanguageRequests.View language(LanguageRequests.Phase expected) {
            var view=session.interpretationStatus(owner,ticket);
            h.assertTrue(view.phase()!=LanguageRequests.Phase.INTERPRETING&&view.phase()!=LanguageRequests.Phase.RESOLVING,"Waiting for language/binding");
            require(view.phase()==expected,"Unexpected language result "+view); return view;
        }
        void ready() { h.assertTrue(session.identityReady(),"Waiting for identity publication"); }
        void await(CompletableFuture<Void> f) { h.assertTrue(f.isDone(),"Waiting for store closure"); f.join(); }
        void require(boolean ok,String message) { if (!ok) { fatal=message; h.fail(message); } }
    }
}
