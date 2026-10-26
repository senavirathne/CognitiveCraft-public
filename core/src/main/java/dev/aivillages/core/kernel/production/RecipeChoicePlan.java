package dev.aivillages.core.kernel.production;

import java.util.*;
import dev.aivillages.core.kernel.*;
import static dev.aivillages.core.kernel.Contracts.*;

/** Read-only construction/lease-key proposal. Jobs alone may publish or grant it. */
public record RecipeChoicePlan(UUID root,long revision,ValidatedRequest request,RecipeChoice choice,List<StockProposal> stock) {
    public record StockProposal(ContainerRef source,int slot,RecipeChoice.ItemKey item,long units,String proposedKey) {
        public StockProposal {Objects.requireNonNull(source);Objects.requireNonNull(item);if(slot<0 || units<1 || proposedKey==null || proposedKey.isBlank())throw new IllegalArgumentException("Stock proposal");}
    }
    public RecipeChoicePlan {
        Objects.requireNonNull(root);Objects.requireNonNull(request);Objects.requireNonNull(choice);stock=List.copyOf(stock);
        if(revision<0 || !request.request().capability().equals(choice.capability())
                || !(request.request().arguments().get("amount") instanceof IntValue amount) || amount.value()!=choice.demand())
            throw new IllegalArgumentException("Immutable root request and choice must agree");
        if(!(request.request().arguments().get("actor") instanceof ActorValue actor)
                || !(request.request().arguments().get("destination") instanceof ContainerValue destination)
                || !actor.value().dimension().equals(destination.value().dimension())
                || choice.batches().stream().flatMap(b->b.bindings().stream()).anyMatch(b->!b.source().dimension().equals(actor.value().dimension())))
            throw new IllegalArgumentException("Bound native actor/source/destination dimensions required");
        var expected=proposals(request,choice);
        if(!expected.equals(stock))throw new IllegalArgumentException("Stock proposal must derive from the selected grid");
    }
    public static RecipeChoicePlan propose(UUID root,long revision,ValidatedRequest request,RecipeChoice choice) {
        return new RecipeChoicePlan(root,revision,request,choice,proposals(request,choice));
    }
    private static List<StockProposal> proposals(ValidatedRequest request,RecipeChoice choice) {
        if(!(request.request().arguments().get("source") instanceof ContainerValue source))throw new IllegalArgumentException("Explicit source required");
        record Key(ContainerRef source,int slot,RecipeChoice.ItemKey item){}
        var counts=new LinkedHashMap<Key,Long>();
        for(var batch:choice.batches())for(var b:batch.bindings()) {
            if(!b.source().equals(source.value()))throw new IllegalArgumentException("Unapproved alternative source");
            counts.merge(new Key(b.source(),b.sourceSlot(),b.item()),1L,Math::addExact);
        }
        return counts.entrySet().stream().map(e->{var k=e.getKey();String key="choice-stock:2:"+request.context().scope().worldId()+":"+
                StrictJson.canonical(RecipeChoice.container(k.source()))+":"+k.slot()+":"+k.item().id()+":"+k.item().componentDigest();
            return new StockProposal(k.source(),k.slot(),k.item(),e.getValue(),key);}).toList();
    }
    public String choiceDigest(){return choice.digest();}
}
