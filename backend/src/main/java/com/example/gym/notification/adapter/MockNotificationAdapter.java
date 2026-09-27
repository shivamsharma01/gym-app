package com.example.gym.notification.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.example.gym.notification.channel.NotificationChannel;
import com.example.gym.notification.channel.adapter.NotificationChannelAdapter;
import com.example.gym.notification.outbound.OutboundNotification;

@Component
public class MockNotificationAdapter implements NotificationChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(MockNotificationAdapter.class);

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public void send(OutboundNotification notification) {
        log.info("Mock {} notification to {} key={} subject={}",
                notification.getChannel(), notification.getRecipient(),
                notification.getTemplateKey(), notification.getSubject());
    }
}
