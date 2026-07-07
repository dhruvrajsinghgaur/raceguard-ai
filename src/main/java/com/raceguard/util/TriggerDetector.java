package com.raceguard.util;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;

import java.util.Set;

public final class TriggerDetector {

    private TriggerDetector() {}

    /** Tags methods that plausibly execute on their own thread, distinct from the calling thread. */
    public static String detect(MethodDeclaration method) {
        if (method.getAnnotationByName("Scheduled").isPresent()) return "SCHEDULED";
        if (method.getAnnotationByName("MessageMapping").isPresent()) return "WEBSOCKET_MESSAGE_HANDLER";
        if (method.getAnnotationByName("OnMessage").isPresent()) return "WEBSOCKET_MESSAGE_HANDLER";
        if (method.getAnnotationByName("EventListener").isPresent()) return "EVENT_LISTENER";
        if (method.getAnnotationByName("Async").isPresent()) return "ASYNC";

        String name = method.getNameAsString();
        if (Set.of("handleTextMessage", "handleMessage", "afterConnectionEstablished", "afterConnectionClosed")
                .contains(name)) {
            return "WEBSOCKET_HANDLER_OVERRIDE";
        }
        for (Parameter p : method.getParameters()) {
            String t = p.getTypeAsString();
            if (t.contains("WebSocketSession") || t.contains("Session")) {
                return "LIKELY_WEBSOCKET_HANDLER";
            }
        }
        return null;
    }

}