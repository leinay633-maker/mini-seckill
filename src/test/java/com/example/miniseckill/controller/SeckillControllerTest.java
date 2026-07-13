package com.example.miniseckill.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.miniseckill.common.BusinessException;
import com.example.miniseckill.common.GlobalExceptionHandler;
import com.example.miniseckill.common.Result;
import com.example.miniseckill.config.ClientIpResolver;
import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.config.WebMvcConfig;
import com.example.miniseckill.dto.SeckillOrderRequest;
import com.example.miniseckill.service.CaptchaService;
import com.example.miniseckill.service.SeckillService;
import com.example.miniseckill.service.UserAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        controllers = SeckillController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = WebMvcConfig.class)
)
@ContextConfiguration(classes = SeckillControllerTest.MvcSliceConfig.class)
class SeckillControllerTest {

    private static final String ORDER_JSON = """
            {"activityId":1,"userId":10001,"skuId":1001,"token":"token-1"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SeckillService seckillService;
    @MockBean
    private SeckillProperties seckillProperties;
    @MockBean
    private UserAuthService userAuthService;
    @MockBean
    private CaptchaService captchaService;
    @MockBean
    private ClientIpResolver clientIpResolver;

    @Configuration(proxyBeanMethods = false)
    @Import({SeckillController.class, GlobalExceptionHandler.class})
    static class MvcSliceConfig {
    }

    @Test
    void successfulOrderReturnsHttp200AndBodyCodeZero() throws Exception {
        when(clientIpResolver.resolve(any())).thenReturn("203.0.113.10");
        when(seckillService.placeOrder(any(SeckillOrderRequest.class), eq("203.0.113.10")))
                .thenReturn(Result.success(null));

        mockMvc.perform(orderRequest(ORDER_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void logicalDuplicateResultKeepsHttp200AndBodyCode1002() throws Exception {
        when(clientIpResolver.resolve(any())).thenReturn("203.0.113.10");
        when(seckillService.placeOrder(any(SeckillOrderRequest.class), eq("203.0.113.10")))
                .thenReturn(Result.fail(1002, "请勿重复下单"));

        mockMvc.perform(orderRequest(ORDER_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1002));
    }

    @Test
    void duplicateBusinessExceptionReturnsHttp409AndBodyCode1002() throws Exception {
        when(clientIpResolver.resolve(any())).thenReturn("203.0.113.10");
        when(seckillService.placeOrder(any(SeckillOrderRequest.class), eq("203.0.113.10")))
                .thenThrow(new BusinessException(1002, "请勿重复下单"));

        mockMvc.perform(orderRequest(ORDER_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(1002));
    }

    @Test
    void rateLimitExceptionReturnsHttp429AndBodyCode429() throws Exception {
        when(clientIpResolver.resolve(any())).thenReturn("203.0.113.10");
        when(seckillService.placeOrder(any(SeckillOrderRequest.class), eq("203.0.113.10")))
                .thenThrow(new BusinessException(429, "请求过于频繁"));

        mockMvc.perform(orderRequest(ORDER_JSON))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(429));
    }

    @Test
    void forbiddenUserReturnsHttp403AndDoesNotCallSeckillService() throws Exception {
        doThrow(new BusinessException(403, "用户与登录身份不一致"))
                .when(userAuthService).assertUserAllowed(any(), eq(10001L));

        mockMvc.perform(orderRequest(ORDER_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        verify(seckillService, never()).placeOrder(any(), any());
    }

    @Test
    void invalidOrderDtoReturnsHttp400AndBodyCode400() throws Exception {
        mockMvc.perform(orderRequest("{\"activityId\":1,\"userId\":0,\"skuId\":1001}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verify(userAuthService, never()).assertUserAllowed(any(), any());
        verify(seckillService, never()).placeOrder(any(), any());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder orderRequest(String body) {
        return post("/api/seckill/order")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }
}
