package com.example.miniseckill.support;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.example.miniseckill.config.*;
import com.example.miniseckill.mapper.*;
import com.example.miniseckill.service.*;
import java.time.Duration;
import java.util.*;
import org.luaj.vm2.LuaValue;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.RedisScript;
/** Java orchestration and actual Lua, but DB/Redis transport/clock are deterministic doubles. */
public final class CoordinatorHarness {
    public final CoordinationRedis store=new CoordinationRedis();
    public final StringRedisTemplate redis=mock(StringRedisTemplate.class);
    public final SeckillMessageMapper messages=mock(SeckillMessageMapper.class);
    public final StockFactsMapper facts=mock(StockFactsMapper.class);
    public final SeckillProperties properties=new SeckillProperties();
    public final StockCoordinationProperties policy=new StockCoordinationProperties();
    public final SoldOutCacheService soldOut=mock(SoldOutCacheService.class);
    public final CoordinationFaults faults=mock(CoordinationFaults.class);
    public final SeckillMetrics metrics=mock(SeckillMetrics.class);
    public final InventoryCoordinator inventory;
    @SuppressWarnings({"unchecked","rawtypes"})
    public CoordinatorHarness() {
        properties.getStockShard().setBucketCount(2);properties.getMysqlStockSegment().setEnabled(false);
        ValueOperations<String,String> values=mock(ValueOperations.class);
        HashOperations<String,Object,Object> hashes=mock(HashOperations.class);
        when(redis.opsForValue()).thenReturn(values);when(redis.opsForHash()).thenReturn(hashes);
        when(values.setIfAbsent(anyString(),anyString(),any(Duration.class))).thenAnswer(i->store.lease(i.getArgument(0),i.getArgument(1),i.<Duration>getArgument(2).toMillis()));
        when(hashes.get(anyString(),any())).thenAnswer(i->store.hget(i.getArgument(0),i.getArgument(1).toString()));
        when(redis.execute(any(RedisScript.class),anyList(),any(Object[].class))).thenAnswer(i->{
            RedisScript<?> script=i.getArgument(0);List<String> keys=i.getArgument(1);
            Object[] raw=(Object[])i.getRawArguments()[2];String[] argv=Arrays.stream(raw).map(Object::toString).toArray(String[]::new);
            LuaValue value=store.source(script.getScriptAsString(),keys,argv);
            if(script.getResultType()==Long.class)return value.tolong();
            if(script.getResultType()==String.class)return value.tojstring();
            List<String> result=new ArrayList<>();for(int n=1;n<=value.length();n++)result.add(value.get(n).tojstring());return result;
        });
        inventory=new InventoryCoordinator(redis,messages,facts,properties,policy,soldOut,faults,metrics,new RedisProperties());
    }
    public void initialize() {inventory.initializeNew(1L,1001L,10);}
    public void owner(String request) {store.set(com.example.miniseckill.util.RedisKeyUtil.userSkuKey(1L,7L,1001L),request);}
}
