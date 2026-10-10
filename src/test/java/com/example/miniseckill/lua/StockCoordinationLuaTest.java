package com.example.miniseckill.lua;

import static org.junit.jupiter.api.Assertions.*;
import com.example.miniseckill.support.CoordinationRedis;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/** Exact production Lua; lease clock and Redis commands are deterministic doubles. */
class StockCoordinationLuaTest {
    private final CoordinationRedis redis=new CoordinationRedis();
    private final List<String> repair=List.of("total","version","inflight","deadlines","lease","b0","b1");
    private final List<String> reserve=List.of("total","version","inflight","deadlines","lease","user","b0","b1");
    private final List<String> settle=List.of("total","version","inflight","deadlines","lease","user","status","b0","b1");
    private void init() {assertEquals(1,redis.eval("coord_initialize",repair,"10","2:v0").toint());redis.lease("lease","A",20_000);}
    private long reserve(String id,String version) {redis.set("user",id);return redis.eval("coord_reserve",reserve,id,"7","1000",version,"0").tolong();}
    private long settle(String id,boolean refund,String version) {
        String meta=redis.hget("inflight",id);
        return redis.eval("coord_settle",settle,id,meta==null?"7:0":meta,refund?"1":"0",version,"3","60000").tolong();
    }
    private String repair(String owner,String expectedVersion,String stock) {return redis.eval("coord_repair",repair,owner,expectedVersion,stock,"2:next").tojstring();}
    @Test void liveDeductionCannotBeOverwrittenByLegitimateOwner() {
        init();assertEquals("READY",redis.eval("coord_begin",repair,"A").get(1).tojstring());
        assertEquals(1,reserve("r","2:v1"));assertEquals("VERSION_CHANGED",repair("A","2:v0","10"));
        assertEquals("9",redis.get("total"));assertEquals("4",redis.get("b0"));assertEquals(1,redis.hlen("inflight"));
    }
    @Test void emptyToNonemptyToEmptyIsNotAnUndetectedAba() {
        init();reserve("r","2:v1");assertEquals(1,settle("r",false,"2:v2"));
        assertEquals(0,redis.hlen("inflight"));assertEquals("VERSION_CHANGED",repair("A","2:v0","10"));assertEquals("9",redis.get("total"));
    }
    @Test void leaseExpiryAloneRevokesWriteEvenWithoutASuccessor() {
        init();redis.advance(20_001);assertEquals("LEASE_LOST",repair("A","2:v0","99"));assertEquals("10",redis.get("total"));
    }
    @Test void pausedOwnerCannotWriteOrUnlockSuccessor() {
        init();redis.advance(20_001);assertTrue(redis.lease("lease","B",20_000));
        assertEquals("APPLIED",repair("B","2:v0","8"));assertEquals("LEASE_LOST",repair("A","2:v0","10"));
        assertEquals(0,redis.eval("compare_delete",List.of("lease"),"A").toint());assertEquals("B",redis.get("lease"));assertEquals("8",redis.get("total"));
    }
    @Test void beginAndCommitBothRefuseUnresolvedReservations() {
        init();reserve("r","2:v1");assertEquals("INFLIGHT",redis.eval("coord_begin",repair,"A").get(1).tojstring());
        assertEquals("INFLIGHT",repair("A","2:v1","10"));
    }
    @Test void refundIsExactlyOnceAndIncludesMatchingBucket() {
        init();reserve("r","2:v1");assertEquals(1,settle("r",true,"2:v2"));assertEquals(0,settle("r",true,"2:v3"));
        assertEquals("10",redis.get("total"));assertEquals("5",redis.get("b0"));assertNull(redis.get("user"));assertEquals("3",redis.get("status"));
    }
    @Test void durableAcceptanceRemovesLedgerWithoutRefund() {
        init();reserve("r","2:v1");assertEquals(1,settle("r",false,"2:v2"));assertEquals("9",redis.get("total"));assertEquals("r",redis.get("user"));
        assertEquals("UNCHANGED",repair("A","2:v2","9"));
    }
    @Test void oldCancellationCannotReleaseOrProjectOverNewBusinessOwner() {
        init();reserve("old","2:v1");redis.set("user","new");redis.set("status","2");settle("old",true,"2:v2");
        assertEquals("new",redis.get("user"));assertEquals("2",redis.get("status"));assertEquals("10",redis.get("total"));
    }
    @Test void wrongMetadataDoesNotMutateAnyReservation() {
        init();reserve("r","2:v1");assertEquals(-3,redis.eval("coord_settle",settle,"r","8:0","1","2:v2","3","60000").toint());
        assertEquals("9",redis.get("total"));assertEquals(1,redis.hlen("inflight"));
    }
    @Test void missingStockIsNotBlindlyIncreasedByRefund() {
        init();reserve("r","2:v1");redis.delete("total");settle("r",true,"2:v2");assertNull(redis.get("total"));
        assertEquals("APPLIED",repair("A","2:v2","10"));assertEquals("10",redis.get("total"));assertEquals("5",redis.get("b0"));
    }
    @Test void repairReplacesTotalAndAllBucketsInOneScriptAndPreservesCorrectDistribution() {
        init();redis.set("total","3");redis.delete("b1");assertEquals("APPLIED",repair("A","2:v0","7"));
        assertEquals("7",redis.get("total"));assertEquals("4",redis.get("b0"));assertEquals("3",redis.get("b1"));
        assertEquals("UNCHANGED",repair("A","2:next","7"));
    }
    @Test void allTypesAreCheckedBeforeTheFirstStockMutation() {
        init();redis.delete("b1");redis.hash("b1","corrupt","value");
        assertEquals(-4,reserve("r","2:v1"));assertEquals("10",redis.get("total"));assertEquals(0,redis.hlen("inflight"));
        assertEquals("CORRUPT_TYPE",repair("A","2:v0","7"));assertEquals("5",redis.get("b0"));
    }
    @Test void wrongLayoutAndMissingFrontierFailClosed() {
        init();redis.set("version","3:v0");assertEquals(-4,reserve("r","2:v1"));assertEquals("LAYOUT_MISMATCH",redis.eval("coord_begin",repair,"A").get(1).tojstring());
        redis.delete("version");assertEquals(-1,reserve("r","2:v1"));assertEquals("UNINITIALIZED",redis.eval("coord_begin",repair,"A").get(1).tojstring());assertEquals("10",redis.get("total"));
    }
    @Test void secondInitializationCannotResetActiveStock() {
        init();reserve("r","2:v1");assertEquals(0,redis.eval("coord_initialize",repair,"999","2:new").toint());assertEquals("9",redis.get("total"));
    }
    @Test void deadlineUsesRedisClockAndNeverDeletesIntentByTtl() {
        init();reserve("r","2:v1");assertEquals(0,redis.eval("coord_expired",List.of("deadlines"),"100").length());redis.advance(1001);
        assertEquals("r",redis.eval("coord_expired",List.of("deadlines"),"100").get(1).tojstring());redis.advance(99_000_000);assertEquals(1,redis.hlen("inflight"));
    }
    @Test void queuingProjectionCannotRegressSuccessOrAnotherOwner() {
        redis.set("user","r");redis.set("status","2");assertEquals(0,redis.eval("coord_project",List.of("user","status"),"r","1","60000","0").toint());
        assertEquals(0,redis.eval("coord_project",List.of("user","status"),"old","3","60000","1").toint());assertEquals("2",redis.get("status"));assertEquals("r",redis.get("user"));
    }
    @Test void zeroStockAndLostUserLeaseNeverCreateIntent() {
        init();redis.set("user","other");assertEquals(-3,redis.eval("coord_reserve",reserve,"r","7","1000","2:v1","0").toint());
        redis.set("total","0");assertEquals(0,reserve("r","2:v1"));assertEquals(0,redis.hlen("inflight"));
    }
    @Test void unshardedModeAndOverflowingCorruptionAreHandledWithoutPartialMutation() {
        List<String> r=repair.subList(0,5),a=reserve.subList(0,6),s=settle.subList(0,7);
        assertEquals(1,redis.eval("coord_initialize",r,"2","0:v0").toint());redis.set("user","r");
        assertEquals(1,redis.eval("coord_reserve",a,"r","7","1000","0:v1","0").toint());
        assertEquals("7:-1",redis.hget("inflight","r"));redis.set("total","9223372036854775807");
        assertEquals(1,redis.eval("coord_settle",s,"r","7:-1","1","0:v2","3","60000").toint());assertEquals("9223372036854775807",redis.get("total"));assertEquals(0,redis.hlen("inflight"));
    }
    @Test void concurrentAtomicReservationsNeverCrossZero() throws Exception {
        init();ExecutorService pool=Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> work=new ArrayList<>();
            for(int i=0;i<24;i++) {final int n=i;work.add(pool.submit(()->{
                List<String> k=new ArrayList<>(reserve);k.set(5,"user"+n);redis.set("user"+n,"r"+n);
                return redis.eval("coord_reserve",k,"r"+n,Integer.toString(n),"1000","2:v"+n,"0").toint();
            }));}
            int accepted=0;for(Future<Integer> f:work)if(f.get(10,TimeUnit.SECONDS)==1)accepted++;
            assertEquals(10,accepted);assertEquals("0",redis.get("total"));assertEquals("0",redis.get("b0"));assertEquals("0",redis.get("b1"));assertEquals(10,redis.hlen("inflight"));
        } finally {pool.shutdownNow();}
    }
}
