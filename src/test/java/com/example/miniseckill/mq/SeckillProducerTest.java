package com.example.miniseckill.mq;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import com.example.miniseckill.common.MessageStatus;
import com.example.miniseckill.config.AdmissionCapacityProperties;
import com.example.miniseckill.dto.SeckillMessage;
import com.example.miniseckill.mapper.SeckillMessageMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
class SeckillProducerTest {
    final RabbitTemplate rabbit=mock(RabbitTemplate.class);final SeckillMessageMapper messages=mock(SeckillMessageMapper.class);final SeckillProducer producer=new SeckillProducer(rabbit,messages);
    SeckillMessage message(){return new SeckillMessage("r",1L,7L,1001L,1L);}
    @Test void confirmAckIsQualifiedByCapturedAttemptNotJustSendingStatus(){producer.confirm(new CorrelationData("r|attempt-A"),true,null);verify(messages).finishSend("r","attempt-A",1,null);}
    @Test void nackCarriesTheSameAttemptToken(){producer.confirm(new CorrelationData("r|attempt-A"),false,"nack");verify(messages).finishSend("r","attempt-A",4,"nack");}
    @Test void malformedOrLegacyCallbacksCannotWrite(){for(String id:new String[]{"r","|","r|","r|a|b"})producer.confirm(new CorrelationData(id),true,null);producer.confirm(null,true,null);verifyNoInteractions(messages);}
    @Test void returnedMessageIncludesAttemptToken(){MessageProperties p=new MessageProperties();p.setCorrelationId("r|A");producer.returnedMessage(new ReturnedMessage(new Message(new byte[0],p),312,"NO_ROUTE","x","r"));verify(messages).finishSend("r","A",5,"312:NO_ROUTE");}
    @Test void returnedMessageWithoutCorrelationIsIgnored(){producer.returnedMessage(new ReturnedMessage(new Message(new byte[0],new MessageProperties()),312,"NO_ROUTE","x","r"));verifyNoInteractions(messages);}
    @Test void positiveConfirmWithReturnedMessageCannotTurnItIntoSent(){CorrelationData data=new CorrelationData("r|A");data.setReturned(new ReturnedMessage(new Message(new byte[0]),312,"NO_ROUTE","x","r"));producer.confirm(data,true,null);verify(messages).finishSend("r","A",4,null);}
    @Test void loserOfDatabaseClaimDoesNotPublishOrCallStatusOnlyMethods(){assertFalse(producer.send(message()));verify(messages).claimSend(eq("r"),anyString(),eq(5));verifyNoInteractions(rabbit);}
    @Test void winnerPublishesWithExactClaimedTokenOnBothCorrelationPaths(){
        when(messages.claimSend(eq("r"),anyString(),eq(5))).thenReturn(1);assertTrue(producer.send(message()));
        ArgumentCaptor<String> token=ArgumentCaptor.forClass(String.class);verify(messages).claimSend(eq("r"),token.capture(),eq(5));
        ArgumentCaptor<CorrelationData> correlation=ArgumentCaptor.forClass(CorrelationData.class);ArgumentCaptor<MessagePostProcessor> post=ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(rabbit).convertAndSend(anyString(),anyString(),any(),post.capture(),correlation.capture());
        assertEquals("r|"+token.getValue(),correlation.getValue().getId());assertEquals(correlation.getValue().getId(),post.getValue().postProcessMessage(new Message(new byte[0])).getMessageProperties().getCorrelationId());
    }
    @Test void fusedInitialSendUsesTokenAlreadyStoredByInsert(){producer.sendInitial(message());ArgumentCaptor<CorrelationData> data=ArgumentCaptor.forClass(CorrelationData.class);verify(rabbit).convertAndSend(anyString(),anyString(),any(),any(MessagePostProcessor.class),data.capture());assertEquals("r|r",data.getValue().getId());verifyNoInteractions(messages);}
    @Test void publishFailureUsesCapturedAttemptAndCannotBlindlyRefundOrReclaim(){
        when(messages.claimSend(eq("r"),anyString(),eq(5))).thenReturn(1);doThrow(new IllegalStateException("send failed")).when(rabbit).convertAndSend(anyString(),anyString(),any(),any(MessagePostProcessor.class),any(CorrelationData.class));
        assertThrows(IllegalStateException.class,()->producer.send(message()));verify(messages).finishSend(eq("r"),anyString(),eq(3),eq("send failed"));verify(messages,never()).markFailed(any(),anyInt(),any());
    }
    @Test void pendingCompatibilityPathStillMustWinClaim(){AdmissionCapacityProperties p=new AdmissionCapacityProperties();p.setInitialSendingEnabled(false);new SeckillProducer(rabbit,messages,p).sendInitial(message());verify(messages).claimSend(eq("r"),anyString(),eq(5));verifyNoInteractions(rabbit);}
}
