package com.zsq.middleware.netty.server;

import com.zsq.middleware.model.Device;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class CloseStreamEvent extends ApplicationEvent {

    private final Device device;

    public CloseStreamEvent(Object source, Device device) {
        super(source);
        this.device = device;
    }

}