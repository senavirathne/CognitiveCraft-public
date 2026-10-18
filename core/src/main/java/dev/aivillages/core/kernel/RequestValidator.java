package dev.aivillages.core.kernel;

import java.util.Map;
import java.util.Objects;

import static dev.aivillages.core.kernel.Contracts.*;

/** Pure request boundary; it neither resolves capabilities nor reserves resources. */
public final class RequestValidator {
    private RequestValidator() { }

    public sealed interface Result permits Accepted, Rejected { }
    public record Accepted(ValidatedRequest value) implements Result { }
    public record Rejected(Outcomes.Reason reason, String path) implements Result {
        public Rejected { Objects.requireNonNull(reason); Objects.requireNonNull(path); }
    }

    public static Result validate(CapabilityRequest input, TrustedContext context,
                                  ObservationRef observation, CapabilityCatalog catalog,
                                  RequestEnvironment environment) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(observation);
        Objects.requireNonNull(catalog);
        Objects.requireNonNull(environment);
        if (input == null) return invalid("$");
        var found = catalog.find(input.capability());
        if (found.isEmpty()) return invalid("$.capability");
        CapabilitySpec spec = found.get();
        if (!spec.id().equals(input.capability())) return invalid("$.capability");
        Map<String, Value> values = input.arguments();
        if (values.size() != spec.parameters().size()) return invalid("$.arguments");
        for (Parameter parameter : spec.parameters()) {
            Value value = values.get(parameter.name());
            if (value == null || value.type() != parameter.type())
                return invalid("$.arguments." + parameter.name());
            if (value instanceof IntValue integer &&
                    (integer.value() < parameter.minimum() || integer.value() > parameter.maximum()))
                return invalid("$.arguments." + parameter.name());
        }
        for (String key : values.keySet()) {
            if (spec.parameters().stream().noneMatch(p -> p.name().equals(key)))
                return invalid("$.arguments." + key);
        }
        if (spec.completion() == Completion.ATTRIBUTABLE_CROP_DELIVERY
                || spec.completion() == Completion.ATTRIBUTABLE_RESOURCE_DELIVERY) {
            if (!ResourceDelivery.spec(spec.id()).filter(spec::equals).isPresent()
                    || !(values.get("actor") instanceof ActorValue actorValue)
                    || !(values.get("source") instanceof AreaValue areaValue)
                    || !(values.get("destination") instanceof ContainerValue destinationValue))
                return invalid("$.arguments");
            ActorRef actor = actorValue.value();
            Cuboid source = areaValue.value();
            ContainerRef destination = destinationValue.value();
            if (LogAcquisition.is(spec.id())) {
                try { if(ResourceLeases.cells(source)>256) return invalid("$.arguments.source"); }
                catch(ArithmeticException overflow) { return invalid("$.arguments.source"); }
            }
            if (!actor.dimension().equals(source.dimension())
                    || !actor.dimension().equals(destination.dimension())
                    || !actor.dimension().equals(observation.dimension())) return invalid("$.dimension");
            if (!environment.enrolled(actor, context)) return invalid("$.arguments.actor");
            if (!environment.loaded(source, observation)) return invalid("$.arguments.source");
            if (!environment.available(destination, observation)) return invalid("$.arguments.destination");
        }
        if (spec.completion() == Completion.ATTRIBUTABLE_TRANSFORMATION_DELIVERY) {
            if(!RecipeProduction.is(spec.id()) || !spec.equals(RecipeProduction.of(spec.id()).spec()) || !(values.get("actor") instanceof ActorValue actor)
                    || !(values.get("source") instanceof ContainerValue source)
                    || !(values.get("destination") instanceof ContainerValue destination)
                    || !actor.value().dimension().equals(source.value().dimension())
                    || !actor.value().dimension().equals(destination.value().dimension())
                    || !actor.value().dimension().equals(observation.dimension())
                    || !environment.enrolled(actor.value(),context)
                    || !environment.available(source.value(),observation)
                    || !environment.available(destination.value(),observation)) return invalid("$.arguments");
            if(RecipeProduction.of(spec.id()).axe() && (!(values.get("table") instanceof FacilityValue table)
                    || !actor.value().dimension().equals(table.value().dimension())))return invalid("$.arguments.table");
        }
        if(spec.completion()==Completion.ATTRIBUTABLE_PLACEMENT) {
            if(!spec.equals(CraftingTablePlacement.SPEC) || !CraftingTablePlacement.valid(input))return invalid("$.arguments");
            var actor=((ActorValue)values.get("actor")).value();var source=((ContainerValue)values.get("source")).value();var target=CraftingTablePlacement.target(input);
            if(!actor.dimension().equals(observation.dimension()) || !environment.enrolled(actor,context)
                    || !environment.available(source,observation)
                    || !environment.loaded(new Cuboid(target.dimension(),target.x(),target.y(),target.z(),target.x(),target.y(),target.z()),observation))return invalid("$.arguments");
        }
        if (spec.completion() == Completion.ALL_CHILDREN) {
            if (!spec.equals(PlanningGoals.SPEC) || !PlanningGoals.valid(input)) return invalid("$.arguments");
            var actor=((ActorValue)values.get("actor")).value();
            if(!actor.dimension().equals(observation.dimension()) || !environment.enrolled(actor,context)
                    || !environment.loaded(((AreaValue)values.get("source")).value(),observation)
                    || !environment.available(((ContainerValue)values.get("destination")).value(),observation)
                    || !environment.available(((ContainerValue)values.get("otherDestination")).value(),observation))
                return invalid("$.arguments");
        }
        return new Accepted(new ValidatedRequest(input, context, observation));
    }

    private static Rejected invalid(String path) {
        return new Rejected(Outcomes.Reason.REQUEST_INVALID, path);
    }
}
