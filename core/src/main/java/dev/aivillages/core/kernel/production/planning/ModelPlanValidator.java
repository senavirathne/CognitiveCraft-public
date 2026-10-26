package dev.aivillages.core.kernel.production.planning;

import java.nio.charset.StandardCharsets;
import java.util.*;
import dev.aivillages.core.kernel.StrictJson;
import static dev.aivillages.core.kernel.production.planning.PlanningModel.*;

/** Optional model composition of exact compiler-supplied action tokens. It accepts no claimed physical result. */
public final class ModelPlanValidator {
    private ModelPlanValidator(){}
    public static final int MAX_BYTES=32768;
    public static Map<String,Object> catalogue(Analysis a){if(a.feasibility()!=Feasibility.FEASIBLE)throw new IllegalArgumentException("No feasible permitted graph");var p=a.plan();var rows=new ArrayList<Object>();for(int i=0;i<p.actions().size();i++){var action=p.actions().get(i);rows.add(Map.of("actionToken",action.token(i),"operation",action.value(),"requires",i==0?List.of():List.of(p.actions().get(i-1).token(i-1))));}return Map.of("schema",SCHEMA,"goalDigest",p.goal().digest(),"actions",rows);}
    public static ValidatedPlan validate(String json,Snapshot s,Analysis expected){if(json==null || json.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES || expected.feasibility()!=Feasibility.FEASIBLE || expected.plan()==null)throw new IllegalArgumentException("Bounded feasible model composition required");var p=expected.plan();
        if(!p.world().equals(s.world()) || !p.root().equals(s.root()) || !p.owner().equals(s.owner().originalOwner()) || !p.context().equals(s.context()) || !p.goal().equals(s.goal()) || !p.budgetInitialDigest().equals(s.budget().initialDigest()) || p.originalDeadlineMillis()!=s.budget().absoluteDeadlineMillis())throw new IllegalArgumentException("Catalogue must bind this authorized goal/scope/budget");
        try{var root=StrictJson.transportObject(json);keys(root,"schema","goalDigest","steps");if(!(root.get("schema") instanceof Long schema) || schema!=SCHEMA || !p.goal().digest().equals(root.get("goalDigest")) || !(root.get("steps") instanceof List<?> steps) || steps.size()>MAX_ACTIONS)throw new IllegalArgumentException("Wrong goal, version or graph bound");
            var expectedTokens=new ArrayList<String>();for(int i=0;i<p.actions().size();i++)expectedTokens.add(p.actions().get(i).token(i));if(steps.size()!=expectedTokens.size())throw new IllegalArgumentException("Complete validated composition required");
            var ids=new HashMap<String,String>();var edges=new HashMap<String,List<String>>();var tokens=new HashSet<String>();
            for(var raw:steps){var node=object(raw);keys(node,"id","actionToken","dependsOn");String id=string(node.get("id")),token=string(node.get("actionToken"));if(id.isBlank() || id.length()>64 || !expectedTokens.contains(token) || ids.putIfAbsent(id,token)!=null || !tokens.add(token) || !(node.get("dependsOn") instanceof List<?> links) || links.size()>MAX_ACTIONS)throw new IllegalArgumentException("Invented/duplicate action or invalid edge");edges.put(id,links.stream().map(ModelPlanValidator::string).toList());}
            var marks=new HashMap<String,Integer>();for(var id:ids.keySet())visit(id,edges,marks);var byToken=new HashMap<String,String>();ids.forEach((id,token)->byToken.put(token,id));for(int i=1;i<expectedTokens.size();i++)if(!ancestors(byToken.get(expectedTokens.get(i)),edges,new HashSet<>()).contains(byToken.get(expectedTokens.get(i-1))))throw new IllegalArgumentException("Required material/facility dependency lost");
            return p;
        }catch(StrictJson.Invalid invalid){throw new IllegalArgumentException("Invalid model proposal",invalid);}}
    private static void visit(String id,Map<String,List<String>> graph,Map<String,Integer> marks){if(!graph.containsKey(id) || marks.getOrDefault(id,0)==1)throw new IllegalArgumentException("Unknown edge or cyclic graph");if(marks.getOrDefault(id,0)==2)return;marks.put(id,1);for(var d:graph.get(id))visit(d,graph,marks);marks.put(id,2);}
    private static Set<String> ancestors(String id,Map<String,List<String>> graph,Set<String> seen){for(var d:graph.get(id))if(seen.add(d))ancestors(d,graph,seen);return seen;}
    private static String string(Object value){if(!(value instanceof String s))throw new IllegalArgumentException("String required");return s;}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value){if(!(value instanceof Map<?,?> m))throw new IllegalArgumentException("Object required");return (Map<String,Object>)m;}
    private static void keys(Map<String,Object> value,String... keys){if(!value.keySet().equals(Set.of(keys)))throw new IllegalArgumentException("Model cannot supply authority, budgets, bindings, receipts, completed steps or results");}
}
