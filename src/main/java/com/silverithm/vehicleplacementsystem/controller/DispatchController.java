package com.silverithm.vehicleplacementsystem.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.silverithm.vehicleplacementsystem.dto.AssignmentResponseDTO;
import com.silverithm.vehicleplacementsystem.dto.RequestDispatchDTO;
import com.silverithm.vehicleplacementsystem.service.DispatchHistoryService;
import com.silverithm.vehicleplacementsystem.service.DispatchService;
import com.silverithm.vehicleplacementsystem.service.DispatchServiceV2;
import com.silverithm.vehicleplacementsystem.service.DispatchServiceV3;
import com.silverithm.vehicleplacementsystem.service.DispatchServiceV4;
import com.silverithm.vehicleplacementsystem.service.SSEService;
import java.io.IOException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
public class DispatchController {

    @Autowired
    private DispatchService dispatchService;

    @Autowired
    private DispatchServiceV2 dispatchServiceV2;

    @Autowired
    private DispatchServiceV3 dispatchServiceV3;

    @Autowired
    private DispatchServiceV4 dispatchServiceV4;

    @Autowired
    private DispatchServiceV4 dispatchServiceV5;

    @Autowired
    private SSEService sseService;

    @Autowired
    private DispatchHistoryService dispatchHistoryService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Qualifier("responseQueue")
    @Autowired
    private Queue responseQueue;


    @RabbitListener(queues = "dispatch.queue")
    public void handleDispatchRequest(RequestDispatchDTO requestDispatchDTO, Message message, Channel channel)
            throws IOException {
        log.info("Received message: {}", requestDispatchDTO);
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String jobId = message.getMessageProperties().getHeaders().get("jobId").toString();
        String username = message.getMessageProperties().getHeaders().get("username").toString();

        try {
            List<AssignmentResponseDTO> result = dispatchServiceV3.getOptimizedAssignments(requestDispatchDTO, jobId);

            // 결과 메시지 생성
            Message responseMessage = MessageBuilder
                    .withBody(objectMapper.writeValueAsBytes(result))
                    .setHeader("jobId", jobId)
                    .setHeader("username", username)
                    .build();

            // 응답 큐로 결과 전송
            rabbitTemplate.send(responseQueue.getName(), responseMessage);

        } catch (Exception e) {
            log.error("배차 요청 처리 중 오류 발생: ", e);
            sseService.notifyError(jobId);
            ResponseEntity.badRequest().build();
        }

    }


}
