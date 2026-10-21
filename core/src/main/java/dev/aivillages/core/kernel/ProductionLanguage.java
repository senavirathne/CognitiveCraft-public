package dev.aivillages.core.kernel;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static dev.aivillages.core.kernel.Contracts.CapabilityId;
import static dev.aivillages.core.kernel.ResourceDelivery.ItemIdentity;

/** Raw production goals and ingredient/product roles; no world facts or execution. */
public final class ProductionLanguage {
    private ProductionLanguage() { }
    public static final String TRANSFORMATION_VERBS="turn|convert|transform";
    public static final String GOAL_VERBS="need|want|require|would\\s+like";
    public enum Issue { NONE, INVALID_QUANTITY, INPUT_QUANTITY_MISMATCH, UNSUPPORTED_RECIPE }
    public record Description(CapabilityId capability,ItemIdentity input,ItemIdentity output,
                              Long inputAmount,Long outputAmount,String inputEvidence,
                              String referenceText,String modelText,Issue issue) { }
    private static final String ONES="one|two|three|four|five|six|seven|eight|nine";
    private static final String NUMBER="(?:(?:twenty|thirty|forty|fifty|sixty)(?:[ -](?:"+ONES+"))?"
            +"|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|"+ONES
            +"|[+-]?[0-9]+(?:[.,/][0-9]+)?)";
    private static final String MATERIAL="(?:(?:stripped\\s+oak|oak|birch|spruce)\\s+logs?"
            +"|(?:(?:oak|birch)\\s+)?planks?|sticks?|crafting\\s+tables?|wooden\\s+axes?|iron\\s+ingots?)";
    private static final String INPUT="(?<units>(?:(?:these|those|this|that|the)\\s+)?(?:available\\s+)?"
            +"(?:(?<iq>"+NUMBER+")\\s+)?(?:available\\s+)?(?<input>"+MATERIAL+"))";
    private static final Pattern TRANSFORM=Pattern.compile("\\b(?<verb>"+TRANSFORMATION_VERBS+")\\s+"+INPUT
            +"(?<source>\\s+from\\s+.{1,160}?)?\\s+(?:into|to)\\s+(?<oq>"+NUMBER+")\\s+(?<output>"+MATERIAL+")\\b",Pattern.CASE_INSENSITIVE);
    private static final Pattern PRODUCE=Pattern.compile("\\b(?<verb>make|craft|produce)\\s+(?<oq>"+NUMBER+")\\s+(?<output>"+MATERIAL+")"
            +"\\s+(?<relation>from|using|with)\\s+"+INPUT+"\\b",Pattern.CASE_INSENSITIVE);
    private static final Pattern ITEMS=Pattern.compile("\\b"+MATERIAL+"\\b",Pattern.CASE_INSENSITIVE);
    private static final Pattern GOAL=Pattern.compile("^\\s*(?<prefix>(?:[\\p{L}\\p{M}\\p{N}][\\p{L}\\p{M}\\p{N}_ -]{0,127}?\\s*,\\s*)?)"
            +"(?:i|we)\\s+(?:"+GOAL_VERBS+")\\s+(?<oq>"+NUMBER+")\\s+(?<output>"+MATERIAL+")\\b(?<tail>.*)$",
            Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
    private static final String STOCK="(?:(?:the\\s+)?(?:available|existing)\\s+(?:materials|stock|supplies)"
            +"|(?:the\\s+)?(?:materials|stock|supplies)\\s+(?:we|i)\\s+(?:already\\s+)?(?:have|own)"
            +"|what\\s+(?:we|i)(?:['’]ve|\\s+have)?\\s+(?:already\\s+)?(?:got|have))";
    private static final Pattern STOCK_CLAUSE=Pattern.compile("(?:\\s*[.;]\\s*(?:please\\s+)?use\\s+|\\s+(?:using|with|from)\\s+)"
            +"(?<stock>"+STOCK+")(?=\\s*(?:[.;!?]|$|(?:and\\s+)?(?:put|store|deposit|deliver)\\b))",Pattern.CASE_INSENSITIVE);
    // Existing registered single-input producers. Multi-ingredient methods require their own semantics.
    private static final List<CapabilityId> PRODUCERS=List.of(OakPlankProduction.ID,BirchPlankProduction.ID,
            StickProduction.ID,CraftingTableProduction.ID);
    private static final List<CapabilityId> GOAL_PRODUCERS=List.of(OakPlankProduction.ID,BirchPlankProduction.ID,
            StickProduction.ID,CraftingTableProduction.ID,WoodenAxeProduction.ID);

    public static Description describe(String text) {
        var goal=goal(text);if(goal!=null)return goal;
        Matcher match=TRANSFORM.matcher(text);boolean transformation=match.find();
        if(!transformation) {match=PRODUCE.matcher(text);if(!match.find())return null;}
        String inputMaterial=match.group("input"),outputMaterial=match.group("output");
        // An uncounted table introduced by 'using' is the existing facility role,
        // not a recipe ingredient. Leave it to the capability's facility parser.
        if(!transformation && match.group("relation").equalsIgnoreCase("using")
                && match.group("iq")==null && inputMaterial.matches("(?i)crafting\\s+tables?"))return null;
        List<RecipeProduction.Definition> recipes=PRODUCERS.stream().map(RecipeProduction::of)
                .filter(r->matches(inputMaterial,r.input()) && matches(outputMaterial,r.output())).toList();
        long mentions=ITEMS.matcher(text.substring(match.start())).results().count();
        if(recipes.size()!=1 || mentions!=2)
            return new Description(null,null,null,null,null,match.group("units"),text,text,Issue.UNSUPPORTED_RECIPE);
        var recipe=recipes.getFirst();String inputQuantity=match.group("iq"),outputQuantity=match.group("oq");
        Long inputs=quantity(inputQuantity),outputs=quantity(outputQuantity);Issue issue=Issue.NONE;
        if(outputs==null || outputs<1 || outputs>recipe.maximumAmount()
                || inputQuantity!=null && (inputs==null || inputs<1 || inputs>recipe.inputs(recipe.maximumAmount())))
            issue=Issue.INVALID_QUANTITY;
        else if(inputs!=null && inputs!=recipe.inputs(outputs))issue=Issue.INPUT_QUANTITY_MISMATCH;
        String source=transformation && match.group("source")!=null ? match.group("source") : "";
        String suffix=source+text.substring(match.end());
        String prefix=text.substring(0,match.start())+"make "+outputQuantity+" "+plural(recipe.output());
        boolean explicitContainer=Pattern.compile("\\bfrom\\b",Pattern.CASE_INSENSITIVE).matcher(suffix).find();
        return new Description(recipe.spec().id(),recipe.input(),recipe.output(),inputs,outputs,match.group("units"),
                prefix+suffix,prefix+(explicitContainer ? "" : " from stock")+suffix,issue);
    }

    private static Description goal(String text) {
        var goal=GOAL.matcher(text);if(!goal.matches())return null;
        var recipes=GOAL_PRODUCERS.stream().map(RecipeProduction::of)
                .filter(r->matches(goal.group("output"),r.output())).toList();
        if(recipes.size()!=1)return new Description(null,null,null,null,null,null,text,text,Issue.UNSUPPORTED_RECIPE);
        var recipe=recipes.getFirst();Long output=quantity(goal.group("oq"));
        Issue issue=output==null || output<1 || output>recipe.maximumAmount() ? Issue.INVALID_QUANTITY : Issue.NONE;
        String tail=goal.group("tail"),stockEvidence=null;
        var stock=STOCK_CLAUSE.matcher(tail);
        if(stock.find()) {
            stockEvidence=stock.group("stock");int start=stock.start(),end=stock.end();
            if(stock.find())issue=Issue.UNSUPPORTED_RECIPE;
            tail=tail.substring(0,start)+tail.substring(end);
        }
        if(!supportedGoalTail(tail))issue=Issue.UNSUPPORTED_RECIPE;
        String prefix=goal.group("prefix")+"make "+goal.group("oq")+" "+plural(recipe.output());
        String referenceText=prefix+tail;
        boolean source=Pattern.compile("\\bfrom\\b",Pattern.CASE_INSENSITIVE).matcher(tail).find();
        return new Description(recipe.spec().id(),recipe.input(),recipe.output(),null,output,stockEvidence,
                referenceText,prefix+(stockEvidence!=null && !source ? " from stock" : "")+tail,issue);
    }

    /** Unknown instructions and inventory qualifiers cannot disappear as omission. */
    private static boolean supportedGoalTail(String raw) {
        String tail=raw.strip().replaceFirst("[.!?;]+$","").strip();
        if(tail.isEmpty() || tail.matches("(?i)for\\s+(?:me|us)"))return true;
        if(Pattern.compile("[.!?;]\\s*\\p{L}").matcher(tail).find())return false;
        if(tail.matches("(?is)^(?:from|to|into|in)\\s+.+"))return true;
        if(tail.matches("(?is)^(?:at|using)\\s+.+") && Pattern.compile("\\btable\\b",Pattern.CASE_INSENSITIVE).matcher(tail).find())return true;
        return tail.matches("(?is)^(?:and\\s+)?(?:put|store|deposit|deliver)\\b.*");
    }

    private static boolean matches(String raw,ItemIdentity item) {
        String noun=raw.toLowerCase(Locale.ROOT).replaceAll("\\s+"," ")
                .replaceAll("logs$","log").replaceAll("planks$","plank").replaceAll("sticks$","stick")
                .replaceAll("tables$","table").replaceAll("axes$","axe").replaceAll("ingots$","ingot");
        if(noun.equals("plank"))return item.equals(ResourceDelivery.OAK_PLANKS) || item.equals(ResourceDelivery.BIRCH_PLANKS);
        return noun.equals(item.id().substring(item.id().indexOf(':')+1).replace('_',' ')
                .replaceAll("planks$","plank"));
    }
    private static String plural(ItemIdentity item) {
        return switch(item.id()) {
            case "minecraft:oak_planks" -> "oak planks";
            case "minecraft:birch_planks" -> "birch planks";
            case "minecraft:stick" -> "sticks";
            case "minecraft:crafting_table" -> "crafting tables";
            case "minecraft:wooden_axe" -> "wooden axes";
            default -> throw new IllegalArgumentException("Unregistered single-input product");
        };
    }
    private static Long quantity(String raw) {
        if(raw==null)return null;
        if(raw.matches("[0-9]+")) {try{return Long.parseLong(raw);}catch(NumberFormatException overflow){return null;}}
        String word=raw.toLowerCase(Locale.ROOT).replace('-',' ');
        List<String> small=List.of("","one","two","three","four","five","six","seven","eight","nine","ten",
                "eleven","twelve","thirteen","fourteen","fifteen","sixteen","seventeen","eighteen","nineteen");
        int at=small.indexOf(word);if(at>0)return (long)at;
        String[] parts=word.split(" ");int tens=List.of("","","twenty","thirty","forty","fifty","sixty").indexOf(parts[0]);
        int unit=parts.length==1?0:parts.length==2?small.indexOf(parts[1]):-1;
        return tens>=2 && unit>=0 && unit<10 ? (long)tens*10+unit : null;
    }
}
