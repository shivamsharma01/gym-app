package com.example.gym.notification.adapter;

import org.springframework.stereotype.Component;

import com.example.gym.notification.channel.NotificationChannel;
import com.example.gym.notification.channel.adapter.NotificationChannelAdapter;
import com.example.gym.notification.outbound.OutboundNotification;
import com.example.gym.notification.whatsapp.WhatsAppClient;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class WhatsAppNotificationAdapter implements NotificationChannelAdapter {

	private final WhatsAppClient whatsappClient;

	@Override
	public NotificationChannel channel() {
		return NotificationChannel.WHATSAPP;
	}

	@Override
	public void send(OutboundNotification notification) {

		String providerMessageId = whatsappClient.sendTemplate(notification);

		notification.setProviderMessageId(providerMessageId);
	}
}
