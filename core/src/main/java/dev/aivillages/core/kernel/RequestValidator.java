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
        if (spec.completion() == Completion.ATTRIBUTABLE_CROP_DELIVERY) {
            if (!spec.id().equals(CropDelivery.ID)
                    || !(values.get("actor") instanceof ActorValue actorValue)
                    || !(values.get("source") instanceof AreaValue areaValue)
                    || !(values.get("destination") instanceof ContainerValue destinationValue))
                return invalid("$.arguments");
            ActorRef actor = actorValue.value();
            Cuboid source = areaValue.value();
            ContainerRef destination = destinationValue.value();
            if (!actor.dimension().equals(source.dimension())
                    || !actor.dimension().equals(destination.dimension())
                    || !actor.dimension().equals(observation.dimension())) return invalid("$.dimension");
            if (!environment.enrolled(actor, context)) return invalid("$.arguments.actor");
            if (!environment.loaded(source, observation)) return invalid("$.arguments.source");
            if (!environment.available(destination, observation)) return invalid("$.arguments.destination");
        }
        return new Accepted(new ValidatedRequest(input, context, observation));
    }

    private static Rejected invalid(String path) {
        return new Rejected(Outcomes.Reason.REQUEST_INVALID, path);
    }
}
