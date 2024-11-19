package com.silverithm.vehicleplacementsystem.service;

import com.silverithm.vehicleplacementsystem.repository.EmitterRepository;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
@RequiredArgsConstructor
public class SSEService {
    // 기본 타임아웃 설정
    private static final Long DEFAULT_TIMEOUT = 60L * 1000 * 60 * 5;

    private final EmitterRepository emitterRepository;

    /**
     * 클라이언트가 구독을 위해 호출하는 메서드.
     *
     * @param userName - 구독하는 클라이언트의 사용자 아이디.
     * @return SseEmitter - 서버에서 보낸 이벤트 Emitter
     */
    public SseEmitter subscribe(String userName) {
        SseEmitter emitter = createEmitter(userName);

        sendToClient(userName, "EventStream Created. [userId=" + userName + "]");
        return emitter;
    }

    /**
     * 서버의 이벤트를 클라이언트에게 보내는 메서드 다른 서비스 로직에서 이 메서드를 사용해 데이터를 Object event에 넣고 전송하면 된다.
     *
     * @param userName - 메세지를 전송할 사용자의 아이디.
     * @param event    - 전송할 이벤트 객체.
     */
    public void notify(String userName, Object event) {
        sendToClient(userName, event);

    }

    /**
     * 클라이언트에게 데이터를 전송
     *
     * @param userName - 데이터를 받을 사용자의 아이디.
     * @param data     - 전송할 데이터.
     */
    private void sendToClient(String userName, Object data) {
        SseEmitter emitter = emitterRepository.get(userName);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().id(String.valueOf(userName)).name("sse").data(data));
            } catch (IOException exception) {
                emitterRepository.deleteById(userName);
                emitter.completeWithError(exception);
            }
        }
    }

    /**
     * 사용자 아이디를 기반으로 이벤트 Emitter를 생성
     *
     * @param userName - 사용자 아이디.
     * @return SseEmitter - 생성된 이벤트 Emitter.
     */
    private SseEmitter createEmitter(String userName) {
        SseEmitter emitter = new SseEmitter(DEFAULT_TIMEOUT);
        emitterRepository.save(userName, emitter);

        // Emitter가 완료될 때(모든 데이터가 성공적으로 전송된 상태) Emitter를 삭제한다.
        emitter.onCompletion(() -> emitterRepository.deleteById(userName));
        // Emitter가 타임아웃 되었을 때(지정된 시간동안 어떠한 이벤트도 전송되지 않았을 때) Emitter를 삭제한다.
        emitter.onTimeout(() -> emitterRepository.deleteById(userName));

        return emitter;
    }
}