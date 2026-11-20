package dev.aivillages.core.kernel;

import java.util.*;
import static dev.aivillages.core.kernel.Contracts.*;
import static dev.aivillages.core.kernel.SkillIr.*;

/** Structural cut analysis of the original recompiled IR. This value confers no execution or save authority. */
final class RecipeActivityContinuation {
    private RecipeActivityContinuation() { }
    record Point(int rootPosition,int batchPosition,long batchesRemaining) { }

    /** Only a unique completed craft in an otherwise parameter-only recipe loop is reconstructible here.
     * Native saved-prefix proof, the original method pin, Jobs ownership and remaining limits are separate requirements.
     * Conditional/data-dependent methods require an interpreter checkpoint and remain unsupported by this analysis. */
    static Point afterCraft(Program program,CapabilityRequest original,int nativePrefixSize) {
        var capability=original.capability();var definition=RecipeProduction.of(capability);
        if(!program.capability().equals(capability) || !program.dependencies().isEmpty() || program.body().size()!=3
                || !(original.arguments().get("amount") instanceof IntValue amount))throw unsupported();
        var operations=RecipeProduction.operations(capability);
        call(program.body().getFirst(),operations.getFirst());
        if(!(program.body().get(1) instanceof Repeat loop) || !(program.body().get(2) instanceof Result result)
                || !new ParameterExpr("amount",Type.INT).equals(result.value())
                || loop.body().size()!=operations.size()-1)throw unsupported();
        long count=integer(loop.count(),amount.value(),0);
        if(count!=definition.batches(amount.value()) || count<1 || count>loop.maximumCount()
                || nativePrefixSize<2 || nativePrefixSize%3!=2 || nativePrefixSize>=Math.multiplyExact(count,3))throw unsupported();
        int afterCraft=-1;
        for(int index=0;index<loop.body().size();index++) {
            var op=operations.get(index+1);
            call(loop.body().get(index),op);
            if(op.signature().effects().contains(Effect.CRAFT)) {
                if(afterCraft!=-1)throw unsupported();
                afterCraft=index+1;
            }
        }
        if(afterCraft<1)throw unsupported();
        return new Point(2,afterCraft,count-nativePrefixSize/3);
    }

    /** The original single-log recipe reaches one pickup before its final move and transfer. */
    static Point afterPickup(Program program, CapabilityRequest original, int nativePrefixSize) {
        var capability = original.capability();
        if (!LogAcquisition.is(capability) || !program.capability().equals(capability) || !program.dependencies().isEmpty()
                || !new IntValue(1).equals(original.arguments().get("amount")) || nativePrefixSize != 2
                || program.body().size() != 2 || !(program.body().getFirst() instanceof Repeat loop)
                || integer(loop.count(), 1, 0) != 1 || !(program.body().getLast() instanceof Result result)
                || !new ParameterExpr("amount", Type.INT).equals(result.value())) throw unsupported();
        var ops = LogAcquisition.operations(capability);
        var body = loop.body();
        if (body.size() != 7 || !(body.get(2) instanceof Repeat observes) || observes.body().size() != 1
                || integer(observes.count(), 1, 0) < 1 || integer(observes.count(), 1, 0) > observes.maximumCount()) throw unsupported();
        call(body.get(0), ops.get(0));
        call(body.get(1), ops.get(1));
        call(observes.body().getFirst(), ops.get(2));
        call(body.get(3), ops.get(0));
        call(body.get(4), ops.get(3));
        call(body.get(5), ops.get(4));
        call(body.get(6), ops.get(5), true);
        return new Point(1, 5, 1);
    }

    private static void call(Node node,GatewayPrimitives.Operation operation) {
        call(node, operation, false);
    }
    private static void call(Node node, GatewayPrimitives.Operation operation, boolean oneUnitAmount) {
        var signature=operation.signature();
        if(!(node instanceof Call c) || c.artifactRef()!=null || !signature.id().equals(c.primitiveId())
                || signature.version()!=c.primitiveVersion() || !signature.fingerprint().equals(c.fingerprint())
                || c.resultType()!=signature.resultType() || c.arguments().size()!=signature.parameters().size())throw unsupported();
        // No local result can influence the remaining program. In particular,
        // reconstructing this cut never guesses a previous observation value.
        for (var parameter : signature.parameters()) {
            var argument = c.arguments().get(parameter.name());
            if (oneUnitAmount && parameter.name().equals("amount") && new IntegerExpr(1).equals(argument)) continue;
            if (!new ParameterExpr(parameter.name(), parameter.type()).equals(argument)) throw unsupported();
        }
    }
    private static long integer(Expr expression,long amount,int depth) {
        if(depth>SkillCompiler.MAX_CONTROL_DEPTH || expression.type()!=Type.INT)throw unsupported();
        if(expression instanceof IntegerExpr value)return value.value();
        if(expression.equals(new ParameterExpr("amount",Type.INT)))return amount;
        if(expression instanceof BinaryExpr binary) {
            long a=integer(binary.left(),amount,depth+1),b=integer(binary.right(),amount,depth+1);
            return switch(binary.operator()) {
                case ADD -> Math.addExact(a,b);
                case SUB -> Math.subtractExact(a,b);
                case DIV -> {if(b<1 || a<0)throw unsupported();yield a/b;}
                default -> throw unsupported();
            };
        }
        throw unsupported();
    }
    private static IllegalArgumentException unsupported(){return new IllegalArgumentException("Original IR has no uniquely reconstructible recipe cut");}
}
