package pointcloud;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.regex.Pattern;

final class Expression {
    static final String NUMBER = "[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?";
    private static final Pattern TOKEN = Pattern.compile(
            "[A-Za-z][A-Za-z0-9]*|" + NUMBER + "|==|!=|<=|>=|&&|\\|\\||[!<>()]");

    @FunctionalInterface
    interface Selection {
        boolean keep(PointBatch batch, int point);
    }

    private final String source;
    private final EnumSet<Dimension> dimensions;
    private int cursor;
    private String next;

    private Expression(String source, EnumSet<Dimension> dimensions) {
        this.source = source;
        this.dimensions = dimensions;
        advance();
    }

    static Selection parse(String source, EnumSet<Dimension> dimensions) {
        var parser = new Expression(source, dimensions);
        var result = parser.or(0);
        if (parser.next != null) {
            throw new IllegalArgumentException("unexpected expression token " + parser.next);
        }
        return result;
    }

    static double finite(String text) {
        double value = Double.parseDouble(text);
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("expected a finite number: " + text);
        }
        return value;
    }

    private Selection or(int depth) {
        var first = and(depth);
        if (!accept("||")) {
            return first;
        }
        var list = new ArrayList<Selection>();
        list.add(first);
        do {
            list.add(and(depth));
        } while (accept("||"));
        var terms = list.toArray(Selection[]::new);
        return (batch, point) -> {
            for (var term : terms) {
                if (term.keep(batch, point)) {
                    return true;
                }
            }
            return false;
        };
    }

    private Selection and(int depth) {
        var first = term(depth);
        if (!accept("&&")) {
            return first;
        }
        var list = new ArrayList<Selection>();
        list.add(first);
        do {
            list.add(term(depth));
        } while (accept("&&"));
        var terms = list.toArray(Selection[]::new);
        return (batch, point) -> {
            for (var term : terms) {
                if (!term.keep(batch, point)) {
                    return false;
                }
            }
            return true;
        };
    }

    private Selection term(int depth) {
        if (depth == 128) {
            throw new IllegalArgumentException("expression nesting exceeds 128");
        }
        if (accept("!")) {
            var operand = term(depth + 1);
            return (batch, point) -> !operand.keep(batch, point);
        }
        if (accept("(")) {
            var group = or(depth + 1);
            if (!accept(")")) {
                throw new IllegalArgumentException("expected ')' in expression");
            }
            return group;
        }
        var dimension = Dimension.parse(take("dimension"));
        dimensions.add(dimension);
        String comparison = take("comparison");
        double value = finite(take("number"));
        return switch (comparison) {
            case "==" -> (b, p) -> dimension.value(b, p) == value;
            case "!=" -> (b, p) -> dimension.value(b, p) != value;
            case "<" -> (b, p) -> dimension.value(b, p) < value;
            case "<=" -> (b, p) -> dimension.value(b, p) <= value;
            case ">" -> (b, p) -> dimension.value(b, p) > value;
            case ">=" -> (b, p) -> dimension.value(b, p) >= value;
            default -> throw new IllegalArgumentException("expected a comparison operator: " + comparison);
        };
    }

    private String take(String expected) {
        if (next == null) {
            throw new IllegalArgumentException("expected " + expected + " at end of expression");
        }
        String result = next;
        advance();
        return result;
    }

    private boolean accept(String token) {
        if (!token.equals(next)) {
            return false;
        }
        advance();
        return true;
    }

    private void advance() {
        while (cursor < source.length() && Character.isWhitespace(source.charAt(cursor))) {
            cursor++;
        }
        if (cursor == source.length()) {
            next = null;
            return;
        }
        var matcher = TOKEN.matcher(source).region(cursor, source.length());
        if (!matcher.lookingAt()) {
            throw new IllegalArgumentException("invalid expression token at column " + (cursor + 1));
        }
        next = matcher.group();
        cursor = matcher.end();
    }
}
