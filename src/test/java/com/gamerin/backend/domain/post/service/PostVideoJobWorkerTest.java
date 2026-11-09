package com.gamerin.backend.domain.post.service;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class PostVideoJobWorkerTest {

    @Test
    void recoversInterruptedJobsOnceBeforeProcessingQueue() throws Exception {
        PostVideoJobService service = mock(PostVideoJobService.class);
        when(service.processNextJob()).thenReturn(true, false, false);
        com.gamerin.backend.domain.upload.service.UploadJobService uploads = mock(com.gamerin.backend.domain.upload.service.UploadJobService.class);
        PostVideoJobWorker worker = new PostVideoJobWorker(service, uploads);

        worker.drain();
        worker.drain();

        InOrder order = inOrder(service);
        order.verify(service).recoverInterruptedJobs();
        order.verify(service, times(3)).processNextJob();
        verify(service, times(1)).recoverInterruptedJobs();
        verify(uploads, times(1)).recoverInterruptedJobs();
        verify(uploads, times(2)).cleanTerminalFiles();
        verify(uploads, times(3)).processNextJob();
        worker.destroy();
    }
}
