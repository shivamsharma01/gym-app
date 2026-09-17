package com.example.gym.notification.channel.adapter;

import com.example.gym.notification.channel.NotificationChannel;
import com.example.gym.notification.outbound.OutboundNotification;

/**
 * Delivery adapter. Production would swap this for an email/SMS vendor; this gym ships a mock that
 * records success without talking to a provider.
 */
public interface NotificationChannelAdapter {

    NotificationChannel channel();

    void send(OutboundNotification notification);
}
