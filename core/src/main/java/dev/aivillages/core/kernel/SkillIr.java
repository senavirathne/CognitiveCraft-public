package dev.aivillages.core.kernel;

import java.util.List;
import java.util.Map;

import static dev.aivillages.core.kernel.Contracts.*;

/** Typed, finite IR schema 1. Result values are advisory, never completion receipts. */
public final class SkillIr {
    private SkillIr() { }

    public sealed interface Expr permits ParameterExpr, LocalExpr, IntegerExpr, BooleanExpr, BinaryExpr {
        Type type();
    }
    public record ParameterExpr(String name, Type type) implements Expr { }
    public record LocalExpr(String name, Type type) implements Expr { }
    public record IntegerExpr(long value) implements Expr { public Type type() { return Type.INT; } }
    public record BooleanExpr(boolean value) implements Expr { public Type type() { return Type.BOOL; } }
    public enum Operator { ADD, SUB, EQ, LT, GTE }
    public record BinaryExpr(Operator operator, Expr left, Expr right, Type type) implements Expr { }

    public sealed interface Node permits Bind, Call, Branch, Repeat, Result { }
    public record Bind(String name, Type type, Expr value) implements Node { }
    /** Exactly one of primitiveId and artifactRef is populated. */
    public record Call(String primitiveId, int primitiveVersion, String fingerprint,
                       ArtifactRef artifactRef, Map<String, Expr> arguments,
                       String into, Type resultType) implements Node {
        public Call { arguments = Map.copyOf(arguments); }
    }
    public record Branch(Expr test, List<Node> whenTrue, List<Node> whenFalse) implements Node {
        public Branch { whenTrue = List.copyOf(whenTrue); whenFalse = List.copyOf(whenFalse); }
    }
    public record Repeat(Expr count, long maximumCount, List<Node> body) implements Node {
        public Repeat { body = List.copyOf(body); }
    }
    public record Result(Expr value) implements Node { }

    public record Program(CapabilityId capability, List<ArtifactRef> dependencies, List<Node> body) {
        public Program { dependencies = List.copyOf(dependencies); body = List.copyOf(body); }
    }
}
