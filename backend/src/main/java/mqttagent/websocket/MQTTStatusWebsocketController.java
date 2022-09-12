package mqttagent.websocket;

import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

 
@Controller
public class MQTTStatusWebsocketController {
 
    /* 
    @GetMapping("/stomp-broadcast")
    public String getWebSocketBroadcast() {
        return "stomp-broadcast";
    }
    
    @MessageMapping("/command")
    @SendTo("/mqtt-out/messages")
    public StatusMessage send(StatusMessage chatMessage) throws Exception {
        return new StatusMessage(chatMessage.getFrom(), chatMessage.getText(), "ALL");
    }
     */

    @SendTo("/mqtt-out/command")
    public StatusMessage send(StatusMessage statusMessage) throws Exception {
        return  statusMessage;
    } 
}
