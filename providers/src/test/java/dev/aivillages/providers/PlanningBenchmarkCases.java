package dev.aivillages.providers;

import dev.aivillages.core.kernel.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class PlanningBenchmarkCases {
    private PlanningBenchmarkCases() { }
    record Case(String id,String category,String request,String expectedStatus,int first,int second,MaterialPlan.Context material) { }
    static List<Case> load() throws Exception {
        return load("requests.json");
    }
    static List<Case> load(String dataset) throws Exception {
        if(!Set.of("requests.json","acceptance-requests.json","structure-cases.json","resource-cases.json","facility-cases.json").contains(dataset))throw new IllegalArgumentException("Committed dataset required");
        try(var stream=PlanningBenchmarkCases.class.getResourceAsStream("/planning/"+dataset)) {
            if(stream==null)throw new IllegalStateException("Committed benchmark cases required");
            var root=StrictJson.transportObject(new String(stream.readAllBytes(),StandardCharsets.UTF_8));
            var result=new ArrayList<Case>();
            for(Object value:(List<?>)root.get("cases")) {
                @SuppressWarnings("unchecked") var row=(Map<String,Object>)value;
                boolean wheat=row.get("kind").equals("wheat");
                @SuppressWarnings("unchecked") var goals=(Map<String,Long>)row.get("goals");
                @SuppressWarnings("unchecked") var inventory=(Map<String,Long>)row.get("inventory");
                result.add(new Case((String)row.get("id"),(String)row.get("category"),(String)row.get("request"),(String)row.get("expectedStatus"),
                        wheat?Math.toIntExact((Long)row.get("first")):0,wheat?Math.toIntExact((Long)row.get("second")):0,
                        wheat?null:new MaterialPlan.Context((String)row.get("request"),goals,inventory,(Boolean)row.get("tableAvailable"),(Boolean)row.get("placementAllowed"))));
            }
            return List.copyOf(result);
        }
    }
}
