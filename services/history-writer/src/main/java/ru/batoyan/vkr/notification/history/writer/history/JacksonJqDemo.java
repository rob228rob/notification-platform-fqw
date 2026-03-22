package ru.batoyan.vkr.notification.history.writer.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.thisptr.jackson.jq.*;
import net.thisptr.jackson.jq.exception.JsonQueryException;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Минимальный production-ready wrapper + примеры всего синтаксиса.
 * Запускай как обычный main — никакого фреймворка не нужно.
 */
public class JacksonJqDemo {

    // ── Singleton scope ──────────────────────────────────────────────────────
    // Дорогая инициализация — один раз при старте приложения.
    // После init — read-only, полностью thread-safe.
    private static final Scope ROOT_SCOPE;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Кэш скомпилированных выражений.
    // Query — иммутабелен, безопасно шарить между потоками.
    private static final ConcurrentHashMap<String, JsonQuery> CACHE =
            new ConcurrentHashMap<>();

    static {
        ROOT_SCOPE = Scope.newEmptyScope();
        BuiltinFunctionLoader.getInstance()
                .loadFunctions(Versions.JQ_1_6, ROOT_SCOPE);

        // Если подключил jackson-jq-extra — раскомментируй:
        // net.thisptr.jackson.jq.extra.ExtraFunctionLoader
        //     .getInstance().loadFunctions(Versions.JQ_1_6, ROOT_SCOPE);
    }

    // ── Основной API ─────────────────────────────────────────────────────────

    /** Выполнить выражение, вернуть все результаты. */
    static List<JsonNode> query(JsonNode input, String expr) {
        JsonQuery q = CACHE.computeIfAbsent(expr, e -> {
            try {
                return JsonQuery.compile(ROOT_SCOPE.toString(), Version.LATEST);
            } catch (JsonQueryException ex) {
                throw new RuntimeException("Compile failed: " + e, ex);
            }
        });

        List<JsonNode> out = new ArrayList<>();
        try {
            // newChildScope() — лёгкий, изолированный на один вызов
            q.apply(Scope.newChildScope(ROOT_SCOPE), input, out::add);
        } catch (JsonQueryException ex) {
            throw new RuntimeException("Eval failed: [" + expr + "]", ex);
        }
        return out;
    }

    /** Один результат или empty. */
    static Optional<JsonNode> one(JsonNode input, String expr) {
        List<JsonNode> r = query(input, expr);
        if (r.isEmpty()) return Optional.empty();
        JsonNode n = r.get(0);
        return (n.isNull() || n.isMissingNode()) ? Optional.empty() : Optional.of(n);
    }

    /** Типизированный результат. */
    static <T> Optional<T> as(JsonNode input, String expr, Class<T> type) {
        return one(input, expr).map(n -> MAPPER.convertValue(n, type));
    }

    // ── Demo ─────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        String raw = """
            {
              "store": {
                "books": [
                  {"title": "Clean Code",   "price": 34.99, "tags": ["java","best"]},
                  {"title": "The Pragmatic", "price": 49.99, "tags": ["career"]},
                  {"title": "SICP",          "price": 0.0,   "tags": ["lisp","cs"]}
                ],
                "owner": {"name": "Alice", "age": 42}
              }
            }
            """;

        JsonNode root = MAPPER.readTree(raw);

        section("Базовый доступ");
        show(root, ".store.owner.name");            // "Alice"
        show(root, ".store.books[0].title");        // "Clean Code"
        show(root, ".store.books[-1].title");       // "SICP"
        show(root, ".store.books[0:2] | .[].title");// два заголовка

        section("Soft access — нет ошибки на отсутствующем поле");
        show(root, ".store.missing? // \"default\"");    // "default"
        show(root, ".store.books[0].isbn? // null");     // null — не ошибка
        show(root, ".store.books[0].isbn // \"n/a\"");   // "n/a"

        section("Итерация и трансформация");
        show(root, "[.store.books[].title]");
        show(root, ".store.books[] | select(.price > 10) | .title");
        show(root, "[.store.books[] | {title, price}]");
        show(root, ".store.books | sort_by(.price) | reverse | .[0].title");
        show(root, ".store.books | map(select(.price > 0)) | length");

        section("Функции");
        show(root, ".store.books | map(.price) | add");
        show(root, ".store.books | map(.price) | add / length");
        show(root, ".store.books | max_by(.price) | .title");
        show(root, "[.store.books[].tags[]] | unique");
        show(root, ".store.books | group_by(.price > 10) | length");
        show(root, ".store.books[0].tags | contains([\"java\"])");

        section("String interpolation");
        show(root, ".store.owner | \"Name: \\(.name), age: \\(.age)\"");

        section("Reduce и foreach");
        show(root, "[.store.books[].price] | reduce .[] as $p (0; . + $p)");

        section("Рекурсивный спуск");
        show(root, ".. | strings | select(startswith(\"C\"))");

        section("Условия");
        show(root, ".store.books[] | if .price > 40 then \"expensive\" else \"cheap\" end");

        section("Path access");
        show(root, "path(.store.books[0].title)");
        show(root, "getpath([\"store\",\"owner\",\"name\"])");

        section("try-catch");
        show(root, "try .store.MISSING.deep catch \"caught: \" + .");
        show(root, "[.store.books[] | try .isbn catch null]");
    }

    static void section(String name) {
        System.out.println("\n── " + name + " " + "─".repeat(40 - name.length()));
    }

    static void show(JsonNode root, String expr) {
        try {
            List<JsonNode> results = query(root, expr);
            System.out.printf("  %-52s → %s%n", expr,
                    results.size() == 1 ? results.get(0) : results);
        } catch (Exception e) {
            System.out.printf("  %-52s → ERROR: %s%n", expr, e.getMessage());
        }
    }
}