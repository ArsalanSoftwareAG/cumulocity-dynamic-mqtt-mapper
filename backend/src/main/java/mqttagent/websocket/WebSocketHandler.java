package mqttagent.websocket;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;


import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Component
public class WebSocketHandler extends TextWebSocketHandler {
	
	List<WebSocketSession> sessions = new CopyOnWriteArrayList<>();

    @Autowired
    ObjectMapper objectMapper;


	public void sendMessage(WebSocketSession session, StatusMessage message) {
		
        String payload;
        try {
            payload = objectMapper.writeValueAsString(message);
            TextMessage tm = new TextMessage(payload);
            session.sendMessage(tm);
        } catch (IOException e) {
            log.error("Could not handle|send message {}, ", e, message);
            e.printStackTrace();
        }
	}


	public void send(StatusMessage msg)
			throws InterruptedException, IOException {
        sessions.forEach(session -> {
			sendMessage(session, msg);
        });
	}
	
	
	@Override
	public void afterConnectionEstablished(WebSocketSession session) throws Exception {
		//the messages will be broadcasted to all users.
		sessions.add(session);
	}

}
