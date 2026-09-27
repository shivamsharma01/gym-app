package com.example.gym.notification.channel.adapter;

import com.example.gym.notification.channel.NotificationChannel;
import com.example.gym.notification.outbound.OutboundNotification;

/**
 * Delivery adapter.This component acts as a bridge to send out notifications.
 * In a live environment, it sends actual messages to customers via email or
 * WhatsApp vendors, but for testing, it uses a WhatsApp test account and a
 * simulated email tool that logs a successful delivery without contacting a
 * real provider.
 */
public interface NotificationChannelAdapter {

	NotificationChannel channel();

	void send(OutboundNotification notification);
}
