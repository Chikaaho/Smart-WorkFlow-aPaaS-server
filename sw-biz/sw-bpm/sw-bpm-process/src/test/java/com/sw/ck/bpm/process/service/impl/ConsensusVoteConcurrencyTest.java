package com.sw.ck.bpm.process.service.impl;

import com.sw.ck.bpm.api.facade.BpmTaskFacade;
import com.sw.ck.bpm.process.mapper.BpmConsensusVoteMapper;
import com.sw.ck.bpm.process.mapper.BpmSignRecordMapper;
import com.sw.ck.bpm.process.mapper.BpmTaskDeadlineMapper;
import com.sw.ck.bpm.process.mapper.BpmCommunicationMapper;
import com.sw.ck.bpm.process.mapper.BpmAuthorizeRuleMapper;
import com.sw.ck.bpm.process.service.ApprovalActionService;
import com.sw.ck.bpm.process.service.BpmInstanceService;
import com.sw.ck.bpm.process.service.BpmProcessDefService;
import com.sw.ck.bpm.process.service.impl.ApprovalLifecycleServiceImpl;
import com.sw.ck.common.event.DomainEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * I3 §4.5：会签动作计数的多实例安全 —— 同任务同人并发投票，
 * DB 唯一键 uk_sw_bpm_vote_task_actor 只允许一次合法计数。
 */
@SpringBootTest(classes = ConsensusVoteConcurrencyTest.Config.class)
class ConsensusVoteConcurrencyTest {

    @Autowired
    ApprovalLifecycleServiceImpl lifecycleService;

    @Test
    void concurrentSameTaskActorVotesSettleExactlyOnce() throws Exception {
        var port = lifecycleService.consensusVotePort();

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return port.record("0", "pi-i3-vote", "node_consensus", "task-1", "1001", "APPROVE");
            }));
        }
        ready.await();
        start.countDown();

        int accepted = 0;
        for (Future<Boolean> future : futures) {
            if (future.get()) accepted++;
        }
        pool.shutdown();

        // 唯一键保证：6 路并发只有一次合法计数
        assertThat(accepted).isEqualTo(1);
        assertThat(port.count("0", "pi-i3-vote", "node_consensus", "APPROVE")).isEqualTo(1);

        // 换 outcome 重投也必须被同键拦截（同人同任务一票）
        boolean secondOutcome = port.record("0", "pi-i3-vote", "node_consensus", "task-1", "1001", "DISAPPROVE");
        assertThat(secondOutcome).isFalse();
        assertThat(port.count("0", "pi-i3-vote", "node_consensus", "DISAPPROVE")).isEqualTo(0);
    }

    @Configuration(proxyBeanMethods = false)
    static class Config extends ConsensusVoteTestSupport {
        @Bean
        ApprovalLifecycleServiceImpl approvalLifecycleService(
                BpmTaskFacade bpmTaskFacade,
                BpmInstanceService bpmInstanceService,
                BpmProcessDefService bpmProcessDefService,
                ApprovalActionService approvalActionService,
                BpmAuthorizeRuleMapper authorizeRuleMapper,
                BpmCommunicationMapper communicationMapper,
                BpmSignRecordMapper signRecordMapper,
                BpmTaskDeadlineMapper deadlineMapper,
                BpmConsensusVoteMapper consensusVoteMapper,
                com.sw.ck.bpm.process.mapper.ParticipantSnapshotMapper participantSnapshotMapper,
                ObjectProvider<com.sw.ck.system.api.user.UserQueryFacade> userQueryFacade,
                ObjectProvider<com.sw.ck.bpm.process.service.TaskActionService> taskActionService,
                DomainEventPublisher domainEventPublisher,
                com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
            return new ApprovalLifecycleServiceImpl(bpmTaskFacade, bpmInstanceService,
                    bpmProcessDefService, approvalActionService, authorizeRuleMapper,
                    communicationMapper, signRecordMapper, deadlineMapper, consensusVoteMapper,
                    participantSnapshotMapper, userQueryFacade, taskActionService,
                    domainEventPublisher, objectMapper);
        }

        @Bean
        BpmTaskFacade bpmTaskFacade() {
            return Mockito.mock(BpmTaskFacade.class);
        }

        @Bean
        BpmInstanceService bpmInstanceService() {
            return Mockito.mock(BpmInstanceService.class);
        }

        @Bean
        BpmProcessDefService bpmProcessDefService() {
            return Mockito.mock(BpmProcessDefService.class);
        }

        @Bean
        ApprovalActionService approvalActionService() {
            return Mockito.mock(ApprovalActionService.class);
        }

        @Bean
        DomainEventPublisher domainEventPublisher() {
            return Mockito.mock(DomainEventPublisher.class);
        }

        @Bean
        com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
            return new com.fasterxml.jackson.databind.ObjectMapper();
        }

        @Bean
        ObjectProvider<com.sw.ck.system.api.user.UserQueryFacade> userQueryFacadeProvider() {
            return Mockito.mock(ObjectProvider.class);
        }

        @Bean
        ObjectProvider<com.sw.ck.bpm.process.service.TaskActionService> taskActionServiceProvider() {
            return Mockito.mock(ObjectProvider.class);
        }
    }
}
