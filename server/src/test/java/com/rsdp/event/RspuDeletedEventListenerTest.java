package com.rsdp.event;

import com.rsdp.service.vector.ProductVectorStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link RspuDeletedEventListener} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class RspuDeletedEventListenerTest {

    @Mock
    private ProductVectorStore productVectorStore;

    @InjectMocks
    private RspuDeletedEventListener listener;

    @Test
    void onRspuDeleted_shouldDeleteVectors() {
        RspuDeletedEvent event = new RspuDeletedEvent("RSPU-TEST01", List.of("IMG-01", "IMG-02"));

        listener.onRspuDeleted(event);

        verify(productVectorStore).deleteByImageIds(List.of("IMG-01", "IMG-02"));
    }

    @Test
    void onRspuDeleted_shouldDoNothingWhenNoImages() {
        RspuDeletedEvent event = new RspuDeletedEvent("RSPU-TEST01", List.of());

        listener.onRspuDeleted(event);

        verify(productVectorStore, times(0)).deleteByImageIds(List.of());
    }
}
