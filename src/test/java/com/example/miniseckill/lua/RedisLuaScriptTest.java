package com.example.miniseckill.lua;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

class RedisLuaScriptTest {

    @Test
    void seckillStockScriptReturnsMinusOneWhenStockKeyIsMissing() {
        FakeRedis redis = new FakeRedis();

        LuaValue result = runScript("lua/seckill_stock.lua", redis, new String[] {"stock:missing"});

        assertEquals(-1, result.toint());
    }

    @Test
    void seckillStockScriptDeductsAvailableStockAndStopsAtZero() {
        FakeRedis redis = new FakeRedis();
        redis.set("stock:sku:1001", 2);

        assertEquals(1, runScript("lua/seckill_stock.lua", redis, new String[] {"stock:sku:1001"}).toint());
        assertEquals("1", redis.get("stock:sku:1001"));
        assertEquals(1, runScript("lua/seckill_stock.lua", redis, new String[] {"stock:sku:1001"}).toint());
        assertEquals("0", redis.get("stock:sku:1001"));
        assertEquals(0, runScript("lua/seckill_stock.lua", redis, new String[] {"stock:sku:1001"}).toint());
        assertEquals("0", redis.get("stock:sku:1001"));
    }

    @Test
    void rateLimitScriptAllowsRequestsWithinWindowLimitAndRejectsOverflow() {
        FakeRedis redis = new FakeRedis();

        assertEquals(1, runScript("lua/rate_limit.lua", redis, new String[] {"rate:sku:1001"}, "3", "2").toint());
        assertEquals(1, runScript("lua/rate_limit.lua", redis, new String[] {"rate:sku:1001"}, "3", "2").toint());
        assertEquals(0, runScript("lua/rate_limit.lua", redis, new String[] {"rate:sku:1001"}, "3", "2").toint());

        assertEquals("3", redis.get("rate:sku:1001"));
        assertEquals(3, redis.ttl("rate:sku:1001"));
    }

    @Test
    void compareDeleteScriptDeletesOnlyWhenValueMatches() {
        FakeRedis redis = new FakeRedis();
        redis.set("token:user:10001", "token-001");

        assertEquals(0, runScript("lua/compare_delete.lua", redis, new String[] {"token:user:10001"}, "wrong").toint());
        assertEquals("token-001", redis.get("token:user:10001"));

        assertEquals(1, runScript("lua/compare_delete.lua", redis, new String[] {"token:user:10001"}, "token-001").toint());
        assertNull(redis.get("token:user:10001"));

        assertEquals(-1, runScript("lua/compare_delete.lua", redis, new String[] {"token:user:10001"}, "token-001").toint());
    }

    private LuaValue runScript(String resourcePath, FakeRedis redis, String[] keys, String... args) {
        Globals globals = JsePlatform.standardGlobals();
        globals.set("redis", redis.asLuaTable());
        globals.set("KEYS", luaArray(keys));
        globals.set("ARGV", luaArray(args));
        return globals.load(readResource(resourcePath), resourcePath).call();
    }

    private LuaTable luaArray(String[] values) {
        LuaTable table = new LuaTable();
        for (int i = 0; i < values.length; i++) {
            table.set(i + 1, values[i]);
        }
        return table;
    }

    private String readResource(String path) {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(path)) {
            if (inputStream == null) {
                throw new IllegalArgumentException("Missing test resource: " + path);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read test resource: " + path, ex);
        }
    }

    private static final class FakeRedis {
        private final Map<String, String> values = new HashMap<>();
        private final Map<String, Integer> expirations = new HashMap<>();

        void set(String key, int value) {
            values.put(key, String.valueOf(value));
        }

        void set(String key, String value) {
            values.put(key, value);
        }

        String get(String key) {
            return values.get(key);
        }

        Integer ttl(String key) {
            return expirations.get(key);
        }

        LuaTable asLuaTable() {
            LuaTable table = new LuaTable();
            table.set("call", new RedisCallFunction());
            return table;
        }

        private final class RedisCallFunction extends VarArgFunction {
            @Override
            public Varargs invoke(Varargs args) {
                String command = args.arg(1).checkjstring().toUpperCase(Locale.ROOT);
                String key = args.arg(2).checkjstring();
                if ("GET".equals(command)) {
                    String value = FakeRedis.this.get(key);
                    return value == null ? LuaValue.NIL : LuaValue.valueOf(value);
                }
                if ("DECR".equals(command)) {
                    return LuaValue.valueOf(incrementBy(key, -1));
                }
                if ("INCR".equals(command)) {
                    return LuaValue.valueOf(incrementBy(key, 1));
                }
                if ("DEL".equals(command)) {
                    return values.remove(key) == null ? LuaValue.ZERO : LuaValue.ONE;
                }
                if ("EXPIRE".equals(command)) {
                    expirations.put(key, args.arg(3).checkint());
                    return LuaValue.ONE;
                }
                throw new UnsupportedOperationException("Unsupported redis command in test: " + command);
            }

            private int incrementBy(String key, int delta) {
                int next = Integer.parseInt(values.getOrDefault(key, "0")) + delta;
                values.put(key, String.valueOf(next));
                return next;
            }
        }
    }
}
